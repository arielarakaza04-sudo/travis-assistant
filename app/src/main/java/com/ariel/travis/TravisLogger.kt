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

    fun log(context: Context, tag: String, message: String) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, FILE_NAME)
            val line = "${timeFormat.format(Date())}  [$tag]  $message\n"
            FileWriter(file, true).use { it.append(line) }
        } catch (e: Exception) {
            // Logging must never crash the app it's trying to debug.
        }
    }

    fun logCrash(context: Context, throwable: Throwable) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, FILE_NAME)
            val line = "${timeFormat.format(Date())}  [FATAL]  ${throwable.stackTraceToString()}\n"
            FileWriter(file, true).use { it.append(line) }
        } catch (e: Exception) {
            // ignore
        }
    }
}
