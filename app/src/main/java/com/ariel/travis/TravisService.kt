package com.ariel.travis

import kotlinx.coroutines.*
import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class TravisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "travis_service_channel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "TravisService"
    }

    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var hasAudioFocus = false

    private var audioBuffer = ByteArrayOutputStream()

    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var focusRequest: AudioFocusRequest? = null
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    override fun onCreate() {
        super.onCreate()

        // Catch anything that would otherwise silently kill the process, and
        // write it to travis_log.txt before the app dies, so we can read the
        // real crash reason in Acode instead of guessing.
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            TravisLogger.logCrash(applicationContext, throwable)
        }

        // Whenever a new log line comes in, refresh the notification so
        // expanding it in the shade shows current activity - no file
        // browsing or terminal needed to see what Travis is doing.
        TravisLogger.onNewLine = { refreshNotification() }

        TravisLogger.log(this, TAG, "onCreate() start")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Listening in the background"))

        tts = TextToSpeech(this, this)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)

        requestAudioFocusOnce()
        TravisLogger.log(this, TAG, "onCreate() done, hasAudioFocus=$hasAudioFocus")
        TravisLogger.log(this, TAG, "isRecognitionAvailable=${SpeechRecognizer.isRecognitionAvailable(this)}")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TravisLogger.log(this, TAG, "onStartCommand()")
        startListening()
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
        TravisLogger.log(this, TAG, "TTS init status=$status")
    }

    private fun requestAudioFocusOnce() {
        if (hasAudioFocus) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()

            focusRequest = request
            val result = audioManager.requestAudioFocus(request)
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }
        TravisLogger.log(this, TAG, "requestAudioFocusOnce() -> $hasAudioFocus")
    }

    private fun releaseAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        hasAudioFocus = false
    }

    private fun startListening() {
        if (isListening) {
            TravisLogger.log(this, TAG, "startListening() skipped, already listening")
            return
        }
        isListening = true
        TravisLogger.log(this, TAG, "startListening()")

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Hardcoded to en-US instead of Locale.getDefault(). On a device set to
            // a Rwandan locale, getDefault() resolves to a language Google's speech
            // recognizer doesn't support (Kinyarwanda), causing every single
            // recognition attempt to instantly fail with ERROR_LANGUAGE_NOT_SUPPORTED.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val heard = matches?.get(0)?.lowercase(Locale.getDefault()) ?: ""
                TravisLogger.log(this@TravisService, TAG, "onResults heard=\"$heard\"")

                if (heard.contains("travis")) {
                    val isEnrollCommand = heard.contains("remember my voice")
                    val capturedSamples = bytesToShorts(audioBuffer.toByteArray())
                    val profiles = VoiceProfileManager.loadProfiles(applicationContext)
                    val speakerName = VoiceProfileManager.identifySpeaker(applicationContext, capturedSamples)

                    val allowed = isEnrollCommand || profiles.isEmpty() || speakerName != null
                    TravisLogger.log(this@TravisService, TAG, "allowed=$allowed speaker=$speakerName")

                    if (allowed) {
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

                isListening = false
                restartListening()
            }

            override fun onError(error: Int) {
                TravisLogger.log(this@TravisService, TAG, "onError code=$error")
                isListening = false
                val delay = when (error) {
                    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1000L
                    else -> 500L
                }
                restartListening(delay)
            }

            override fun onReadyForSpeech(params: Bundle?) {
                audioBuffer = ByteArrayOutputStream()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {
                buffer?.let { audioBuffer.write(it) }
            }
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            TravisLogger.log(this, TAG, "startListening() threw: ${e.message}")
            isListening = false
            restartListening(1000L)
        }
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        if (bytes.isEmpty()) return ShortArray(0)
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private fun restartListening(delay: Long = 500L) {
        android.os.Handler(mainLooper).postDelayed({ startListening() }, delay)
    }

    override fun onDestroy() {
        TravisLogger.log(this, TAG, "onDestroy()")
        recognizer?.destroy()
        releaseAudioFocus()
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Travis")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(TravisLogger.getRecent()))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    /**
     * Pull down the notification shade and expand the Travis notification
     * to see this - it's the recent activity log, updated live. This is the
     * debugging view: no file browsing or terminal required.
     */
    private fun refreshNotification() {
        try {
            val notification = buildNotification("Listening in the background")
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
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
