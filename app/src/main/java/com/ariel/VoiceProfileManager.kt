package com.ariel.travis

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Phase 1: voice enrollment.
 * Records a short audio sample, extracts a rough frequency-based
 * "voiceprint" (log-energy across bands), and stores it locally
 * keyed by name. Phase 2 will add matching against these profiles.
 */
object VoiceProfileManager {

    private const val SAMPLE_RATE = 16000
    private const val RECORD_SECONDS = 2
    private const val FFT_SIZE = 32768 // next power of 2 above SAMPLE_RATE * RECORD_SECONDS
    private const val NUM_BANDS = 20
    private const val PROFILES_FILE = "voice_profiles.json"

    fun enroll(context: Context, name: String, tts: TextToSpeech?) {
        if (!hasMicPermission(context)) {
            tts?.speak(
                "I need microphone permission to learn your voice.",
                TextToSpeech.QUEUE_FLUSH, null, "voice_enroll_noperm"
            )
            return
        }

        if (tts == null) return

        val promptId = "voice_enroll_prompt"

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (utteranceId == promptId) {
                    // Recording must happen off the main thread
                    Thread {
                        val samples = captureAudio(RECORD_SECONDS * 1000)
                        if (samples != null) {
                            val features = extractFeatures(samples)
                            saveProfile(context, name, features)
                            tts.speak(
                                "Got it, I'll remember your voice as $name.",
                                TextToSpeech.QUEUE_FLUSH, null, "voice_enroll_done"
                            )
                        } else {
                            tts.speak(
                                "Something went wrong while listening. Let's try again.",
                                TextToSpeech.QUEUE_FLUSH, null, "voice_enroll_error"
                            )
                        }
                    }.start()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })

        tts.speak(
            "Okay, say a few words after the beep so I can learn your voice.",
            TextToSpeech.QUEUE_FLUSH, null, promptId
        )
    }

    private fun hasMicPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun captureAudio(durationMs: Int): ShortArray? {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) return null

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2
            )
        } catch (e: SecurityException) {
            return null
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }

        val totalSamples = SAMPLE_RATE * durationMs / 1000
        val buffer = ShortArray(totalSamples)
        var samplesRead = 0

        recorder.startRecording()
        while (samplesRead < totalSamples) {
            val read = recorder.read(buffer, samplesRead, totalSamples - samplesRead)
            if (read <= 0) break
            samplesRead += read
        }
        recorder.stop()
        recorder.release()

        return buffer
    }

    private fun extractFeatures(samples: ShortArray): DoubleArray {
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            real[i] = if (i < samples.size) samples[i].toDouble() / Short.MAX_VALUE else 0.0
        }

        FFT.transform(real, imag)

        val half = FFT_SIZE / 2
        val magnitudes = DoubleArray(half)
        for (i in 0 until half) {
            magnitudes[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
        }

        val bands = DoubleArray(NUM_BANDS)
        val bandSize = half / NUM_BANDS
        for (b in 0 until NUM_BANDS) {
            var sum = 0.0
            val start = b * bandSize
            val end = if (b == NUM_BANDS - 1) half else start + bandSize
            for (i in start until end) {
                sum += magnitudes[i]
            }
            val avg = sum / (end - start)
            bands[b] = ln(avg + 1e-6) // log energy, avoids log(0)
        }

        return bands
    }

    private fun saveProfile(context: Context, name: String, features: DoubleArray) {
        val file = File(context.filesDir, PROFILES_FILE)
        val root = if (file.exists()) JSONObject(file.readText()) else JSONObject()

        val featuresArray = JSONArray()
        for (v in features) featuresArray.put(v)

        root.put(name.lowercase(), featuresArray)
        file.writeText(root.toString())
    }

    /** Used by Phase 2 matching logic. */
    fun loadProfiles(context: Context): Map<String, DoubleArray> {
        val file = File(context.filesDir, PROFILES_FILE)
        if (!file.exists()) return emptyMap()

        val root = JSONObject(file.readText())
        val result = mutableMapOf<String, DoubleArray>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val arr = root.getJSONArray(key)
            val vec = DoubleArray(arr.length()) { i -> arr.getDouble(i) }
            result[key] = vec
        }
        return result
    }

    // Cosine similarity threshold - tune this after real-world testing.
    // Higher = stricter (fewer false accepts, more false rejects).
    private const val MATCH_THRESHOLD = 0.92

    /**
     * Compares a captured audio sample against all stored voice profiles.
     * Returns the best-matching name if similarity clears the threshold,
     * or null if no profile matches closely enough (unknown speaker).
     */
    fun identifySpeaker(context: Context, samples: ShortArray): String? {
        if (samples.isEmpty()) return null

        val profiles = loadProfiles(context)
        if (profiles.isEmpty()) return null

        val candidateFeatures = extractFeatures(samples)

        var bestName: String? = null
        var bestScore = -1.0

        for ((name, storedFeatures) in profiles) {
            val score = cosineSimilarity(candidateFeatures, storedFeatures)
            if (score > bestScore) {
                bestScore = score
                bestName = name
            }
        }

        return if (bestScore >= MATCH_THRESHOLD) bestName else null
    }

    private fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
