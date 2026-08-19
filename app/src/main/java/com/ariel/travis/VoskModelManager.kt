package com.ariel.travis

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Downloads the Vosk model the first time Travis runs, unpacks it into
 * app-private storage, and loads it. On every run after that, it's already
 * on disk, so this just loads it directly - no network needed.
 *
 * This avoids committing a large binary model file to the GitHub repo, which
 * would exceed GitHub's mobile web upload size limit.
 */
object VoskModelManager {

    // FIX: upgraded from vosk-model-small-en-us-0.15 (~40MB) to the lgraph
    // model (~128MB). The small model was mishearing basic commands and even
    // the "travis" wake word itself. This one trades a bigger one-time
    // download for meaningfully better recognition accuracy.
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip"
    private const val MODEL_FOLDER_NAME = "vosk-model-en-us-0.22-lgraph"
    private const val MAX_ATTEMPTS = 4

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun getOrDownloadModel(
        context: Context,
        onProgress: (String) -> Unit,
        onReady: (Model) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val baseDir = File(context.filesDir, "vosk-model")
        val modelDir = File(baseDir, MODEL_FOLDER_NAME)

        if (isValidModelDir(modelDir)) {
            onProgress("Model already downloaded, loading...")
            loadModel(modelDir, onReady, onError)
            return
        }

        Thread {
            var lastError: Exception? = null
            for (attempt in 1..MAX_ATTEMPTS) {
                try {
                    onProgress("Downloading speech model (~128MB, attempt $attempt/$MAX_ATTEMPTS)...")
                    baseDir.mkdirs()
                    val zipFile = File(context.filesDir, "vosk-model.zip")

                    val request = Request.Builder().url(MODEL_URL).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw Exception("Model download failed: HTTP ${response.code}")
                        }
                        val body = response.body ?: throw Exception("Empty response body")
                        val totalBytes = body.contentLength()
                        var downloaded = 0L
                        var lastReportedMb = -1L

                        body.byteStream().use { input ->
                            FileOutputStream(zipFile).use { output ->
                                val buffer = ByteArray(65536)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    val mb = downloaded / (1024 * 1024)
                                    if (mb != lastReportedMb) {
                                        lastReportedMb = mb
                                        val totalMb = if (totalBytes > 0) (totalBytes / (1024 * 1024)).toString() else "?"
                                        onProgress("Downloading model: ${mb}MB / ${totalMb}MB...")
                                    }
                                }
                            }
                        }
                    }

                    onProgress("Unpacking model...")
                    unzip(zipFile, baseDir)
                    zipFile.delete()

                    if (!isValidModelDir(modelDir)) {
                        throw Exception("Model extracted but expected files are missing - download may be corrupt")
                    }

                    onProgress("Loading model...")
                    loadModel(modelDir, onReady, onError)
                    return@Thread
                } catch (e: Exception) {
                    lastError = e
                    onProgress("Attempt $attempt failed: ${e.message}. Retrying...")
                    Thread.sleep(3000)
                }
            }
            onError(lastError ?: Exception("Model download failed after $MAX_ATTEMPTS attempts"))
        }.start()
    }

    private fun isValidModelDir(modelDir: File): Boolean {
        return modelDir.exists() && File(modelDir, "am").exists() && File(modelDir, "conf").exists()
    }

    private fun loadModel(modelDir: File, onReady: (Model) -> Unit, onError: (Exception) -> Unit) {
        try {
            val model = Model(modelDir.absolutePath)
            onReady(model)
        } catch (e: Exception) {
            onError(e)
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(8192)
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}