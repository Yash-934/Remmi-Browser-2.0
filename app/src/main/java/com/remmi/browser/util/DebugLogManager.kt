package com.remmi.browser.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unified Thread-Safe Diagnostic Log Store for Remmi Browser.
 * Accessible across GeckoEngineManager, BlockExtension, TorManager, and UI layers.
 */
object DebugLogManager {
  private const val TAG = "RemmiDebugLog"
  private const val MAX_LOGS = 300

  private val _logs = MutableStateFlow<List<String>>(emptyList())
  val logs: StateFlow<List<String>> = _logs.asStateFlow()

  fun log(message: String) {
    val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    val timestamp = timeFormat.format(Date())
    val entry = "[$timestamp] $message"
    Log.d(TAG, message)
    synchronized(this) {
      val current = _logs.value.toMutableList()
      current.add(0, entry) // Newest first
      if (current.size > MAX_LOGS) {
        current.removeAt(current.size - 1)
      }
      _logs.value = current
    }
  }

  fun clear() {
    synchronized(this) {
      _logs.value = emptyList()
    }
  }
}
