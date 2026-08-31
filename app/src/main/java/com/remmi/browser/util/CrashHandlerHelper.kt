package com.remmi.browser.util

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.remmi.adblock.AdblockBridge
import com.remmi.browser.engine.GeckoEngineManager
import com.remmi.browser.security.CurrentTorRoute
import com.remmi.browser.storage.RemmiDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ReportType {
  JAVA_CRASH,
  ABNORMAL_TERMINATION
}

enum class StartupPhase(val id: String) {
  PROCESS_START("PROCESS_START"),
  SQLCIPHER_LOAD_START("SQLCIPHER_LOAD_START"),
  SQLCIPHER_LOAD_OK("SQLCIPHER_LOAD_OK"),
  SQLCIPHER_LOAD_FAILED("SQLCIPHER_LOAD_FAILED"),
  APPLICATION_CREATED("APPLICATION_CREATED"),
  ADBLOCK_CONSTRUCTION_START("ADBLOCK_CONSTRUCTION_START"),
  ADBLOCK_NATIVE_LOAD_OK("ADBLOCK_NATIVE_LOAD_OK"),
  ADBLOCK_NATIVE_INIT_OK("ADBLOCK_NATIVE_INIT_OK"),
  ADBLOCK_CONSTRUCTION_END("ADBLOCK_CONSTRUCTION_END"),
  DATABASE_BOOTSTRAP_START("DATABASE_BOOTSTRAP_START"),
  DATABASE_SQLCIPHER_OPEN_START("DATABASE_SQLCIPHER_OPEN_START"),
  DATABASE_SQLCIPHER_OPEN_OK("DATABASE_SQLCIPHER_OPEN_OK"),
  DATABASE_SQLCIPHER_OPEN_FAILED("DATABASE_SQLCIPHER_OPEN_FAILED"),
  MAIN_ACTIVITY_CREATE("MAIN_ACTIVITY_CREATE"),
  BROWSER_SCREEN_COMPOSE("BROWSER_SCREEN_COMPOSE"),
  GECKO_MANAGER_CONSTRUCT_START("GECKO_MANAGER_CONSTRUCT_START"),
  GECKO_MANAGER_CONSTRUCT_END("GECKO_MANAGER_CONSTRUCT_END"),
  FIRST_FRAME("FIRST_FRAME"),
  APP_READY("APP_READY"),
  SHUTDOWN("SHUTDOWN")
}

data class CrashExportResult(
  val fullReport: String,
  val savedPath: String,
  val timestamp: Long,
  val reportType: ReportType = ReportType.JAVA_CRASH
)

object CrashHandlerHelper {
  private const val TAG = "CrashHandlerHelper"
  const val PREFS_NAME = "remmi_crash_reports"

  const val KEY_PREVIOUS_RUN_CLEAN = "previous_run_clean"
  const val KEY_STARTUP_TIMESTAMP = "startup_timestamp"
  const val KEY_STARTUP_SESSION_ID = "startup_session_id"
  const val KEY_STARTUP_PHASE = "startup_phase"
  const val KEY_LAST_CLEAN_TIMESTAMP = "last_clean_timestamp"

  const val KEY_PENDING_REPORT = "pending_report_content"
  const val KEY_PENDING_TIMESTAMP = "pending_report_timestamp"
  const val KEY_PENDING_TYPE = "pending_report_type"
  const val KEY_PENDING_SAVED_PATH = "pending_report_saved_path"
  const val KEY_PENDING_EXPORT_CONFIRMED = "pending_export_confirmed"

  const val KEY_LAST_NATIVE_OP = "last_native_op"
  const val KEY_NATIVE_API_VERSION = "saved_native_api_version"
  const val KEY_NATIVE_BUILD_ID = "saved_native_build_id"
  const val KEY_NATIVE_ABI = "saved_native_abi"

  @Volatile
  var currentSessionId: String = "unknown"
    private set

  @Volatile
  var currentPhase: StartupPhase = StartupPhase.PROCESS_START
    private set

  @Volatile
  var lastNativeOperation: String = "NONE"
    private set

  @Volatile
  private var appContext: Context? = null

  /**
   * Called as the earliest step during process initialization (RemmiApp.onCreate).
   * Checks for abnormal termination of previous run and sets up new session heartbeat.
   */
  fun onProcessStart(context: Context) {
    try {
      appContext = context.applicationContext
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val wasClean = prefs.getBoolean(KEY_PREVIOUS_RUN_CLEAN, true)
      val prevSessionId = prefs.getString(KEY_STARTUP_SESSION_ID, null)
      val prevPhase = prefs.getString(KEY_STARTUP_PHASE, "UNKNOWN") ?: "UNKNOWN"
      val prevTimestamp = prefs.getLong(KEY_STARTUP_TIMESTAMP, 0L)
      val lastCleanTimestamp = prefs.getLong(KEY_LAST_CLEAN_TIMESTAMP, 0L)
      val hasPendingJavaCrash = prefs.contains(KEY_PENDING_REPORT)

      // If previous run was NOT clean AND there was a session recorded AND no Java crash report was written,
      // this indicates an abnormal process termination (native crash / OOM / force kill / external termination).
      if (!wasClean && prevSessionId != null && !hasPendingJavaCrash) {
        val abnormalReport = buildDiagnosticReport(
          context = context,
          reportType = ReportType.ABNORMAL_TERMINATION,
          thread = null,
          throwable = null,
          sessionId = prevSessionId,
          lastPhase = prevPhase,
          wasClean = false,
          lastCleanTimestamp = lastCleanTimestamp,
          reportTime = System.currentTimeMillis()
        )

        val reportTimestamp = System.currentTimeMillis()
        prefs.edit()
          .putString(KEY_PENDING_REPORT, abnormalReport)
          .putLong(KEY_PENDING_TIMESTAMP, reportTimestamp)
          .putString(KEY_PENDING_TYPE, ReportType.ABNORMAL_TERMINATION.name)
          .putBoolean(KEY_PENDING_EXPORT_CONFIRMED, false)
          .commit()

        // Attempt immediate persistence of the abnormal termination report
        val exportPath = saveToDownloads(context, abnormalReport, reportTimestamp, ReportType.ABNORMAL_TERMINATION)
        if (exportPath != null) {
          prefs.edit()
            .putBoolean(KEY_PENDING_EXPORT_CONFIRMED, true)
            .putString(KEY_PENDING_SAVED_PATH, exportPath)
            .commit()
        }
      }

      // Initialize the new session heartbeat
      val newSessionId = UUID.randomUUID().toString()
      currentSessionId = newSessionId
      currentPhase = StartupPhase.PROCESS_START

      prefs.edit()
        .putBoolean(KEY_PREVIOUS_RUN_CLEAN, false)
        .putLong(KEY_STARTUP_TIMESTAMP, System.currentTimeMillis())
        .putString(KEY_STARTUP_SESSION_ID, newSessionId)
        .putString(KEY_STARTUP_PHASE, StartupPhase.PROCESS_START.id)
        .commit()

      DebugLogManager.log("[APP_LIFECYCLE] PROCESS_START (session=$newSessionId)")
    } catch (e: Throwable) {
      Log.e(TAG, "Error in onProcessStart: ${e.message}", e)
    }
  }

  /**
   * Updates startup phase checkpoint synchronously to ensure crash journal is always up to date.
   * When APP_READY is reached, marks the run clean.
   */
  fun updateStartupPhase(context: Context? = null, phase: StartupPhase) {
    try {
      currentPhase = phase
      val ctx = context?.applicationContext ?: appContext
      if (ctx != null) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit().putString(KEY_STARTUP_PHASE, phase.id)

        if (phase == StartupPhase.APP_READY) {
          editor.putBoolean(KEY_PREVIOUS_RUN_CLEAN, true)
          editor.putLong(KEY_LAST_CLEAN_TIMESTAMP, System.currentTimeMillis())
        }
        editor.commit()
      }

      DebugLogManager.log("[APP_LIFECYCLE] ${phase.id}")
      DebugLogManager.flushSynchronously()
    } catch (e: Throwable) {
      Log.e(TAG, "Error updating startup phase: ${e.message}", e)
    }
  }

  /**
   * Synchronously records a native operation marker into persistent preferences and the debug journal.
   * Ensures that if an uncatchable native process crash/abort occurs, the exact operation is captured.
   */
  fun recordNativeOp(
    context: Context? = null,
    op: String,
    apiVersion: String? = null,
    buildId: String? = null,
    abi: String? = null
  ) {
    try {
      lastNativeOperation = op
      val ctx = context?.applicationContext ?: appContext
      if (ctx != null) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit().putString(KEY_LAST_NATIVE_OP, op)
        if (apiVersion != null) editor.putString(KEY_NATIVE_API_VERSION, apiVersion)
        if (buildId != null) editor.putString(KEY_NATIVE_BUILD_ID, buildId)
        if (abi != null) editor.putString(KEY_NATIVE_ABI, abi)
        editor.commit()
      }

      DebugLogManager.log("[NATIVE_OP] $op")
      DebugLogManager.flushSynchronously()
    } catch (e: Throwable) {
      Log.e(TAG, "Error recording native op: ${e.message}", e)
    }
  }

  /**
   * Marks clean shutdown when the activity/app finishes gracefully.
   */
  fun markCleanShutdown(context: Context) {
    try {
      currentPhase = StartupPhase.SHUTDOWN
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      prefs.edit()
        .putBoolean(KEY_PREVIOUS_RUN_CLEAN, true)
        .putString(KEY_STARTUP_PHASE, StartupPhase.SHUTDOWN.id)
        .putLong(KEY_LAST_CLEAN_TIMESTAMP, System.currentTimeMillis())
        .commit()

      DebugLogManager.log("[APP_LIFECYCLE] SHUTDOWN")
    } catch (e: Throwable) {
      Log.e(TAG, "Error marking clean shutdown: ${e.message}", e)
    }
  }

  /**
   * Layer 1: Install standard UncaughtExceptionHandler for Java/Kotlin exceptions.
   */
  fun install(app: Application) {
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      try {
        val now = System.currentTimeMillis()
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastClean = prefs.getLong(KEY_LAST_CLEAN_TIMESTAMP, 0L)

        val report = buildDiagnosticReport(
          context = app,
          reportType = ReportType.JAVA_CRASH,
          thread = thread,
          throwable = throwable,
          sessionId = currentSessionId,
          lastPhase = currentPhase.id,
          wasClean = false,
          lastCleanTimestamp = lastClean,
          reportTime = now
        )

        Log.e(TAG, "FATAL UNCAUGHT EXCEPTION:\n$report")
        DebugLogManager.log("[APP_LIFECYCLE] FATAL_EXCEPTION: ${throwable.javaClass.name} - ${throwable.message}")
        DebugLogManager.flushSynchronously()

        // 1. Save pending report to SharedPreferences synchronously
        prefs.edit()
          .putString(KEY_PENDING_REPORT, report)
          .putLong(KEY_PENDING_TIMESTAMP, now)
          .putString(KEY_PENDING_TYPE, ReportType.JAVA_CRASH.name)
          .putBoolean(KEY_PREVIOUS_RUN_CLEAN, false)
          .putBoolean(KEY_PENDING_EXPORT_CONFIRMED, false)
          .commit()

        // 2. Save to app-private internal storage
        try {
          val internalFile = File(app.filesDir, "crash_latest.txt")
          internalFile.writeText(report)
        } catch (_: Throwable) {}

        // 3. Attempt immediate synchronous export to Downloads/Remmi Crash Reports/
        val exportPath = saveToDownloads(app, report, now, ReportType.JAVA_CRASH)
        if (exportPath != null) {
          prefs.edit()
            .putBoolean(KEY_PENDING_EXPORT_CONFIRMED, true)
            .putString(KEY_PENDING_SAVED_PATH, exportPath)
            .commit()
        }
      } catch (e: Throwable) {
        Log.e(TAG, "Failed to capture crash report: ${e.message}", e)
      } finally {
        defaultHandler?.uncaughtException(thread, throwable)
      }
    }
  }

  suspend fun checkAndExportPendingCrashAsync(context: Context): CrashExportResult? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      checkAndExportPendingReport(context)
    }

  fun checkAndExportPendingCrash(context: Context): CrashExportResult? {
    return checkAndExportPendingReport(context)
  }

  /**
   * Checks for pending diagnostic/crash reports, ensures export to Downloads, and removes
   * the pending marker only upon confirmed persistence.
   */
  fun checkAndExportPendingReport(context: Context): CrashExportResult? {
    try {
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val report = prefs.getString(KEY_PENDING_REPORT, null) ?: return null
      val timestamp = prefs.getLong(KEY_PENDING_TIMESTAMP, System.currentTimeMillis())
      val typeStr = prefs.getString(KEY_PENDING_TYPE, ReportType.JAVA_CRASH.name) ?: ReportType.JAVA_CRASH.name
      val reportType = if (typeStr == ReportType.ABNORMAL_TERMINATION.name) {
        ReportType.ABNORMAL_TERMINATION
      } else {
        ReportType.JAVA_CRASH
      }

      val alreadyConfirmed = prefs.getBoolean(KEY_PENDING_EXPORT_CONFIRMED, false)
      var savedPath = prefs.getString(KEY_PENDING_SAVED_PATH, null)

      if (!alreadyConfirmed || savedPath == null) {
        savedPath = saveToDownloads(context, report, timestamp, reportType)
        if (savedPath != null) {
          prefs.edit()
            .putBoolean(KEY_PENDING_EXPORT_CONFIRMED, true)
            .putString(KEY_PENDING_SAVED_PATH, savedPath)
            .apply()
        }
      }

      // Only clear the pending report marker once export/persistence is confirmed
      val isConfirmed = (savedPath != null || alreadyConfirmed)
      if (isConfirmed) {
        prefs.edit()
          .remove(KEY_PENDING_REPORT)
          .remove(KEY_PENDING_TIMESTAMP)
          .remove(KEY_PENDING_TYPE)
          .remove(KEY_PENDING_SAVED_PATH)
          .remove(KEY_PENDING_EXPORT_CONFIRMED)
          .apply()
      }

      return CrashExportResult(
        fullReport = report,
        savedPath = savedPath ?: "Downloads/Remmi Crash Reports/",
        timestamp = timestamp,
        reportType = reportType
      )
    } catch (e: Throwable) {
      Log.e(TAG, "Error checking pending crash: ${e.message}", e)
      return null
    }
  }

  /**
   * Safely exports report text to Downloads/Remmi Crash Reports/ using MediaStore on Q+
   * and direct filesystem on older versions, with automatic fallback to app-private storage.
   */
  fun saveToDownloads(
    context: Context,
    report: String,
    timestamp: Long,
    reportType: ReportType = ReportType.JAVA_CRASH
  ): String? {
    val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestamp))
    val fileName = if (reportType == ReportType.ABNORMAL_TERMINATION) {
      "abnormal_termination_$dateStr.txt"
    } else {
      "crash_$dateStr.txt"
    }
    val latestFileName = if (reportType == ReportType.ABNORMAL_TERMINATION) {
      "abnormal_latest.txt"
    } else {
      "crash_latest.txt"
    }

    var resultPath: String? = null

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver

        // 1. Write timestamped file with IS_PENDING safety
        val contentValues = ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
          put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
          put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Remmi Crash Reports")
          put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
          try {
            resolver.openOutputStream(uri)?.use { os ->
              os.write(report.toByteArray(Charsets.UTF_8))
              os.flush()
            }
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            resultPath = "Downloads/Remmi Crash Reports/$fileName"
          } catch (writeEx: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            throw writeEx
          }
        }

        // 2. Also write/update latest file
        try {
          val latestValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, latestFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Remmi Crash Reports")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
          }
          val latestUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, latestValues)
          if (latestUri != null) {
            resolver.openOutputStream(latestUri)?.use { os ->
              os.write(report.toByteArray(Charsets.UTF_8))
              os.flush()
            }
            latestValues.clear()
            latestValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(latestUri, latestValues, null, null)
          }
        } catch (_: Throwable) {}

      } else {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val crashDir = File(downloadsDir, "Remmi Crash Reports")
        if (!crashDir.exists()) {
          crashDir.mkdirs()
        }
        val targetFile = File(crashDir, fileName)
        targetFile.writeText(report)

        val latestFile = File(crashDir, latestFileName)
        latestFile.writeText(report)

        resultPath = targetFile.absolutePath
      }
    } catch (e: Throwable) {
      Log.e(TAG, "Error saving report to public Downloads: ${e.message}", e)
      // Fallback to app-private external / internal storage
      try {
        val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val fallbackDir = File(extDir, "Remmi Crash Reports").apply { mkdirs() }
        val fallbackFile = File(fallbackDir, fileName)
        fallbackFile.writeText(report)
        File(fallbackDir, latestFileName).writeText(report)
        resultPath = fallbackFile.absolutePath
      } catch (_: Throwable) {
        try {
          val internalDir = File(context.filesDir, "Remmi Crash Reports").apply { mkdirs() }
          val internalFile = File(internalDir, fileName)
          internalFile.writeText(report)
          File(internalDir, latestFileName).writeText(report)
          resultPath = internalFile.absolutePath
        } catch (_: Throwable) {}
      }
    }

    return resultPath
  }

  fun buildDiagnosticReport(
    context: Context,
    reportType: ReportType,
    thread: Thread?,
    throwable: Throwable?,
    sessionId: String,
    lastPhase: String,
    wasClean: Boolean,
    lastCleanTimestamp: Long,
    reportTime: Long
  ): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z", Locale.US)
    val now = dateFormat.format(Date(reportTime))
    val lastCleanTimeStr = if (lastCleanTimestamp > 0L) {
      dateFormat.format(Date(lastCleanTimestamp))
    } else {
      "None (First run or previous unclean termination)"
    }

    val packageInfo = try {
      context.packageManager.getPackageInfo(context.packageName, 0)
    } catch (_: Throwable) { null }

    val versionName = packageInfo?.versionName ?: "1.0.0"
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      packageInfo?.longVersionCode ?: 1L
    } else {
      @Suppress("DEPRECATION")
      packageInfo?.versionCode?.toLong() ?: 1L
    }

    val prefs = try { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) } catch (_: Throwable) { null }
    val savedLastNativeOp = prefs?.getString(KEY_LAST_NATIVE_OP, null)
    val displayLastNativeOp = if (lastNativeOperation != "NONE") lastNativeOperation else (savedLastNativeOp ?: "NONE")

    val adblock = try { AdblockBridge.getInstance() } catch (_: Throwable) { null }
    val adblockVersion = adblock?.nativeApiVersion?.takeIf { it != "unknown" }
      ?: (prefs?.getString(KEY_NATIVE_API_VERSION, null) ?: "unknown")
    val adblockBuildId = adblock?.nativeBuildId?.takeIf { it != "unknown" }
      ?: (prefs?.getString(KEY_NATIVE_BUILD_ID, null) ?: "unknown")
    val adblockAbi = adblock?.nativeAbi?.takeIf { it != "unknown" }
      ?: (prefs?.getString(KEY_NATIVE_ABI, null) ?: "unknown")
    val adblockApiVersionNumeric = adblock?.nativeNumericApiVersion?.toString() ?: "0"
    val nativeCompatState = if (adblock?.isJniSignatureCompatible == true) "COMPATIBLE" else "GATED_FALLBACK"
    val adblockState = adblock?.state?.name ?: "UNKNOWN"

    val sqlcipherLoadState = if (com.remmi.browser.storage.SqlCipherInitializer.isLoaded()) "LOADED" else "NOT_LOADED"

    val dbState = try {
      when (val s = RemmiDatabase.databaseState.value) {
        is RemmiDatabase.DatabaseState.Ready -> "READY"
        is RemmiDatabase.DatabaseState.Error -> "ERROR: ${s.throwable.javaClass.simpleName}"
        RemmiDatabase.DatabaseState.Loading -> "LOADING"
      }
    } catch (_: Throwable) { "UNKNOWN" }

    val geckoState = try {
      GeckoEngineManager.peekInitState() ?: "NOT_INITIALIZED"
    } catch (_: Throwable) { "UNKNOWN" }

    val torState = try {
      if (CurrentTorRoute.currentSocksPort != null) "ACTIVE" else "INACTIVE"
    } catch (_: Throwable) { "UNKNOWN" }

    val ghostState = try {
      if (CurrentTorRoute.isGhostActive) "ENABLED" else "DISABLED"
    } catch (_: Throwable) { "UNKNOWN" }

    val shieldState = "ENABLED"
    val webExtState = "REGISTERED"

    val recentBreadcrumbs = try {
      DebugLogManager.getRecentEvents(100)
    } catch (_: Throwable) {
      emptyList()
    }
    val breadcrumbText = if (recentBreadcrumbs.isNotEmpty()) {
      recentBreadcrumbs.joinToString("\n")
    } else {
      "No diagnostic events recorded"
    }

    val stackTrace = if (throwable != null) {
      Log.getStackTraceString(throwable)
    } else {
      ""
    }

    return """
======================================================================
REMMI BROWSER - AUTOMATIC DIAGNOSTIC REPORT
======================================================================

Report Type:
${reportType.name}

Timestamp: $now
APK version: $versionName ($versionCode)
Package: ${context.packageName}
Startup Session ID: $sessionId

DEVICE:
Brand: ${Build.BRAND}
Manufacturer: ${Build.MANUFACTURER}
Model: ${Build.MODEL}
Device: ${Build.DEVICE}
Android Version: ${Build.VERSION.RELEASE}
SDK: ${Build.VERSION.SDK_INT}
Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}

PROCESS:
Process ID: ${android.os.Process.myPid()}
Thread if available: ${thread?.let { "${it.name} (ID: ${it.id})" } ?: "N/A (Process-level termination)"}

STARTUP STATE:
Previous run clean: $wasClean
Previous startup phase: $lastPhase
Last startup phase: $lastPhase
Current startup phase: ${currentPhase.id}
Startup session ID: $sessionId
Last clean timestamp: $lastCleanTimeStr

NATIVE:
Native ABI: $adblockAbi (Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")})
Adblock native version: $adblockVersion
Adblock native build ID: $adblockBuildId
Adblock API version: $adblockApiVersionNumeric
Native compatibility state: $nativeCompatState
Last native operation: $displayLastNativeOp
SQLCipher load state: $sqlcipherLoadState

SUBSYSTEM STATE:
Adblock: $adblockState
Gecko: $geckoState
Database: $dbState
SQLCipher load state: $sqlcipherLoadState
Tor: $torState
Ghost: $ghostState
Shield: $shieldState
WebExtension: $webExtState

RECENT DIAGNOSTIC EVENTS (LAST 100):
$breadcrumbText

JAVA EXCEPTION:
${if (throwable != null) """
Exception Class: ${throwable.javaClass.name}
Message: ${throwable.message ?: "No error message provided"}
Stacktrace:
$stackTrace
""".trimIndent() else "N/A"}

NATIVE CRASH:
${if (throwable == null) """
NATIVE_PROCESS_TERMINATION_SUSPECTED
Last native operation: $displayLastNativeOp
NO JAVA EXCEPTION CAPTURED.
Process ended before the Java crash handler could execute or the process was terminated externally.
""".trimIndent() else "N/A (Captured by Java UncaughtExceptionHandler)"}

END REPORT
======================================================================
    """.trimIndent()
  }
}
