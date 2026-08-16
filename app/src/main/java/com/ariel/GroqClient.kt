package com.ariel.travis

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
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

    suspend fun getResponse(userText: String): String = suspendCancellableCoroutine { cont ->
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
                if (cont.isActive) cont.resume("Sorry, I couldn't reach my brain right now.")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bodyStr = response.body?.string() ?: ""
                    val reply = JSONObject(bodyStr)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    if (cont.isActive) cont.resume(reply)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume("Something went wrong understanding that.")
                }
            }
        })
    }
}
