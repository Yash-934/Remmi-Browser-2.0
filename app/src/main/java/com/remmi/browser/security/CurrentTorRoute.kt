package com.remmi.browser.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class GhostRoutePhase {
  SHIELD,
  STARTING_TOR,
  VERIFYING_TOR,
  APPLYING_GECKO,
  VERIFYING_GECKO,
  READY,
  FAILED,
  ROTATING
}

/**
 * Single source of truth for the active Tor route in Remmi Browser.
 * Prevents hardcoding of SOCKS ports and enforces stale-generation rejection.
 */
data class TorRouteInfo(
  val host: String = "127.0.0.1",
  val socksPort: Int? = null,
  val isGhostActive: Boolean = false,
  val isVerified: Boolean = false,
  val exitIp: String? = null,
  val failoverDirect: Boolean = false,
  val generation: Long = 0L,
  val phase: GhostRoutePhase = GhostRoutePhase.SHIELD,
)

object CurrentTorRoute {
  private val generationSequence = AtomicLong(1L)
  private val _route = MutableStateFlow(
    TorRouteInfo(
      socksPort = null,
      isGhostActive = false,
      generation = 0L,
      phase = GhostRoutePhase.SHIELD
    )
  )
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

  val currentPhase: GhostRoutePhase
    get() = _route.value.phase

  val isReady: Boolean
    get() {
      val r = _route.value
      return r.phase == GhostRoutePhase.READY &&
          r.isGhostActive &&
          r.socksPort != null &&
          r.socksPort > 0 &&
          r.isVerified &&
          !r.failoverDirect &&
          r.generation > 0L
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
      generation = generation,
      phase = GhostRoutePhase.STARTING_TOR,
    )
    return generation
  }

  fun markRotatingGhost(): Long {
    val current = _route.value
    val generation = generationSequence.incrementAndGet()
    _route.value = current.copy(
      isGhostActive = true,
      isVerified = false,
      phase = GhostRoutePhase.ROTATING,
      generation = generation
    )
    return generation
  }

  fun setPhase(
    phase: GhostRoutePhase,
    generation: Long
  ): Boolean {
    val current = _route.value
    if (generation != current.generation) {
      return false
    }
    _route.value = current.copy(
      phase = phase
    )
    return true
  }

  fun updateRoute(
    socksPort: Int?,
    isGhostActive: Boolean,
    isVerified: Boolean = false,
    exitIp: String? = null,
    failoverDirect: Boolean = false,
    generation: Long
  ): Boolean {
    val current = _route.value

    // Never allow a stale transition to overwrite a newer route.
    if (generation < current.generation) {
      return false
    }

    _route.value = TorRouteInfo(
      host = "127.0.0.1",
      socksPort = socksPort,
      isGhostActive = isGhostActive,
      isVerified = isVerified,
      exitIp = exitIp,
      failoverDirect = failoverDirect,
      generation = generation,
      phase = if (isGhostActive && isVerified) GhostRoutePhase.READY else current.phase
    )
    return true
  }

  fun clearRoute(generation: Long? = null): Long {
    val targetGen = generation ?: generationSequence.incrementAndGet()
    val current = _route.value
    if (targetGen < current.generation) {
      return current.generation
    }
    _route.value = TorRouteInfo(
      host = "127.0.0.1",
      socksPort = null,
      isGhostActive = false,
      isVerified = false,
      exitIp = null,
      failoverDirect = true,
      generation = targetGen,
      phase = GhostRoutePhase.SHIELD,
    )
    return targetGen
  }

  fun markShieldActive(generation: Long? = null): Long {
    return clearRoute(generation)
  }
}
