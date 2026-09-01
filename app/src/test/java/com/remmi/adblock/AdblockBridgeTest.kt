package com.remmi.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdblockBridgeTest {

  private lateinit var bridge: AdblockBridge

  @Before
  fun setUp() {
    bridge = AdblockBridge.getInstance()
    bridge.compileRules("")
  }

  @Test
  fun testGoogleTagManagerGtagJsBlocked() {
    val decision = bridge.evaluateDecision(
      url = "https://www.googletagmanager.com/gtag/js?id=G-EPK7X69JWC",
      sourceUrl = "https://adblock-tester.com/",
      initiator = "https://adblock-tester.com/",
      method = "GET",
      resourceType = "script",
      aggressive = false,
      thirdParty = true
    )
    assertTrue("gtag.js request must be blocked", decision.blocked)
  }

  @Test
  fun testGoogleAnalyticsCollectBlocked() {
    val decision = bridge.evaluateDecision(
      url = "https://www.google-analytics.com/g/collect?v=2&tid=G-EPK7X69JWC&cid=555.666",
      sourceUrl = "https://adblock-tester.com/",
      initiator = "https://adblock-tester.com/",
      method = "POST",
      resourceType = "ping",
      aggressive = false,
      thirdParty = true
    )
    assertTrue("google-analytics collect request must be blocked", decision.blocked)
  }

  @Test
  fun testFirstPartyCleanResourceAllowed() {
    val decision = bridge.evaluateDecision(
      url = "https://example.com/main.bundle.js",
      sourceUrl = "https://example.com/",
      initiator = "https://example.com/",
      method = "GET",
      resourceType = "script",
      aggressive = false,
      thirdParty = false
    )
    assertFalse("First party script on clean site must be allowed", decision.blocked)
  }

  @Test
  fun testRuleCompilationAndExceptionLifecycle() {
    val customRules = """
      ||custom-adnetwork.net^
      @@||safe.custom-adnetwork.net^
      ||generic-tracker.com^${'$'}important
    """.trimIndent()

    val count = bridge.compileRules(customRules)
    assertTrue("Compiled count must be positive", count > 0)

    val blockedDec = bridge.evaluateDecision("https://custom-adnetwork.net/ads.js")
    assertTrue("custom-adnetwork.net must be blocked", blockedDec.blocked)

    val allowedDec = bridge.evaluateDecision("https://safe.custom-adnetwork.net/script.js")
    assertFalse("safe.custom-adnetwork.net must be allowed via exception", allowedDec.blocked)
  }
}
