package com.example

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HotspotAutomatorManager {
    private const val TAG = "HotspotAutomator"
    private const val PREFS_NAME = "hotspot_automator_prefs"
    private const val KEY_DEVICE_ADDRESS = "device_address"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_AUTOMATION_ENABLED = "automation_enabled"
    private const val KEY_AUTO_CLOSE_SETTINGS = "auto_close_settings"

    private lateinit var prefs: SharedPreferences

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    // State regarding pending hotspot toggling
    val isHotspotPendingToggle = MutableStateFlow(false)
    val targetHotspotState = MutableStateFlow<Boolean?>(null) // true = ON, false = OFF

    data class LogEntry(
        val timestamp: String,
        val message: String,
        val type: LogType
    )

    enum class LogType {
        INFO, SUCCESS, WARNING, ERROR, ACTION
    }

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            addLog("HotspotAutomatorManager initialized", LogType.INFO)
        }
    }

    fun addLog(message: String, type: LogType = LogType.INFO) {
        val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        
        // Write to native Android logcat
        when (type) {
            LogType.INFO -> Log.i(TAG, message)
            LogType.SUCCESS -> Log.i(TAG, "[✓] $message")
            LogType.WARNING -> Log.w(TAG, "[!] $message")
            LogType.ERROR -> Log.e(TAG, "[✗] $message")
            LogType.ACTION -> Log.d(TAG, "[▶] $message")
        }

        // Add to list and keep under 200 items for performance
        val currentList = _logs.value.toMutableList()
        currentList.add(0, LogEntry(timestamp, message, type))
        if (currentList.size > 200) {
            currentList.removeLast()
        }
        _logs.value = currentList
    }

    fun clearLogs() {
        _logs.value = emptyList()
        addLog("Logs cleared", LogType.INFO)
    }

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
        addLog("Accessibility service running state changed: $running", LogType.INFO)
    }

    // Settings getters and setters
    fun getTargetDeviceAddress(): String? {
        return if (::prefs.isInitialized) prefs.getString(KEY_DEVICE_ADDRESS, null) else null
    }

    fun getTargetDeviceName(): String? {
        return if (::prefs.isInitialized) prefs.getString(KEY_DEVICE_NAME, null) else null
    }

    fun saveTargetDevice(address: String?, name: String?) {
        if (!::prefs.isInitialized) return
        prefs.edit().apply {
            putString(KEY_DEVICE_ADDRESS, address)
            putString(KEY_DEVICE_NAME, name)
            apply()
        }
        addLog("Saved target Bluetooth device: $name ($address)", LogType.SUCCESS)
    }

    fun isAutomationEnabled(): Boolean {
        return if (::prefs.isInitialized) prefs.getBoolean(KEY_AUTOMATION_ENABLED, true) else true
    }

    fun setAutomationEnabled(enabled: Boolean) {
        if (!::prefs.isInitialized) return
        prefs.edit().putBoolean(KEY_AUTOMATION_ENABLED, enabled).apply()
        addLog("Automation enabled state changed: $enabled", LogType.INFO)
    }

    fun isAutoCloseSettingsEnabled(): Boolean {
        return if (::prefs.isInitialized) prefs.getBoolean(KEY_AUTO_CLOSE_SETTINGS, true) else true
    }

    fun setAutoCloseSettingsEnabled(enabled: Boolean) {
        if (!::prefs.isInitialized) return
        prefs.edit().putBoolean(KEY_AUTO_CLOSE_SETTINGS, enabled).apply()
        addLog("Auto close settings changed: $enabled", LogType.INFO)
    }

    fun triggerHotspotToggle(turnOn: Boolean) {
        targetHotspotState.value = turnOn
        isHotspotPendingToggle.value = true
        addLog("Triggering hotspot toggle automation to: " + (if (turnOn) "ON" else "OFF"), LogType.ACTION)
    }

    fun clearToggleRequest() {
        targetHotspotState.value = null
        isHotspotPendingToggle.value = false
        addLog("Hotspot toggle automation reset/cleared", LogType.INFO)
    }
}
