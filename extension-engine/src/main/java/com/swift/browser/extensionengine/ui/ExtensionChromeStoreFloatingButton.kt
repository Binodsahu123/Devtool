package com.swift.browser.extensionengine.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swift.browser.extensionengine.ExtensionEngineApi

/**
 * Compact, floating action button for Chrome Web Store extension pages.
 * Positioned above the bottom download FAB for non-intrusive, quick installation.
 */
@Composable
fun ExtensionChromeStoreFloatingButton(
    currentUrl: String,
    api: ExtensionEngineApi,
    modifier: Modifier = Modifier
) {
    val state by api.uiState.collectAsState()
    val cwsExtId = remember(currentUrl) { api.checkChromeWebStorePage(currentUrl) }

    val isInstalled = remember(cwsExtId, state.installedExtensions) {
        if (cwsExtId == null) false
        else state.installedExtensions.any { it.id.equals(cwsExtId, ignoreCase = true) }
    }

    AnimatedVisibility(
        visible = cwsExtId != null,
        enter = scaleIn(initialScale = 0.6f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
        exit = scaleOut(targetScale = 0.6f) + fadeOut(),
        modifier = modifier
    ) {
        if (cwsExtId == null) return@AnimatedVisibility

        val installState = when {
            isInstalled -> ExtensionInstallState.INSTALLED
            state.isInstalling -> ExtensionInstallState.PROCESSING
            else -> ExtensionInstallState.INSTALL
        }

        val containerColor by animateColorAsState(
            targetValue = when (installState) {
                ExtensionInstallState.INSTALL -> MaterialTheme.colorScheme.primary
                ExtensionInstallState.PROCESSING -> MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                ExtensionInstallState.INSTALLED -> Color(0xFF1E8E3E)
            },
            animationSpec = tween(300),
            label = "FabContainerColor"
        )

        val contentColor by animateColorAsState(
            targetValue = when (installState) {
                ExtensionInstallState.INSTALL -> MaterialTheme.colorScheme.onPrimary
                ExtensionInstallState.PROCESSING -> MaterialTheme.colorScheme.onPrimary
                ExtensionInstallState.INSTALLED -> Color.White
            },
            animationSpec = tween(300),
            label = "FabContentColor"
        )

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = containerColor,
            contentColor = contentColor,
            shadowElevation = 8.dp,
            tonalElevation = 4.dp,
            modifier = Modifier
                .height(48.dp)
                .wrapContentWidth()
                .clip(RoundedCornerShape(24.dp))
                .clickable(
                    enabled = installState == ExtensionInstallState.INSTALL,
                    onClick = {
                        api.promptWebStoreInstall(cwsExtId, "Chrome Extension")
                    }
                )
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
        ) {
            AnimatedContent(
                targetState = installState,
                transitionSpec = {
                    if (targetState == ExtensionInstallState.INSTALLED) {
                        (fadeIn(animationSpec = tween(300)) + scaleIn(initialScale = 0.8f))
                            .togetherWith(fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 1.1f))
                    } else if (targetState == ExtensionInstallState.PROCESSING) {
                        (fadeIn(animationSpec = tween(250)) + slideInVertically { it / 2 })
                            .togetherWith(fadeOut(animationSpec = tween(200)) + slideOutVertically { -it / 2 })
                    } else {
                        (fadeIn(animationSpec = tween(250)) + scaleIn(initialScale = 0.9f))
                            .togetherWith(fadeOut(animationSpec = tween(200)))
                    }
                },
                label = "FloatingExtensionButtonState",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) { targetState ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    when (targetState) {
                        ExtensionInstallState.INSTALL -> {
                            Icon(
                                imageVector = Icons.Default.Extension,
                                contentDescription = "Add Extension",
                                modifier = Modifier.size(20.dp),
                                tint = contentColor
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Add to Chrome",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = contentColor
                            )
                        }

                        ExtensionInstallState.PROCESSING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = contentColor,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val progressText = state.downloadProgressPercent?.let { "$it%" } ?: "Adding..."
                            Text(
                                text = progressText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = contentColor
                            )
                        }

                        ExtensionInstallState.INSTALLED -> {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Installed",
                                modifier = Modifier.size(20.dp),
                                tint = contentColor
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Added to Chrome",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = contentColor
                            )
                        }
                    }
                }
            }
        }
    }
}
