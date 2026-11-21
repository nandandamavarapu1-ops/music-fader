package com.musicfader.app.ui

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.musicfader.app.R
import com.musicfader.app.databinding.ActivityMainBinding
import com.musicfader.app.service.FaderNotificationListener
import com.musicfader.app.service.FaderService
import com.musicfader.app.util.Prefs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    // Receives STATE_CHANGED broadcasts from FaderService so the toggle stays
    // in sync when changed from the notification.
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == FaderService.ACTION_STATE_CHANGED) {
                syncToggleUi()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        setupToggle()
        setupFadeInSlider()
        setupFadeOutSlider()
        setupPermissionBanner()
    }

    override fun onResume() {
        super.onResume()
        syncToggleUi()
        syncSliders()
        updatePermissionBanner()

        val filter = IntentFilter(FaderService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
    }

    // -------------------------------------------------------------------------
    // UI setup
    // -------------------------------------------------------------------------

    private fun setupToggle() {
        binding.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !hasNotificationListenerPermission()) {
                // Cannot enable without notification access.
                binding.toggleSwitch.isChecked = false
                promptNotificationAccess()
                return@setOnCheckedChangeListener
            }

            prefs.isEnabled = isChecked
            if (isChecked) {
                startFaderService()
            } else {
                // Keep service running but tell it to pause fading.
                sendServiceToggle()
            }
            updateToggleLabel(isChecked)
        }
    }

    private fun setupFadeInSlider() {
        // Range: 1–10 seconds, step 1 s.
        binding.seekFadeIn.max = 9   // 0-indexed → 1..10
        binding.seekFadeIn.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val seconds = progress + 1
                prefs.fadeInDurationMs = seconds * 1000
                binding.tvFadeInValue.text = getString(R.string.seconds_format, seconds)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun setupFadeOutSlider() {
        binding.seekFadeOut.max = 9
        binding.seekFadeOut.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val seconds = progress + 1
                prefs.fadeOutDurationMs = seconds * 1000
                binding.tvFadeOutValue.text = getString(R.string.seconds_format, seconds)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun setupPermissionBanner() {
        binding.btnGrantAccess.setOnClickListener {
            promptNotificationAccess()
        }
    }

    // -------------------------------------------------------------------------
    // UI sync helpers
    // -------------------------------------------------------------------------

    private fun syncToggleUi() {
        val enabled = prefs.isEnabled
        // Suppress the listener while we programmatically set the state.
        binding.toggleSwitch.setOnCheckedChangeListener(null)
        binding.toggleSwitch.isChecked = enabled
        setupToggle()
        updateToggleLabel(enabled)
    }

    private fun syncSliders() {
        val fadeInSec = (prefs.fadeInDurationMs / 1000).coerceIn(1, 10)
        binding.seekFadeIn.progress = fadeInSec - 1
        binding.tvFadeInValue.text = getString(R.string.seconds_format, fadeInSec)

        val fadeOutSec = (prefs.fadeOutDurationMs / 1000).coerceIn(1, 10)
        binding.seekFadeOut.progress = fadeOutSec - 1
        binding.tvFadeOutValue.text = getString(R.string.seconds_format, fadeOutSec)
    }

    private fun updateToggleLabel(enabled: Boolean) {
        binding.tvToggleLabel.text =
            if (enabled) getString(R.string.status_active) else getString(R.string.status_inactive)
    }

    private fun updatePermissionBanner() {
        val granted = hasNotificationListenerPermission()
        binding.permissionBanner.visibility =
            if (granted) android.view.View.GONE else android.view.View.VISIBLE
    }

    // -------------------------------------------------------------------------
    // Service interaction
    // -------------------------------------------------------------------------

    private fun startFaderService() {
        val intent = Intent(this, FaderService::class.java)
        ContextCompat.startForegroundService(this, intent)
        requestNotificationPermissionIfNeeded()
    }

    private fun sendServiceToggle() {
        val intent = Intent(this, FaderService::class.java).apply {
            action = FaderService.ACTION_TOGGLE
        }
        startService(intent)
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private fun hasNotificationListenerPermission(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (flat.isNullOrBlank()) return false
        val cn = ComponentName(this, FaderNotificationListener::class.java)
        return flat.split(":").any {
            try { ComponentName.unflattenFromString(it) == cn } catch (e: Exception) { false }
        }
    }

    private fun promptNotificationAccess() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_permission_title)
            .setMessage(R.string.dialog_permission_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION_PERMISSION &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQ_NOTIFICATION_PERMISSION = 42
    }
}
