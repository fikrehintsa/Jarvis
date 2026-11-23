package com.example.jarvis

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity(), RecognitionListener {

    private val wakeWords = setOf("jarvis", "travis")
    private val modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private val modelFolderInZip = "vosk-model-small-en-us-0.15"

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var imageCapture: ImageCapture? = null
    private var armedUntil = 0L
    private var listening = false

    private val memoryPrefs by lazy { getSharedPreferences("jarvis_memory", Context.MODE_PRIVATE) }
    private val aliasPrefs by lazy { getSharedPreferences("jarvis_alias", Context.MODE_PRIVATE) }

    private val permissions: Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            onPermissionsReady()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        initTts()

        if (permissions.all { has(it) }) {
            onPermissionsReady()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    // ---------- UI ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val title = TextView(this).apply {
            text = "JARVIS"
            textSize = 32f
            gravity = Gravity.CENTER
        }
        statusView = TextView(this).apply {
            text = "Starting..."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        logView = TextView(this).apply { textSize = 14f }
        scroll = ScrollView(this).apply { addView(logView) }

        root.addView(title)
        root.addView(statusView)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        setContentView(root)
    }

    private fun setStatus(text: String) {
        runOnUiThread { statusView.text = text }
    }

    private fun log(line: String) {
        runOnUiThread {
            logView.append(line + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // ---------- Permissions and setup ----------

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun onPermissionsReady() {
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            setStatus("Microphone permission is required.")
            return
        }
        if (has(Manifest.permission.CAMERA)) setupCamera()
        if (model == null) loadModel()
    }

    private fun setupCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val capture = ImageCapture.Builder().build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, capture)
                imageCapture = capture
            } catch (e: Exception) {
                log("Camera setup failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        mainHandler.post { if (listening) speechService?.setPause(false) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { if (listening) speechService?.setPause(false) }
                    }
                })
                ttsReady = true
            }
        }
    }

    private fun speak(text: String) {
        log("Jarvis: $text")
        if (!ttsReady) return
        speechService?.setPause(true)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    // ---------- Offline speech model ----------

    private fun loadModel() {
        Thread {
            try {
                val modelDir = File(filesDir, "model")
                if (!File(modelDir, "am/final.mdl").exists()) {
                    downloadModel(modelDir)
                }
                setStatus("Loading voice model...")
                model = Model(modelDir.absolutePath)
                runOnUiThread { startListening() }
            } catch (e: Exception) {
                setStatus("Model error. Connect to internet once and reopen the app.")
                log("Error: ${e.message}")
            }
        }.start()
    }

    private fun downloadModel(modelDir: File) {
        setStatus("Downloading voice model (about 40 MB)...")
        val zipFile = File(cacheDir, "model.zip")
        val conn = URL(modelUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.inputStream.use { input ->
            FileOutputStream(zipFile).use { out -> input.copyTo(out) }
        }

        setStatus("Unpacking voice model...")
        val tmp = File(filesDir, "model_tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val tmpPath = tmp.canonicalPath

        ZipInputStream(zipFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = File(tmp, entry.name)
                if (!target.canonicalPath.startsWith(tmpPath)) {
                    throw SecurityException("Bad zip entry")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zip.copyTo(out) }
                }
                entry = zip.nextEntry
            }
        }

        modelDir.deleteRecursively()
        if (!File(tmp, modelFolderInZip).renameTo(modelDir)) {
            throw IllegalStateException("Could not install model")
        }
        tmp.deleteRecursively()
        zipFile.delete()
    }

    private fun startListening() {
        val m = model ?: return
        try {
            val recognizer = Recognizer(m, 16000.0f)
            val service = SpeechService(recognizer, 16000.0f)
            service.startListening(this)
            speechService = service
            listening = true
            setStatus("Listening... say \"Jarvis\"")
            speak("Jarvis is ready. Say help to hear what I can do.")
        } catch (e: Exception) {
            setStatus("Could not start microphone.")
            log("Error: ${e.message}")
        }
    }

    // ---------- Speech callbacks ----------

    override fun onPartialResult(hypothesis: String?) {}

    override fun onResult(hypothesis: String?) {
        if (hypothesis == null) return
        val text = try {
            JSONObject(hypothesis).optString("text", "")
        } catch (e: Exception) {
            ""
        }
        mainHandler.post { handleText(text) }
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(exception: Exception?) {
        setStatus("Speech error: ${exception?.message}")
    }

    override fun onTimeout() {}

    // ---------- Command logic ----------

    private fun handleText(raw: String) {
        val text = raw.trim().lowercase(Locale.US)
        if (text.isEmpty()) return
        log("Heard: $text")

        val words = text.split(" ").filter { it.isNotEmpty() }
        val wakeIndex = words.indexOfFirst { it in wakeWords }
        val command: String

        if (wakeIndex >= 0) {
            command = words.drop(wakeIndex + 1).joinToString(" ")
            if (command.isEmpty()) {
                armedUntil = System.currentTimeMillis() + 8000
                speak("Yes?")
                return
            }
        } else if (System.currentTimeMillis() < armedUntil) {
            command = text
        } else {
            return
        }

        armedUntil = 0
        runCommand(command)
    }

    private fun runCommand(command: String) {
        when {
            command.contains("remind") -> setReminder(command)

            command.contains("timer") -> setTimer(command)

            command.startsWith("remember ") -> rememberFact(command)

            command.contains("forget") && (command.contains("everything") || command.contains("all")) -> {
                memoryPrefs.edit().clear().apply()
                aliasPrefs.edit().clear().apply()
                speak("Okay, I forgot everything.")
            }

            command.contains(" means ") -> saveNickname(command)

            command == "help" || command.contains("what can you do") || command.contains("your commands") ->
                showHelp()

            command.startsWith("where ") || command.contains("do you remember") ||
                command.startsWith("what is my") || command.startsWith("what was my") ||
                command.startsWith("what's my") -> recallFact(command)

            command.contains("selfie") || command.contains("photo") || command.contains("picture") ->
                takeSelfie()

            command.startsWith("call ") -> callContact(command.removePrefix("call ").trim())

            command.contains("flashlight") || command.contains("torch") ->
                setTorch(!command.contains("off"))

            command.contains("time") -> {
                val now = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                speak("It is $now")
            }

            else -> speak("Sorry, I don't know that command. Say help to hear what I can do.")
        }
    }

    // ---------- Help ----------

    private fun showHelp() {
        log(
            "--- Commands ---\n" +
                "Jarvis, take a selfie\n" +
                "Jarvis, call <name>\n" +
                "Jarvis, remind me to <task> in 10 minutes\n" +
                "Jarvis, remind me to <task> at 6 30 p m\n" +
                "Jarvis, set a timer for 5 minutes\n" +
                "Jarvis, remember that <something>\n" +
                "Jarvis, where is my <thing>\n" +
                "Jarvis, <nickname> means <contact name>\n" +
                "Jarvis, flashlight on / off\n" +
                "Jarvis, what time is it\n" +
                "Jarvis, forget everything\n" +
                "----------------"
        )
        speak(
            "I can take selfies, make calls, set reminders and timers, " +
                "remember things for you, control the flashlight, and tell the time."
        )
    }

    // ---------- Reminders and timers ----------

    private val numberWords = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80,
        "ninety" to 90
    )

    /** Reads a number at position i. Returns (value, wordsUsed) or null. */
    private fun numberAt(w: List<String>, i: Int): Pair<Int, Int>? {
        if (i < 0 || i >= w.size) return null
        w[i].toIntOrNull()?.let { return Pair(it, 1) }
        val base = numberWords[w[i]] ?: return null
        if (base in 20..90 && base % 10 == 0 && i + 1 < w.size) {
            val next = numberWords[w[i + 1]]
            if (next != null && next in 1..9) return Pair(base + next, 2)
        }
        return Pair(base, 1)
    }

    private fun unitMillis(token: String): Long? = when (token) {
        "second", "seconds" -> 1000L
        "minute", "minutes" -> 60_000L
        "hour", "hours" -> 3_600_000L
        else -> null
    }

    /** Result of a time search: when to fire, plus the word range that held the time phrase. */
    private class TimeSpec(val triggerAt: Long, val start: Int, val end: Int)

    private fun findDuration(w: List<String>, requireIn: Boolean): TimeSpec? {
        for (i in w.indices) {
            val from: Int
            if (requireIn) {
                if (w[i] != "in") continue
                from = i + 1
            } else {
                from = i
            }
            if (from >= w.size) continue

            val qty: Pair<Int, Int> =
                if (w[from] == "a" || w[from] == "an") Pair(1, 1)
                else numberAt(w, from) ?: continue

            val unitIndex = from + qty.second
            if (unitIndex >= w.size) continue
            val ms = unitMillis(w[unitIndex]) ?: continue

            val trigger = System.currentTimeMillis() + qty.first * ms
            return TimeSpec(trigger, i, unitIndex + 1)
        }
        return null
    }

    private fun nextOccurrence(hour24: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour24)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun findClock(w: List<String>): TimeSpec? {
        for (i in w.indices) {
            if (w[i] != "at") continue
            val h = numberAt(w, i + 1) ?: continue
            var hour = h.first
            if (hour !in 0..23) continue
            var idx = i + 1 + h.second
            var minute = 0

            if (idx < w.size && w[idx] == "o'clock") {
                idx++
            } else if (idx < w.size && w[idx] == "oh") {
                val m = numberAt(w, idx + 1)
                if (m != null && m.first in 1..9) {
                    minute = m.first
                    idx += 1 + m.second
                }
            } else {
                val m = numberAt(w, idx)
                if (m != null && m.first in 0..59 && unitMillis(w.getOrElse(idx + m.second) { "" }) == null) {
                    minute = m.first
                    idx += m.second
                }
            }

            var isPm: Boolean? = null
            if (idx < w.size) {
                val t = w[idx]
                val t2 = w.getOrElse(idx + 1) { "" }
                if (t == "pm" || (t == "p" && t2 == "m")) {
                    isPm = true
                    idx += if (t == "pm") 1 else 2
                } else if (t == "am" || (t == "a" && t2 == "m")) {
                    isPm = false
                    idx += if (t == "am") 1 else 2
                }
            }

            val trigger: Long
            if (isPm != null) {
                if (hour > 12) {
                    trigger = nextOccurrence(hour, minute)
                } else {
                    val h24 = (hour % 12) + if (isPm) 12 else 0
                    trigger = nextOccurrence(h24, minute)
                }
            } else if (hour > 12 || hour == 0) {
                trigger = nextOccurrence(hour, minute)
            } else {
                // No am/pm: pick whichever of the morning or evening time comes first.
                val morning = nextOccurrence(hour % 12, minute)
                val evening = nextOccurrence((hour % 12) + 12, minute)
                trigger = minOf(morning, evening)
            }
            return TimeSpec(trigger, i, idx)
        }
        return null
    }

    private fun scheduleAlarm(triggerAt: Long, message: String) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ReminderReceiver::class.java).putExtra("message", message)
        val code = (System.currentTimeMillis() % 1_000_000_000L).toInt()
        val operation = PendingIntent.getBroadcast(
            this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val show = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), operation)
    }

    private fun setReminder(command: String) {
        val w = command.split(" ").filter { it.isNotEmpty() }
        val spec = findDuration(w, true) ?: findClock(w)
        if (spec == null) {
            speak("When? Say for example, in ten minutes, or at six thirty p m.")
            return
        }

        val messageWords = w.filterIndexed { index, _ -> index < spec.start || index >= spec.end }
            .dropWhile { it == "remind" || it == "me" }
            .dropWhile { it == "to" }
        val message = if (messageWords.isEmpty()) "Reminder" else messageWords.joinToString(" ")

        try {
            scheduleAlarm(spec.triggerAt, message)
            val whenText = SimpleDateFormat("h:mm a", Locale.US).format(Date(spec.triggerAt))
            speak("Okay, I will remind you to $message at $whenText.")
        } catch (e: Exception) {
            speak("Sorry, I could not set that reminder.")
            log("Alarm error: ${e.message}")
        }
    }

    private fun setTimer(command: String) {
        val w = command.split(" ").filter { it.isNotEmpty() }
        val spec = findDuration(w, false)
        if (spec == null) {
            speak("How long? Say for example, set a timer for five minutes.")
            return
        }
        try {
            scheduleAlarm(spec.triggerAt, "Your timer is finished.")
            val whenText = SimpleDateFormat("h:mm a", Locale.US).format(Date(spec.triggerAt))
            speak("Timer set. It will ring at $whenText.")
        } catch (e: Exception) {
            speak("Sorry, I could not set that timer.")
            log("Alarm error: ${e.message}")
        }
    }

    // ---------- Memory ----------

    private fun loadFacts(): MutableList<String> =
        (memoryPrefs.getString("facts", "") ?: "")
            .split("\n")
            .filter { it.isNotBlank() }
            .toMutableList()

    private fun saveFacts(list: List<String>) {
        memoryPrefs.edit().putString("facts", list.takeLast(200).joinToString("\n")).apply()
    }

    private fun rememberFact(command: String) {
        val fact = command.removePrefix("remember").trim().removePrefix("that").trim()
        if (fact.isEmpty()) {
            speak("What should I remember?")
            return
        }
        val facts = loadFacts()
        facts.add(fact)
        saveFacts(facts)
        speak("Okay, I will remember that.")
    }

    private val stopWords = setOf(
        "where", "is", "are", "was", "were", "my", "the", "a", "an", "what", "did", "i",
        "put", "do", "you", "remember", "about", "me", "of", "to", "in", "on", "it",
        "whats", "what's", "tell", "your", "have", "left", "kept", "keep", "that"
    )

    private fun swapPronouns(fact: String): String =
        fact.split(" ").joinToString(" ") {
            when (it) {
                "my" -> "your"
                "mine" -> "yours"
                "me" -> "you"
                "i" -> "you"
                else -> it
            }
        }

    private fun recallFact(command: String) {
        val keys = command.split(" ").filter { it.isNotEmpty() && it !in stopWords }
        if (keys.isEmpty()) {
            speak("What should I look for?")
            return
        }
        val best = loadFacts().asReversed()
            .map { fact -> Pair(fact, keys.count { key -> fact.contains(key) }) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }

        if (best == null) {
            speak("I don't remember anything about that.")
        } else {
            speak("You told me: " + swapPronouns(best.first))
        }
    }

    private fun saveNickname(command: String) {
        val parts = command.split(" means ", limit = 2)
        val alias = parts[0].trim()
        val target = parts.getOrElse(1) { "" }.trim()
        if (alias.isEmpty() || target.isEmpty()) {
            speak("Say it like this: mom means Sarah.")
            return
        }
        aliasPrefs.edit().putString(alias, target).apply()
        speak("Okay, $alias means $target.")
    }

    // ---------- Selfie, call, torch ----------

    private fun takeSelfie() {
        val capture = imageCapture
        if (capture == null) {
            speak("The camera is not ready. Please allow camera permission.")
            return
        }
        speak("Smile!")
        mainHandler.postDelayed({
            val name = "Jarvis_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Jarvis")
            }
            val options = ImageCapture.OutputFileOptions.Builder(
                contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ).build()

            capture.takePicture(
                options,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        speak("Selfie saved.")
                    }

                    override fun onError(exception: ImageCaptureException) {
                        speak("Sorry, the selfie failed.")
                        log("Camera error: ${exception.message}")
                    }
                }
            )
        }, 3500)
    }

    private fun callContact(spokenName: String) {
        if (spokenName.isEmpty()) {
            speak("Who should I call?")
            return
        }
        if (!has(Manifest.permission.READ_CONTACTS) || !has(Manifest.permission.CALL_PHONE)) {
            speak("I need contacts and phone permission.")
            return
        }
        val name = aliasPrefs.getString(spokenName, null) ?: spokenName

        var number: String? = null
        try {
            val phone = ContactsContract.CommonDataKinds.Phone
            contentResolver.query(
                phone.CONTENT_URI,
                arrayOf(phone.NUMBER),
                "${phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) number = cursor.getString(0)
            }
        } catch (e: Exception) {
            log("Contact error: ${e.message}")
        }

        val target = number
        if (target == null) {
            speak("I could not find $name in your contacts.")
            return
        }
        speak("Calling $name")
        mainHandler.postDelayed({
            try {
                startActivity(
                    Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(target)))
                )
            } catch (e: Exception) {
                log("Call error: ${e.message}")
            }
        }, 1500)
    }

    private fun setTorch(on: Boolean) {
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = manager.cameraIdList.firstOrNull { cameraId ->
                val c = manager.getCameraCharacteristics(cameraId)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            if (id == null) {
                speak("This phone has no flashlight.")
                return
            }
            manager.setTorchMode(id, on)
            speak(if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) {
            speak("I could not control the flashlight.")
            log("Torch error: ${e.message}")
        }
    }

    // ---------- Lifecycle ----------

    override fun onPause() {
        super.onPause()
        if (listening) speechService?.setPause(true)
    }

    override fun onResume() {
        super.onResume()
        if (listening) speechService?.setPause(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.shutdown()
        model?.close()
        tts?.shutdown()
    }
}
