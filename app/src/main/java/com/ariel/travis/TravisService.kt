
package com.ariel.travis

import kotlinx.coroutines.*
import android.app.*
import android.content.Context
import android.content.Intent
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
    private var isMuted = false

    // Accumulates raw audio for the current utterance, used for voice verification
    private var audioBuffer = ByteArrayOutputStream()

    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Listening in the background"))
        tts = TextToSpeech(this, this)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
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
            override fun onEndOfSpeech() {
                // Mute handling happens once per cycle in startListening() -> muteBeep(),
                // not here, to avoid overlapping mute/unmute timers.
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer?.startListening(intent)
        muteBeep()
    }

    private fun muteBeep() {
        // Guard against overlapping mute/unmute cycles when restarts happen
        // faster than the unmute timer fires - this was the source of the
        // volume "jumping" on its own.
        if (isMuted) return
        isMuted = true
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        android.os.Handler(mainLooper).postDelayed({
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            isMuted = false
        }, 400)
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
