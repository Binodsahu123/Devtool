package com.swift.browser.extensionengine.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.swift.browser.extensionengine.ExtensionEngineApi

@Composable
fun GenericPopupView(
    extensionId: String,
    popupUrl: String,
    api: ExtensionEngineApi,
    modifier: Modifier = Modifier
) {
    GenericExtensionPageView(
        extensionId = extensionId,
        pageUrl = popupUrl,
        api = api,
        modifier = modifier
    )
}
