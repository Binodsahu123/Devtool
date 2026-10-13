package com.swift.browser.extensionengine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Transport-neutral account/device sync boundary. The browser must inject a real provider; this
 * interface deliberately contains no Google/Chrome account implementation.
 */
interface StorageSyncTransport {
    suspend fun upload(extensionId: String, delta: JSONObject): UploadResult
    suspend fun download(extensionId: String, afterRemoteRevision: Long): DownloadResult

    data class UploadResult(
        val acknowledgedThroughRevision: Long,
        val remoteRevision: Long
    )

    data class DownloadResult(
        val remoteRevision: Long,
        val changes: JSONArray
    )
}
