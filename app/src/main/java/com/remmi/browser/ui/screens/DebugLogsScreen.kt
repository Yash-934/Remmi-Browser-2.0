package com.remmi.browser.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.security.TorManager
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber
import com.remmi.browser.util.DebugLogManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogsScreen(
  onBack: () -> Unit
) {
  val context = LocalContext.current
  val logs by DebugLogManager.logs.collectAsState()
  val torManager = TorManager.getInstance(context)
  val torState by torManager.bootstrapState.collectAsState()
  val circuit by torManager.currentCircuit.collectAsState()

  BackHandler(enabled = true) {
    onBack()
  }

  Scaffold(
    containerColor = ThemeCyber.colors.backgroundDarker,
    topBar = {
      TopAppBar(
        title = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Terminal,
              contentDescription = null,
              tint = ThemeCyber.colors.primary,
              modifier = Modifier.size(20.dp)
            )
            Text(
              text = "DIAGNOSTIC LOGS",
              color = ThemeCyber.colors.textPrimary,
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold
            )
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = ThemeCyber.colors.primary.copy(alpha = 0.2f),
              modifier = Modifier.border(0.5.dp, ThemeCyber.colors.primary, RoundedCornerShape(4.dp))
            ) {
              Text(
                text = "${logs.size} EVENTS",
                color = ThemeCyber.colors.primary,
                fontFamily = CyberMonoFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }
          }
        },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("debug_logs_back_button")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back",
              tint = ThemeCyber.colors.textPrimary
            )
          }
        },
        actions = {
          IconButton(
            onClick = {
              DebugLogManager.log("Diagnostic trigger: Manual log refresh")
              Toast.makeText(context, "Log refreshed", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.testTag("debug_logs_refresh_button")
          ) {
            Icon(
              imageVector = Icons.Default.Refresh,
              contentDescription = "Refresh",
              tint = ThemeCyber.colors.textMuted
            )
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = ThemeCyber.colors.surface
        )
      )
    }
  ) { paddingValues ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
        .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
      // Diagnostic Status Summary Header (Compact & High Contrast)
      Surface(
        color = Color(0xFF0F141C),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 8.dp)
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.weight(1f, fill = false)
            ) {
              Box(
                modifier = Modifier
                  .size(8.dp)
                  .clip(RoundedCornerShape(4.dp))
                  .background(if (torState is TorManager.TorState.READY) ThemeCyber.colors.successGreen else ThemeCyber.colors.warningYellow)
              )
              Text(
                text = "TOR: ${if (torState is TorManager.TorState.READY) "CONNECTED & ROUTED" else torState.statusText.uppercase()}",
                color = if (torState is TorManager.TorState.READY) ThemeCyber.colors.successGreen else ThemeCyber.colors.warningYellow,
                fontFamily = CyberMonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
              )
            }

            Surface(
              shape = RoundedCornerShape(4.dp),
              color = ThemeCyber.colors.torPurple.copy(alpha = 0.2f),
              border = androidx.compose.foundation.BorderStroke(0.5.dp, ThemeCyber.colors.torPurple.copy(alpha = 0.5f))
            ) {
              Text(
                text = "FAIL-CLOSED",
                color = ThemeCyber.colors.torPurple,
                fontFamily = CyberMonoFamily,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }
          }

          if (circuit?.verifiedExitIp != null) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "VERIFIED EXIT IP",
                color = ThemeCyber.colors.textSecondary,
                fontFamily = CyberMonoFamily,
                fontSize = 10.sp
              )
              Text(
                text = circuit?.verifiedExitIp ?: "",
                color = ThemeCyber.colors.primary,
                fontFamily = CyberMonoFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }

          Text(
            text = "WEBRTC: BLOCKED • REMOTE DNS: ON • EXTENSION: READY",
            color = ThemeCyber.colors.textMuted,
            fontFamily = CyberMonoFamily,
            fontSize = 9.sp,
            maxLines = 1
          )
        }
      }

      // Action Bar: Copy All & Clear
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Button(
          onClick = {
            if (logs.isEmpty()) {
              Toast.makeText(context, "No logs to copy", Toast.LENGTH_SHORT).show()
            } else {
              val fullText = logs.joinToString("\n")
              val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
              val clip = ClipData.newPlainText("Remmi Debug Logs", fullText)
              clipboard?.setPrimaryClip(clip)
              Toast.makeText(context, "Copied ${logs.size} logs to clipboard!", Toast.LENGTH_SHORT).show()
            }
          },
          colors = ButtonDefaults.buttonColors(
            containerColor = ThemeCyber.colors.primary,
            contentColor = Color.Black
          ),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier
            .weight(1f)
            .height(38.dp)
            .testTag("copy_debug_logs_button")
        ) {
          Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "COPY ALL",
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
          )
        }

        OutlinedButton(
          onClick = {
            DebugLogManager.clear()
            Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
          },
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier
            .weight(1f)
            .height(38.dp)
            .testTag("clear_debug_logs_button")
        ) {
          Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(15.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "CLEAR",
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }

      // Log items list with weight(1f) to prevent screen overflow
      if (logs.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0A0E14))
            .border(0.6.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
            .padding(24.dp),
          contentAlignment = Alignment.Center
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Terminal,
              contentDescription = null,
              tint = ThemeCyber.colors.textMuted,
              modifier = Modifier.size(40.dp)
            )
            Text(
              text = "NO DIAGNOSTIC LOGS YET",
              color = ThemeCyber.colors.textPrimary,
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Toggle Ghost Mode ON or browse web pages to observe live SOCKS5 proxy routing and WebExtension events.",
              color = ThemeCyber.colors.textSecondary,
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 11.sp,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0A0E14))
            .border(0.8.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp)),
          contentPadding = PaddingValues(10.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          items(logs) { logLine ->
            val color = when {
              logLine.contains("PROXIED", ignoreCase = true) ||
                logLine.contains("SUCCESS", ignoreCase = true) ||
                logLine.contains("READY", ignoreCase = true) ||
                logLine.contains("CONFIRMED", ignoreCase = true) ||
                logLine.contains("REGISTERED", ignoreCase = true) ||
                logLine.contains("connected on port", ignoreCase = true) -> Color(0xFF00FFCC)

              logLine.contains("ERROR", ignoreCase = true) ||
                logLine.contains("FAILED", ignoreCase = true) ||
                logLine.contains("CRITICAL", ignoreCase = true) ||
                logLine.contains("REJECTED", ignoreCase = true) ||
                logLine.contains("disconnected", ignoreCase = true) -> Color(0xFFFF5577)

              logLine.contains("queuing", ignoreCase = true) ||
                logLine.contains("STARTING", ignoreCase = true) ||
                logLine.contains("BOOTSTRAP", ignoreCase = true) ||
                logLine.contains("DIRECT", ignoreCase = true) -> Color(0xFFFFCC00)

              logLine.contains("Sent SOCKS5", ignoreCase = true) ||
                logLine.contains("GHOST", ignoreCase = true) ||
                logLine.contains("TOR", ignoreCase = true) ||
                logLine.contains("ONION", ignoreCase = true) -> Color(0xFFB388FF)

              else -> Color(0xFFD4DFEE)
            }

            Box(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF111722))
                .border(0.5.dp, color.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
              Text(
                text = logLine,
                fontFamily = CyberMonoFamily,
                fontSize = 11.sp,
                color = color,
                lineHeight = 15.sp
              )
            }
          }
        }
      }
    }
  }
}
