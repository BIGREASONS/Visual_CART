package com.paypal.visualcart.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.paypal.visualcart.core.designsystem.ButtonVariant
import com.paypal.visualcart.core.designsystem.DeepInk
import com.paypal.visualcart.core.designsystem.ForestGreen
import com.paypal.visualcart.core.designsystem.ForestGreenDark
import com.paypal.visualcart.core.designsystem.ForestGreenLight
import com.paypal.visualcart.core.designsystem.MutedSage
import com.paypal.visualcart.core.designsystem.MutedSlate
import com.paypal.visualcart.core.designsystem.PureWhite
import com.paypal.visualcart.core.designsystem.SageLight
import com.paypal.visualcart.core.designsystem.Stone
import com.paypal.visualcart.core.designsystem.StoneLight
import com.paypal.visualcart.core.designsystem.Terracotta
import com.paypal.visualcart.core.designsystem.VisualCartButton
import com.paypal.visualcart.core.designsystem.VisualCartCard
import com.paypal.visualcart.core.designsystem.VisualCartIcons
import com.paypal.visualcart.core.designsystem.VisualCartTypography
import com.paypal.visualcart.core.designsystem.WarmIvory

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentStep by remember { mutableIntStateOf(0) }
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
                .padding(horizontal = 24.dp)
        ) {
            // Top Stepper Navigation Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stepper indicators
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { index ->
                        val isActive = index == currentStep
                        val isDone = index < currentStep
                        Box(
                            modifier = Modifier
                                .height(4.dp)
                                .width(if (isActive) 36.dp else 20.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    when {
                                        isActive -> ForestGreen
                                        isDone -> MutedSage
                                        else -> Stone
                                    }
                                )
                        )
                    }
                }

                // Skip button
                if (currentStep < 2) {
                    VisualCartButton(
                        text = "Skip",
                        onClick = onFinished,
                        variant = ButtonVariant.GHOST,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.width(40.dp))
                }
            }

            // Step Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> -width } + fadeOut()
                            )
                        } else {
                            (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> width } + fadeOut()
                            )
                        }
                    },
                    label = "onboarding_step"
                ) { step ->
                    when (step) {
                        0 -> OnboardingStepConcept()
                        1 -> OnboardingStepQuickSettings()
                        2 -> OnboardingStepOverlayPermission(
                            hasPermission = hasOverlayPermission,
                            onRequestPermission = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                }
                            }
                        )
                    }
                }
            }

            // Bottom Navigation Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentStep > 0) {
                    VisualCartButton(
                        text = "Back",
                        onClick = { currentStep-- },
                        variant = ButtonVariant.SECONDARY,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                }

                VisualCartButton(
                    text = if (currentStep == 2) "Start Using Cart" else "Continue",
                    onClick = {
                        if (currentStep < 2) {
                            currentStep++
                        } else {
                            onFinished()
                        }
                    },
                    variant = ButtonVariant.PRIMARY,
                    modifier = Modifier.weight(if (currentStep > 0) 1.5f else 1f)
                )
            }
        }
    }
}

@Composable
private fun OnboardingStepConcept() {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = ForestGreenLight,
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Text(
                text = "STEP 1 OF 3 · THE UNIVERSAL CART",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = VisualCartTypography.labelSmall.copy(
                    color = ForestGreen,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            )
        }

        Text(
            text = "See it. Save it.\nFind it.",
            style = VisualCartTypography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = DeepInk
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "A single, intelligent visual cart that works across every shopping app without switching contexts or copying URLs.",
            style = VisualCartTypography.bodyLarge,
            color = MutedSlate
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Editorial Visual Card
        VisualCartCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ConceptFeatureRow(
                    stepNumber = "01",
                    title = "Capture in Any App",
                    description = "Spot a deal on Amazon, Instagram, Flipkart, or Chrome? Capture it with one tap."
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(StoneLight)
                )
                ConceptFeatureRow(
                    stepNumber = "02",
                    title = "AI Product Understanding",
                    description = "Our multimodal engine extracts canonical specs, models, and verifies matches across stores."
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(StoneLight)
                )
                ConceptFeatureRow(
                    stepNumber = "03",
                    title = "Pay & Save Securely",
                    description = "Compare verified live prices side-by-side with PayPal buyer protection guaranteed."
                )
            }
        }
    }
}

@Composable
private fun ConceptFeatureRow(
    stepNumber: String,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = stepNumber,
            style = VisualCartTypography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = ForestGreen
            ),
            modifier = Modifier.width(32.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = VisualCartTypography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = DeepInk
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = VisualCartTypography.bodySmall,
                color = MutedSlate,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun OnboardingStepQuickSettings() {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SageLight,
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Text(
                text = "STEP 2 OF 3 · INSTANT ACCESS",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = VisualCartTypography.labelSmall.copy(
                    color = DeepInk,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            )
        }

        Text(
            text = "Android Quick\nSettings Tile",
            style = VisualCartTypography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = DeepInk
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Save what is on your screen from any shopping app without even opening Visual Cart.",
            style = VisualCartTypography.bodyLarge,
            color = MutedSlate
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Visual Quick Settings Tile Mockup
        VisualCartCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = DeepInk
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "QUICK SETTINGS PREVIEW",
                    style = VisualCartTypography.labelSmall.copy(
                        color = MutedSage,
                        letterSpacing = 1.sp
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Simulated QS Tile
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = ForestGreen,
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .border(1.dp, MutedSage.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(PureWhite.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = VisualCartIcons.QuickSettingsTile,
                                contentDescription = null,
                                tint = PureWhite,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Visual Cart",
                                style = VisualCartTypography.titleSmall.copy(
                                    color = PureWhite,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = "Tap to capture product",
                                style = VisualCartTypography.bodySmall.copy(
                                    color = PureWhite.copy(alpha = 0.8f),
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Step by step setup
        VisualCartCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "How to add the Quick Settings Tile:",
                    style = VisualCartTypography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = DeepInk
                )
                InstructionBullet(number = "1", text = "Swipe down twice from the top of your screen to open Quick Settings.")
                InstructionBullet(number = "2", text = "Tap the Edit (pencil) icon at the bottom of the shade.")
                InstructionBullet(number = "3", text = "Scroll to find 'Visual Cart' and drag it to your active tiles.")
            }
        }
    }
}

@Composable
private fun InstructionBullet(number: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(StoneLight),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
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
            style = VisualCartTypography.bodySmall,
            color = DeepInk,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun OnboardingStepOverlayPermission(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (hasPermission) ForestGreenLight else SageLight,
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Text(
                text = if (hasPermission) "PERMISSION GRANTED" else "STEP 3 OF 3 · COMPANION BUBBLE",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = VisualCartTypography.labelSmall.copy(
                    color = if (hasPermission) ForestGreen else DeepInk,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            )
        }

        Text(
            text = "Enable Floating\nCart Bubble",
            style = VisualCartTypography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = DeepInk
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "A discrete floating bubble stays at the edge of your screen while shopping. Tap it anytime to scan deals instantly.",
            style = VisualCartTypography.bodyLarge,
            color = MutedSlate
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Rationale card
        VisualCartCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (hasPermission) ForestGreenLight else SageLight),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (hasPermission) Icons.Default.CheckCircle else Icons.Default.Info,
                            contentDescription = null,
                            tint = if (hasPermission) ForestGreen else DeepInk,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (hasPermission) "Overlay Access Active" else "Why we request overlay access",
                            style = VisualCartTypography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = DeepInk
                        )
                        Text(
                            text = if (hasPermission) "Floating cart companion is ready." else "System permission: Display over other apps",
                            style = VisualCartTypography.bodySmall,
                            color = MutedSlate
                        )
                    }
                }

                Text(
                    text = "Android protects your screen by requiring explicit user permission before any app can display floating buttons. Visual Cart only reads screen contents when you explicitly tap the bubble or Quick Settings tile.",
                    style = VisualCartTypography.bodySmall,
                    color = DeepInk,
                    lineHeight = 18.sp
                )

                if (!hasPermission) {
                    VisualCartButton(
                        text = "Grant Overlay Permission",
                        onClick = onRequestPermission,
                        variant = ButtonVariant.PRIMARY,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ForestGreenLight)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = ForestGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Permission granted. Ready to shop!",
                            style = VisualCartTypography.bodySmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = ForestGreenDark
                            )
                        )
                    }
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
fun OnboardingScreenPreview() {
    com.paypal.visualcart.core.designsystem.VisualCartTheme {
        OnboardingScreen(onFinished = {})
    }
}

