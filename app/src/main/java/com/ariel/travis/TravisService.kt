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
    }

    private lateinit var tts: TextToSpeech
    private var model: Model? = null
    private var speechService: SpeechService? = null

    private var statusLine: String = "Starting..."
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()

        // Catch anything that would otherwise silently kill the process, and
        // write it to the notification log before the app dies.
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
            tts.language = Locale.US
        }
        TravisLogger.log(this, TAG, "TTS init status=$status")
    }

    private fun startVoskListening() {
        val currentModel = model ?: return
        try {
            val recognizer = Recognizer(currentModel, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE)
            // No timeout arg - listens continuously instead of the old
            // single-shot-then-restart loop that caused the error 9/12 storm.
            speechService?.startListening(this)
            statusLine = "Listening"
            TravisLogger.log(this, TAG, "Vosk listening started")
            refreshNotification()
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

        if (heard.contains("travis")) {
            val handledTask = TaskHandler.handle(applicationContext, heard, tts)
            if (!handledTask) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val reply = GroqClient.getResponse(heard)
                        withContext(Dispatchers.Main) {
                            tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "travis_reply")
                        }
                    } catch (e: Exception) {
                        TravisLogger.log(this@TravisService, TAG, "GroqClient error: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        TravisLogger.log(this, TAG, "onDestroy()")
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
