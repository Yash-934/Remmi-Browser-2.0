package com.remmi.browser.engine

import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoPreferenceController as NativePrefCtrl
import org.mozilla.geckoview.GeckoPreferenceController.SetGeckoPreference
import org.mozilla.geckoview.GeckoPreferenceController.GeckoPreference
import kotlin.coroutines.resume

class GeckoPreferenceController(private val runtime: GeckoRuntime?) {
  companion object {
    const val PREF_BRANCH_USER: Int = NativePrefCtrl.PREF_BRANCH_USER
    const val PREF_BRANCH_DEFAULT: Int = NativePrefCtrl.PREF_BRANCH_DEFAULT
    private const val TAG = "GeckoPreferenceCtrl"
    
    val REQUIRED_PROXY_ROUTING = setOf(
      "network.proxy.type",
      "network.proxy.socks",
      "network.proxy.socks_port",
      "network.proxy.socks_version",
      "network.proxy.socks_remote_dns",
      "network.proxy.failover_direct"
    )
    
    val REQUIRED_GHOST_PRIVACY = setOf(
      "media.peerconnection.enabled"
    )
  }

  suspend fun getPreferences(keys: List<String>): Map<String, Any?> = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    try {
      NativePrefCtrl.getGeckoPrefs(keys.toMutableList()).accept(
        { result ->
          if (cont.isActive) {
            val map = mutableMapOf<String, Any?>()
            result?.forEach { pref ->
               map[pref.pref] = pref.value
            }
            cont.resume(map)
          }
        },
        {
          if (cont.isActive) cont.resume(emptyMap())
        }
      )
    } catch (t: Throwable) {
      if (cont.isActive) cont.resume(emptyMap())
    }
  }

  suspend fun applyPreferences(prefs: Map<String, Any>, branch: Int = PREF_BRANCH_USER): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    if (prefs.isEmpty()) {
      cont.resume(true)
      return@suspendCancellableCoroutine
    }

    val setList = mutableListOf<SetGeckoPreference<*>>()
    for ((name, value) in prefs) {
      when (value) {
        is String -> setList.add(SetGeckoPreference.setStringPref(name, value, branch))
        is Int -> setList.add(SetGeckoPreference.setIntPref(name, value, branch))
        is Boolean -> setList.add(SetGeckoPreference.setBoolPref(name, value, branch))
        else -> {
          Log.e(TAG, "Unsupported preference type for key=$name: ${value::class.java.simpleName}")
          if (cont.isActive) cont.resume(false)
          return@suspendCancellableCoroutine
        }
      }
    }

    try {
      NativePrefCtrl.setGeckoPrefs(setList).accept(
        { result ->
          Log.d(TAG, "[GECKO_PREF_RAW_RESULT] type=${result?.javaClass?.name} value=$result")
          
          val resultMap = when (result) {
            is Map<*, *> -> result.mapNotNull { (k, v) -> if (k is String && v is Boolean) k to v else null }.toMap()
            else -> {
              Log.w(TAG, "[ROUTE] Unknown or non-map GeckoPreference result type: ${result?.javaClass?.name}")
              // If result is not Map, check if all requested preferences were accepted or if result itself represents successful completion
              emptyMap<String, Boolean>()
            }
          }
          var total = 0
          var successful = 0
          var failed = 0
          var criticalFailed = 0
          val criticalFailedList = mutableListOf<String>()
          val failedList = mutableListOf<String>()

          Log.d(TAG, "[ROUTE] GEOCKO_PROXY_APPLY_START")
          
          for ((name, value) in prefs) {
             val success = if (resultMap.isNotEmpty()) {
               resultMap[name] == true
             } else {
               // When GeckoView returns an unmapped or void completion, we verify non-null completion
               result != null
             }
             total++
             if (success) successful++ else failed++
             
             Log.d(TAG, "[GECKO_PREF_RESULT] $name=$value result=$success")
             
             if (!success) {
                 failedList.add(name)
                 if (REQUIRED_PROXY_ROUTING.contains(name) || REQUIRED_GHOST_PRIVACY.contains(name)) {
                     criticalFailed++
                     criticalFailedList.add(name)
                 }
             }
          }
          
          Log.d(TAG, "[ROUTE] GECKO_PREF_SUMMARY total=$total successful=$successful failed=$failed criticalFailed=$criticalFailed")

          if (criticalFailed > 0) {
            Log.e(TAG, "[ROUTE] GECKO_PREF_FAILURE error=CRITICAL Ghost preferences failed: $criticalFailedList")
            if (cont.isActive) cont.resume(false)
          } else {
            if (failed > 0) {
                Log.w(TAG, "Non-critical preferences failed: $failedList")
            }
            if (cont.isActive) cont.resume(true)
          }
        },
        { error ->
          Log.e(TAG, "[ROUTE] GECKO_PREF_FAILURE error=${error?.message}", error)
          if (cont.isActive) cont.resume(false)
        }
      )
    } catch (t: Throwable) {
      Log.e(TAG, "[ROUTE] GECKO_PREF_FAILURE exception=${t.javaClass.simpleName} message=${t.message}", t)
      if (cont.isActive) cont.resume(false)
    }
  }
}
