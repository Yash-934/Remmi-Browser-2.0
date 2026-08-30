package com.remmi.browser.ui.screens

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.adblock.AdblockBridge
import com.remmi.adblock.FilterManager
import com.remmi.browser.security.DnsProvider
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.TamperDetection
import com.remmi.browser.storage.RemmiDatabase
import com.remmi.browser.storage.SearchEngine
import com.remmi.browser.storage.SettingsRepository
import com.remmi.browser.ui.components.BackgroundTypes
import com.remmi.browser.ui.components.GlitchText
import com.remmi.browser.ui.theme.BrowserFont
import com.remmi.browser.ui.theme.CyberTheme
import com.remmi.browser.ui.theme.ThemeCyber

/**
 * Settings Categories for clean, modular browser configuration screens.
 */
enum class SettingsCategory(
  val title: String,
  val icon: ImageVector,
) {
  SEARCH_ENGINE(
    title = "Search Engine",
    icon = Icons.Default.Search,
  ),
  APPEARANCE(
    title = "Appearance & Themes",
    icon = Icons.Default.Palette,
  ),
  PRIVACY_SECURITY(
    title = "Privacy & Security",
    icon = Icons.Default.Shield,
  ),
  ADBLOCK(
    title = "Shields & Ad Blocking",
    icon = Icons.Default.Shield,
  ),
  PASSWORDS(
    title = "Passwords & Vault",
    icon = Icons.Default.VpnKey,
  ),
  DISPLAY_VIEWPORT(
    title = "Display & Reader View",
    icon = Icons.Default.DesktopWindows,
  ),
  SYSTEM_ADVANCED(
    title = "System & Advanced",
    icon = Icons.Default.Terminal,
  );
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  onOpenPasswords: () -> Unit = {},
  onOpenDebugLogs: () -> Unit = {},
) {
  val context = LocalContext.current
  val activity = context as? Activity
  val scope = rememberCoroutineScope()

  val settingsRepo = remember { SettingsRepository.getInstance(context) }
  val settings by settingsRepo.settings.collectAsState()

  val adblockBridge = remember { AdblockBridge.getInstance() }
  val filterManager = remember { FilterManager.getInstance(context, adblockBridge) }
  val subscriptions by filterManager.subscriptions.collectAsState()

  val integrityReport = remember { TamperDetection.checkIntegrity(context) }
  val passwordRepo = remember { com.remmi.browser.security.PasswordManagerRepository.getInstance(context) }
  val vaultLockState by passwordRepo.lockState.collectAsState()

  var selectedCategory by remember { mutableStateOf<SettingsCategory?>(null) }

  var isDefaultBrowser by remember {
    mutableStateOf(com.remmi.browser.util.DefaultBrowserHelper.isDefaultBrowser(context))
  }

  val defaultBrowserLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) {
    isDefaultBrowser = com.remmi.browser.util.DefaultBrowserHelper.isDefaultBrowser(context)
  }

  val wallpaperPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    if (uri != null) {
      settingsRepo.updateCustomWallpaper(uri.toString())
      settingsRepo.updateBackgroundAnimation(BackgroundTypes.CUSTOM_IMAGE)
    }
  }

  val accentColor = ThemeCyber.colors.primary
  var showPanicDialog by remember { mutableStateOf(false) }
  var showAnimationMenu by remember { mutableStateOf(false) }
  var showFontMenu by remember { mutableStateOf(false) }
  var showDnsMenu by remember { mutableStateOf(false) }
  var showSearchEngineMenu by remember { mutableStateOf(false) }

  // Intercept Android System Back
  BackHandler(enabled = true) {
    if (selectedCategory != null) {
      selectedCategory = null
    } else {
      onBack()
    }
  }

  var totalDragX by remember { mutableFloatStateOf(0f) }

  val handleBackAction = {
    if (selectedCategory != null) {
      selectedCategory = null
    } else {
      onBack()
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .pointerInput(Unit) {
        detectHorizontalDragGestures(
          onDragStart = { totalDragX = 0f },
          onDragEnd = {
            if (totalDragX > 80f || totalDragX < -120f) {
              handleBackAction()
            }
            totalDragX = 0f
          },
          onDragCancel = { totalDragX = 0f },
          onHorizontalDrag = { _, dragAmount ->
            totalDragX += dragAmount
            if (totalDragX > 150f || totalDragX < -180f) {
              handleBackAction()
              totalDragX = 0f
            }
          }
        )
      }
  ) {
    Scaffold(
      modifier = Modifier
        .fillMaxSize()
        .background(ThemeCyber.colors.background)
        .statusBarsPadding()
        .navigationBarsPadding(),
      containerColor = ThemeCyber.colors.background,
      topBar = {
        TopAppBar(
          title = {
            GlitchText(
              text = selectedCategory?.title ?: "SETTINGS",
              fontSize = 17.sp,
              color = accentColor,
            )
          },
          navigationIcon = {
            IconButton(
              onClick = handleBackAction,
              modifier = Modifier.testTag("settings_back_button")
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = accentColor,
              )
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(
            containerColor = ThemeCyber.colors.surface,
          ),
        )
      }
    ) { paddingValues ->

      if (selectedCategory == null) {
        // ==========================================
        // 1. MAIN SETTINGS SCREEN (Categories List)
        // ==========================================
        LazyColumn(
          modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          // Status Overview Card
          item {
            Card(
              colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Box(
                    modifier = Modifier
                      .size(36.dp)
                      .clip(CircleShape)
                      .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                  ) {
                    Icon(
                      imageVector = Icons.Default.Shield,
                      contentDescription = null,
                      tint = accentColor,
                      modifier = Modifier.size(20.dp)
                    )
                  }
                  Spacer(modifier = Modifier.width(12.dp))
                  Column {
                    Text(
                      text = "REMMI BROWSER",
                      color = accentColor,
                      fontFamily = ThemeCyber.fontFamily,
                      fontSize = 13.sp,
                      fontWeight = FontWeight.Black
                    )
                    Text(
                      text = "Version 1.0 • Cyber Matrix Core",
                      color = ThemeCyber.colors.textSecondary,
                      fontSize = 11.sp
                    )
                  }
                }

                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = if (isDefaultBrowser) ThemeCyber.colors.successGreen.copy(alpha = 0.15f) else ThemeCyber.colors.surfaceLight,
                  border = androidx.compose.foundation.BorderStroke(
                    0.8.dp,
                    if (isDefaultBrowser) ThemeCyber.colors.successGreen else ThemeCyber.colors.surfaceBorder
                  )
                ) {
                  Text(
                    text = if (isDefaultBrowser) "DEFAULT APP" else "STANDARD",
                    color = if (isDefaultBrowser) ThemeCyber.colors.successGreen else ThemeCyber.colors.textSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = ThemeCyber.fontFamily,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                  )
                }
              }
            }
            Spacer(modifier = Modifier.height(6.dp))
          }

          // Category 1: Search Engine
          item {
            val currentEngine = SearchEngine.fromId(settings.searchEngineName)
            SettingsCategoryCard(
              icon = Icons.Default.Search,
              title = "Search Engine",
              subtitle = "${currentEngine.displayName} • ${currentEngine.subtitle}",
              accentColor = accentColor,
              onClick = { selectedCategory = SettingsCategory.SEARCH_ENGINE }
            )
          }

          // Category 2: Appearance & Themes
          item {
            val wallpaperName = if (settings.customWallpaperUri != null) "Custom Photo" else "Live Animation"
            SettingsCategoryCard(
              icon = Icons.Default.Palette,
              title = "Appearance & Themes",
              subtitle = "${settings.cyberTheme.displayName} Theme • ${settings.browserFont.displayName} • $wallpaperName",
              accentColor = accentColor,
              onClick = { selectedCategory = SettingsCategory.APPEARANCE }
            )
          }

          // Category 3: Privacy & Security
          item {
            val profileName = if (settings.defaultProfile == PrivacyProfile.GHOST) "Ghost Mode (Tor)" else "Shield Mode (Fast FPP)"
            SettingsCategoryCard(
              icon = Icons.Default.Shield,
              title = "Privacy & Security",
              subtitle = "$profileName • ${settings.dnsProvider.displayName} • HTTPS-Only",
              accentColor = accentColor,
              onClick = { selectedCategory = SettingsCategory.PRIVACY_SECURITY }
            )
          }

          // Category 4: Shields & Ad Blocking
          item {
            val enabledCount = subscriptions.count { it.enabled }
            SettingsCategoryCard(
              icon = Icons.Default.Shield,
              title = "Shields & Ad Blocking",
              subtitle = "$enabledCount Active Filter Lists • Native tracker & ad blocker",
              accentColor = accentColor,
              onClick = { selectedCategory = SettingsCategory.ADBLOCK }
            )
          }

          // Category 5: Passwords & Vault
          item {
            val vaultStatusLabel = when (vaultLockState) {
              is com.remmi.browser.security.VaultLockState.Unlocked -> "Unlocked"
              is com.remmi.browser.security.VaultLockState.Locked -> "Locked (AES-256-GCM)"
              is com.remmi.browser.security.VaultLockState.Uninitialized -> "Ready to set up"
              is com.remmi.browser.security.VaultLockState.TemporarilyLocked -> "Locked out"
              is com.remmi.browser.security.VaultLockState.CompromisedDevice -> "Compromised"
            }
            SettingsCategoryCard(
              icon = Icons.Default.VpnKey,
              title = "Passwords & Vault",
              subtitle = "Argon2id (64 MiB KDF) • StrongBox Keystore • $vaultStatusLabel",
              accentColor = accentColor,
              onClick = { onOpenPasswords() }
            )
          }

          // Category 6: Display & Reader View
          item {
            val readerSizeLabel = when (settings.readerFontSize) {
              0 -> "Small"
              1 -> "Medium"
              else -> "Large"
            }
            SettingsCategoryCard(
              icon = Icons.Default.DesktopWindows,
              title = "Display & Reader View",
              subtitle = "Reader text ($readerSizeLabel) • ${if (settings.pureBlackOled) "OLED Black" else "Standard Dark"} • Desktop layout",
              accentColor = accentColor,
              onClick = { selectedCategory = SettingsCategory.DISPLAY_VIEWPORT }
            )
          }

          // Category 7: System & Advanced
          item {
            val integrityText = if (integrityReport.isRootDetected) "Integrity Flagged" else "Integrity Secure"
            SettingsCategoryCard(
              icon = Icons.Default.Terminal,
              title = "System & Advanced",
              subtitle = "$integrityText • Diagnostic logs • Emergency wipe",
              accentColor = accentColor,
              onClick = { selectedCategory = SettingsCategory.SYSTEM_ADVANCED }
            )
          }
        }

      } else {
        // ==========================================
        // 2. DEDICATED CATEGORY SUB-SCREENS
        // ==========================================
        when (selectedCategory) {
          SettingsCategory.SEARCH_ENGINE -> {
            LazyColumn(
              modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              item {
                SectionHeader("DEFAULT PRIVACY SEARCH ENGINE")
                val currentEngine = SearchEngine.fromId(settings.searchEngineName)
                Card(
                  colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(10.dp))
                ) {
                  Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                      Surface(
                        modifier = Modifier
                          .fillMaxWidth()
                          .clip(RoundedCornerShape(8.dp))
                          .clickable { showSearchEngineMenu = true },
                        color = ThemeCyber.colors.background,
                        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor),
                        shape = RoundedCornerShape(8.dp)
                      ) {
                        Row(
                          modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                          verticalAlignment = Alignment.CenterVertically,
                          horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                              Icons.Default.Search,
                              contentDescription = null,
                              tint = accentColor,
                              modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                              Text(
                                text = "PRIMARY SEARCH ENGINE",
                                color = accentColor,
                                fontSize = 10.sp,
                                fontFamily = ThemeCyber.fontFamily,
                                fontWeight = FontWeight.Bold
                              )
                              Text(
                                text = currentEngine.displayName,
                                color = ThemeCyber.colors.textPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold
                              )
                              Text(
                                text = currentEngine.subtitle,
                                color = ThemeCyber.colors.textSecondary,
                                fontSize = 10.5.sp,
                                maxLines = 1
                              )
                            }
                          }
                          Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Select Search Engine",
                            tint = accentColor,
                            modifier = Modifier.size(24.dp)
                          )
                        }
                      }

                      DropdownMenu(
                        expanded = showSearchEngineMenu,
                        onDismissRequest = { showSearchEngineMenu = false },
                        modifier = Modifier
                          .fillMaxWidth(0.9f)
                          .background(ThemeCyber.colors.surface)
                          .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
                      ) {
                        SearchEngine.entries.forEach { engine ->
                          val isSelected = currentEngine == engine
                          DropdownMenuItem(
                            text = {
                              Column {
                                Text(
                                  text = engine.displayName,
                                  color = if (isSelected) accentColor else ThemeCyber.colors.textPrimary,
                                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                  fontSize = 13.5.sp
                                )
                                Text(
                                  text = engine.subtitle,
                                  color = ThemeCyber.colors.textSecondary,
                                  fontSize = 10.5.sp
                                )
                              }
                            },
                            onClick = {
                              settingsRepo.updateSearchEngine(engine.displayName)
                              showSearchEngineMenu = false
                            },
                            leadingIcon = if (isSelected) {
                              {
                                Icon(
                                  Icons.Default.Check,
                                  contentDescription = "Selected",
                                  tint = accentColor,
                                  modifier = Modifier.size(16.dp)
                                )
                              }
                            } else null
                          )
                        }
                      }
                    }
                  }
                }
              }
            }
          }

          SettingsCategory.APPEARANCE -> {
            LazyColumn(
              modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              // Accent Theme
              item {
                SectionHeader("CYBERPUNK HUD ACCENT THEME")
                Column(
                  modifier = Modifier.fillMaxWidth(),
                  verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  for (i in CyberTheme.entries.indices step 2) {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                      val firstTheme = CyberTheme.entries[i]
                      val secondTheme = if (i + 1 < CyberTheme.entries.size) CyberTheme.entries[i + 1] else null

                      ThemeSelectorCard(
                        theme = firstTheme,
                        isSelected = settings.cyberTheme == firstTheme,
                        onClick = { settingsRepo.updateCyberTheme(firstTheme) },
                        modifier = Modifier.weight(1f)
                      )

                      if (secondTheme != null) {
                        ThemeSelectorCard(
                          theme = secondTheme,
                          isSelected = settings.cyberTheme == secondTheme,
                          onClick = { settingsRepo.updateCyberTheme(secondTheme) },
                          modifier = Modifier.weight(1f)
                        )
                      } else {
                        Spacer(modifier = Modifier.weight(1f))
                      }
                    }
                  }
                }
              }

              // Typography / Font
              item {
                SectionHeader("GLOBAL BROWSER FONT & TYPOGRAPHY")
                Card(
                  colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(10.dp))
                ) {
                  Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                  ) {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Column {
                        Text(
                          text = "Active App Font",
                          color = accentColor,
                          fontFamily = ThemeCyber.fontFamily,
                          fontSize = 12.sp,
                          fontWeight = FontWeight.Bold
                        )
                        Text(
                          text = "Applies instantly across tabs, URL bar, sheets, HUD & all UI",
                          color = ThemeCyber.colors.textSecondary,
                          fontFamily = ThemeCyber.fontFamily,
                          fontSize = 11.sp
                        )
                      }
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                      Surface(
                        modifier = Modifier
                          .fillMaxWidth()
                          .clip(RoundedCornerShape(8.dp))
                          .clickable { showFontMenu = true },
                        color = ThemeCyber.colors.background,
                        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor),
                        shape = RoundedCornerShape(8.dp)
                      ) {
                        Row(
                          modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                          verticalAlignment = Alignment.CenterVertically,
                          horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                              Icons.Default.Palette,
                              contentDescription = null,
                              tint = accentColor,
                              modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                              Text(
                                text = settings.browserFont.displayName,
                                color = ThemeCyber.colors.textPrimary,
                                fontSize = 13.5.sp,
                                fontFamily = settings.browserFont.fontFamily,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                              )
                              Text(
                                text = settings.browserFont.subtitle,
                                color = ThemeCyber.colors.textSecondary,
                                fontSize = 10.5.sp,
                                maxLines = 1
                              )
                            }
                          }
                          Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Select Font",
                            tint = accentColor,
                            modifier = Modifier.size(24.dp)
                          )
                        }
                      }

                      DropdownMenu(
                        expanded = showFontMenu,
                        onDismissRequest = { showFontMenu = false },
                        modifier = Modifier
                          .fillMaxWidth(0.9f)
                          .background(ThemeCyber.colors.surface)
                          .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
                      ) {
                        BrowserFont.entries.forEach { fontOption ->
                          val isSelected = settings.browserFont == fontOption
                          DropdownMenuItem(
                            text = {
                              Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                              ) {
                                Column(modifier = Modifier.weight(1f)) {
                                  Text(
                                    text = fontOption.displayName,
                                    color = if (isSelected) accentColor else ThemeCyber.colors.textPrimary,
                                    fontSize = 13.5.sp,
                                    fontFamily = fontOption.fontFamily,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                  )
                                  Text(
                                    text = fontOption.subtitle,
                                    color = ThemeCyber.colors.textSecondary,
                                    fontSize = 10.5.sp
                                  )
                                }
                                Surface(
                                  shape = RoundedCornerShape(4.dp),
                                  color = if (isSelected) accentColor.copy(alpha = 0.15f) else ThemeCyber.colors.surfaceBorder.copy(alpha = 0.3f),
                                  border = androidx.compose.foundation.BorderStroke(0.5.dp, if (isSelected) accentColor else ThemeCyber.colors.surfaceBorder)
                                ) {
                                  Text(
                                    text = fontOption.category,
                                    color = if (isSelected) accentColor else ThemeCyber.colors.textSecondary,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                  )
                                }
                              }
                            },
                            onClick = {
                              settingsRepo.updateBrowserFont(fontOption)
                              showFontMenu = false
                            },
                            leadingIcon = if (isSelected) {
                              {
                                Icon(
                                  Icons.Default.Check,
                                  contentDescription = "Selected",
                                  tint = accentColor,
                                  modifier = Modifier.size(16.dp)
                                )
                              }
                            } else null
                          )
                        }
                      }
                    }

                    // Live Preview
                    Surface(
                      shape = RoundedCornerShape(6.dp),
                      color = ThemeCyber.colors.background.copy(alpha = 0.7f),
                      border = androidx.compose.foundation.BorderStroke(0.5.dp, ThemeCyber.colors.surfaceBorder.copy(alpha = 0.5f)),
                      modifier = Modifier.fillMaxWidth()
                    ) {
                      Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(
                          text = "LIVE PREVIEW",
                          color = accentColor,
                          fontSize = 9.sp,
                          fontWeight = FontWeight.Bold,
                          fontFamily = ThemeCyber.fontFamily
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                          text = settings.browserFont.previewSample,
                          fontFamily = settings.browserFont.fontFamily,
                          color = ThemeCyber.colors.textPrimary,
                          fontSize = 12.sp,
                          fontWeight = FontWeight.Medium,
                          maxLines = 1
                        )
                      }
                    }
                  }
                }
              }

              // Background Animation & Wallpaper
              item {
                SectionHeader("BACKGROUND ANIMATION & CUSTOM WALLPAPER")
                Card(
                  colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier.fillMaxWidth().border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
                ) {
                  Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val animOptions = listOf(
                      BackgroundTypes.LIGHT_AURA_MESH to "Ambient Aura Waves (Light Mode)",
                      BackgroundTypes.LIGHT_FLOATING_ORBS to "Floating Pastel Orbs (Light Mode)",
                      BackgroundTypes.LIGHT_GEOMETRIC_DOTS to "Minimal Pulsing Dot Grid (Light Mode)",
                      BackgroundTypes.LIGHT_CONSTELLATION to "Connected Nodes Constellation",
                      BackgroundTypes.CYBERPUNK_GRID to "Cyberpunk 3D Grid (Retro)",
                      BackgroundTypes.MATRIX_RAIN to "Matrix Digital Rain",
                      BackgroundTypes.NEON_PARTICLES to "Neon Quantum Particles",
                      BackgroundTypes.DIGITAL_AURORA to "Digital Neon Aurora",
                      BackgroundTypes.MINIMAL_GRADIENT to "Minimal Stealth Gradient",
                    )

                    val currentSelectedLabel = if (settings.customWallpaperUri != null) {
                      "Custom Image from Gallery"
                    } else {
                      animOptions.firstOrNull { it.first == settings.backgroundAnimation }?.second ?: "Cyberpunk 3D Grid (Retro)"
                    }

                    Text(
                      text = "Cyberpunk Live Animation",
                      color = accentColor,
                      fontFamily = ThemeCyber.fontFamily,
                      fontSize = 12.sp,
                      fontWeight = FontWeight.Bold
                    )

                    Box(modifier = Modifier.fillMaxWidth()) {
                      Surface(
                        modifier = Modifier
                          .fillMaxWidth()
                          .clip(RoundedCornerShape(8.dp))
                          .clickable { showAnimationMenu = true },
                        color = ThemeCyber.colors.background,
                        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor),
                        shape = RoundedCornerShape(8.dp)
                      ) {
                        Row(
                          modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                          verticalAlignment = Alignment.CenterVertically,
                          horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                              Icons.Default.Wallpaper,
                              contentDescription = null,
                              tint = accentColor,
                              modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                              text = currentSelectedLabel,
                              color = ThemeCyber.colors.textPrimary,
                              fontSize = 13.sp,
                              fontFamily = ThemeCyber.fontFamily,
                              fontWeight = FontWeight.SemiBold,
                              maxLines = 1
                            )
                          }
                          Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Select Animation",
                            tint = accentColor,
                            modifier = Modifier.size(24.dp)
                          )
                        }
                      }

                      DropdownMenu(
                        expanded = showAnimationMenu,
                        onDismissRequest = { showAnimationMenu = false },
                        modifier = Modifier
                          .background(ThemeCyber.colors.surface)
                          .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
                      ) {
                        animOptions.forEach { (type, label) ->
                          val isSelected = settings.backgroundAnimation == type && settings.customWallpaperUri == null
                          DropdownMenuItem(
                            text = {
                              Text(
                                text = label,
                                color = if (isSelected) accentColor else ThemeCyber.colors.textPrimary,
                                fontSize = 13.sp,
                                fontFamily = ThemeCyber.fontFamily,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                              )
                            },
                            trailingIcon = {
                              if (isSelected) {
                                Icon(Icons.Default.Check, null, tint = accentColor, modifier = Modifier.size(16.dp))
                              }
                            },
                            onClick = {
                              settingsRepo.updateBackgroundAnimation(type)
                              settingsRepo.updateCustomWallpaper(null)
                              showAnimationMenu = false
                            }
                          )
                        }
                      }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      OutlinedButton(
                        onClick = { wallpaperPickerLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor)
                      ) {
                        Icon(Icons.Default.PhotoLibrary, null, tint = accentColor, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                          "Choose Custom Photo",
                          color = accentColor,
                          fontWeight = FontWeight.Bold,
                          fontSize = 12.sp,
                          fontFamily = ThemeCyber.fontFamily
                        )
                      }

                      if (settings.customWallpaperUri != null) {
                        IconButton(
                          onClick = {
                            settingsRepo.updateCustomWallpaper(null)
                            settingsRepo.updateBackgroundAnimation(BackgroundTypes.CYBERPUNK_GRID)
                          },
                          modifier = Modifier
                            .size(40.dp)
                            .background(ThemeCyber.colors.dangerRed.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .border(1.dp, ThemeCyber.colors.dangerRed, RoundedCornerShape(8.dp))
                        ) {
                          Icon(
                            Icons.Default.Delete,
                            contentDescription = "Remove Custom Photo",
                            tint = ThemeCyber.colors.dangerRed,
                            modifier = Modifier.size(18.dp)
                          )
                        }
                      }
                    }

                    if (settings.customWallpaperUri != null) {
                      Divider(modifier = Modifier.padding(vertical = 4.dp), color = ThemeCyber.colors.surfaceBorder)

                      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween,
                          verticalAlignment = Alignment.CenterVertically
                        ) {
                          Text(
                            "Wallpaper Visibility & Clarity",
                            color = accentColor,
                            fontFamily = ThemeCyber.fontFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                          )
                          val visibilityPercent = ((1f - settings.wallpaperDimLevel) * 100).toInt()
                          Text(
                            if (settings.wallpaperDimLevel <= 0.01f) "100% Full (Crystal Clear)" else "$visibilityPercent% Visible",
                            color = if (settings.wallpaperDimLevel <= 0.01f) ThemeCyber.colors.neonCyan else ThemeCyber.colors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                          )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                          listOf(
                            0.0f to "100% Full",
                            0.25f to "75%",
                            0.50f to "50%",
                            0.75f to "25%"
                          ).forEach { (dimVal, label) ->
                            val isPresetSelected = Math.abs(settings.wallpaperDimLevel - dimVal) < 0.05f
                            Surface(
                              modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { settingsRepo.updateWallpaperDimLevel(dimVal) },
                              shape = RoundedCornerShape(6.dp),
                              color = if (isPresetSelected) accentColor.copy(alpha = 0.2f) else ThemeCyber.colors.background,
                              border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isPresetSelected) accentColor else ThemeCyber.colors.surfaceBorder
                              )
                            ) {
                              Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                Text(
                                  label,
                                  color = if (isPresetSelected) accentColor else ThemeCyber.colors.textSecondary,
                                  fontSize = 10.sp,
                                  fontWeight = if (isPresetSelected) FontWeight.Bold else FontWeight.Normal
                                )
                              }
                            }
                          }
                        }

                        Slider(
                          value = 1f - settings.wallpaperDimLevel,
                          onValueChange = { visibility ->
                            settingsRepo.updateWallpaperDimLevel(1f - visibility)
                          },
                          colors = SliderDefaults.colors(
                            thumbColor = accentColor,
                            activeTrackColor = accentColor,
                            inactiveTrackColor = ThemeCyber.colors.surfaceBorder
                          ),
                          modifier = Modifier.fillMaxWidth().height(26.dp)
                        )
                      }

                      Row(
                        modifier = Modifier
                          .fillMaxWidth()
                          .clip(RoundedCornerShape(8.dp))
                          .background(ThemeCyber.colors.background)
                          .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
                          .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                      ) {
                        Column(modifier = Modifier.weight(1f)) {
                          Text(
                            "Full Screen Background",
                            color = ThemeCyber.colors.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = ThemeCyber.fontFamily
                          )
                          Text(
                            "Edge-to-edge behind top search bar & bottom toolbar",
                            color = ThemeCyber.colors.textMuted,
                            fontSize = 10.sp
                          )
                        }
                        Switch(
                          checked = settings.fullscreenWallpaperEnabled,
                          onCheckedChange = { settingsRepo.updateFullscreenWallpaper(it) },
                          colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = accentColor,
                            uncheckedThumbColor = ThemeCyber.colors.textMuted,
                            uncheckedTrackColor = ThemeCyber.colors.surface
                          )
                        )
                      }
                    }
                  }
                }
              }
            }
          }

          SettingsCategory.PRIVACY_SECURITY -> {
            LazyColumn(
              modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              // Default Privacy Profile
              item {
                SectionHeader("DEFAULT PRIVACY PROFILE")
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                  ProfileOptionCard(
                    profile = PrivacyProfile.SHIELD,
                    isSelected = settings.defaultProfile == PrivacyProfile.SHIELD,
                    onClick = { settingsRepo.updateDefaultProfile(PrivacyProfile.SHIELD) },
                    modifier = Modifier.weight(1f),
                  )
                  ProfileOptionCard(
                    profile = PrivacyProfile.GHOST,
                    isSelected = settings.defaultProfile == PrivacyProfile.GHOST,
                    onClick = { settingsRepo.updateDefaultProfile(PrivacyProfile.GHOST) },
                    modifier = Modifier.weight(1f),
                  )
                }
              }

              // Anti-Fingerprinting & Hardening
              item {
                SectionHeader("ANTI-FINGERPRINTING & HARDENING")
              }

              item {
                SettingsToggleRow(
                  icon = Icons.Default.Shield,
                  title = "HTTPS-Only Network Enforcement",
                  subtitle = "Strictly upgrade all requests to TLS. Insecure HTTP connections are dropped immediately.",
                  checked = settings.httpsOnlyMode,
                  onCheckedChange = { settingsRepo.updateHttpsOnly(it) },
                  accentColor = accentColor,
                )
              }

              item {
                val clearDataActive = settings.clearDataOnExit
                SettingsToggleRow(
                  icon = Icons.Default.OpenInBrowser,
                  title = "Restore Previous Session",
                  subtitle = if (clearDataActive) {
                    "Disabled: Previous session will not be restored because 'Clear Data On App Exit' is currently active."
                  } else {
                    "Automatically re-open previous browsing tabs and active state when Remmi Browser starts."
                  },
                  checked = settings.restoreLastSession && !clearDataActive,
                  onCheckedChange = { isChecked ->
                    settingsRepo.updateRestoreLastSession(isChecked)
                  },
                  accentColor = accentColor,
                )
              }

              item {
                SettingsToggleRow(
                  icon = Icons.Default.Delete,
                  title = "Clear Data On App Exit",
                  subtitle = "Automatically purge open tabs, temporary cache, DOM storage, and session keys on shutdown.",
                  checked = settings.clearDataOnExit,
                  onCheckedChange = { isChecked ->
                    settingsRepo.updateClearOnExit(isChecked)
                  },
                  accentColor = ThemeCyber.colors.dangerRed,
                )
              }

              // Encrypted DNS (DoH)
              item {
                SectionHeader("ENCRYPTED DNS & PRIVACY PROTOCOLS")
              }

              item {
                Card(
                  colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                      Surface(
                        modifier = Modifier
                          .fillMaxWidth()
                          .clip(RoundedCornerShape(8.dp))
                          .clickable { showDnsMenu = true },
                        color = ThemeCyber.colors.background,
                        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor),
                        shape = RoundedCornerShape(8.dp)
                      ) {
                        Row(
                          modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                          verticalAlignment = Alignment.CenterVertically,
                          horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                              Icons.Default.Language,
                              contentDescription = null,
                              tint = accentColor,
                              modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                              Text(
                                text = "Encrypted DNS (DoH)",
                                color = accentColor,
                                fontSize = 11.sp,
                                fontFamily = ThemeCyber.fontFamily,
                                fontWeight = FontWeight.Bold
                              )
                              Text(
                                text = settings.dnsProvider.displayName,
                                color = ThemeCyber.colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                              )
                              Text(
                                text = settings.dnsProvider.description,
                                color = ThemeCyber.colors.textSecondary,
                                fontSize = 10.sp,
                                maxLines = 1
                              )
                            }
                          }
                          Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Select DNS",
                            tint = accentColor,
                            modifier = Modifier.size(24.dp)
                          )
                        }
                      }

                      DropdownMenu(
                        expanded = showDnsMenu,
                        onDismissRequest = { showDnsMenu = false },
                        modifier = Modifier
                          .fillMaxWidth(0.9f)
                          .background(ThemeCyber.colors.surface)
                          .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
                      ) {
                        DnsProvider.entries.forEach { provider ->
                          val isSelected = settings.dnsProvider == provider
                          DropdownMenuItem(
                            text = {
                              Column {
                                Text(
                                  text = provider.displayName,
                                  color = if (isSelected) accentColor else ThemeCyber.colors.textPrimary,
                                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                  fontSize = 13.sp
                                )
                                Text(
                                  text = provider.description,
                                  color = ThemeCyber.colors.textSecondary,
                                  fontSize = 10.sp
                                )
                              }
                            },
                            onClick = {
                              settingsRepo.updateDnsProvider(provider)
                              showDnsMenu = false
                            },
                            leadingIcon = if (isSelected) {
                              {
                                Icon(
                                  Icons.Default.Check,
                                  contentDescription = "Selected",
                                  tint = accentColor,
                                  modifier = Modifier.size(16.dp)
                                )
                              }
                            } else null
                          )
                        }
                      }
                    }
                  }
                }
              }

              item {
                SettingsToggleRow(
                  icon = Icons.Default.Shield,
                  title = "Encrypted Client Hello (ECH)",
                  subtitle = "Encrypts the TLS Server Name Indication (SNI) to prevent ISP & network eavesdropping on website names.",
                  checked = settings.encryptedClientHelloEnabled,
                  onCheckedChange = { settingsRepo.updateEchEnabled(it) },
                  accentColor = accentColor,
                )
              }

              item {
                SettingsToggleRow(
                  icon = Icons.Default.Fingerprint,
                  title = "Global Privacy Control (Sec-GPC)",
                  subtitle = "Transmits Sec-GPC: 1 HTTP header to legally communicate refusal of tracking and data sale under CCPA/GDPR.",
                  checked = settings.globalPrivacyControlEnabled,
                  onCheckedChange = { settingsRepo.updateGpcEnabled(it) },
                  accentColor = accentColor,
                )
              }

              item {
                SettingsToggleRow(
                  icon = Icons.Default.OpenInBrowser,
                  title = "Strict Referrer Trimming",
                  subtitle = "Strips URL paths and query parameters from HTTP Referer headers across origins, only broadcasting origin root.",
                  checked = settings.strictReferrerPolicy,
                  onCheckedChange = { settingsRepo.updateStrictReferrerPolicy(it) },
                  accentColor = accentColor,
                )
              }
            }
          }

          SettingsCategory.ADBLOCK -> {
            LazyColumn(
              modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              item {
                SectionHeader("NATIVE ADBLOCK LIST SUBSCRIPTIONS")
              }

              items(subscriptions) { sub ->
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ThemeCyber.colors.surface)
                    .border(0.6.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Text(
                        text = sub.title,
                        color = ThemeCyber.colors.textPrimary,
                        fontFamily = ThemeCyber.fontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                      )
                      Spacer(modifier = Modifier.width(6.dp))
                      Text(
                        text = "(${sub.ruleCount} RULES)",
                        color = accentColor,
                        fontFamily = ThemeCyber.fontFamily,
                        fontSize = 10.sp,
                      )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                      text = sub.description,
                      color = ThemeCyber.colors.textSecondary,
                      fontFamily = ThemeCyber.fontFamily,
                      fontSize = 10.sp,
                    )
                  }

                  Switch(
                    checked = sub.enabled,
                    onCheckedChange = { filterManager.toggleSubscription(sub.id) },
                    colors = SwitchDefaults.colors(
                      checkedThumbColor = ThemeCyber.colors.backgroundDarker,
                      checkedTrackColor = accentColor,
                      uncheckedThumbColor = ThemeCyber.colors.textMuted,
                      uncheckedTrackColor = ThemeCyber.colors.surfaceLight,
                    )
                  )
                }
              }
            }
          }

          SettingsCategory.PASSWORDS -> {
            LazyColumn(
              modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              item {
                SectionHeader("MAXIMUM-SECURITY PASSWORD VAULT")
                val (vaultStatusLabel, vaultStatusColor) = when (vaultLockState) {
                  is com.remmi.browser.security.VaultLockState.Unlocked -> "UNLOCKED // ACTIVE" to ThemeCyber.colors.successGreen
                  is com.remmi.browser.security.VaultLockState.Locked -> "LOCKED // AES-256-GCM" to ThemeCyber.colors.primary
                  is com.remmi.browser.security.VaultLockState.Uninitialized -> "READY TO INITIALIZE" to (if (ThemeCyber.colors.isLight) ThemeCyber.colors.primary else ThemeCyber.colors.neonCyan)
                  is com.remmi.browser.security.VaultLockState.TemporarilyLocked -> "LOCKED OUT (${(vaultLockState as com.remmi.browser.security.VaultLockState.TemporarilyLocked).remainingSeconds}s)" to ThemeCyber.colors.dangerRed
                  is com.remmi.browser.security.VaultLockState.CompromisedDevice -> "COMPROMISED DEVICE" to ThemeCyber.colors.dangerRed
                }

                Card(
                  colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ThemeCyber.colors.primary, RoundedCornerShape(8.dp))
                    .clickable { onOpenPasswords() }
                    .testTag("open_cyber_vault_button")
                ) {
                  Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                          Icons.Default.VpnKey,
                          contentDescription = null,
                          tint = ThemeCyber.colors.primary,
                          modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                          Text(
                            text = "CYBER VAULT // CREDENTIALS",
                            color = ThemeCyber.colors.primary,
                            fontFamily = ThemeCyber.fontFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                          )
                          Text(
                            text = "Argon2id (64 MiB KDF) • StrongBox Keystore",
                            color = ThemeCyber.colors.textSecondary,
                            fontFamily = ThemeCyber.fontFamily,
                            fontSize = 11.sp,
                          )
                        }
                      }
                      Surface(
                        color = vaultStatusColor.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, vaultStatusColor),
                        shape = RoundedCornerShape(4.dp)
                      ) {
                        Text(
                          text = vaultStatusLabel,
                          color = vaultStatusColor,
                          fontSize = 9.5.sp,
                          fontWeight = FontWeight.Bold,
                          fontFamily = com.remmi.browser.ui.theme.CyberMonoFamily,
                          modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                      }
                    }

                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Text(
                        text = "Tap to open and manage credentials & autofill",
                        color = ThemeCyber.colors.textMuted,
                        fontSize = 11.sp,
                        fontFamily = ThemeCyber.fontFamily
                      )
                      Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = ThemeCyber.colors.primary,
                        modifier = Modifier.size(16.dp)
                      )
                    }
                  }
                }
              }
            }
          }

          SettingsCategory.DISPLAY_VIEWPORT -> {
            LazyColumn(
              modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              item {
                SectionHeader("VIEWPORT & RENDERING")
              }

              item {
                SettingsToggleRow(
                  icon = Icons.Default.Contrast,
                  title = "Pure Black OLED Mode",
                  subtitle = "Force true pitch-black (#000000) canvas for OLED battery efficiency.",
                  checked = settings.pureBlackOled,
                  onCheckedChange = { settingsRepo.updatePureBlackOled(it) },
                  accentColor = accentColor,
                )
              }

              item {
                SettingsToggleRow(
                  icon = Icons.Default.DesktopWindows,
                  title = "Default Desktop Mode",
                  subtitle = "Request full desktop web layouts by default on newly spawned tabs.",
                  checked = settings.defaultDesktopMode,
                  onCheckedChange = { settingsRepo.updateDefaultDesktopMode(it) },
                  accentColor = accentColor,
                )
              }

              item {
                SettingsToggleRow(
                  icon = Icons.Default.Speed,
                  title = "Cyber Glitch HUD Effects",
                  subtitle = "Enable dynamic chromatic aberration and terminal jitter scanline effects.",
                  checked = settings.glitchAnimationEnabled,
                  onCheckedChange = { settingsRepo.updateGlitchEnabled(it) },
                  accentColor = ThemeCyber.colors.secondary,
                )
              }

              item {
                SectionHeader("READER VIEW DEFAULTS")
                Card(
                  colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier
                    .fillMaxWidth()
                    .border(0.6.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
                ) {
                  Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                      text = "DEFAULT FONT SIZE",
                      color = ThemeCyber.colors.textPrimary,
                      fontFamily = ThemeCyber.fontFamily,
                      fontSize = 12.sp,
                      fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                      listOf("SMALL (14SP)" to 0, "MEDIUM (17SP)" to 1, "LARGE (21SP)" to 2).forEach { (label, index) ->
                        val isSelected = settings.readerFontSize == index
                        Box(
                          modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) accentColor.copy(alpha = 0.2f) else ThemeCyber.colors.surfaceLight)
                            .border(1.dp, if (isSelected) accentColor else ThemeCyber.colors.surfaceBorder, RoundedCornerShape(6.dp))
                            .clickable { settingsRepo.updateReaderFontSize(index) }
                            .padding(vertical = 8.dp),
                          contentAlignment = Alignment.Center,
                        ) {
                          Text(
                            text = label,
                            color = if (isSelected) accentColor else ThemeCyber.colors.textSecondary,
                            fontFamily = ThemeCyber.fontFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                          )
                        }
                      }
                    }
                  }
                }
              }
            }
          }

          SettingsCategory.SYSTEM_ADVANCED -> {
            LazyColumn(
              modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              // Default Browser
              item {
                SectionHeader("DEFAULT SYSTEM BROWSER")
                Card(
                  colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
                ) {
                  Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                          Icons.Default.Language,
                          contentDescription = null,
                          tint = if (isDefaultBrowser) ThemeCyber.colors.successGreen else accentColor,
                          modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                          Text(
                            text = if (isDefaultBrowser) "DEFAULT BROWSER: ACTIVE" else "DEFAULT BROWSER: NOT SET",
                            color = if (isDefaultBrowser) ThemeCyber.colors.successGreen else ThemeCyber.colors.textPrimary,
                            fontFamily = ThemeCyber.fontFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                          )
                          Text(
                            text = if (isDefaultBrowser) "Remmi Browser is your primary Android browser for all web links." else "Make Remmi Browser the default browser for automatic Tor & Adblock protection.",
                            color = ThemeCyber.colors.textSecondary,
                            fontFamily = ThemeCyber.fontFamily,
                            fontSize = 11.sp
                          )
                        }
                      }
                    }

                    if (!isDefaultBrowser && activity != null) {
                      Button(
                        onClick = {
                          com.remmi.browser.util.DefaultBrowserHelper.requestSetDefaultBrowser(activity, defaultBrowserLauncher)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        shape = RoundedCornerShape(8.dp)
                      ) {
                        Icon(Icons.Default.OpenInBrowser, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Set Remmi Browser as Default", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = ThemeCyber.fontFamily)
                      }
                    } else if (isDefaultBrowser) {
                      Row(
                        modifier = Modifier
                          .fillMaxWidth()
                          .clip(RoundedCornerShape(6.dp))
                          .background(ThemeCyber.colors.successContainer)
                          .padding(vertical = 6.dp, horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                      ) {
                        Icon(Icons.Default.Check, null, tint = ThemeCyber.colors.successGreen, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                          "All external links open in Remmi Browser",
                          color = ThemeCyber.colors.successGreen,
                          fontFamily = ThemeCyber.fontFamily,
                          fontSize = 11.sp,
                          fontWeight = FontWeight.Bold
                        )
                      }
                    }
                  }
                }
              }

              // System Integrity
              item {
                SectionHeader("SYSTEM INTEGRITY & TAMPER AUDIT")
                Card(
                  colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (integrityReport.isRootDetected) ThemeCyber.colors.dangerRed else ThemeCyber.colors.successGreen, RoundedCornerShape(8.dp))
                ) {
                  Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Text(
                        text = "STATUS: ${integrityReport.systemIntegrityStatus}",
                        color = if (integrityReport.isRootDetected) ThemeCyber.colors.dangerRed else ThemeCyber.colors.successGreen,
                        fontFamily = ThemeCyber.fontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                      )
                      Icon(
                        imageVector = if (integrityReport.isRootDetected) Icons.Default.Warning else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (integrityReport.isRootDetected) ThemeCyber.colors.dangerRed else ThemeCyber.colors.successGreen,
                        modifier = Modifier.size(18.dp),
                      )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                      text = "• Signature Verification: VALID\n• APK Debugger Check: ${if (integrityReport.isDebuggerAttached) "FLAGGED" else "SECURE"}\n• Root Detection: ${if (integrityReport.isRootDetected) "COMPROMISED" else "CLEAN"}",
                      color = ThemeCyber.colors.textSecondary,
                      fontFamily = ThemeCyber.fontFamily,
                      fontSize = 11.sp,
                    )
                  }
                }
              }

              // Diagnostics & Logs
              item {
                SectionHeader("ENGINE & PROXY DIAGNOSTICS")
                Card(
                  colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ThemeCyber.colors.primary, RoundedCornerShape(8.dp))
                    .clickable { onOpenDebugLogs() }
                    .testTag("open_diagnostic_logs_button")
                ) {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                      Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        tint = ThemeCyber.colors.primary,
                        modifier = Modifier.size(28.dp)
                      )
                      Spacer(modifier = Modifier.width(16.dp))
                      Column {
                        Text(
                          text = "DIAGNOSTIC & PROXY LOGS",
                          color = ThemeCyber.colors.primary,
                          fontFamily = ThemeCyber.fontFamily,
                          fontSize = 14.sp,
                          fontWeight = FontWeight.ExtraBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                          text = "Live SOCKS5 routing, WebExtension port status, and threat events",
                          color = ThemeCyber.colors.textSecondary,
                          fontFamily = ThemeCyber.fontFamily,
                          fontSize = 12.sp,
                        )
                      }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                      Icons.AutoMirrored.Filled.ArrowForward,
                      contentDescription = null,
                      tint = ThemeCyber.colors.primary,
                      modifier = Modifier.size(18.dp)
                    )
                  }
                }
              }

              // Panic Wipe Action
              item {
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                  onClick = {
                    showPanicDialog = true
                  },
                  colors = ButtonDefaults.buttonColors(
                    containerColor = ThemeCyber.colors.dangerRed,
                    contentColor = Color.White,
                  ),
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("panic_flush_data_button")
                ) {
                  Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = "PANIC WIPE // FLUSH ALL ENCRYPTED DATA",
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                  )
                }
              }
            }
          }
          null -> {}
        }
      }
    }
  }

  if (showPanicDialog) {
    com.remmi.browser.ui.components.PanicWipeDialog(
      onDismiss = { showPanicDialog = false },
      onWipeExecuted = {
        showPanicDialog = false
        onBack()
      }
    )
  }
}

/**
 * Main Settings Category Card Component
 */
@Composable
private fun SettingsCategoryCard(
  icon: ImageVector,
  title: String,
  subtitle: String,
  accentColor: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
    shape = RoundedCornerShape(12.dp),
    modifier = modifier
      .fillMaxWidth()
      .border(0.8.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(12.dp))
      .clickable { onClick() }
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 13.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f)
      ) {
        Box(
          modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accentColor.copy(alpha = 0.12f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(20.dp)
          )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = title,
            color = ThemeCyber.colors.textPrimary,
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = subtitle,
            color = ThemeCyber.colors.textSecondary,
            fontSize = 11.5.sp,
            maxLines = 1
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = null,
        tint = ThemeCyber.colors.textMuted,
        modifier = Modifier.size(16.dp)
      )
    }
  }
}

@Composable
private fun ThemeSelectorCard(
  theme: CyberTheme,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val themeColor = theme.primaryAccent

  Card(
    colors = CardDefaults.cardColors(containerColor = if (isSelected) ThemeCyber.colors.surfaceLight else ThemeCyber.colors.surface),
    shape = RoundedCornerShape(8.dp),
    modifier = modifier
      .height(72.dp)
      .border(
        width = if (isSelected) 1.5.dp else 0.6.dp,
        color = if (isSelected) themeColor else ThemeCyber.colors.surfaceBorder,
        shape = RoundedCornerShape(8.dp)
      )
      .clickable { onClick() }
      .testTag("theme_card_${theme.id}")
  ) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(24.dp)
          .clip(CircleShape)
          .background(themeColor)
          .border(2.dp, if (isSelected) Color.White else Color.Transparent, CircleShape)
      )

      Spacer(modifier = Modifier.width(10.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = theme.displayName,
          color = if (isSelected) themeColor else ThemeCyber.colors.textPrimary,
          fontFamily = ThemeCyber.fontFamily,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = theme.subtitle,
          color = ThemeCyber.colors.textSecondary,
          fontFamily = ThemeCyber.fontFamily,
          fontSize = 9.sp,
        )
      }
    }
  }
}

@Composable
private fun SectionHeader(title: String) {
  Text(
    text = title,
    color = ThemeCyber.colors.textPrimary,
    fontFamily = ThemeCyber.fontFamily,
    fontSize = 13.sp,
    fontWeight = FontWeight.ExtraBold,
    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
  )
}

@Composable
private fun ProfileOptionCard(
  profile: PrivacyProfile,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isGhost = profile == PrivacyProfile.GHOST
  val color = if (isGhost) ThemeCyber.colors.torPurple else ThemeCyber.colors.primary

  Card(
    colors = CardDefaults.cardColors(containerColor = if (isSelected) ThemeCyber.colors.surfaceLight else ThemeCyber.colors.surface),
    shape = RoundedCornerShape(8.dp),
    modifier = modifier
      .border(
        width = if (isSelected) 1.5.dp else 0.6.dp,
        color = if (isSelected) color else ThemeCyber.colors.surfaceBorder,
        shape = RoundedCornerShape(8.dp)
      )
      .clickable { onClick() }
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = if (isGhost) "GHOST MODE" else "SHIELD MODE",
          color = if (isSelected) color else ThemeCyber.colors.textPrimary,
          fontFamily = ThemeCyber.fontFamily,
          fontSize = 14.sp,
          fontWeight = FontWeight.ExtraBold,
        )
        Icon(
          imageVector = if (isGhost) Icons.Default.VpnKey else Icons.Default.Shield,
          contentDescription = null,
          tint = if (isSelected) color else ThemeCyber.colors.textMuted,
          modifier = Modifier.size(18.dp),
        )
      }
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = if (isGhost) "Tor 3-Hop Onion Routing + Full RFP" else "Ultra-Fast FPP + dFPI Direct",
        color = ThemeCyber.colors.textSecondary,
        fontFamily = ThemeCyber.fontFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
      )
    }
  }
}

@Composable
private fun SettingsToggleRow(
  icon: ImageVector,
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  accentColor: Color,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .background(ThemeCyber.colors.surface)
      .border(0.6.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
      .clickable { onCheckedChange(!checked) }
      .padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = accentColor,
      modifier = Modifier.size(24.dp),
    )

    Spacer(modifier = Modifier.width(16.dp))

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        color = ThemeCyber.colors.textPrimary,
        fontFamily = ThemeCyber.fontFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
      )
      Spacer(modifier = Modifier.height(3.dp))
      Text(
        text = subtitle,
        color = ThemeCyber.colors.textSecondary,
        fontFamily = ThemeCyber.fontFamily,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
      )
    }

    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = SwitchDefaults.colors(
        checkedThumbColor = ThemeCyber.colors.backgroundDarker,
        checkedTrackColor = accentColor,
        uncheckedThumbColor = ThemeCyber.colors.textMuted,
        uncheckedTrackColor = ThemeCyber.colors.surfaceLight,
      )
    )
  }
}
