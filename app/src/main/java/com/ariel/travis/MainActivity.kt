package com.ariel.travis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else {
            true // not needed below Android 13
        }
        // Call/contacts are optional features - Travis still starts without them,
        // it just won't be able to place calls until granted.
        val callGranted = permissions[Manifest.permission.CALL_PHONE] ?: false
        val contactsGranted = permissions[Manifest.permission.READ_CONTACTS] ?: false

        if (micGranted && notifGranted) {
            startTravisService()
        }
        // If mic/notif denied, Travis simply won't start listening until granted.
        // If call/contacts denied, calling commands just won't work - handled gracefully
        // in TaskHandler at request time rather than here.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val neededPermissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = neededPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startTravisService()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startTravisService() {
        val serviceIntent = Intent(this, TravisService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}