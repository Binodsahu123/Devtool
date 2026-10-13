package com.swift.browser.extensionengine

import java.io.File
import java.text.Normalizer
import java.util.Locale

object PathSanitizer {

    /**
     * Sanitizes and normalizes an entry path from an archive or URL request.
     * Rejects path traversal attempts, absolute paths, null bytes, and unsafe separators.
     * Throws [ExtensionError.SecurityError.PathTraversalDetected] if unsafe.
     */
    fun sanitizeRelativePath(relativePath: String): String {
        if (relativePath.isBlank() || relativePath.length > 4096 || relativePath.contains('\u0000')) {
            throw ExtensionError.SecurityError.PathTraversalDetected(relativePath)
        }
        var decoded = relativePath
        try {
            var previous: String? = null
            var iterations = 0
            while (decoded != previous && decoded.contains('%') && iterations < 4) {
                previous = decoded
                decoded = java.net.URLDecoder.decode(decoded, Charsets.UTF_8.name())
                iterations++
            }
        } catch (_: Exception) {
            throw ExtensionError.SecurityError.PathTraversalDetected(relativePath)
        }
        if (decoded.contains('\u0000')) throw ExtensionError.SecurityError.PathTraversalDetected(relativePath)
        if (decoded.startsWith('/') || decoded.startsWith('\\') ||
            decoded.matches(Regex("^[A-Za-z]:([/\\\\]).*")) || decoded.startsWith("//")) {
            throw ExtensionError.SecurityError.PathTraversalDetected(relativePath)
        }
        val normalized = Normalizer.normalize(decoded.replace('\\', '/'), Normalizer.Form.NFC).trim()
        if (normalized.isBlank() || normalized == ".") throw ExtensionError.SecurityError.PathTraversalDetected(relativePath)
        var clean = normalized
        while (clean.startsWith("./")) {
            clean = clean.substring(2)
        }
        if (clean.isBlank() || clean == ".") throw ExtensionError.SecurityError.PathTraversalDetected(relativePath)
        val segments = clean.split('/')
        val reserved = Regex("(?i)^(con|prn|aux|nul|clock\$|com[1-9]|lpt[1-9])(?:\\..*)?$")
        for (segment in segments) {
            if (segment.isEmpty() || segment == "." || segment == ".." || segment.contains('\u0000') || segment.contains(':')) {
                throw ExtensionError.SecurityError.PathTraversalDetected(relativePath)
            }
            if (segment.matches(reserved)) throw ExtensionError.SecurityError.PathTraversalDetected(relativePath)
        }
        return clean
    }

    /**
     * Verifies that targetFile is strictly contained within rootDirectory using canonical path evaluation.
     * Throws [ExtensionError.SecurityError.PathTraversalDetected] if unsafe.
     */
    fun checkCanonicalContainment(rootDirectory: File, targetFile: File) {
        val rootCanonical = rootDirectory.canonicalPath
        val targetCanonical = targetFile.canonicalPath

        if (!targetCanonical.startsWith(rootCanonical + File.separator) && targetCanonical != rootCanonical) {
            throw ExtensionError.SecurityError.PathTraversalDetected(targetFile.path)
        }
    }

    /**
     * Verifies canonical containment returning a Boolean decision without throwing.
     */
    fun verifyCanonicalContainment(rootDirectory: File, targetPath: String): Boolean {
        return try {
            val rootCanonical = rootDirectory.canonicalPath
            val targetFile = if (File(targetPath).isAbsolute) File(targetPath) else File(rootDirectory, targetPath)
            val targetCanonical = targetFile.canonicalPath
            targetCanonical.startsWith(rootCanonical + File.separator) || targetCanonical == rootCanonical
        } catch (e: Exception) {
            false
        }
    }

    fun verifyCanonicalContainment(rootDirectory: File, targetFile: File): Boolean {
        return try {
            val rootCanonical = rootDirectory.canonicalPath
            val targetCanonical = targetFile.canonicalPath
            targetCanonical.startsWith(rootCanonical + File.separator) || targetCanonical == rootCanonical
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if relativePath is safe without throwing exceptions.
     */
    fun isSafePath(relativePath: String): Boolean {
        return try {
            sanitizeRelativePath(relativePath)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isSafeRelativePath(relativePath: String): Boolean {
        return isSafePath(relativePath)
    }

    /**
     * Validates that an extension ID consists strictly of alphanumeric characters, hyphens, and underscores.
     */
    fun isSafeExtensionId(extensionId: String): Boolean {
        if (extensionId.isBlank() || extensionId.length > 128) return false
        return extensionId.matches(Regex("^[a-zA-Z0-9_\\-]+$"))
    }

    fun canonicalCollisionKey(path: String): String {
        return Normalizer.normalize(path.replace('\\', '/'), Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
    }
}
