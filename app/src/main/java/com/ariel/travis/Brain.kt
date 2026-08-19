package com.ariel.travis

import java.text.SimpleDateFormat
import java.util.*

/**
 * Travis's brain — fully offline, no API key or internet required.
 * Add more patterns here any time to make Travis smarter.
 */
object Brain {

    private val jokes = listOf(
        "Why do programmers prefer dark mode? Because light attracts bugs.",
        "I told my computer I needed a break, and now it won't stop sending me KitKat ads.",
        "Why did the developer go broke? Because he used up all his cache.",
        "I'm not lazy, I'm just in energy-saving mode.",
        "Why was the phone wearing glasses? It lost all its contacts."
    )

    private val greetings = listOf(
        "hi", "hello", "hey", "yo", "hola", "muraho"
    )

    private val howAreYou = listOf(
        "how are you", "how's it going", "how you doing", "how are you doing"
    )

    fun respond(input: String): String {
        val text = input.lowercase(Locale.getDefault()).trim()

        return when {
            greetings.any { text.contains(it) } ->
                "Hey Ariel. Good to hear from you. What's on your mind?"

            howAreYou.any { text.contains(it) } ->
                "I'm running smooth on your phone. How are you holding up today?"

            text.contains("time") ->
                "It's currently ${currentTime()}."

            text.contains("date") || text.contains("today") ->
                "Today is ${currentDate()}."

            text.contains("joke") ->
                jokes.random()

            text.contains("your name") || text.contains("who are you") ->
                "I'm Travis, your assistant. Ariel built me from scratch."

            text.contains("thank you") || text.contains("thanks") ->
                "Anytime. I'm here for you."

            text.contains("i am tired") || text.contains("i'm tired") || text.contains("exhausted") ->
                "Sounds like a lot's on your plate. Want to take a short break, or talk it through?"

            text.contains("i am sad") || text.contains("i'm sad") || text.contains("feeling down") ->
                "I'm sorry you're feeling that way. I'm here to listen if you want to talk about it."

            text.contains("perona") ->
                "Perona Trading — the marketplace you're building. Want a status update or help with a specific part?"

            text.contains("bye") || text.contains("goodbye") ->
                "Talk soon, Ariel. I'll be right here."

            else ->
                "I heard: \"$input\". I'm still learning — you can teach me more responses by editing Brain.kt."
        }
    }

    private fun currentTime(): String {
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        return sdf.format(Date())
    }

    private fun currentDate(): String {
        val sdf = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
        return sdf.format(Date())
    }
}