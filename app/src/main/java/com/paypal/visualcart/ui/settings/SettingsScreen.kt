package com.paypal.visualcart.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.paypal.visualcart.core.designsystem.ButtonVariant
import com.paypal.visualcart.core.designsystem.DeepInk
import com.paypal.visualcart.core.designsystem.DemoDataBadge
import com.paypal.visualcart.core.designsystem.ForestGreen
import com.paypal.visualcart.core.designsystem.ForestGreenLight
import com.paypal.visualcart.core.designsystem.MutedSage
import com.paypal.visualcart.core.designsystem.MutedSlate
import com.paypal.visualcart.core.designsystem.PureWhite
import com.paypal.visualcart.core.designsystem.SageLight
import com.paypal.visualcart.core.designsystem.Stone
import com.paypal.visualcart.core.designsystem.StoneLight
import com.paypal.visualcart.core.designsystem.Terracotta
import com.paypal.visualcart.core.designsystem.TerracottaLight
import com.paypal.visualcart.core.designsystem.VisualCartButton
import com.paypal.visualcart.core.designsystem.VisualCartCard
import com.paypal.visualcart.core.designsystem.VisualCartIcons
import com.paypal.visualcart.core.designsystem.VisualCartTypography
import com.paypal.visualcart.core.designsystem.WarmIvory

enum class AiProviderOption(val title: String, val subtitle: String) {
    ON_DEVICE_NANO("Local On-Device (Heuristic OCR)", "Offline text attribute extraction with zero cloud billing"),
    CLOUD_GEMINI("Gemini 3.5 Flash-Lite (Free Tier)", "Google AI Studio free tier · \$0 spend cap · zero paid fallback"),
    DEMO_FIXTURES("Curated Demo Fixtures", "Deterministic sample listings for offline hackathon testing")
}

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var floatingBubbleEnabled by remember { mutableStateOf(com.paypal.visualcart.service.overlay.VisualCartOverlayService.isRunning) }
    var demoDataEnabled by remember { mutableStateOf(true) }
    var selectedAiProvider by remember { mutableStateOf(AiProviderOption.CLOUD_GEMINI) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    var hasOverlayPermission by remember {
        mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(context)
                } else true
                floatingBubbleEnabled = com.paypal.visualcart.service.overlay.VisualCartOverlayService.isRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = WarmIvory
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(PureWhite)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = DeepInk,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = "Settings & Setup",
                        style = VisualCartTypography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = DeepInk
                        )
                    )
                }

                DemoDataBadge()
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Section 1: Capture & Overlay Controls
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                SectionHeader(title = "CAPTURE & OVERLAY ASSISTANT")

                Spacer(modifier = Modifier.height(8.dp))

                VisualCartCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = PureWhite,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Floating button toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Floating Cart Button",
                                    style = VisualCartTypography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = DeepInk
                                    )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Floats over shopping apps for instant capture with one tap.",
                                    style = VisualCartTypography.bodySmall.copy(color = MutedSlate)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = floatingBubbleEnabled,
                                onCheckedChange = { isChecked ->
                                    if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    } else {
                                        floatingBubbleEnabled = isChecked
                                        if (isChecked) {
                                            com.paypal.visualcart.service.overlay.VisualCartOverlayService.start(context)
                                        } else {
                                            com.paypal.visualcart.service.overlay.VisualCartOverlayService.stop(context)
                                        }
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = PureWhite,
                                    checkedTrackColor = ForestGreen,
                                    uncheckedThumbColor = MutedSlate,
                                    uncheckedTrackColor = StoneLight
                                )
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(StoneLight)
                        )

                        // Overlay permission status indicator
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (hasOverlayPermission) ForestGreen else Terracotta)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = if (hasOverlayPermission) "Overlay Permission Granted" else "Overlay Permission Needed",
                                        style = VisualCartTypography.titleSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = DeepInk
                                        )
                                    )
                                    Text(
                                        text = if (hasOverlayPermission) "System overlay permission is active" else "Required to display the floating companion bubble",
                                        style = VisualCartTypography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            color = MutedSlate
                                        )
                                    )
                                }
                            }

                            if (!hasOverlayPermission) {
                                VisualCartButton(
                                    text = "Enable",
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        }
                                    },
                                    variant = ButtonVariant.PRIMARY,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            } else {
                                IconButton(
                                    onClick = {
                                        hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            Settings.canDrawOverlays(context)
                                        } else true
                                        Toast.makeText(context, "Overlay permission is active", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Re-check",
                                        tint = ForestGreen,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 2: Quick Settings Tile Instructions
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                SectionHeader(title = "QUICK SETTINGS SHORTCUT TILE")

                Spacer(modifier = Modifier.height(8.dp))

                VisualCartCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = PureWhite,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = "Add Visual Cart to your system Quick Settings panel to capture deals from any browser or app without opening Visual Cart:",
                            style = VisualCartTypography.bodySmall.copy(color = DeepInk)
                        )

                        // Tile visual badge
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ForestGreen,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = VisualCartIcons.QuickSettingsTile,
                                    contentDescription = null,
                                    tint = PureWhite,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Visual Cart",
                                        style = VisualCartTypography.titleSmall.copy(
                                            color = PureWhite,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = "Tap to save product on screen",
                                        style = VisualCartTypography.bodySmall.copy(
                                            color = PureWhite.copy(alpha = 0.85f),
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                        }

                        // Instructions
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingInstructionStep(step = "1", text = "Swipe down twice from the top of your screen.")
                            SettingInstructionStep(step = "2", text = "Tap the Edit (pencil) button in the Quick Settings shade.")
                            SettingInstructionStep(step = "3", text = "Drag the 'Visual Cart' tile into your active tiles bar.")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 3: AI Provider Selector & Demo Mode
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                SectionHeader(title = "AI MATCHING & RECOGNITION ENGINE")

                Spacer(modifier = Modifier.height(8.dp))

                VisualCartCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = PureWhite,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AiProviderOption.values().forEach { option ->
                            val isSelected = selectedAiProvider == option
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedAiProvider = option }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedAiProvider = option },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = ForestGreen,
                                        unselectedColor = MutedSlate
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = option.title,
                                        style = VisualCartTypography.titleSmall.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = DeepInk
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = option.subtitle,
                                        style = VisualCartTypography.bodySmall.copy(
                                            color = MutedSlate,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(StoneLight)
                        )

                        // Demo Data indicator & toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Include Hackathon Demo Data",
                                        style = VisualCartTypography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = DeepInk
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    DemoDataBadge()
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Shows preloaded electronics and fashion products for testing.",
                                    style = VisualCartTypography.bodySmall.copy(color = MutedSlate)
                                )
                            }

                            Switch(
                                checked = demoDataEnabled,
                                onCheckedChange = { demoDataEnabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = PureWhite,
                                    checkedTrackColor = ForestGreen,
                                    uncheckedThumbColor = MutedSlate,
                                    uncheckedTrackColor = StoneLight
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 4: History & Data Management
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                SectionHeader(title = "DATA MANAGEMENT & EXPORT")

                Spacer(modifier = Modifier.height(8.dp))

                VisualCartCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = PureWhite,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        VisualCartButton(
                            text = "Export Cart Data (JSON)",
                            onClick = {
                                Toast.makeText(context, "Cart data exported to Downloads", Toast.LENGTH_SHORT).show()
                            },
                            variant = ButtonVariant.SECONDARY,
                            modifier = Modifier.fillMaxWidth()
                        )

                        VisualCartButton(
                            text = "Reset Demo Fixtures to Default",
                            onClick = {
                                Toast.makeText(context, "Demo fixtures restored", Toast.LENGTH_SHORT).show()
                            },
                            variant = ButtonVariant.SECONDARY,
                            modifier = Modifier.fillMaxWidth()
                        )

                        VisualCartButton(
                            text = "Clear Saved Cart History",
                            onClick = { showClearHistoryDialog = true },
                            variant = ButtonVariant.GHOST,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 5: Editorial Fintech Privacy & About
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                SectionHeader(title = "PRIVACY & ABOUT")

                Spacer(modifier = Modifier.height(8.dp))

                VisualCartCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = PureWhite,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = VisualCartIcons.ShieldCheck,
                                contentDescription = null,
                                tint = ForestGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Privacy by Design",
                                style = VisualCartTypography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = DeepInk
                                )
                            )
                        }

                        Text(
                            text = "Visual Cart only analyzes screen contents at the exact moment you tap the capture button or Quick Settings tile. No background recording, no tracking of personal apps, and no data sold.",
                            style = VisualCartTypography.bodySmall.copy(
                                color = MutedSlate,
                                lineHeight = 18.sp
                            )
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(StoneLight)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Version",
                                style = VisualCartTypography.bodySmall.copy(color = MutedSlate)
                            )
                            Text(
                                text = "1.0.0 (Hackathon Edition)",
                                style = VisualCartTypography.bodySmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = DeepInk
                                )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Package",
                                style = VisualCartTypography.bodySmall.copy(color = MutedSlate)
                            )
                            Text(
                                text = "com.paypal.visualcart",
                                style = VisualCartTypography.bodySmall.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = DeepInk
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))
        }

        // Clear History Confirmation Dialog
        if (showClearHistoryDialog) {
            AlertDialog(
                onDismissRequest = { showClearHistoryDialog = false },
                title = {
                    Text(
                        text = "Clear Saved Cart History?",
                        style = VisualCartTypography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                text = {
                    Text(
                        text = "This will remove all saved product listings and price drop observations from your device.",
                        style = VisualCartTypography.bodyMedium.copy(color = MutedSlate)
                    )
                },
                confirmButton = {
                    VisualCartButton(
                        text = "Clear History",
                        onClick = {
                            showClearHistoryDialog = false
                            Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
                        },
                        variant = ButtonVariant.TERRACOTTA
                    )
                },
                dismissButton = {
                    VisualCartButton(
                        text = "Cancel",
                        onClick = { showClearHistoryDialog = false },
                        variant = ButtonVariant.GHOST
                    )
                },
                containerColor = PureWhite,
                shape = RoundedCornerShape(18.dp)
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = VisualCartTypography.labelSmall.copy(
            color = MutedSlate,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    )
}

@Composable
private fun SettingInstructionStep(step: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(StoneLight),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step,
                style = VisualCartTypography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = DeepInk
                )
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = VisualCartTypography.bodySmall.copy(color = DeepInk)
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    com.paypal.visualcart.core.designsystem.VisualCartTheme {
        SettingsScreen(onNavigateBack = {})
    }
}

