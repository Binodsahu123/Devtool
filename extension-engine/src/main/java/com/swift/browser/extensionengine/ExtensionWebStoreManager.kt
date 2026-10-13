package com.swift.browser.extensionengine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ExtensionWebStoreManager {

    fun getLocalFallbackExtensions(): List<ExtensionMeta> {
        return listOf(
            ExtensionMeta(
                id = "cjpalhdlnbpafiamejdnhcphjbkeiame",
                name = "uBlock Origin",
                description = "An efficient wide-spectrum content blocker. Easy on CPU and memory.",
                version = "v1.58.0",
                size = "3.1 MB",
                provider = "Raymond Hill (gorhill)",
                lastUpdated = "2026-05-10",
                permissionDescription = "Block network advertisements, modify stylesheet files, secure privacy layers.",
                defaultInstalled = false,
                iconPath = ""
            ),
            ExtensionMeta(
                id = "dhdgffkkbafomglifgghicadnoocndbo",
                name = "Tampermonkey",
                description = "The world's most popular userscript manager. Customize webpage behaviors dynamically.",
                version = "v5.1.1",
                size = "1.8 MB",
                provider = "Jan Biniok",
                lastUpdated = "2026-04-18",
                permissionDescription = "Inject user scripts, capture browser tabs, control active page actions.",
                defaultInstalled = false,
                iconPath = ""
            ),
            ExtensionMeta(
                id = "gighmmpiobklfepjocnamgkkbiglidom",
                name = "AdBlock",
                description = "The native ad blocker to clean websites and secure privacy.",
                version = "v5.19.0",
                size = "4.2 MB",
                provider = "getadblock.com",
                lastUpdated = "2026-05-20",
                permissionDescription = "Block network ads, modify stylesheet styles.",
                defaultInstalled = false,
                iconPath = ""
            ),
            ExtensionMeta(
                id = "eimadpbcbfnmbkopoojfekhnkhdbieeh",
                name = "Dark Reader",
                description = "Dark mode for every website. Take care of your eyes, use dark reader for night and daily browsing.",
                version = "v4.9.82",
                size = "1.2 MB",
                provider = "darkreader.org",
                lastUpdated = "2026-06-02",
                permissionDescription = "Invert website colors, inject custom stylesheet stylesheets.",
                defaultInstalled = false,
                iconPath = ""
            ),
            ExtensionMeta(
                id = "kbfnbcaeplbcioakkpcpgfkobkghlhen",
                name = "Grammarly",
                description = "Improve your writing with Grammarly's AI-powered communication assistant.",
                version = "v14.12.0",
                size = "14.2 MB",
                provider = "Grammarly Inc.",
                lastUpdated = "2026-06-01",
                permissionDescription = "Read and parse text inputs, check layout errors.",
                defaultInstalled = false,
                iconPath = ""
            ),
            ExtensionMeta(
                id = "mpbjkejclgdegidofafiongckaokgajg",
                name = "Buster: Captcha Solver",
                description = "Solve difficult captchas easily by completing voice challenges.",
                version = "v2.8.1",
                size = "420 KB",
                provider = "Armin Sebastian",
                lastUpdated = "2026-05-01",
                permissionDescription = "Read captchas, simulate speech playback, click audio solvers.",
                defaultInstalled = false,
                iconPath = ""
            )
        )
    }

    fun extractExtensionId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        
        // Match direct 32-letter Chrome Web Store ID (letters a to p)
        val idRegex = Regex("^[a-p]{32}$", RegexOption.IGNORE_CASE)
        if (idRegex.matches(trimmed)) {
            return trimmed.lowercase()
        }

        // Match chromewebstore.google.com/detail/.../ID or chrome.google.com/webstore/detail/.../ID
        val urlRegex = Regex("""(?:chrome\.google\.com/webstore/detail/|chromewebstore\.google\.com/detail/)(?:[^/]+/)?([a-p]{32})""", RegexOption.IGNORE_CASE)
        val match = urlRegex.find(trimmed)
        if (match != null) {
            return match.groupValues[1].lowercase()
        }

        return null
    }

    fun searchChromeWebStore(
        scope: CoroutineScope,
        query: String,
        apiKey: String,
        okHttpClient: OkHttpClient,
        onResult: (List<ExtensionMeta>) -> Unit
    ) {
        if (query.isBlank()) {
            onResult(emptyList())
            return
        }

        scope.launch(Dispatchers.IO) {
            val results = mutableListOf<ExtensionMeta>()
            val lower = query.lowercase().trim()

            // 1. Check if user typed a direct CWS URL or 32-character Extension ID
            val directId = extractExtensionId(query)
            if (directId != null) {
                val existing = getLocalFallbackExtensions().find { it.id.equals(directId, ignoreCase = true) }
                if (existing != null) {
                    results.add(existing)
                } else {
                    results.add(
                        ExtensionMeta(
                            id = directId,
                            name = "Chrome Extension ($directId)",
                            description = "Chrome Web Store package for extension ID $directId",
                            version = "Latest",
                            size = "Variable",
                            provider = "Chrome Web Store",
                            lastUpdated = "Latest Release",
                            permissionDescription = "Standard Chromium WebExtensions permissions",
                            defaultInstalled = false,
                            iconPath = "https://clients2.googleusercontent.com/crx/blobs/legacy/apid/${directId}/extension_128_0.png"
                        )
                    )
                }
            }

            // 2. Search verified local fallback catalog
            val catalogMatches = getLocalFallbackExtensions().filter {
                it.name.contains(lower, ignoreCase = true) ||
                it.description.contains(lower, ignoreCase = true) ||
                it.id.contains(lower, ignoreCase = true)
            }
            for (match in catalogMatches) {
                if (results.none { it.id == match.id }) {
                    results.add(match)
                }
            }

            withContext(Dispatchers.Main) {
                onResult(results)
            }
        }
    }

    fun downloadChromeExtension(
        scope: CoroutineScope,
        context: Context,
        extensionId: String,
        okHttpClient: OkHttpClient,
        extensionManager: ExtensionManager,
        onInstalled: (ParsedExtension) -> Unit,
        onStageChanged: ((com.swift.browser.extensionengine.ui.InstallStage, String, Int) -> Unit)? = null,
        onProgress: ((progressPercent: Int, bytesRead: Long, totalBytes: Long) -> Unit)? = null,
        onResult: (Boolean, String) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val cleanExtensionId = extensionId.lowercase(Locale.ROOT).trim()
            val threadName = Thread.currentThread().name
            android.util.Log.i("WebStoreManager", "INSTALL_BEGIN: starting installation for extensionId=$cleanExtensionId thread=$threadName")
            
            if (!Regex("^[a-p]{32}$").matches(cleanExtensionId)) {
                withContext(Dispatchers.Main) { 
                    onStageChanged?.invoke(com.swift.browser.extensionengine.ui.InstallStage.FAILED, "Invalid Chrome extension ID", 0)
                    onResult(false, "Invalid Chrome extension ID") 
                }
                return@launch
            }
            val app = context.applicationContext
            val tempFile = File(app.cacheDir, "temp_webstore_${cleanExtensionId}.crx")
            try {
                if (tempFile.exists()) {
                    tempFile.delete()
                }

                withContext(Dispatchers.Main) {
                    onStageChanged?.invoke(com.swift.browser.extensionengine.ui.InstallStage.CONNECTING, "Connecting to Chrome Web Store...", 2)
                    onProgress?.invoke(0, 0L, 0L)
                }

                val urls = listOf(
                    "https://clients2.google.com/service/update2/crx?response=redirect&acceptformat=crx2,crx3&prodversion=130.0.0.0&x=id%3D${cleanExtensionId}%26installsource%3Dondemand%26uc",
                    "https://clients2.google.com/service/update2/crx?response=redirect&os=win&arch=x86-64&nacl_arch=x86-64&prod=chromecrx&prodchannel=stable&prodversion=130.0.0.0&acceptformat=crx2,crx3&x=id%3D${cleanExtensionId}%26uc",
                    "https://clients2.google.com/service/update2/crx?response=redirect&acceptformat=crx3&prodversion=120.0.0.0&x=id%3D${cleanExtensionId}%26uc",
                    "https://clients2.googleusercontent.com/service/update2/crx?response=redirect&acceptformat=crx2,crx3&prodversion=130.0.0.0&x=id%3D${cleanExtensionId}%26uc"
                )

                val secureClient = okHttpClient.newBuilder()
                    .followRedirects(true)
                    .followSslRedirects(false)
                    .build()
                var downloadSuccess = false
                var errorMsg = ""

                for (url in urls) {
                    try {
                        android.util.Log.i("WebStoreManager", "DOWNLOAD_BEGIN: requesting $url thread=$threadName")
                        val req = Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
                            .build()

                        secureClient.newCall(req).execute().use { response ->
                            if (response.isSuccessful) {
                                val body = response.body
                                if (body != null) {
                                    val contentLength = body.contentLength()
                                    val maxDownloadBytes = 512L * 1024L * 1024L
                                    if (contentLength > maxDownloadBytes) {
                                        throw ExtensionError.InstallerError.QuotaExceeded(contentLength, maxDownloadBytes)
                                    }
                                    val inputStream = body.byteStream()
                                    val output = FileOutputStream(tempFile)

                                    val buffer = ByteArray(16384)
                                    var bytesRead: Long = 0
                                    var lastReported = -1

                                    output.use { fos ->
                                        inputStream.use { fis ->
                                            var count = fis.read(buffer)
                                            while (count != -1) {
                                                fos.write(buffer, 0, count)
                                                bytesRead += count
                                                if (bytesRead > maxDownloadBytes) {
                                                    throw ExtensionError.InstallerError.QuotaExceeded(bytesRead, maxDownloadBytes)
                                                }

                                                if (contentLength > 0) {
                                                    val progress = ((bytesRead * 100) / contentLength).toInt().coerceIn(0, 100)
                                                    if (progress != lastReported) {
                                                        lastReported = progress
                                                        val mappedUiPercent = 5 + ((progress * 60) / 100) // Range 5%..65%
                                                        val readMbText = String.format(Locale.US, "%.1f", bytesRead / (1024f * 1024f))
                                                        val totalMbText = String.format(Locale.US, "%.1f", contentLength / (1024f * 1024f))
                                                        val msg = "Downloading: $progress% ($readMbText / $totalMbText MB)"
                                                        withContext(Dispatchers.Main) {
                                                            onStageChanged?.invoke(com.swift.browser.extensionengine.ui.InstallStage.DOWNLOADING, msg, mappedUiPercent)
                                                            onProgress?.invoke(progress, bytesRead, contentLength)
                                                        }
                                                    }
                                                } else {
                                                    val readMbText = String.format(Locale.US, "%.1f", bytesRead / (1024f * 1024f))
                                                    val msg = "Downloading: $readMbText MB"
                                                    withContext(Dispatchers.Main) {
                                                        onStageChanged?.invoke(com.swift.browser.extensionengine.ui.InstallStage.DOWNLOADING, msg, 35)
                                                        onProgress?.invoke(50, bytesRead, -1L)
                                                    }
                                                }
                                                count = fis.read(buffer)
                                            }
                                        }
                                    }

                                    if (bytesRead > 0) {
                                        downloadSuccess = true
                                        android.util.Log.i("WebStoreManager", "DOWNLOAD_100: downloaded $bytesRead bytes for extension $cleanExtensionId")
                                    } else {
                                        tempFile.delete()
                                        errorMsg = "Downloaded package was empty (0 bytes)"
                                    }
                                } else {
                                    errorMsg = "Empty response body from Web Store server"
                                }
                            } else {
                                errorMsg = "HTTP ${response.code}: ${response.message}"
                            }
                        }
                        if (downloadSuccess) break
                    } catch (e: Exception) {
                        errorMsg = e.localizedMessage ?: e.javaClass.simpleName
                        android.util.Log.w("WebStoreManager", "Download attempt failed for $url", e)
                    }
                }

                if (!downloadSuccess || !tempFile.exists() || tempFile.length() == 0L) {
                    if (tempFile.exists()) tempFile.delete()
                    android.util.Log.e("WebStoreManager", "INSTALL_FAILED: download failed for extension $cleanExtensionId: $errorMsg")
                    withContext(Dispatchers.Main) {
                        onStageChanged?.invoke(com.swift.browser.extensionengine.ui.InstallStage.FAILED, "Download failed: $errorMsg", 0)
                        onResult(false, "Download failed: $errorMsg")
                    }
                    return@launch
                }

                val totalDownloadedBytes = tempFile.length()
                val readMbText = String.format(Locale.US, "%.1f", totalDownloadedBytes / (1024f * 1024f))
                withContext(Dispatchers.Main) {
                    onStageChanged?.invoke(com.swift.browser.extensionengine.ui.InstallStage.DOWNLOAD_COMPLETE, "Download complete ($readMbText MB)", 65)
                }

                // Verification + extraction are performed by the single installer pipeline.
                // This avoids reading/decompressing the same CRX twice while preserving the
                // requested extension-ID check inside the real installer verifier.
                withContext(Dispatchers.Main) {
                    onStageChanged?.invoke(
                        com.swift.browser.extensionengine.ui.InstallStage.VERIFYING,
                        "Verifying and installing package...",
                        72
                    )
                }

                // INSTALL STAGE
                withContext(Dispatchers.Main) {
                    onStageChanged?.invoke(com.swift.browser.extensionengine.ui.InstallStage.INSTALLING, "Persisting extension files...", 88)
                }

                val parsed = try {
                    extensionManager.installExtension(Uri.fromFile(tempFile), expectedExtensionId = cleanExtensionId)
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }

                android.util.Log.i("WebStoreManager", "EXTRACTION_COMPLETE: extracted files for ${parsed.id}")
                android.util.Log.i("WebStoreManager", "MANIFEST_PARSED: parsed manifest for ${parsed.id} (mv=${parsed.manifestVersion})")
                android.util.Log.i("WebStoreManager", "REGISTRY_COMMIT: registered extension in database & memory for ${parsed.id}")

                if (parsed.isServiceWorker) {
                    android.util.Log.i("WebStoreManager", "WORKER_REGISTERED: registered service worker for ${parsed.id}")
                    withContext(Dispatchers.Main) {
                        onStageChanged?.invoke(com.swift.browser.extensionengine.ui.InstallStage.REGISTERING_WORKER, "Registering service worker...", 95)
                    }
                    android.util.Log.i("WebStoreManager", "WORKER_DORMANT: MV3 worker remains dormant until an eligible event for ${parsed.id}")
                }

                withContext(Dispatchers.Main) {
                    onStageChanged?.invoke(com.swift.browser.extensionengine.ui.InstallStage.INSTALLED, "Installed successfully!", 100)
                    onInstalled(parsed)
                    onResult(true, "Installed '${parsed.name}' from Chrome Web Store!")
                }
            } catch (t: Throwable) {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                val errMsg = t.localizedMessage ?: t.message ?: t.javaClass.simpleName
                android.util.Log.e("WebStoreManager", "INSTALL_FAILED: installation error for extension $extensionId: $errMsg", t)
                withContext(Dispatchers.Main) {
                    onStageChanged?.invoke(com.swift.browser.extensionengine.ui.InstallStage.FAILED, "Installation error: $errMsg", 0)
                    onResult(false, "Installation error: $errMsg")
                }
            }
        }
    }

    fun exportExtension(
        scope: CoroutineScope,
        context: Context,
        extensionId: String,
        extensionManager: ExtensionManager,
        onResult: (Boolean, String) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val dbExt = extensionManager.engine.database.extensionDao().getExtensionById(extensionId)
                val extName = dbExt?.name?.replace("[^a-zA-Z0-9]".toRegex(), "_") ?: "extension"
                val extensionDir = ExtensionDirectoryResolver.getExtensionDir(context, extensionId, dbExt?.name)
                if (!extensionDir.exists() || !extensionDir.isDirectory) {
                    throw Exception("Extension source files do not exist.")
                }

                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir != null && !downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }

                val zipFileName = "${extName}_${extensionId}_export.zip"
                val destinationZipFile = File(downloadsDir, zipFileName)

                FileOutputStream(destinationZipFile).use { fos ->
                    ZipOutputStream(fos).use { zos ->
                        zipDirectory(extensionDir, extensionDir, zos)
                    }
                }

                withContext(Dispatchers.Main) {
                    onResult(true, "Exported successfully to Downloads/$zipFileName")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onResult(false, "Export failed: ${e.localizedMessage}")
                }
            }
        }
    }

    fun shareExtension(
        scope: CoroutineScope,
        context: Context,
        extensionId: String,
        extensionManager: ExtensionManager
    ) {
        scope.launch(Dispatchers.IO) {
            val app = context.applicationContext
            try {
                val dbExt = extensionManager.engine.database.extensionDao().getExtensionById(extensionId)
                val extName = dbExt?.name?.replace("[^a-zA-Z0-9]".toRegex(), "_") ?: "extension"
                val extensionDir = ExtensionDirectoryResolver.getExtensionDir(app, extensionId, dbExt?.name)

                if (!extensionDir.exists() || !extensionDir.isDirectory) {
                    throw Exception("Extension files not found.")
                }

                val shareFile = File(app.getExternalFilesDir(null), "share_${extName}_$extensionId.zip")
                if (shareFile.exists()) shareFile.delete()

                FileOutputStream(shareFile).use { fos ->
                    ZipOutputStream(fos).use { zos ->
                        zipDirectory(extensionDir, extensionDir, zos)
                    }
                }

                val uri = FileProvider.getUriForFile(
                    app,
                    "${app.packageName}.provider",
                    shareFile
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Share Extension: ${dbExt?.name ?: extensionId}")
                    putExtra(Intent.EXTRA_TEXT, "Here is the Chrome Extension: ${dbExt?.name ?: extensionId} (from Swift Browser)")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooserIntent = Intent.createChooser(intent, "Share Extension via").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                app.startActivity(chooserIntent)
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "Share failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun zipDirectory(rootFolder: File, sourceFolder: File, zos: ZipOutputStream) {
        val files = sourceFolder.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                zipDirectory(rootFolder, file, zos)
            } else {
                val relativePath = file.absolutePath.substring(rootFolder.absolutePath.length + 1)
                val entry = ZipEntry(relativePath)
                zos.putNextEntry(entry)
                file.inputStream().use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }
}
