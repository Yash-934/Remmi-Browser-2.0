package com.remmi.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.security.TorCircuit
import com.remmi.browser.security.TorManager
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber

@Composable
fun CircuitVisualizerSheet(
  torState: TorManager.TorState,
  circuit: TorCircuit?,
  onRotateCircuit: () -> Unit,
  onStartTor: (() -> Unit)? = null,
  onLaunchOrbot: (() -> Unit)? = null,
  isOrbotInstalled: Boolean = false,
  onCheckTorProject: (() -> Unit)? = null,
  onDismiss: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.background)
      .padding(16.dp)
      .verticalScroll(rememberScrollState())
  ) {
    // Header
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.VpnKey,
          contentDescription = null,
          tint = ThemeCyber.colors.torPurple,
          modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        GlitchText(
          text = "TOR ONION ROUTING",
          fontSize = 18.sp,
          color = ThemeCyber.colors.torPurple,
        )
      }

      IconButton(
        onClick = onDismiss,
        modifier = Modifier
          .clip(RoundedCornerShape(6.dp))
          .background(ThemeCyber.colors.surfaceLight)
      ) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = "Close",
          tint = ThemeCyber.colors.textPrimary,
        )
      }
    }

    Spacer(modifier = Modifier.height(14.dp))

    // SOCKS5 Status Card
    Card(
      colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
      shape = RoundedCornerShape(8.dp),
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, ThemeCyber.colors.torPurple.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
    ) {
      Column(modifier = Modifier.padding(14.dp)) {
        val currentPort = circuit?.socksPort ?: (torState as? TorManager.TorState.READY)?.port ?: com.remmi.browser.security.CurrentTorRoute.currentSocksPort ?: 0

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "CIRCUIT ID: ${circuit?.circuitId ?: if (torState is TorManager.TorState.READY) "ACTIVE" else "INACTIVE"}",
            color = ThemeCyber.colors.torPurple,
            fontFamily = CyberMonoFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
          )

          val (badgeText, badgeColor) = when (torState) {
            is TorManager.TorState.READY -> Pair("ROUTED ($currentPort)", ThemeCyber.colors.successGreen)
            is TorManager.TorState.STARTING_SERVICE -> Pair("STARTING", ThemeCyber.colors.warningYellow)
            is TorManager.TorState.SERVICE_FOREGROUND_CONFIRMED -> Pair("ACTIVE 20%", ThemeCyber.colors.warningYellow)
            is TorManager.TorState.TOR_BOOTSTRAPPING -> Pair("${torState.progress}%", ThemeCyber.colors.warningYellow)
            is TorManager.TorState.TOR_CIRCUIT_ESTABLISHED -> Pair("CIRCUIT 70%", ThemeCyber.colors.torPurple)
            is TorManager.TorState.SOCKS_DISCOVERY -> Pair("SOCKS 80%", ThemeCyber.colors.torPurple)
            is TorManager.TorState.SOCKS5_VERIFY -> Pair("SOCKS5 85%", ThemeCyber.colors.torPurple)
            is TorManager.TorState.REMOTE_TOR_VERIFY -> Pair("VERIFYING", ThemeCyber.colors.torPurple)
            is TorManager.TorState.FAILED -> Pair("BLOCKED", ThemeCyber.colors.dangerRed)
            is TorManager.TorState.STOPPING -> Pair("STOPPING", ThemeCyber.colors.textMuted)
            is TorManager.TorState.OFF -> Pair("OFFLINE", ThemeCyber.colors.textMuted)
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .clip(RoundedCornerShape(4.dp))
              .background(badgeColor.copy(alpha = 0.15f))
              .padding(horizontal = 6.dp, vertical = 2.dp)
          ) {
            Box(
              modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(badgeColor)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = badgeText,
              color = badgeColor,
              fontFamily = CyberMonoFamily,
              fontSize = 9.sp,
              fontWeight = FontWeight.Bold,
            )
          }
        }

        Spacer(modifier = Modifier.height(6.dp))

        when (torState) {
          is TorManager.TorState.READY -> {
            Text(
              text = "SOCKS5 127.0.0.1:$currentPort // FAILOVER_DIRECT=FALSE // REMOTE DNS",
              color = ThemeCyber.colors.successGreen,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
            if (circuit?.isVerifiedTor == true) {
              Text(
                text = "✓ Verified with check.torproject.org (Exit IP: ${circuit.verifiedExitIp ?: "Protected"})",
                color = ThemeCyber.colors.primary,
                fontFamily = CyberMonoFamily,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp)
              )
            }
          }
          is TorManager.TorState.STARTING_SERVICE -> {
            Text(
              text = "Launching Tor foreground service...",
              color = ThemeCyber.colors.warningYellow,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
          is TorManager.TorState.SERVICE_FOREGROUND_CONFIRMED -> {
            Text(
              text = "Foreground service confirmed. Initializing onion daemon...",
              color = ThemeCyber.colors.warningYellow,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
          is TorManager.TorState.TOR_BOOTSTRAPPING -> {
            Text(
              text = "${torState.progress}% • ${torState.status}",
              color = ThemeCyber.colors.warningYellow,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
          is TorManager.TorState.TOR_CIRCUIT_ESTABLISHED -> {
            Text(
              text = "Circuit created. Discovering SOCKS listener...",
              color = ThemeCyber.colors.torPurple,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
          is TorManager.TorState.SOCKS_DISCOVERY -> {
            Text(
              text = "Probing SOCKS port ${torState.candidatePort}...",
              color = ThemeCyber.colors.torPurple,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
          is TorManager.TorState.SOCKS5_VERIFY -> {
            Text(
              text = "Verifying RFC 1928 SOCKS5 handshake on port ${torState.port}...",
              color = ThemeCyber.colors.torPurple,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
          is TorManager.TorState.REMOTE_TOR_VERIFY -> {
            Text(
              text = "Verifying onion exit routing with check.torproject.org (attempt ${torState.attempt})...",
              color = ThemeCyber.colors.torPurple,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
          is TorManager.TorState.FAILED -> {
            Text(
              text = "[${torState.category}] ${torState.message}",
              color = ThemeCyber.colors.dangerRed,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
          is TorManager.TorState.STOPPING -> {
            Text(
              text = "Stopping Tor onion service...",
              color = ThemeCyber.colors.textMuted,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
          is TorManager.TorState.OFF -> {
            Text(
              text = "Ghost Mode proxy inactive. Start Tor to route encrypted traffic.",
              color = ThemeCyber.colors.textMuted,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action Buttons Row
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (torState is TorManager.TorState.READY) {
            Button(
              onClick = onRotateCircuit,
              colors = ButtonDefaults.buttonColors(
                containerColor = ThemeCyber.colors.torPurple,
                contentColor = ThemeCyber.colors.backgroundDarker,
              ),
              shape = RoundedCornerShape(6.dp),
              modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .testTag("circuit_new_identity_button")
            ) {
              Icon(
                imageVector = Icons.Default.Autorenew,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "NEW IDENTITY",
                fontFamily = CyberMonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
              )
            }
          } else {
            Button(
              onClick = { onStartTor?.invoke() },
              colors = ButtonDefaults.buttonColors(
                containerColor = ThemeCyber.colors.torPurple,
                contentColor = ThemeCyber.colors.backgroundDarker,
              ),
              shape = RoundedCornerShape(6.dp),
              modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .testTag("circuit_start_tor_button")
            ) {
              Icon(
                imageVector = Icons.Default.VpnKey,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "CONNECT TOR",
                fontFamily = CyberMonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
              )
            }
          }

          if (isOrbotInstalled && onLaunchOrbot != null) {
            OutlinedButton(
              onClick = onLaunchOrbot,
              shape = RoundedCornerShape(6.dp),
              colors = ButtonDefaults.outlinedButtonColors(
                contentColor = ThemeCyber.colors.primary,
              ),
              border = androidx.compose.foundation.BorderStroke(1.dp, ThemeCyber.colors.primary),
              modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .testTag("circuit_launch_orbot_button")
            ) {
              Text(
                text = "ORBOT",
                fontFamily = CyberMonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
              )
            }
          }

          if (onCheckTorProject != null && torState is TorManager.TorState.READY) {
            OutlinedButton(
              onClick = onCheckTorProject,
              shape = RoundedCornerShape(6.dp),
              colors = ButtonDefaults.outlinedButtonColors(
                contentColor = ThemeCyber.colors.torPurple,
              ),
              border = androidx.compose.foundation.BorderStroke(1.dp, ThemeCyber.colors.torPurple),
              modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .testTag("circuit_verify_tor_button")
            ) {
              Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "TOR CHECK",
                fontFamily = CyberMonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
              )
            }
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(20.dp))

    Text(
      text = "3-HOP ENCRYPTED ONION ROUTE",
      color = ThemeCyber.colors.textMuted,
      fontFamily = CyberMonoFamily,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
    )

    Spacer(modifier = Modifier.height(12.dp))

    if (circuit != null && torState is TorManager.TorState.READY) {
      // Hop 0: Device Client
      CircuitNodeCard(
        hopTitle = "ORIGIN // LOCAL CLIENT",
        nodeName = "Remmi Android Client",
        ip = "127.0.0.1 (Isolated)",
        country = "Local Isolated Sandbox",
        latency = 0,
        color = ThemeCyber.colors.primary,
        isExit = false,
      )

      CircuitConnectorLine()

      // Hop 1: Guard Node
      CircuitNodeCard(
        hopTitle = "HOP 1 // GUARD (ENTRY)",
        nodeName = circuit.guardNodeSummary ?: "Verified Tor Guard",
        ip = "Encrypted Onion Ingress",
        country = "Onion Network",
        latency = 0,
        color = ThemeCyber.colors.warningYellow,
        isExit = false,
      )

      CircuitConnectorLine()

      // Hop 2: Middle Relay
      CircuitNodeCard(
        hopTitle = "HOP 2 // MIDDLE RELAY",
        nodeName = circuit.middleNodeSummary ?: "Zero-Knowledge Onion Relay",
        ip = "Encrypted Inner Tunnel",
        country = "Onion Network",
        latency = 0,
        color = ThemeCyber.colors.torPurple,
        isExit = false,
      )

      CircuitConnectorLine()

      // Hop 3: Exit Node
      val exitIp = circuit.verifiedExitIp ?: "Protected Tor Exit"
      CircuitNodeCard(
        hopTitle = "HOP 3 // EXIT RELAY",
        nodeName = circuit.exitNodeSummary ?: "Verified Tor Exit",
        ip = exitIp,
        country = if (circuit.isVerifiedTor) "Verified by check.torproject.org" else "Tor Exit Node",
        latency = circuit.latencyMs,
        color = ThemeCyber.colors.successGreen,
        isExit = true,
      )
    } else {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .background(ThemeCyber.colors.surface)
          .padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = if (torState.isConnecting) "TOR IS CURRENTLY BOOTSTRAPPING..." else "TOR CIRCUIT IS CURRENTLY OFFLINE.\nSWITCH TO GHOST MODE OR TAP CONNECT.",
          color = ThemeCyber.colors.textMuted,
          fontFamily = CyberMonoFamily,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}


@Composable
private fun CircuitConnectorLine() {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 24.dp),
    horizontalAlignment = Alignment.Start,
  ) {
    Box(
      modifier = Modifier
        .width(2.dp)
        .height(16.dp)
        .background(ThemeCyber.colors.surfaceBorder)
    )
  }
}

@Composable
private fun CircuitNodeCard(
  hopTitle: String,
  nodeName: String,
  ip: String,
  country: String,
  latency: Long,
  color: Color,
  isExit: Boolean,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(ThemeCyber.colors.surface)
      .border(0.8.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
      .padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(24.dp)
        .clip(CircleShape)
        .background(color.copy(alpha = 0.15f))
        .border(1.dp, color, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = if (isExit) Icons.Default.Lock else Icons.Default.Shield,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(13.dp),
      )
    }

    Spacer(modifier = Modifier.width(12.dp))

    Column(modifier = Modifier.weight(1f)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = hopTitle,
          color = color,
          fontFamily = CyberMonoFamily,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
        )
        if (latency > 0) {
          Text(
            text = "${latency}ms",
            color = ThemeCyber.colors.textMuted,
            fontFamily = CyberMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
          )
        }
      }

      Spacer(modifier = Modifier.height(2.dp))

      Text(
        text = nodeName,
        color = ThemeCyber.colors.textPrimary,
        fontFamily = CyberMonoFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
      )

      Spacer(modifier = Modifier.height(1.dp))

      Text(
        text = "$ip • $country",
        color = ThemeCyber.colors.textSecondary,
        fontFamily = CyberMonoFamily,
        fontSize = 10.sp,
      )
    }
  }
}
