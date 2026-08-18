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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object GroqClient {
    private const val API_KEY = BuildConfig.GROQ_API_KEY
    private const val URL = "https://api.groq.com/openai/v1/chat/completions"
    private val client = OkHttpClient()
    private const val TAG = "GroqClient"

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
            put("model", "llama-3.3-70b-versatile")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "You are Travis, a helpful personal voice assistant on the user's phone. " +
                            "Keep replies concise and conversational, suitable for being spoken aloud. " +
                            "Never refer to yourself as an AI language model or mention Groq or Llama - you are Travis."
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

        client.newCall(request).enqueue(object : Callback {
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
