package com.remmi.browser.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.adblock.AdblockBridge
import com.remmi.adblock.FilterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FilterSubscriptionPipelineTest {

  private lateinit var context: Context
  private lateinit var adblockBridge: AdblockBridge
  private lateinit var filterManager: FilterManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    adblockBridge = AdblockBridge()
    filterManager = FilterManager(adblockBridge, context)
  }

  @Test
  fun testAdblockBridgeFallbackRulesAndMatching() = runBlocking {
    // Verify default tracker domain is blocked by default fallback
    val blocked = adblockBridge.shouldBlock("https://google-analytics.com/analytics.js")
    assertTrue("google-analytics.com should be blocked", blocked)

    val doubleclickBlocked = adblockBridge.shouldBlock("https://adservice.google.com/ads?id=123")
    assertTrue("adservice.google.com should be blocked", doubleclickBlocked)

    // Clean site should be allowed
    val allowed = adblockBridge.shouldBlock("https://en.wikipedia.org/wiki/Tor")
    assertFalse("wikipedia.org should be allowed", allowed)

    // Custom compilation
    val customRules = """
      ||malware-tracker.com^
      ||bad-ad-network.net^
      @@||good-tracker.org^
    """.trimIndent()

    val count = adblockBridge.compileRules(customRules)
    assertTrue("Compiled count should be at least 3", count >= 3)

    val blockedCustom = adblockBridge.shouldBlock("https://malware-tracker.com/track")
    assertTrue("malware-tracker.com should be blocked", blockedCustom)

    val allowedException = adblockBridge.shouldBlock("https://good-tracker.org/ping")
    assertFalse("good-tracker.org should be allowed via @@ exception", allowedException)
  }

  @Test
  fun testFilterManagerSubscriptionsLifecycle() {
    val subs = filterManager.subscriptions.value
    assertTrue("Default subscription list should not be empty", subs.isNotEmpty())

    // Fresh install metadata should not have fake hardcoded numbers
    for (sub in subs) {
      if (sub.lastUpdated == 0L) {
        assertEquals("Un-downloaded list must have 0 ruleCount initially", 0, sub.ruleCount)
      }
    }

    val firstSub = subs.first()
    val initialEnabled = firstSub.enabled

    filterManager.toggleSubscription(firstSub.id)
    val toggledSub = filterManager.subscriptions.value.first { it.id == firstSub.id }
    assertEquals(!initialEnabled, toggledSub.enabled)

    // Toggle back
    filterManager.toggleSubscription(firstSub.id)
    val restoredSub = filterManager.subscriptions.value.first { it.id == firstSub.id }
    assertEquals(initialEnabled, restoredSub.enabled)
  }

  @Test
  fun testAdblockBridgePreservesBaselineRulesAcrossCompilations() {
    val baselineDecision = adblockBridge.evaluateDecision("https://google-analytics.com/analytics.js")
    assertTrue("Baseline rule must be blocked before custom compilation", baselineDecision.blocked)

    // Compile external list
    val count = adblockBridge.compileRules("||custom-popup-ad.org^")
    assertTrue("Compiled count must be positive", count > 0)

    // Verify both external rule AND baseline rule are active
    val externalDecision = adblockBridge.evaluateDecision("https://custom-popup-ad.org/ad.js")
    assertTrue("External custom rule must be blocked", externalDecision.blocked)

    val postCompileBaselineDecision = adblockBridge.evaluateDecision("https://google-analytics.com/analytics.js")
    assertTrue("Baseline rule must remain active after external compilation", postCompileBaselineDecision.blocked)
    assertTrue("Engine generation must be positive", postCompileBaselineDecision.engineGeneration > 0)
  }

  @Test
  fun testAdblockBridgeConcurrentEvaluation(): Unit = runBlocking(Dispatchers.Default) {
    adblockBridge.compileRules("||concurrent-test-tracker.com^")
    val jobs = (1..100).map { i ->
      async {
        val isAd = (i % 2 == 0)
        val url = if (isAd) "https://concurrent-test-tracker.com/pixel_$i.png" else "https://github.com/torproject/tor/commit_$i"
        val decision = adblockBridge.evaluateDecision(url)
        assertEquals(isAd, decision.blocked)
        assertTrue(decision.engineGeneration > 0)
      }
    }
    jobs.awaitAll()
  }

  @Test
  fun testValidOldEngineSurvivesFailedOrEmptyUpdate() {
    adblockBridge.compileRules("||resilient-ad-server.com^")
    val before = adblockBridge.evaluateDecision("https://resilient-ad-server.com/banner.js")
    assertTrue("Initial rule must block", before.blocked)

    // Compile empty rules
    val compiledEmpty = adblockBridge.compileRules("   \n! comments only\n  ")
    assertEquals(0, compiledEmpty)

    val after = adblockBridge.evaluateDecision("https://resilient-ad-server.com/banner.js")
    assertTrue("Engine must retain previous rules after failed or empty compile", after.blocked)
  }
}
