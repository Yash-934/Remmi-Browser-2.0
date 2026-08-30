package com.remmi.browser.util

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandlerHelper {
  private const val TAG = "CrashHandlerHelper"
  private const val PREFS_NAME = "remmi_crash_reports"
  private const val KEY_LAST_CRASH = "last_crash_report"
  private const val KEY_CRASH_TIMESTAMP = "last_crash_timestamp"

  fun install(app: Application) {
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      try {
        val report = buildCrashReport(app, thread, throwable)
        Log.e(TAG, "FATAL UNCAUGHT EXCEPTION:\n$report")

        // 1. Save to SharedPreferences synchronously
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
          .putString(KEY_LAST_CRASH, report)
          .putLong(KEY_CRASH_TIMESTAMP, System.currentTimeMillis())
          .commit()

        // 2. Save to internal app storage
        try {
          val internalFile = File(app.filesDir, "last_crash.log")
          internalFile.writeText(report)
        } catch (_: Exception) {}

        // 3. Attempt direct write to Downloads/Remmi Browser immediately
        saveToDownloads(app, report, System.currentTimeMillis())
      } catch (e: Throwable) {
        Log.e(TAG, "Failed to capture crash report: ${e.message}", e)
      } finally {
        defaultHandler?.uncaughtException(thread, throwable)
      }
    }
  }

  suspend fun checkAndExportPendingCrashAsync(context: Context): CrashExportResult? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    checkAndExportPendingCrash(context)
  }

  fun checkAndExportPendingCrash(context: Context): CrashExportResult? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val report = prefs.getString(KEY_LAST_CRASH, null) ?: return null
    val timestamp = prefs.getLong(KEY_CRASH_TIMESTAMP, System.currentTimeMillis())

    // Clear from prefs so it doesn't show repeatedly on subsequent regular launches
    prefs.edit().remove(KEY_LAST_CRASH).remove(KEY_CRASH_TIMESTAMP).apply()

    // 1. Save to Downloads / Remmi Browser folder
    val savedPath = saveToDownloads(context, report, timestamp)

    return CrashExportResult(
      fullReport = report,
      savedPath = savedPath ?: "Downloads/Remmi Browser/",
      timestamp = timestamp
    )
  }

  fun saveToDownloads(context: Context, report: String, timestamp: Long): String? {
    val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
    val fileName = "crash_log_$dateStr.txt"
    val latestFileName = "crash_log_latest.txt"

    var resultPath: String? = null

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver

        // Save timestamped file
        val contentValues = ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
          put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
          put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Remmi Browser")
          put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
          resolver.openOutputStream(uri)?.use { os ->
            os.write(report.toByteArray(Charsets.UTF_8))
          }
          resultPath = "Downloads/Remmi Browser/$fileName"
        }

        // Also save/update latest file
        val latestValues = ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, latestFileName)
          put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
          put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Remmi Browser")
          put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        val latestUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, latestValues)
        if (latestUri != null) {
          resolver.openOutputStream(latestUri)?.use { os ->
            os.write(report.toByteArray(Charsets.UTF_8))
          }
        }
      } else {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val remmiDir = File(downloadsDir, "Remmi Browser")
        if (!remmiDir.exists()) {
          remmiDir.mkdirs()
        }
        val targetFile = File(remmiDir, fileName)
        targetFile.writeText(report)

        val latestFile = File(remmiDir, latestFileName)
        latestFile.writeText(report)

        resultPath = targetFile.absolutePath
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error saving to Downloads: ${e.message}", e)
      // Fallback to app external files dir
      try {
        val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val remmiDir = File(extDir, "Remmi Browser").apply { mkdirs() }
        val fallbackFile = File(remmiDir, fileName)
        fallbackFile.writeText(report)
        resultPath = fallbackFile.absolutePath
      } catch (_: Exception) {}
    }

    return resultPath
  }

  private fun buildCrashReport(context: Context, thread: Thread, throwable: Throwable): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
    val now = dateFormat.format(Date())
    val stackTrace = Log.getStackTraceString(throwable)

    val packageInfo = try {
      context.packageManager.getPackageInfo(context.packageName, 0)
    } catch (_: Exception) { null }

    val versionName = packageInfo?.versionName ?: "1.0.0"
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      packageInfo?.longVersionCode ?: 1L
    } else {
      @Suppress("DEPRECATION")
      packageInfo?.versionCode?.toLong() ?: 1L
    }

    return """
======================================================================
REMMI BROWSER - CRASH REPORT
======================================================================
Timestamp: $now
App Version: $versionName ($versionCode)
Package: ${context.packageName}

DEVICE INFO:
----------------------------------------------------------------------
Brand: ${Build.BRAND}
Manufacturer: ${Build.MANUFACTURER}
Model: ${Build.MODEL}
Device: ${Build.DEVICE}
Android OS: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}

THREAD & EXCEPTION INFO:
----------------------------------------------------------------------
Thread: ${thread.name} (ID: ${thread.id})
Exception Class: ${throwable.javaClass.name}
Message: ${throwable.message ?: "No error message provided"}

STACK TRACE:
----------------------------------------------------------------------
$stackTrace

======================================================================
END OF CRASH REPORT
======================================================================
    """.trimIndent()
  }
}

data class CrashExportResult(
  val fullReport: String,
  val savedPath: String,
  val timestamp: Long
)
