package com.swift.browser.extensionengine

import android.content.Context
import java.io.File

/**
 * Single source of truth for extension package paths.
 *
 * An extension's immutable ID is its filesystem identity.  Display names are metadata only;
 * they must never influence where an extension is installed or resolved.  This prevents
 * duplicate folders caused by renamed/localized extensions and eliminates name/ID split-brain.
 */
object ExtensionDirectoryResolver {

    fun getExtensionsRootDir(context: Context): File {
        val extFilesDir = context.getExternalFilesDir(null)
        val dir = if (extFilesDir != null) {
            File(extFilesDir.parentFile, "extensions")
        } else {
            File(context.filesDir, "extensions")
        }
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            throw IllegalStateException("Unable to create extension storage root: ${dir.absolutePath}")
        }
        return dir
    }


    private fun validateCanonicalId(context: Context, id: String): Pair<File, File> {
        val cleanId = id.trim()
        require(cleanId.isNotBlank()) { "Extension ID must not be blank" }
        require(!cleanId.contains('/') && !cleanId.contains('\\') && cleanId != "." && cleanId != "..") {
            "Invalid extension ID/path segment: $id"
        }
        val rootDir = getExtensionsRootDir(context)
        val dir = File(rootDir, cleanId)
        val canonicalRoot = rootDir.canonicalFile
        val canonicalDir = dir.canonicalFile
        require(canonicalDir.path.startsWith(canonicalRoot.path + File.separator)) {
            "Extension path escapes storage root: $id"
        }
        return rootDir to dir
    }

    /** Returns the canonical ID directory without creating it. */
    fun getCanonicalExtensionDir(context: Context, id: String): File {
        return validateCanonicalId(context, id).second
    }

    /**
     * Returns the canonical directory: <extensions-root>/<extension-id>.
     * The optional name parameter is retained for source compatibility and is intentionally
     * ignored. New code must always address an extension by ID.
     */
    fun getExtensionDir(context: Context, id: String, name: String? = null): File {
        val dir = validateCanonicalId(context, id).second
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            throw IllegalStateException("Unable to create extension directory: ${dir.absolutePath}")
        }
        return dir
    }

    /** Delete only the canonical ID directory and verify containment before removing it. */
    fun deleteExtensionDir(context: Context, id: String): Boolean {
        val dir = getExtensionDir(context, id)
        if (!dir.exists()) return true
        val root = getExtensionsRootDir(context).canonicalFile
        val canonicalDir = dir.canonicalFile
        if (canonicalDir.parentFile?.canonicalPath != root.canonicalPath) {
            throw SecurityException("Refusing to delete non-canonical extension directory: ${dir.absolutePath}")
        }
        return dir.deleteRecursively()
    }

    /**
     * Migrates a DB-tracked legacy install directory to the canonical ID directory.
     * Only the path recorded by the database may be migrated; no display-name scan is performed.
     * Returns null when no migration is needed.
     */
    fun migrateLegacyInstallPath(context: Context, extensionId: String, recordedInstallPath: String): File? {
        val root = getExtensionsRootDir(context).canonicalFile
        val canonicalDir = File(root, extensionId).canonicalFile
        val source = File(recordedInstallPath)
        if (!source.exists() || !source.isDirectory) return null
        if (source.canonicalFile.path == canonicalDir.path) return canonicalDir
        if (source.canonicalFile.parentFile?.canonicalPath != root.path) {
            throw SecurityException("Legacy extension path is outside extension root: $recordedInstallPath")
        }
        if (canonicalDir.exists()) {
            // Canonical directory wins only when it already contains a complete package.
            // Do not overwrite it with a stale legacy folder. If both are present, the
            // DB-tracked legacy directory is stale and can be removed after the canonical
            // manifest has been confirmed.
            val canonicalManifest = File(canonicalDir, "manifest.json")
            if (canonicalManifest.isFile) {
                runCatching { source.deleteRecursively() }
            }
            return canonicalDir
        }
        if (!source.renameTo(canonicalDir)) {
            source.copyRecursively(canonicalDir, overwrite = false)
            if (!source.deleteRecursively()) {
                throw IllegalStateException("Failed to remove legacy extension directory: ${source.absolutePath}")
            }
        }
        return canonicalDir
    }

    fun findFileCaseInsensitive(rootDir: File, relativePath: String): File? {
        val cleanPath = try {
            PathSanitizer.sanitizeRelativePath(relativePath)
        } catch (_: Exception) {
            return null
        }
        val directFile = File(rootDir, cleanPath)
        try {
            if (directFile.exists() && directFile.isFile && PathSanitizer.verifyCanonicalContainment(rootDir, directFile)) {
                return directFile
            }
        } catch (_: Exception) {}

        val segments = cleanPath.split('/', '\\').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null

        var currentDir = rootDir
        for (i in segments.indices) {
            val segment = segments[i]
            val children = currentDir.listFiles() ?: return null
            var foundChild: File? = null
            for (child in children) {
                if (child.name == segment) {
                    foundChild = child
                    break
                }
            }
            if (foundChild == null) {
                foundChild = children.firstOrNull { it.name.equals(segment, ignoreCase = true) }
            }
            if (foundChild == null) return null
            if (i == segments.size - 1) {
                if (!foundChild.isFile) return null
                return if (PathSanitizer.verifyCanonicalContainment(rootDir, foundChild)) foundChild else null
            }
            currentDir = foundChild
        }
        return null
    }

    var bootstrapProvider: ((String) -> String)? = null

    private lateinit var globalRegistry: ExtensionRegistry
    private var globalPermissionManager: PermissionManager? = null

    /** Called exactly once by the owning ExtensionEngineImpl. */
    fun configure(
        registry: ExtensionRegistry,
        permissionManager: PermissionManager
    ) {
        if (::globalRegistry.isInitialized && globalRegistry !== registry) {
            throw IllegalStateException("ExtensionDirectoryResolver is already bound to another registry")
        }
        globalRegistry = registry
        globalPermissionManager = permissionManager
    }

    fun handleExtensionRequest(
        context: Context,
        urlStr: String,
        isPrivate: Boolean = false
    ): android.webkit.WebResourceResponse? {
        check(::globalRegistry.isInitialized) { "ExtensionDirectoryResolver is not configured with the canonical ExtensionRegistry" }
        val server = com.swift.browser.extensionengine.resources.ExtensionResourceServer(
            context,
            globalRegistry,
            globalPermissionManager
        )
        return server.handleUrlRequest(urlStr, isPrivate = isPrivate)
    }
}
