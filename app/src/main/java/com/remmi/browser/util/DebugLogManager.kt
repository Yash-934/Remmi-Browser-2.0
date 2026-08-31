package com.remmi.browser.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Unified Thread-Safe Diagnostic Log Store & Persistent Breadcrumbs for Remmi Browser.
 * Accessible across GeckoEngineManager, BlockExtension, TorManager, and UI layers.
 */
object DebugLogManager {
  private const val TAG = "RemmiDebugLog"
  private const val MAX_LOGS = 300
  private const val MAX_BREADCRUMBS = 200
  private const val BREADCRUMBS_FILE = "remmi_breadcrumbs.log"

  private val _logs = MutableStateFlow<List<String>>(emptyList())
  val logs: StateFlow<List<String>> = _logs.asStateFlow()

  @Volatile
  private var appContext: Context? = null
  private val ioScope = CoroutineScope(Dispatchers.IO)
  private val persistentBreadcrumbs = ConcurrentLinkedDeque<String>()
  private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

  fun init(context: Context) {
    appContext = context.applicationContext
    loadPersistedBreadcrumbs()
  }

  fun log(message: String) {
    val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
    val sanitized = sanitize(message)
    val entry = "[$timestamp] $sanitized"

    Log.d(TAG, sanitized)

    // 1. Update in-memory StateFlow for UI (newest first)
    synchronized(this) {
      val current = _logs.value.toMutableList()
      current.add(0, entry)
      if (current.size > MAX_LOGS) {
        current.removeAt(current.size - 1)
      }
      _logs.value = current
    }

    // 2. Update persistent ring buffer (chronological order)
    persistentBreadcrumbs.addLast(entry)
    while (persistentBreadcrumbs.size > MAX_BREADCRUMBS) {
      persistentBreadcrumbs.pollFirst()
    }

    // 3. Flush rule: Synchronous flush for critical/fatal/lifecycle events, async otherwise
    if (sanitized.contains("[APP_LIFECYCLE]") ||
        sanitized.contains("CRITICAL") ||
        sanitized.contains("FATAL") ||
        sanitized.contains("ERROR")
    ) {
      flushSynchronously()
    } else {
      ioScope.launch {
        flushInternal()
      }
    }
  }

  fun sanitize(message: String): String {
    var sanitized = message
    // 1. Redact query parameters in standard URLs
    sanitized = sanitized.replace(Regex("""(https?://[^\s?#]+)\?[^\s]*""")) { mr ->
      "${mr.groupValues[1]}?[REDACTED_QUERY]"
    }
    // 2. Redact authorization headers, bearer tokens, passwords, cookies, secrets
    sanitized = sanitized.replace(
      Regex("""(?i)\b(authorization|bearer|token|password|passwd|secret|cookie|set-cookie|key|pin|passphrase)\s*[:=]\s*([^\s,;]+)""")
    ) { mr ->
      "${mr.groupValues[1]}: [REDACTED]"
    }
    // 3. Redact Basic auth blobs
    sanitized = sanitized.replace(Regex("""(?i)Basic\s+[A-Za-z0-9+/=]+"""), "Basic [REDACTED]")
    // 4. Redact Onion addresses query params if any
    sanitized = sanitized.replace(Regex("""([a-z2-7]{56}\.onion)/[^\s?#]*\?[^\s]*""")) { mr ->
      "${mr.groupValues[1]}/[REDACTED_PATH]?[REDACTED_QUERY]"
    }
    return sanitized
  }

  fun flushSynchronously() {
    synchronized(persistentBreadcrumbs) {
      val ctx = appContext ?: return
      try {
        val file = File(ctx.filesDir, BREADCRUMBS_FILE)
        val tempFile = File(ctx.filesDir, "$BREADCRUMBS_FILE.tmp")
        val content = persistentBreadcrumbs.joinToString("\n")
        tempFile.writeText(content)
        if (tempFile.exists()) {
          tempFile.renameTo(file)
        }
      } catch (_: Throwable) {}
    }
  }

  private fun flushInternal() {
    synchronized(persistentBreadcrumbs) {
      val ctx = appContext ?: return
      try {
        val file = File(ctx.filesDir, BREADCRUMBS_FILE)
        val tempFile = File(ctx.filesDir, "$BREADCRUMBS_FILE.tmp")
        val content = persistentBreadcrumbs.joinToString("\n")
        tempFile.writeText(content)
        if (tempFile.exists()) {
          tempFile.renameTo(file)
        }
      } catch (_: Throwable) {}
    }
  }

  private fun loadPersistedBreadcrumbs() {
    val ctx = appContext ?: return
    try {
      val file = File(ctx.filesDir, BREADCRUMBS_FILE)
      if (file.exists()) {
        val lines = file.readLines().takeLast(MAX_BREADCRUMBS)
        persistentBreadcrumbs.clear()
        persistentBreadcrumbs.addAll(lines)
      }
    } catch (_: Throwable) {}
  }

  fun getRecentEvents(limit: Int = MAX_BREADCRUMBS): List<String> {
    return persistentBreadcrumbs.toList().takeLast(limit)
  }

  fun clear() {
    synchronized(this) {
      _logs.value = emptyList()
      persistentBreadcrumbs.clear()
      val ctx = appContext
      if (ctx != null) {
        try {
          File(ctx.filesDir, BREADCRUMBS_FILE).delete()
        } catch (_: Throwable) {}
      }
    }
  }
}
