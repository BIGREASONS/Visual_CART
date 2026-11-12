package com.paypal.visualcart.service.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.paypal.visualcart.core.designsystem.DeepInk
import com.paypal.visualcart.core.designsystem.ForestGreen
import com.paypal.visualcart.core.designsystem.ForestGreenLight
import com.paypal.visualcart.core.designsystem.MutedSlate
import com.paypal.visualcart.core.designsystem.PureWhite
import com.paypal.visualcart.core.designsystem.Stone
import com.paypal.visualcart.core.designsystem.VisualCartIcons
import com.paypal.visualcart.core.designsystem.VisualCartShapes
import com.paypal.visualcart.core.designsystem.VisualCartTypography
import com.paypal.visualcart.core.designsystem.WarmIvory
import com.paypal.visualcart.feature.circle.CircleProjectionState
import com.paypal.visualcart.feature.circle.CircleToShopSessionStore
import com.paypal.visualcart.feature.circle.OverlayCapturePreferences

/**
 * Translucent consent dialog activity explaining UniCart's single-use Circle to Shop
 * screen-capture model before launching Android's system MediaProjection prompt.
 */
class CircleToShopConsentActivity : ComponentActivity() {

    private var handedOffToCaptureService = false
    private var isRequestingSystemConsent by mutableStateOf(false)
    private var showDisclosureModal by mutableStateOf(true)

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isRequestingSystemConsent = false

        if (!Settings.canDrawOverlays(this)) {
            CircleToShopSessionStore.clearPendingGrants()
            CircleToShopSessionStore.updateState(CircleProjectionState.IDLE)
            Toast.makeText(
                this,
                "Overlay permission is required to circle products on screen.",
                Toast.LENGTH_LONG
            ).show()
            finishCleanly()
            return@registerForActivityResult
        }

        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            OverlayCapturePreferences.setCircleDisclosureAcknowledged(this, true)
            val grantId = CircleToShopSessionStore.storeSingleUseGrant(result.resultCode, data)
            val startImmediateSelection = intent.getBooleanExtra(EXTRA_START_IMMEDIATE_SELECTION, false)
            try {
                CircleToShopCaptureService.startWithGrant(this, grantId, startImmediateSelection)
                handedOffToCaptureService = true
                finishCleanly()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start CircleToShopCaptureService: ${e.message}", e)
                CircleToShopSessionStore.clearPendingGrants()
                CircleToShopSessionStore.updateState(CircleProjectionState.IDLE)
                Toast.makeText(
                    this,
                    "Unable to start screen capture right now.",
                    Toast.LENGTH_SHORT
                ).show()
                finishCleanly()
            }
        } else {
            CircleToShopSessionStore.clearPendingGrants()
            CircleToShopSessionStore.updateState(CircleProjectionState.REVOKED_OR_DENIED)
            Toast.makeText(
                this,
                "Screen capture permission was not granted.",
                Toast.LENGTH_SHORT
            ).show()
            CircleToShopSessionStore.updateState(CircleProjectionState.IDLE)
            finishCleanly()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val startImmediateSelection = intent.getBooleanExtra(EXTRA_START_IMMEDIATE_SELECTION, false)
        if (CircleToShopSessionStore.isArmed.value) {
            if (startImmediateSelection) {
                CircleToShopCaptureService.triggerSelectionOverlay(this)
            }
            finishCleanly()
            return
        }

        val disclosureAcknowledged = OverlayCapturePreferences.isCircleDisclosureAcknowledged(this)
        showDisclosureModal = OverlayCapturePreferences.shouldShowFirstTimeDisclosure(disclosureAcknowledged)

        if (savedInstanceState == null) {
            CircleToShopSessionStore.updateState(CircleProjectionState.REQUESTING_CONSENT)
        }

        setContent {
            val consentColorScheme = lightColorScheme(
                primary = ForestGreen,
                onPrimary = PureWhite,
                background = WarmIvory,
                onBackground = DeepInk,
                surface = WarmIvory,
                onSurface = DeepInk,
                onSurfaceVariant = MutedSlate,
                outline = Stone
            )

            MaterialTheme(
                colorScheme = consentColorScheme,
                typography = VisualCartTypography,
                shapes = VisualCartShapes
            ) {
                BackHandler(enabled = !isRequestingSystemConsent) {
                    handleCancel()
                }

                if (showDisclosureModal) {
                    CircleToShopConsentModal(
                        isLaunchingSystemPrompt = isRequestingSystemConsent,
                        onAllowClick = {
                            OverlayCapturePreferences.setCircleDisclosureAcknowledged(this, true)
                            launchSystemScreenCaptureConsent()
                        },
                        onCancelClick = {
                            if (!isRequestingSystemConsent) {
                                handleCancel()
                            }
                        }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }
        }

        if (savedInstanceState == null && !showDisclosureModal) {
            launchSystemScreenCaptureConsent()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations && !handedOffToCaptureService && isFinishing) {
            if (CircleToShopSessionStore.state.value == CircleProjectionState.REQUESTING_CONSENT) {
                CircleToShopSessionStore.clearPendingGrants()
                CircleToShopSessionStore.updateState(CircleProjectionState.IDLE)
            }
        }
    }

    private fun launchSystemScreenCaptureConsent() {
        if (isRequestingSystemConsent) return

        if (!Settings.canDrawOverlays(this)) {
            CircleToShopSessionStore.clearPendingGrants()
            CircleToShopSessionStore.updateState(CircleProjectionState.IDLE)
            Toast.makeText(
                this,
                "Overlay permission is required to circle products on screen.",
                Toast.LENGTH_LONG
            ).show()
            finishCleanly()
            return
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (projectionManager == null) {
            CircleToShopSessionStore.clearPendingGrants()
            CircleToShopSessionStore.updateState(CircleProjectionState.IDLE)
            Toast.makeText(
                this,
                "Screen capture is not supported on this device.",
                Toast.LENGTH_SHORT
            ).show()
            finishCleanly()
            return
        }

        isRequestingSystemConsent = true
        try {
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val config = android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                projectionManager.createScreenCaptureIntent(config)
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            mediaProjectionLauncher.launch(captureIntent)
        } catch (e: Exception) {
            isRequestingSystemConsent = false
            Log.e(TAG, "Failed to launch MediaProjection consent intent: ${e.message}", e)
            CircleToShopSessionStore.clearPendingGrants()
            CircleToShopSessionStore.updateState(CircleProjectionState.IDLE)
            Toast.makeText(
                this,
                "Could not open Android screen capture prompt.",
                Toast.LENGTH_SHORT
            ).show()
            finishCleanly()
        }
    }

    private fun handleCancel() {
        CircleToShopSessionStore.clearPendingGrants()
        CircleToShopSessionStore.updateState(CircleProjectionState.IDLE)
        finishCleanly()
    }

    private fun finishCleanly() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val TAG = "CircleConsentActivity"
        const val EXTRA_START_IMMEDIATE_SELECTION = "extra_start_immediate_selection"

        fun createLaunchIntent(context: Context, startImmediateSelection: Boolean = false): Intent {
            return Intent(context, CircleToShopConsentActivity::class.java).apply {
                putExtra(EXTRA_START_IMMEDIATE_SELECTION, startImmediateSelection)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }
    }
}

@Composable
private fun CircleToShopConsentModal(
    isLaunchingSystemPrompt: Boolean,
    onAllowClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.44f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancelClick
            )
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(22.dp),
            color = WarmIvory,
            border = BorderStroke(1.dp, Stone),
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp)
            ) {
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
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ForestGreenLight),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = VisualCartIcons.ScanCamera,
                                contentDescription = null,
                                tint = ForestGreen,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Circle to Shop",
                                color = DeepInk,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 24.sp
                            )
                            Text(
                                text = "Pre-armed screen capture",
                                color = MutedSlate,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    IconButton(
                        onClick = onCancelClick,
                        enabled = !isLaunchingSystemPrompt,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = MutedSlate,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Allow Visual Cart to capture your screen so you can circle products directly from any app without interrupting videos. If Android asks, select 'Entire screen'.",
                    color = DeepInk,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Stone, thickness = 1.dp)
                Spacer(modifier = Modifier.height(14.dp))

                ConsentPrivacyPoint(
                    icon = Icons.Default.Check,
                    title = "Active only right now",
                    description = "Starts a single-use screen capture session just for this lookup."
                )
                Spacer(modifier = Modifier.height(12.dp))
                ConsentPrivacyPoint(
                    icon = VisualCartIcons.ScanCamera,
                    title = "Local crop only",
                    description = "Crops only the circled region on your device and discards the full frame immediately."
                )
                Spacer(modifier = Modifier.height(12.dp))
                ConsentPrivacyPoint(
                    icon = Icons.Default.Lock,
                    title = "Respects protected apps",
                    description = "Honors FLAG_SECURE screens and never reads app trees or uses AccessibilityService."
                )
                Spacer(modifier = Modifier.height(12.dp))
                ConsentPrivacyPoint(
                    icon = VisualCartIcons.ShieldCheck,
                    title = "Stops automatically",
                    description = "Capture ends the moment you circle an item, tap Stop capture, or cancel."
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onAllowClick,
                    enabled = !isLaunchingSystemPrompt,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ForestGreen,
                        contentColor = PureWhite,
                        disabledContainerColor = ForestGreen.copy(alpha = 0.55f),
                        disabledContentColor = PureWhite.copy(alpha = 0.85f)
                    )
                ) {
                    Text(
                        text = if (isLaunchingSystemPrompt) "Opening Android prompt…" else "Allow Screen Capture",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onCancelClick,
                    enabled = !isLaunchingSystemPrompt,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Stone),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 13.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = PureWhite,
                        contentColor = DeepInk,
                        disabledContainerColor = WarmIvory,
                        disabledContentColor = MutedSlate
                    )
                ) {
                    Text(
                        text = "Cancel",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsentPrivacyPoint(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(ForestGreenLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ForestGreen,
                modifier = Modifier.size(14.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DeepInk,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp
            )
            Text(
                text = description,
                color = MutedSlate,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}
