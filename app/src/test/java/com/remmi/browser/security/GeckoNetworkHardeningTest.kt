package com.remmi.browser.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.remmi.browser.engine.GeckoPreferenceController

class GeckoNetworkHardeningTest {

  @Test
  fun testCurrentTorRouteLifecycle() {
    CurrentTorRoute.clearRoute()
    assertNull(CurrentTorRoute.currentSocksPort)
    assertFalse(CurrentTorRoute.isGhostActive)
    assertFalse(CurrentTorRoute.isVerified)
    assertNull(CurrentTorRoute.exitIp)

    CurrentTorRoute.updateRoute(
      socksPort = 9150,
      isGhostActive = true,
      isVerified = true,
      exitIp = "185.220.101.5"
    )

    assertEquals(9150, CurrentTorRoute.currentSocksPort)
    assertTrue(CurrentTorRoute.isGhostActive)
    assertTrue(CurrentTorRoute.isVerified)
    assertEquals("185.220.101.5", CurrentTorRoute.exitIp)

    CurrentTorRoute.clearRoute()
    assertNull(CurrentTorRoute.currentSocksPort)
    assertFalse(CurrentTorRoute.isGhostActive)
  }

  @Test
  fun testNetworkHardeningTorPreferences() {
    val prefs = NetworkHardening.getTorPreferences(9250)
    assertEquals(1, prefs["network.proxy.type"])
    assertEquals("127.0.0.1", prefs["network.proxy.socks"])
    assertEquals(9250, prefs["network.proxy.socks_port"])
    assertEquals(5, prefs["network.proxy.socks_version"])
    assertEquals(true, prefs["network.proxy.socks_remote_dns"])
    assertEquals(false, prefs["network.proxy.failover_direct"])
    assertEquals("", prefs["network.proxy.no_proxies_on"])
    assertEquals(5, prefs["network.trr.mode"]) // TRR disabled in Tor mode
    assertEquals(false, prefs["media.peerconnection.enabled"])
    assertEquals(true, prefs["media.peerconnection.ice.proxy_only"])
    assertEquals(true, prefs["media.peerconnection.ice.default_address_only"])
    assertEquals(true, prefs["network.dns.disablePrefetch"])
    assertEquals(true, prefs["network.dns.disablePrefetchFromHTTPS"])
    assertEquals(3, prefs["security.tls.version.min"])
    assertEquals(4, prefs["security.tls.version.max"])
    assertEquals(false, prefs["network.websocket.allowInsecureFromHTTPS"])
    assertEquals(true, prefs["network.dns.echconfig.enabled"])
    assertEquals(true, prefs["privacy.resistFingerprinting"])
    assertEquals(true, prefs["privacy.firstparty.isolate"])
  }

  @Test
  fun testNetworkHardeningShieldPreferences() {
    val prefs = NetworkHardening.getShieldPreferences()
    assertEquals(0, prefs["network.proxy.type"])
    assertEquals("", prefs["network.proxy.socks"])
    assertEquals(0, prefs["network.proxy.socks_port"])
    assertEquals(true, prefs["network.proxy.failover_direct"])
    assertEquals(false, prefs["media.peerconnection.enabled"])
    assertEquals(2, prefs["network.trr.mode"]) // DoH enabled in Shield mode
    assertEquals("https://cloudflare-dns.com/dns-query", prefs["network.trr.uri"])
    assertEquals(true, prefs["network.dns.echconfig.enabled"])
    assertEquals(3, prefs["security.tls.version.min"])
    assertEquals(4, prefs["security.tls.version.max"])
    assertEquals(false, prefs["network.websocket.allowInsecureFromHTTPS"])
    assertEquals(true, prefs["privacy.fingerprintingProtection"])
  }

  @Test
  fun testAntiFingerprintPreferencesWithDynamicPort() {
    val ghostPrefs = AntiFingerprint.getPreferencesMap(PrivacyProfile.GHOST, socksPort = 9350)
    assertEquals(1, ghostPrefs["network.proxy.type"])
    assertEquals("127.0.0.1", ghostPrefs["network.proxy.socks"])
    assertEquals(9350, ghostPrefs["network.proxy.socks_port"])
    assertEquals(true, ghostPrefs["network.proxy.socks_remote_dns"])
    assertEquals(false, ghostPrefs["network.proxy.failover_direct"])
    assertEquals(true, ghostPrefs["privacy.resistFingerprinting"])

    val shieldPrefs = AntiFingerprint.getPreferencesMap(PrivacyProfile.SHIELD)
    assertEquals(true, shieldPrefs["privacy.fingerprintingProtection"])
    assertEquals(false, shieldPrefs["privacy.resistFingerprinting"])
  }

  @Test
  fun testGeckoPreferenceControllerConstants() {
    assertEquals(org.mozilla.geckoview.GeckoPreferenceController.PREF_BRANCH_USER, GeckoPreferenceController.PREF_BRANCH_USER)
    assertEquals(org.mozilla.geckoview.GeckoPreferenceController.PREF_BRANCH_DEFAULT, GeckoPreferenceController.PREF_BRANCH_DEFAULT)
  }
}
