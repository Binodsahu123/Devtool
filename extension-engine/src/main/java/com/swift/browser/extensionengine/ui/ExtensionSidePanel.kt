package com.swift.browser.extensionengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swift.browser.extensionengine.ExtensionEngineApi
import com.swift.browser.extensionengine.ParsedExtension

/**
 * Chrome-style persistent extension side panel surface hosted side-by-side inside the browser window.
 */
@Composable
fun ExtensionSidePanelSurface(
    extension: ParsedExtension,
    panelUrl: String,
    api: ExtensionEngineApi,
    tabId: String? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(max = 380.dp),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = ExtensionIconMapper.getIconForExtension(extension.id, extension.name),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = extension.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Side Panel",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            val sidePanelHost = remember(extension.id, panelUrl, tabId) {
                ExtensionUiHost.SidePanelHost(
                    extensionId = extension.id,
                    panelUrl = panelUrl,
                    tabId = tabId
                )
            }

            GenericExtensionPageView(
                extensionId = extension.id,
                pageUrl = panelUrl,
                api = api,
                tabId = tabId,
                uiHost = sidePanelHost,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}


