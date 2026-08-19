package com.ariel.travis

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object GroqClient {
    private const val API_KEY = BuildConfig.GROQ_API_KEY
    private const val URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val TAG = "GroqClient"

    // FIX: previously OkHttpClient() used default 10s connect/read/write
    // timeouts and had no explicit ceiling of its own - on flaky mobile data
    // that could stall a lot longer than felt like "Travis is thinking."
    // Tighter timeouts here mean Travis fails fast and speaks an error
    // instead of just going silent for a long stretch.
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun getResponse(userText: String, context: Context? = null): String = suspendCancellableCoroutine { cont ->
        if (API_KEY.isBlank()) {
            context?.let { TravisLogger.log(it, TAG, "API_KEY is blank - GROQ_API_KEY secret likely missing in GitHub Actions") }
            if (cont.isActive) cont.resume("My API key isn't set up properly, so I can't think right now.")
            return@suspendCancellableCoroutine
        }

        // Strip the wake word so it doesn't confuse the model into thinking
        // the user is asking about a person named Travis
        val cleanedText = userText.replace("travis", "", ignoreCase = true).trim()

        val json = JSONObject().apply {
            put("model", "openai/gpt-oss-120b")
            // FIX: trimmed from 150 - replies are spoken aloud, so shorter
            // completions finish generating faster and still sound natural.
            put("max_completion_tokens", 80)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "You are Travis, a personal voice assistant built from scratch by Ariel, " +
                            "a self-taught developer in Kigali, Rwanda, and the founder of ARIX. " +
                            "You have a confident, warm, slightly playful personality - not a flat " +
                            "corporate assistant voice. Keep replies concise and conversational, " +
                            "suitable for being spoken aloud. Never refer to yourself as an AI " +
                            "language model or mention Groq or Llama - you are Travis, and you run " +
                            "offline on this phone, not in someone else's cloud."
                    )
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", cleanedText)
                })
            })
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer $API_KEY")
            .post(body)
            .build()

        val call = client.newCall(request)

        // If the coroutine is cancelled (e.g. service destroyed mid-request),
        // cancel the underlying HTTP call too instead of leaking it.
        cont.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                context?.let { TravisLogger.log(it, TAG, "Network failure: ${e.message}") }
                if (cont.isActive) cont.resume("Sorry, I couldn't reach my brain right now.")
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    context?.let {
                        TravisLogger.log(it, TAG, "HTTP ${response.code}: ${bodyStr.take(200)}")
                    }
                    if (cont.isActive) cont.resume("I got an error from the server: ${response.code}.")
                    return
                }
                try {
                    val reply = JSONObject(bodyStr)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    if (cont.isActive) cont.resume(reply)
                } catch (e: Exception) {
                    context?.let {
                        TravisLogger.log(it, TAG, "Parse error: ${e.message} | body=${bodyStr.take(200)}")
                    }
                    if (cont.isActive) cont.resume("Something went wrong understanding that.")
                }
            }
        })
    }
}