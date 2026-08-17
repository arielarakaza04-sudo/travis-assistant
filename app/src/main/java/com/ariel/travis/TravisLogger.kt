package com.ariel.travis

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes timestamped log lines to a plain text file so they can be read
 * directly in Acode - no adb, no Termux, no terminal needed.
 *
 * File location: /storage/emulated/0/Android/data/com.ariel.travis/files/travis_log.txt
 * Open that path in Acode's file browser after Travis has run for a bit.
 */
object TravisLogger {

    private const val FILE_NAME = "travis_log.txt"
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // In-memory rolling buffer of the most recent log lines. This is what
    // actually gets read back (via the notification), since reaching the
    // log file requires storage access that's proven hard to get to in Acode.
    private const val MAX_LINES = 12
    private val recentLines = ArrayDeque<String>()

    // Optional callback the service can set to be notified whenever a new
    // log line arrives, so it can refresh the notification immediately.
    var onNewLine: (() -> Unit)? = null

    @Synchronized
    private fun addLine(line: String) {
        recentLines.addLast(line)
        while (recentLines.size > MAX_LINES) {
            recentLines.removeFirst()
        }
        onNewLine?.invoke()
    }

    @Synchronized
    fun getRecent(): String {
        return if (recentLines.isEmpty()) "No activity logged yet." else recentLines.joinToString("\n")
    }

    fun log(context: Context, tag: String, message: String) {
        val line = "${timeFormat.format(Date())} [$tag] $message"
        addLine(line)
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, FILE_NAME)
            FileWriter(file, true).use { it.append("$line\n") }
        } catch (e: Exception) {
            // Logging must never crash the app it's trying to debug.
        }
    }

    fun logCrash(context: Context, throwable: Throwable) {
        val line = "${timeFormat.format(Date())} [FATAL] ${throwable.message ?: throwable.toString()}"
        addLine(line)
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, FILE_NAME)
            FileWriter(file, true).use { it.append("$line\n${throwable.stackTraceToString()}\n") }
        } catch (e: Exception) {
            // ignore
        }
    }
}