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
    }

    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var hasAudioFocus = false

    // Accumulates raw audio for the current utterance, used for voice verification
    private var audioBuffer = ByteArrayOutputStream()

    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var focusRequest: AudioFocusRequest? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { /* no-op: we hold focus for the service lifetime */ }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Listening in the background"))
        tts = TextToSpeech(this, this)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        requestAudioFocusOnce()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startListening()
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    /**
     * Requests audio focus exactly once for the lifetime of the service, instead of
     * grabbing/releasing it (or manually muting/unmuting streams) on every listening
     * cycle. Repeated focus changes or stream mute toggles are what caused the
     * perceived "volume going up and down" - this holds focus steady so nothing
     * oscillates while Travis keeps listening in the background.
     */
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
        if (isListening) return
        isListening = true

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val heard = matches?.get(0)?.lowercase(Locale.getDefault()) ?: ""

                if (heard.contains("travis")) {
                    val isEnrollCommand = heard.contains("remember my voice")
                    val capturedSamples = bytesToShorts(audioBuffer.toByteArray())
                    val profiles = VoiceProfileManager.loadProfiles(applicationContext)
                    val speakerName = VoiceProfileManager.identifySpeaker(applicationContext, capturedSamples)

                    // Allow through if: it's an enrollment command, no profiles exist yet
                    // (bootstrap case), or the speaker matched a known profile.
                    val allowed = isEnrollCommand || profiles.isEmpty() || speakerName != null

                    if (allowed) {
                        val handledTask = TaskHandler.handle(applicationContext, heard, tts)
                        if (!handledTask) {
                            CoroutineScope(Dispatchers.IO).launch {
                                val reply = GroqClient.getResponse(heard)
                                withContext(Dispatchers.Main) {
                                    tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "travis_reply")
                                }
                            }
                        }
                        // If handledTask is true, the individual handler in TaskHandler
                        // already spoke whatever confirmation was needed (or intentionally
                        // stayed silent for actions like opening an app).
                    }
                    // If not allowed: unknown voice, ignore silently - no response at all.
                }

                isListening = false
                restartListening()
            }

            override fun onError(error: Int) {
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

        // No manual stream muting here anymore - audio focus is requested once in
        // onCreate() and held for the service's lifetime, so restarting the
        // recognizer no longer touches the volume at all.
        recognizer?.startListening(intent)
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
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
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
