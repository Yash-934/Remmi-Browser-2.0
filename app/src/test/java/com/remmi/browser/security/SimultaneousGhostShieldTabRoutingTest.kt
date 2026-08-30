package com.remmi.browser.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.remmi.browser.engine.GeckoEngineManager
import com.remmi.browser.engine.BrowserTab
import com.remmi.browser.engine.TabManager
import kotlinx.coroutines.runBlocking
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
@Config(sdk = [36])
class SimultaneousGhostShieldTabRoutingTest {

  private lateinit var context: Context
  private lateinit var tabManager: TabManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    tabManager = TabManager.getInstance()
    tabManager.closeAllTabs()
    CurrentTorRoute.clearRoute()
  }

  @Test
  fun testSimultaneousGhostAndShieldTabsMaintainRouteInvariant() {
    runBlocking {
      // 1. Create a Shield tab
      val shieldTab = tabManager.createTab("https://example.com", profile = PrivacyProfile.SHIELD)
      tabManager.switchToTab(shieldTab.id)

      assertFalse("Tor should not be active initially", CurrentTorRoute.isGhostActive)
      assertFalse("Tor should not be ready initially", CurrentTorRoute.isReady)

      // 2. Create a Ghost tab and activate Tor route
      val ghostTab = tabManager.createTab("https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion", profile = PrivacyProfile.GHOST)
      CurrentTorRoute.updateRoute(
        socksPort = 9050,
        isGhostActive = true,
        isVerified = true,
        exitIp = "185.220.101.5"
      )

      assertTrue("CurrentTorRoute must be active", CurrentTorRoute.isGhostActive)
      assertTrue("CurrentTorRoute must be verified", CurrentTorRoute.isVerified)
      assertTrue("CurrentTorRoute must be ready", CurrentTorRoute.isReady)

      // 3. Rapidly switch active tab to Shield tab
      tabManager.switchToTab(shieldTab.id)

      // Verify Tor route is NOT torn down or cleared while Ghost tab exists
      assertTrue("Tor route must remain active when switching to Shield tab while Ghost tab exists", CurrentTorRoute.isGhostActive)
      assertEquals(9050, CurrentTorRoute.currentSocksPort)

      // 4. Verify .onion navigation fails for Shield tab and succeeds for Ghost tab
      val checkShieldOnion = NavigationSecurityAuthority.validateAndSanitizeNavigation(
        "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion",
        isGhost = false
      )
      assertEquals(NavigationDecision.BLOCK, checkShieldOnion.decision)

      val checkGhostOnion = NavigationSecurityAuthority.validateAndSanitizeNavigation(
        "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion",
        isGhost = true
      )
      assertEquals(NavigationDecision.ALLOW, checkGhostOnion.decision)

      // 5. Close Ghost tab, now leaving only Shield tab
      tabManager.closeTab(ghostTab.id)
      val remainingGhostTabs = tabManager.tabs.value.any { it.profile == PrivacyProfile.GHOST }
      assertFalse("No ghost tabs should remain", remainingGhostTabs)

      // Clean up
      CurrentTorRoute.clearRoute()
    }
  }

  @Test
  fun testFailClosedGhostModeWithoutVerifiedProxy() {
    CurrentTorRoute.clearRoute()

    // Mark starting ghost (in-flight bootstrap)
    CurrentTorRoute.markStartingGhost()
    assertFalse("Route must not be ready while starting/unverified", CurrentTorRoute.isReady)
    assertFalse("Route must not be verified", CurrentTorRoute.isVerified)

    // OkHttp factory must throw when attempting Ghost request with unverified route
    var threw = false
    try {
      NetworkRouteAuthority.createHttpClient(isGhost = true, targetUrl = "https://example.com")
    } catch (e: IllegalStateException) {
      threw = true
    }
    assertTrue("createHttpClient must fail closed when Tor route is unverified", threw)

    // .onion request must also be blocked
    val navCheck = NavigationSecurityAuthority.validateAndSanitizeNavigation(
      "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion",
      isGhost = true
    )
    assertEquals("Navigation to .onion must be blocked when route is unverified", NavigationDecision.BLOCK, navCheck.decision)
  }
}
