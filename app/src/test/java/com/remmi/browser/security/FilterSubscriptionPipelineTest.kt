package com.remmi.browser.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.adblock.AdblockBridge
import com.remmi.adblock.FilterManager
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
}
