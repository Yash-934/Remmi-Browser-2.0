package com.remmi.browser.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Single source of truth for the active Tor route in Remmi Browser.
 * Prevents hardcoding of SOCKS ports across the application.
 */
data class TorRouteInfo(
  val host: String = "127.0.0.1",
  val socksPort: Int? = null,
  val isGhostActive: Boolean = false,
  val isVerified: Boolean = false,
  val exitIp: String? = null,
  val failoverDirect: Boolean = false,
  val generation: Long = 0L,
)

object CurrentTorRoute {
  private val generationSequence = AtomicLong(1L)
  private val _route = MutableStateFlow(TorRouteInfo(socksPort = null, isGhostActive = false, generation = 0L))
  val route: StateFlow<TorRouteInfo> = _route.asStateFlow()

  val currentSocksPort: Int?
    get() = _route.value.socksPort

  val isGhostActive: Boolean
    get() = _route.value.isGhostActive

  val isVerified: Boolean
    get() = _route.value.isVerified

  val exitIp: String?
    get() = _route.value.exitIp

  val currentGeneration: Long
    get() = _route.value.generation

  val isReady: Boolean
    get() {
      val r = _route.value
      return r.isGhostActive && r.socksPort != null && (r.socksPort ?: 0) > 0 && r.isVerified && !r.failoverDirect
    }

  fun markStartingGhost(): Long {
    val generation = generationSequence.incrementAndGet()
    _route.value = TorRouteInfo(
      host = "127.0.0.1",
      socksPort = null,
      isGhostActive = true,
      isVerified = false,
      exitIp = null,
      failoverDirect = false,
      generation = generation
    )
    return generation
  }

  fun updateRoute(
    socksPort: Int?,
    isGhostActive: Boolean,
    isVerified: Boolean = false,
    exitIp: String? = null,
    failoverDirect: Boolean = false,
    generation: Long = generationSequence.incrementAndGet(),
  ): Long {
    _route.value = TorRouteInfo(
      host = "127.0.0.1",
      socksPort = socksPort,
      isGhostActive = isGhostActive,
      isVerified = isVerified,
      exitIp = exitIp,
      failoverDirect = failoverDirect,
      generation = generation,
    )
    return generation
  }

  fun clearRoute(generation: Long = generationSequence.incrementAndGet()): Long {
    _route.value = TorRouteInfo(
      host = "127.0.0.1",
      socksPort = null,
      isGhostActive = false,
      isVerified = false,
      exitIp = null,
      failoverDirect = true,
      generation = generation,
    )
    return generation
  }

  fun markShieldActive(generation: Long = generationSequence.incrementAndGet()): Long {
    return clearRoute(generation)
  }
}

