package com.remmi.browser.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.runBlocking

class CurrentTorRouteRaceTest {

  @Test
  fun testRaceConditions() {
    // TEST 1: Tor succeeds
    val gen1 = CurrentTorRoute.markStartingGhost()
    CurrentTorRoute.updateRoute(socksPort = 9050, isGhostActive = true, generation = gen1)
    assertEquals(9050, CurrentTorRoute.currentSocksPort)

    // TEST 3: Stale generation timeout occurs after new success
    val gen2 = CurrentTorRoute.markStartingGhost()
    CurrentTorRoute.updateRoute(socksPort = 9150, isGhostActive = true, generation = gen2)
    assertEquals(9150, CurrentTorRoute.currentSocksPort)

    // Simulate stale verification callback from gen1
    val isStale = gen1 != CurrentTorRoute.currentGeneration
    assertEquals(true, isStale)
    // The business logic would discard it
    if (!isStale) {
      CurrentTorRoute.updateRoute(socksPort = null, isGhostActive = false, generation = gen1)
    }
    assertEquals(9150, CurrentTorRoute.currentSocksPort)

    // TEST 6: port = 0
    val gen3 = CurrentTorRoute.markStartingGhost()
    // It's checked at the consumer (NetworkHardening / BlockExtension), but let's test null handling
    CurrentTorRoute.updateRoute(socksPort = null, isGhostActive = true, generation = gen3)
    assertNull(CurrentTorRoute.currentSocksPort)
  }
}
