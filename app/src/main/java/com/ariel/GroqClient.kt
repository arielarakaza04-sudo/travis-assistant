package com.ariel.travis

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
   private const val API_KEY = "PLACEHOLDER"
    private const val URL = "https://api.groq.com/openai/v1/chat/completions"
    private val client = OkHttpClient()

    suspend fun getResponse(userText: String): String = suspendCancellableCoroutine { cont ->
        val json = JSONObject().apply {
            put("model", "llama-3.3-70b-versatile")
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", userText)
                }
            ))
        }

        val body = RequestBody.create(
            MediaType.parse("application/json"), json.toString()
        )

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
                    val bodyStr = response.body()?.string() ?: ""
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