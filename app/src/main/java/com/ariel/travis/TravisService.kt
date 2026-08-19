package com.ariel.travis

import kotlinx.coroutines.*
import android.app.*
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.Locale

class TravisService : Service(), TextToSpeech.OnInitListener, RecognitionListener {

    companion object {
        const val CHANNEL_ID = "travis_service_channel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "TravisService"
        private const val SAMPLE_RATE = 16000.0f

        // Fuzzy wake word - Vosk on the lgraph model frequently mishears
        // "travis" as one of these, especially with a non-US accent. Add
        // to this list as real mishears show up in TravisLogger.
        private val WAKE_WORD_VARIANTS = listOf(
            "travis", "travus", "traves", "travis's", "trellis", "davis", "travas", "traviss"
        )
    }

    private lateinit var tts: TextToSpeech
    private var model: Model? = null
    private var speechService: SpeechService? = null

    private var statusLine: String = "Starting..."
    private val mainHandler = Handler(Looper.getMainLooper())

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            TravisLogger.logCrash(applicationContext, throwable)
        }

        TravisLogger.onNewLine = { refreshNotification() }
        TravisLogger.log(this, TAG, "onCreate() start")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        tts = TextToSpeech(this, this)

        VoskModelManager.getOrDownloadModel(
            context = applicationContext,
            onProgress = { msg ->
                mainHandler.post {
                    statusLine = msg
                    TravisLogger.log(this, TAG, "Model: $msg")
                    refreshNotification()
                }
            },
            onReady = { loadedModel ->
                mainHandler.post {
                    model = loadedModel
                    TravisLogger.log(this, TAG, "Model ready")
                    startVoskListening()
                }
            },
            onError = { e ->
                mainHandler.post {
                    statusLine = "Model error: ${e.message}"
                    TravisLogger.log(this, TAG, "Model error: ${e.message}")
                    refreshNotification()
                }
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TravisLogger.log(this, TAG, "onStartCommand()")
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val langResult = tts.setLanguage(Locale.US)
            TravisLogger.log(this, TAG, "setLanguage result=$langResult (0=OK, -1/-2=missing/unsupported)")
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    TravisLogger.log(this@TravisService, TAG, "TTS onStart id=$utteranceId")
                    speechService?.setPause(true)
                }
                override fun onDone(utteranceId: String?) {
                    TravisLogger.log(this@TravisService, TAG, "TTS onDone id=$utteranceId")
                    speechService?.setPause(false)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    TravisLogger.log(this@TravisService, TAG, "TTS onError id=$utteranceId")
                    speechService?.setPause(false)
                }
            })
        } else {
            TravisLogger.log(this, TAG, "TTS init FAILED status=$status")
        }
        TravisLogger.log(this, TAG, "TTS init status=$status")
    }

    private fun startVoskListening() {
        val currentModel = model ?: return
        try {
            // Open-vocabulary recognizer - kept unconstrained because
            // TaskHandler needs to catch arbitrary contact names, search
            // terms, song titles, and book names, not just fixed phrases.
            val recognizer = Recognizer(currentModel, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            speechService?.startListening(this)
            statusLine = "Listening"
            TravisLogger.log(this, TAG, "Vosk listening started")
            refreshNotification()

            tts.speak(
                "Travis is online and ready.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "travis_greeting"
            )
        } catch (e: Exception) {
            statusLine = "Failed to start listening: ${e.message}"
            TravisLogger.log(this, TAG, "startVoskListening error: ${e.message}")
            refreshNotification()
        }
    }

    // --- org.vosk.android.RecognitionListener ---

    override fun onPartialResult(hypothesis: String?) {
        // Intentionally not logged - fires very frequently, would flood the log.
    }

    override fun onResult(hypothesis: String?) {
        handleHypothesis(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {
        handleHypothesis(hypothesis)
    }

    override fun onError(exception: Exception?) {
        TravisLogger.log(this, TAG, "Vosk onError: ${exception?.message}")
    }

    override fun onTimeout() {
        TravisLogger.log(this, TAG, "Vosk onTimeout")
    }

    private fun containsWakeWord(heard: String): Boolean {
        return WAKE_WORD_VARIANTS.any { heard.contains(it) }
    }

    private fun handleHypothesis(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return

        val heard = try {
            JSONObject(hypothesis).optString("text", "").lowercase(Locale.getDefault())
        } catch (e: Exception) {
            TravisLogger.log(this, TAG, "JSON parse error: ${e.message}")
            ""
        }

        if (heard.isBlank()) return
        TravisLogger.log(this, TAG, "heard=\"$heard\"")

        if (!containsWakeWord(heard)) return

        serviceScope.launch {
            val handledTask = try {
                TaskHandler.handle(applicationContext, heard, tts)
            } catch (e: Exception) {
                TravisLogger.log(this@TravisService, TAG, "TaskHandler crashed: ${e.message}")
                false
            }
            if (!handledTask) {
                try {
                    val reply = GroqClient.getResponse(heard, applicationContext)
                    withContext(Dispatchers.Main) {
                        tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "travis_reply")
                    }
                } catch (e: Exception) {
                    TravisLogger.log(this@TravisService, TAG, "GroqClient error: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        TravisLogger.log(this, TAG, "onDestroy()")
        serviceScope.cancel()
        speechService?.stop()
        speechService?.shutdown()
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Travis")
            .setContentText(statusLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$statusLine\n\n${TravisLogger.getRecent()}"))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun refreshNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // ignore - notification refresh failing shouldn't crash the service
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Travis Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}