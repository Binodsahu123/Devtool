package com.swift.browser.extensionengine.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Installation state transitions for Chrome / Web Store extensions.
 */
enum class ExtensionInstallState {
    INSTALL,
    PROCESSING,
    INSTALLED
}

/**
 * Production-ready animated install button for Chrome Web Store extensions.
 * Provides smooth transition animations shifting from 'Install' state to a 'Processing...'
 * spinner, and finally to an 'Installed' checkmark once installation confirms.
 */
@Composable
fun ExtensionInstallActionButton(
    state: ExtensionInstallState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    idleText: String = "Add to Chrome",
    processingText: String = "Processing...",
    installedText: String = "Installed",
    progressPercent: Int? = null,
    fontSize: Int = 12
) {
    // Dynamic color animations for smooth state shifting
    val containerColor by animateColorAsState(
        targetValue = when (state) {
            ExtensionInstallState.INSTALL -> MaterialTheme.colorScheme.primary
            ExtensionInstallState.PROCESSING -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
            ExtensionInstallState.INSTALLED -> Color(0xFF1E8E3E) // Chrome Web Store success green
        },
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "ButtonContainerColor"
    )

    val contentColor by animateColorAsState(
        targetValue = when (state) {
            ExtensionInstallState.INSTALL -> MaterialTheme.colorScheme.onPrimary
            ExtensionInstallState.PROCESSING -> MaterialTheme.colorScheme.onPrimary
            ExtensionInstallState.INSTALLED -> Color.White
        },
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "ButtonContentColor"
    )

    // Checkmark pop animation for confirmation
    val checkmarkScale by animateFloatAsState(
        targetValue = if (state == ExtensionInstallState.INSTALLED) 1f else 0.4f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "CheckmarkScale"
    )

    Button(
        onClick = onClick,
        enabled = state == ExtensionInstallState.INSTALL,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = modifier.animateContentSize(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                if (targetState == ExtensionInstallState.INSTALLED) {
                    (fadeIn(animationSpec = tween(300)) + scaleIn(initialScale = 0.8f, animationSpec = tween(300)))
                        .togetherWith(fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 1.1f, animationSpec = tween(200)))
                } else if (targetState == ExtensionInstallState.PROCESSING) {
                    (fadeIn(animationSpec = tween(250)) + slideInVertically { it / 2 })
                        .togetherWith(fadeOut(animationSpec = tween(200)) + slideOutVertically { -it / 2 })
                } else {
                    (fadeIn(animationSpec = tween(250)) + scaleIn(initialScale = 0.9f))
                        .togetherWith(fadeOut(animationSpec = tween(200)))
                }
            },
            label = "InstallButtonStateContent"
        ) { targetState ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.wrapContentSize()
            ) {
                when (targetState) {
                    ExtensionInstallState.INSTALL -> {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = idleText,
                            fontSize = fontSize.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    ExtensionInstallState.PROCESSING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            color = contentColor,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val displayText = if (progressPercent != null && progressPercent in 1..99) {
                            "$progressPercent% Processing..."
                        } else {
                            processingText
                        }
                        Text(
                            text = displayText,
                            fontSize = fontSize.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    ExtensionInstallState.INSTALLED -> {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Installed",
                            modifier = Modifier
                                .size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = installedText,
                            fontSize = fontSize.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
