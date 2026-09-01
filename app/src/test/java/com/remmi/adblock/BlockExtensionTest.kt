package com.remmi.adblock

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlockExtensionTest {

  private lateinit var bridge: AdblockBridge
  private lateinit var blockExtension: BlockExtension
  private lateinit var mockSender: WebExtension.MessageSender

  @Suppress("UNCHECKED_CAST")
  private fun <T> allocateInstance(clazz: Class<T>): T {
    val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
    unsafeField.isAccessible = true
    val unsafe = unsafeField.get(null) as sun.misc.Unsafe
    return unsafe.allocateInstance(clazz) as T
  }

  @Before
  fun setUp() {
    bridge = AdblockBridge.getInstance()
    bridge.compileRules("")
    blockExtension = BlockExtension.getInstance(bridge)

    val mockExtension = allocateInstance(WebExtension::class.java)
    ReflectionHelpers.setField(mockExtension, "id", "remmi_engine_extension")
    mockSender = allocateInstance(WebExtension.MessageSender::class.java)
    ReflectionHelpers.setField(mockSender, "webExtension", mockExtension)
  }

  @Suppress("UNCHECKED_CAST")
  private fun extractResult(result: GeckoResult<Any>?): JSONObject? {
    if (result == null) return null
    return try {
      val res = result.poll(1000)
      when (res) {
        is JSONObject -> res
        is String -> JSONObject(res)
        else -> null
      }
    } catch (e: Exception) {
      null
    }
  }

  @Test
  fun test100SequentialPings() {
    val count = 100
    val latenciesNanos = LongArray(count)
    var successes = 0
    var failures = 0

    for (i in 0 until count) {
      val pingMsg = JSONObject().apply {
        put("type", "PING")
        put("requestId", "ping_test_$i")
      }
      val elapsed = measureNanoTime {
        val result = blockExtension.onMessage("remmi_engine_extension", pingMsg, mockSender)
        val json = extractResult(result)
        if (json != null && json.optBoolean("ok", false) && json.optBoolean("pong", false)) {
          successes++
        } else {
          failures++
        }
      }
      latenciesNanos[i] = elapsed
    }

    val latenciesMs = latenciesNanos.map { it / 1_000_000.0 }.sorted()
    val p50 = latenciesMs[(count * 0.50).toInt()]
    val p95 = latenciesMs[(count * 0.95).toInt()]
    val max = latenciesMs.last()

    println("[TEST_METRICS] 100 PING Results: successes=$successes, failures=$failures, p50=${"%.2f".format(p50)}ms, p95=${"%.2f".format(p95)}ms, max=${"%.2f".format(max)}ms")

    assertEquals("All 100 PING requests must succeed", 100, successes)
    assertEquals("There must be 0 PING failures", 0, failures)
  }

  @Test
  fun testShouldBlockGoogleTagManager() {
    val shouldBlockMsg = JSONObject().apply {
      put("type", "SHOULD_BLOCK")
      put("requestId", "req_gtag_test")
      put("url", "https://www.googletagmanager.com/gtag/js?id=G-EPK7X69JWC")
      put("sourceUrl", "https://adblock-tester.com/")
      put("initiator", "https://adblock-tester.com/")
      put("method", "GET")
      put("resourceType", "script")
      put("aggressive", false)
      put("thirdParty", true)
    }

    val result = blockExtension.onMessage("remmi_engine_extension", shouldBlockMsg, mockSender)
    val responseJson = extractResult(result)

    assertNotNull("Response must be JSON", responseJson)
    assertTrue("Response ok must be true", responseJson!!.optBoolean("ok", false))
    assertTrue("Google Tag Manager script must have cancel=true", responseJson.optBoolean("cancel", false))
  }

  @Test
  fun testShouldBlockGoogleAnalyticsCollect() {
    val shouldBlockMsg = JSONObject().apply {
      put("type", "SHOULD_BLOCK")
      put("requestId", "req_ga_test")
      put("url", "https://www.google-analytics.com/g/collect?v=2&tid=G-EPK7X69JWC&cid=555.666")
      put("sourceUrl", "https://adblock-tester.com/")
      put("initiator", "https://adblock-tester.com/")
      put("method", "POST")
      put("resourceType", "ping")
      put("aggressive", false)
      put("thirdParty", true)
    }

    val result = blockExtension.onMessage("remmi_engine_extension", shouldBlockMsg, mockSender)
    val responseJson = extractResult(result)

    assertNotNull("Response must be JSON", responseJson)
    assertTrue("Response ok must be true", responseJson!!.optBoolean("ok", false))
    assertTrue("Google Analytics collect ping must have cancel=true", responseJson.optBoolean("cancel", false))
  }

  @Test
  fun testShouldAllowFirstPartyCleanResource() {
    val shouldBlockMsg = JSONObject().apply {
      put("type", "SHOULD_BLOCK")
      put("requestId", "req_clean_test")
      put("url", "https://adblock-tester.com/assets/app.js")
      put("sourceUrl", "https://adblock-tester.com/")
      put("initiator", "https://adblock-tester.com/")
      put("method", "GET")
      put("resourceType", "script")
      put("aggressive", false)
      put("thirdParty", false)
    }

    val result = blockExtension.onMessage("remmi_engine_extension", shouldBlockMsg, mockSender)
    val responseJson = extractResult(result)

    assertNotNull("Response must be JSON", responseJson)
    assertTrue("Response ok must be true", responseJson!!.optBoolean("ok", false))
    assertFalse("First party script must have cancel=false", responseJson.optBoolean("cancel", true))
  }

  @Test
  fun testCosmeticResourcesMessagePath() {
    val cosmeticMsg = JSONObject().apply {
      put("type", "GET_COSMETIC_RESOURCES")
      put("requestId", "req_cosmetic_test")
      put("url", "https://adblock-tester.com/")
      put("hostname", "adblock-tester.com")
      put("classes", JSONArray(listOf("banner", "adsbygoogle")))
      put("ids", JSONArray(listOf("sponsor-frame", "header-ad")))
      put("exceptions", JSONArray())
    }

    val result = blockExtension.onMessage("remmi_engine_extension", cosmeticMsg, mockSender)
    val responseJson = extractResult(result)

    assertNotNull("Cosmetic response must be JSON", responseJson)
    assertTrue("Cosmetic response ok must be true", responseJson!!.optBoolean("ok", false))
    assertNotNull("hideSelectors must be present", responseJson.optJSONArray("hideSelectors"))
  }

  @Test
  fun testHiddenClassIdSelectorsMessagePath() {
    val classIdMsg = JSONObject().apply {
      put("type", "GET_HIDDEN_CLASS_ID_SELECTORS")
      put("requestId", "req_classid_test")
      put("classes", JSONArray(listOf("ad-banner", "sponsored-post")))
      put("ids", JSONArray(listOf("ad-unit-1", "sidebar-sponsor")))
      put("exceptions", JSONArray())
    }

    val result = blockExtension.onMessage("remmi_engine_extension", classIdMsg, mockSender)
    val responseJson = extractResult(result)

    assertNotNull("ClassId response must be JSON", responseJson)
    assertTrue("ClassId response ok must be true", responseJson!!.optBoolean("ok", false))
    assertNotNull("hideSelectors array must be present", responseJson.optJSONArray("hideSelectors"))
  }

  @Test
  fun testInvalidAndUnsupportedMessages() {
    // 1. Unsupported type
    val unsupportedMsg = JSONObject().apply {
      put("type", "UNKNOWN_ACTION")
      put("requestId", "req_unknown")
    }
    val unsuppResult = blockExtension.onMessage("remmi_engine_extension", unsupportedMsg, mockSender)
    val unsuppJson = extractResult(unsuppResult)
    assertNotNull(unsuppJson)
    assertFalse("Unsupported type ok must be false", unsuppJson!!.optBoolean("ok", true))
    assertEquals("unsupported_type", unsuppJson.optString("error"))

    // 2. Empty URL in SHOULD_BLOCK
    val emptyUrlMsg = JSONObject().apply {
      put("type", "SHOULD_BLOCK")
      put("url", "")
    }
    val emptyUrlResult = blockExtension.onMessage("remmi_engine_extension", emptyUrlMsg, mockSender)
    val emptyUrlJson = extractResult(emptyUrlResult)
    assertNotNull(emptyUrlJson)
    assertFalse("Empty URL ok must be false", emptyUrlJson!!.optBoolean("ok", true))
  }

  @Test
  fun testConcurrentNativeMessagingStress() {
    val count = 100
    val latch = CountDownLatch(count)
    val responseSuccess = java.util.concurrent.atomic.AtomicInteger(0)

    val threads = (0 until count).map { i ->
      Thread {
        try {
          val isBlockCandidate = i % 2 == 0
          val url = if (isBlockCandidate) {
            "https://www.googletagmanager.com/gtag/js?id=G-CONCURRENT_$i"
          } else {
            "https://example.com/asset_$i.js"
          }

          val msg = JSONObject().apply {
            put("type", "SHOULD_BLOCK")
            put("requestId", "req_concurrent_$i")
            put("url", url)
            put("sourceUrl", "https://adblock-tester.com/")
            put("resourceType", "script")
            put("thirdParty", isBlockCandidate)
          }

          val result = blockExtension.onMessage("remmi_engine_extension", msg, mockSender)
          val json = extractResult(result)
          if (json != null && json.optBoolean("ok", false)) {
            val cancel = json.optBoolean("cancel", false)
            if (isBlockCandidate == cancel) {
              responseSuccess.incrementAndGet()
            }
          }
        } finally {
          latch.countDown()
        }
      }
    }

    threads.forEach { it.start() }
    assertTrue("All threads must finish within 10s", latch.await(10, TimeUnit.SECONDS))
    assertEquals("All 100 concurrent requests must receive exact matching response", count, responseSuccess.get())
  }

  @Test
  fun testConcurrentLoadTiers() {
    val tiers = listOf(1, 5, 10, 25, 50, 100, 250)

    for (tier in tiers) {
      val latch = CountDownLatch(tier)
      val latenciesNanos = java.util.concurrent.ConcurrentLinkedQueue<Long>()
      val successCount = java.util.concurrent.atomic.AtomicInteger(0)

      val threads = (0 until tier).map { i ->
        Thread {
          try {
            val isBlock = i % 2 == 0
            val url = if (isBlock) "https://www.google-analytics.com/analytics.js?t=$i" else "https://example.com/style_$i.css"
            val msg = JSONObject().apply {
              put("type", "SHOULD_BLOCK")
              put("requestId", "tier_${tier}_req_$i")
              put("url", url)
              put("sourceUrl", "https://adblock-tester.com/")
              put("resourceType", if (isBlock) "script" else "stylesheet")
              put("thirdParty", isBlock)
            }
            val start = System.nanoTime()
            val result = blockExtension.onMessage("remmi_engine_extension", msg, mockSender)
            val json = extractResult(result)
            val elapsed = System.nanoTime() - start
            latenciesNanos.add(elapsed)
            if (json != null && json.optBoolean("ok", false)) {
              successCount.incrementAndGet()
            }
          } finally {
            latch.countDown()
          }
        }
      }

      val totalElapsedMs = measureNanoTime {
        threads.forEach { it.start() }
        assertTrue("Tier $tier must complete within 15s", latch.await(15, TimeUnit.SECONDS))
      } / 1_000_000.0

      val latenciesMs = latenciesNanos.map { it / 1_000_000.0 }.sorted()
      val p50 = latenciesMs[(latenciesMs.size * 0.50).toInt().coerceAtMost(latenciesMs.size - 1)]
      val p95 = latenciesMs[(latenciesMs.size * 0.95).toInt().coerceAtMost(latenciesMs.size - 1)]
      val p99 = latenciesMs[(latenciesMs.size * 0.99).toInt().coerceAtMost(latenciesMs.size - 1)]
      val max = latenciesMs.last()

      println("[LOAD_BENCHMARK] Tier=$tier concurrent | Success=${successCount.get()}/$tier | Total=${"%.2f".format(totalElapsedMs)}ms | p50=${"%.2f".format(p50)}ms | p95=${"%.2f".format(p95)}ms | p99=${"%.2f".format(p99)}ms | max=${"%.2f".format(max)}ms")
      assertEquals("All requests in tier $tier must succeed", tier, successCount.get())
    }
  }
}
