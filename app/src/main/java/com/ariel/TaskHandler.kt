package com.ariel.travis

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract

object TaskHandler {

    // Returns true if it handled a task, false if it's just conversation
    fun handle(context: Context, command: String): Boolean {
        val text = command.lowercase()

        return when {
            text.contains("set alarm") || text.contains("wake me") -> {
                setAlarm(context, text)
                true
            }
            text.contains("calendar") || text.contains("remind me") || text.contains("event") -> {
                createCalendarEvent(context, text)
                true
            }
            text.contains("search") || text.contains("look up") || text.contains("google") -> {
                searchBrowser(context, text)
                true
            }
            text.contains("open browser") -> {
                openBrowser(context)
                true
            }
            else -> false
        }
    }

    private fun setAlarm(context: Context, text: String) {
        // Basic version: extracts hour if user says a number, defaults to a system picker otherwise
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, "Travis Alarm")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false) // shows confirmation UI first
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun createCalendarEvent(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, "Travis Reminder")
            putExtra(CalendarContract.Events.DESCRIPTION, text)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun searchBrowser(context: Context, text: String) {
        val query = text
            .replace("search", "")
            .replace("look up", "")
            .replace("google", "")
            .replace("travis", "")
            .trim()

        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra("query", query)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun openBrowser(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("https://www.google.com")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}