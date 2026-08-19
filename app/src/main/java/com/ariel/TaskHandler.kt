package com.ariel.travis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import android.speech.tts.TextToSpeech
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object TaskHandler {

    private const val TAG = "TaskHandler"

    private val weatherClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // FIX: TaskHandler now runs off the main thread (fix #1, to stop it
    // blocking Vosk). But TextToSpeech was created on the main thread, and
    // calling tts.speak() from a background thread without a Looper caused
    // it to silently fail - onStart would fire but no audio ever played.
    // Every speak call in this file now routes through here so it always
    // executes on the main thread, regardless of what thread handle() itself
    // is running on.
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun speakMain(tts: TextToSpeech?, text: String, utteranceId: String) {
        if (tts == null) return
        mainHandler.post {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    private fun speakMainAdd(tts: TextToSpeech?, text: String, utteranceId: String) {
        if (tts == null) return
        mainHandler.post {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    fun handle(context: Context, command: String, tts: TextToSpeech? = null): Boolean {
        val text = command.lowercase()

        return when {
            text.contains("what time") || text.contains("the time") || text.contains("current time") -> {
                tellTime(context, tts)
                true
            }
            text.contains("weather") -> {
                tellWeather(context, tts)
                true
            }
            text.contains("alarm") || text.contains("wake me") -> {
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
            text.contains("browser") -> {
                openBrowser(context)
                true
            }
            text.contains("gallery") || text.contains("photos") || text.contains("photo") -> {
                openGallery(context)
                true
            }
            text.contains("call ") -> {
                callContact(context, text, tts)
                true
            }
            text.contains("play ") -> {
                playMedia(context, text, tts)
                true
            }
            text.contains("read ") -> {
                readBook(context, text, tts)
                true
            }
            text.contains("remember my voice") -> {
                enrollVoice(context, text, tts)
                true
            }
            else -> false
        }
    }

    private fun tellTime(context: Context, tts: TextToSpeech?) {
        val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
        val currentTime = formatter.format(Date())
        speakMain(tts, "It's $currentTime.", "travis_time")
        TravisLogger.log(context, TAG, "tellTime: ttsNull=${tts == null} time=$currentTime")
    }

    private fun tellWeather(context: Context, tts: TextToSpeech?) {
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocationPermission) {
            speakMain(tts, "I need location permission to check the weather.", "travis_weather_noperm")
            return
        }

        Thread {
            try {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)

                if (location == null) {
                    speakMain(tts, "I couldn't get your location to check the weather.", "travis_weather_nolocation")
                    return@Thread
                }

                val url = "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}" +
                    "&longitude=${location.longitude}&current_weather=true"
                val request = Request.Builder().url(url).build()

                weatherClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        speakMain(tts, "I couldn't reach the weather service.", "travis_weather_error")
                        return@Thread
                    }
                    val body = response.body?.string() ?: ""
                    val currentWeather = JSONObject(body).getJSONObject("current_weather")
                    val tempC = currentWeather.getDouble("temperature")
                    speakMain(tts, "It's currently ${tempC.toInt()} degrees Celsius outside.", "travis_weather")
                }
            } catch (e: Exception) {
                speakMain(tts, "I had trouble checking the weather.", "travis_weather_error")
            }
        }.start()
    }

    private fun setAlarm(context: Context, text: String) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, "Travis Alarm")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val canResolve = intent.resolveActivity(context.packageManager) != null
        TravisLogger.log(context, TAG, "setAlarm: canResolve=$canResolve")
        try {
            context.startActivity(intent)
            TravisLogger.log(context, TAG, "setAlarm: startActivity succeeded")
        } catch (e: Exception) {
            TravisLogger.log(context, TAG, "setAlarm: startActivity threw ${e.message}")
        }
    }

    private fun createCalendarEvent(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, "Travis Reminder")
            putExtra(CalendarContract.Events.DESCRIPTION, text)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // No calendar app can handle this intent - fail safely instead
            // of crashing the whole service.
        }
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

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            val encoded = Uri.encode(query)
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://www.google.com/search?q=$encoded")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallback)
        }
    }

    private fun openBrowser(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://www.google.com")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun openGallery(context: Context) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_GALLERY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                data = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallback)
        }
    }

    private fun callContact(context: Context, text: String, tts: TextToSpeech?) {
        val name = text.substringAfter("call ").trim()

        if (name.isEmpty()) {
            speakMain(tts, "Who do you want to call?", "travis_call_empty")
            return
        }

        val hasCallPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCallPermission || !hasContactsPermission) {
            speakMain(tts, "I need call and contacts permission to do that.", "travis_call_noperm")
            return
        }

        val phoneNumber = lookupContactNumber(context, name)

        if (phoneNumber == null) {
            speakMain(tts, "I couldn't find $name in your contacts.", "travis_call_notfound")
            return
        }

        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(callIntent)
    }

    private fun lookupContactNumber(context: Context, name: String): String? {
        val resolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                return cursor.getString(numberIndex)
            }
        }
        return null
    }

    private fun playMedia(context: Context, text: String, tts: TextToSpeech?) {
        val query = text.substringAfter("play ").trim()

        if (query.isEmpty()) {
            speakMain(tts, "What do you want to play?", "travis_play_empty")
            return
        }

        val localUri = findLocalAudio(context, query)
        if (localUri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(localUri, "audio/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            return
        }

        val encoded = Uri.encode(query)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://www.youtube.com/results?search_query=$encoded")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun findLocalAudio(context: Context, query: String): Uri? {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_AUDIO
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) return null

        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE)
        val selection = "${MediaStore.Audio.Media.TITLE} LIKE ?"
        val selectionArgs = arrayOf("%$query%")

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val id = cursor.getLong(idIndex)
                return Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
            }
        }
        return null
    }

    private fun readBook(context: Context, text: String, tts: TextToSpeech?) {
        val name = text.substringAfter("read ").trim()

        if (name.isEmpty()) {
            speakMain(tts, "Which book do you want me to read?", "travis_read_empty")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            speakMain(tts, "I need file access permission first. Please grant All files access in settings.", "travis_read_noperm")
            return
        }

        val documentsDir = File(Environment.getExternalStorageDirectory(), "Documents")
        val pdfFile = documentsDir.listFiles { f ->
            f.extension.equals("pdf", ignoreCase = true) &&
                f.nameWithoutExtension.contains(name, ignoreCase = true)
        }?.firstOrNull()

        if (pdfFile == null) {
            speakMain(tts, "I couldn't find $name in your Documents folder.", "travis_read_notfound")
            return
        }

        try {
            PDFBoxResourceLoader.init(context.applicationContext)
            val document = PDDocument.load(pdfFile)
            val stripper = PDFTextStripper()
            val fullText = stripper.getText(document)
            document.close()
            speakInChunks(fullText, tts)
        } catch (e: Exception) {
            speakMain(tts, "I had trouble reading that file.", "travis_read_error")
        }
    }

    private fun enrollVoice(context: Context, text: String, tts: TextToSpeech?) {
        val name = if (text.contains(" as ")) {
            text.substringAfterLast(" as ").trim()
        } else {
            ""
        }

        if (name.isEmpty()) {
            speakMain(tts, "Who should I remember this voice as? Try saying, remember my voice as your name.", "voice_enroll_noname")
            return
        }

        VoiceProfileManager.enroll(context, name, tts)
    }

    private fun speakInChunks(text: String, tts: TextToSpeech?) {
        if (tts == null) return

        val maxLen = 3500
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        for (sentence in sentences) {
            if (current.length + sentence.length > maxLen) {
                chunks.add(current.toString())
                current.clear()
            }
            current.append(sentence).append(" ")
        }
        if (current.isNotEmpty()) chunks.add(current.toString())

        chunks.forEachIndexed { index, chunk ->
            if (index == 0) {
                speakMain(tts, chunk, "travis_read_chunk_$index")
            } else {
                speakMainAdd(tts, chunk, "travis_read_chunk_$index")
            }
        }
    }
}