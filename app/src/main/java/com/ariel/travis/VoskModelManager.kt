package com.ariel.travis

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Downloads the small English Vosk model (~40MB) the first time Travis runs,
 * unpacks it into app-private storage, and loads it. On every run after that,
 * it's already on disk, so this just loads it directly - no network needed.
 *
 * This avoids committing a large binary model file to the GitHub repo, which
 * would exceed GitHub's mobile web upload size limit.
 */
object VoskModelManager {

    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val MODEL_FOLDER_NAME = "vosk-model-small-en-us-0.15"

    private val client = OkHttpClient()

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
            try {
                onProgress("Downloading speech model (~40MB, first run only)...")
                baseDir.mkdirs()
                val zipFile = File(context.filesDir, "vosk-model.zip")

                val request = Request.Builder().url(MODEL_URL).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Model download failed: HTTP ${response.code}")
                    }
                    val body = response.body ?: throw Exception("Empty response body")
                    FileOutputStream(zipFile).use { output ->
                        body.byteStream().copyTo(output)
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
            } catch (e: Exception) {
                onError(e)
            }
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
