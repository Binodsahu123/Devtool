package com.swift.browser.extensionengine

import android.content.Context
import java.io.File
import java.net.HttpURLConnection

class UpdateDownloader(
    private val context: Context,
    private val maxSizeBytes: Long = 512 * 1024 * 1024L
) {
    fun downloadPackageToFile(codebaseUrl: String, targetFile: File): File {
        var url = ExtensionNetworkSecurity.validateUpdateUrl(codebaseUrl)
        var redirects = 0
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) targetFile.delete()
        try {
            while (true) {
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("Accept", "application/x-chrome-extension, application/zip, application/octet-stream")
                }
                try {
                    val status = connection.responseCode
                    if (status in 300..399) {
                        val location = connection.getHeaderField("Location")
                            ?: throw ExtensionError.InstallerError.FileSystemError(url.toString(), Exception("Redirect missing Location header"))
                        url = ExtensionNetworkSecurity.resolveRedirect(url, location, redirects++)
                        continue
                    }
                    if (status != HttpURLConnection.HTTP_OK) {
                        throw ExtensionError.InstallerError.FileSystemError(url.toString(), Exception("HTTP download failed with status code $status"))
                    }
                    val declaredLength = connection.contentLengthLong
                    if (declaredLength > maxSizeBytes) {
                        throw ExtensionError.InstallerError.QuotaExceeded(declaredLength, maxSizeBytes)
                    }
                    java.io.BufferedOutputStream(targetFile.outputStream(), 64 * 1024).use { output ->
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > maxSizeBytes) {
                                    throw ExtensionError.InstallerError.QuotaExceeded(total, maxSizeBytes)
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (targetFile.length() == 0L) {
                        throw ExtensionError.InstallerError.InvalidArchiveFormat("Downloaded extension package is empty")
                    }
                    return targetFile
                } finally {
                    connection.disconnect()
                }
            }
        } catch (t: Throwable) {
            if (targetFile.exists()) targetFile.delete()
            throw t
        }
    }

    fun downloadPackage(codebaseUrl: String): ByteArray {
        val tempFile = File.createTempFile("ext_upd_dl_", ".tmp", context.cacheDir)
        return try { downloadPackageToFile(codebaseUrl, tempFile).readBytes() }
        finally { tempFile.delete() }
    }
}
