package com.swift.browser.extensionengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionPopupBottomSheet(
    show: Boolean,
    extension: ParsedExtension?,
    popupUrl: String?,
    api: ExtensionEngineApi,
    onDismiss: () -> Unit
) {
    if (!show || extension == null || popupUrl == null) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = ExtensionIconMapper.getIconForExtension(extension.id, extension.name),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(extension.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Extension popup", style = MaterialTheme.typography.bodySmall)
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close popup")
                }
            }

            HorizontalDivider()

            val actionHost = remember(extension.id, popupUrl) {
                ExtensionUiHost.ActionHost(
                    extensionId = extension.id,
                    actionUrl = popupUrl
                )
            }

            GenericExtensionPageView(
                extensionId = extension.id,
                pageUrl = popupUrl,
                api = api,
                uiHost = actionHost,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}
