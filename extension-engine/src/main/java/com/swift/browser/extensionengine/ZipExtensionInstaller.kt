package com.swift.browser.extensionengine

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.HashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class ZipExtensionInstaller(
    private val context: Context,
    private val manifestParser: ManifestParser = ManifestParser(),
    private val registry: ExtensionRegistry? = null
) {
    companion object {
        private const val MAX_PACKAGE_BYTES = 512L * 1024L * 1024L
        private const val MAX_ZIP_ENTRIES = 50_000
        private const val MAX_ENTRY_BYTES = 256L * 1024L * 1024L
        private const val MAX_COMPRESSION_RATIO = 100.0
        private const val MIN_RATIO_CHECK_BYTES = 10L * 1024L * 1024L
        private const val TRANSACTION_VERSION = 1
        private const val TRANSACTION_DIR = "extension-install-transactions"

        fun compareVersions(v1: String, v2: String): Int {
            return ExtensionVersionComparator.compareVersions(v1, v2)
        }
    }

    /**
     * Unpacks, validates, stages, and atomically installs an extension archive from a Uri using streaming I/O.
     */
    suspend fun installFromUri(uri: Uri, expectedExtensionId: String? = null, expectedExistingVersion: String? = null): ParsedExtension {
        // A File URI is already a local archive. Route directly to installFromFile so
        // we do not copy the entire CRX/ZIP into another temporary file first.
        if (uri.scheme == "file" || uri.scheme == null) {
            val path = uri.path ?: uri.toString().removePrefix("file://")
            return installFromFile(File(path), expectedExtensionId, expectedExistingVersion)
        }

        val inputStream = try {
            if (uri.scheme == "file" || uri.scheme == null) {
                val path = uri.path ?: uri.toString().removePrefix("file://")
                File(path).inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
                    ?: throw ExtensionError.InstallerError.InvalidArchiveFormat("Cannot open stream for URI: $uri")
            }
        } catch (e: ExtensionError.InstallerError) {
            throw e
        } catch (e: Exception) {
            throw ExtensionError.InstallerError.FileSystemError(uri.toString(), e)
        }

        val sourceName = uri.lastPathSegment ?: uri.path ?: "stream_archive"
        return inputStream.use { installFromStream(it, sourceName, expectedExtensionId, expectedExistingVersion) }
    }

    /**
     * Unpacks, validates, stages, and atomically installs an extension archive from a local File using streaming I/O.
     */
    fun installFromFile(file: File, expectedExtensionId: String? = null, expectedExistingVersion: String? = null): ParsedExtension {
        if (!file.exists()) {
            throw ExtensionError.InstallerError.FileSystemError(file.absolutePath, java.io.FileNotFoundException("File does not exist: ${file.absolutePath}"))
        }
        if (file.length() > MAX_PACKAGE_BYTES) {
            throw ExtensionError.InstallerError.QuotaExceeded(file.length(), MAX_PACKAGE_BYTES)
        }
        return installFromArchiveFile(file, file.name, expectedExtensionId, expectedExistingVersion)
    }

    /**
     * Unpacks, validates, stages, and atomically installs an extension archive from raw bytes.
     */
    fun installFromBytes(archiveBytes: ByteArray, sourceName: String = "local_archive", expectedExistingVersion: String? = null): ParsedExtension {
        if (archiveBytes.isEmpty()) {
            throw ExtensionError.InstallerError.InvalidArchiveFormat("Archive payload is empty (0 bytes)")
        }
        if (archiveBytes.size.toLong() > MAX_PACKAGE_BYTES) {
            throw ExtensionError.InstallerError.QuotaExceeded(archiveBytes.size.toLong(), MAX_PACKAGE_BYTES)
        }
        return ByteArrayInputStream(archiveBytes).use { installFromStream(it, sourceName, null, expectedExistingVersion) }
    }

    data class StreamArchiveHeader(
        val crxVersion: Int,
        val extractedPublicKey: ByteArray?,
        val rawSignature: ByteArray?,
        val isCrx: Boolean
    )

    /**
     * Memory-efficient streaming installer: Unpacks directly to isolated staging directory without allocating
     * large contiguous byte arrays in RAM, preventing OutOfMemoryError for large extensions (e.g., 50MB-150MB).
     */
    fun installFromStream(rawStream: InputStream, sourceName: String = "stream_archive", expectedExtensionId: String? = null, expectedExistingVersion: String? = null): ParsedExtension {
        val buffered = if (rawStream is java.io.BufferedInputStream) rawStream else java.io.BufferedInputStream(rawStream, 64 * 1024)
        buffered.mark(16)
        val header = ByteArray(4)
        val readHeader = buffered.read(header, 0, 4)
        buffered.reset()

        val isCrx = readHeader == 4 && header[0] == 'C'.toByte() && header[1] == 'r'.toByte() && header[2] == '2'.toByte() && header[3] == '4'.toByte()

        if (isCrx) {
            val tempArchiveFile = File.createTempFile("ext_stream_pkg_", ".tmp", context.cacheDir)
            try {
                java.io.BufferedOutputStream(tempArchiveFile.outputStream(), 64 * 1024).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    var read = buffered.read(buf)
                    var totalStreamed = 0L
                    while (read != -1) {
                        totalStreamed += read
                        if (totalStreamed > MAX_PACKAGE_BYTES) {
                            throw ExtensionError.InstallerError.QuotaExceeded(totalStreamed, MAX_PACKAGE_BYTES)
                        }
                        fos.write(buf, 0, read)
                        read = buffered.read(buf)
                    }
                }
                if (tempArchiveFile.length() == 0L) {
                    throw ExtensionError.InstallerError.InvalidArchiveFormat("Archive contains 0 bytes")
                }
                return installFromArchiveFile(tempArchiveFile, sourceName, expectedExtensionId, expectedExistingVersion)
            } finally {
                if (tempArchiveFile.exists()) tempArchiveFile.delete()
            }
        }

        // Fast One-Pass Streaming Extraction for unsigned local ZIP archives
        val stagingId = UUID.randomUUID().toString()
        val stagingDir = File(context.cacheDir, "ext_staging_$stagingId")
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) {
            throw ExtensionError.InstallerError.StagingFailed("Failed to create staging directory at ${stagingDir.absolutePath}")
        }

        try {
            val count = extractZipInputStreamToStaging(buffered, stagingDir)
            if (count == 0) {
                throw ExtensionError.InstallerError.InvalidArchiveFormat("Archive contains no readable files")
            }
            return installFromExtractedStaging(stagingDir, StreamArchiveHeader(0, null, null, false), sourceName)
        } catch (e: Exception) {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            throw e
        }
    }

    fun installFromArchiveFile(archiveFile: File, sourceName: String = "archive", expectedExtensionId: String? = null, expectedExistingVersion: String? = null): ParsedExtension {
        val installStartNs = System.nanoTime()
        android.util.Log.i("ExtensionInstallPerf", "ARCHIVE_INSTALL_BEGIN source=$sourceName bytes=${archiveFile.length()}")
        // Extraction below independently enforces ZIP entry/path/size/containment limits.
        // Avoid a second full archive decompression pass here; keep cryptographic CRX3
        // verification in the verifier, but let secure extraction perform structural checks.
        val verification = ExtensionPackageVerifier.verifyPackage(
            archiveFile, sourceName, expectedExtensionId = expectedExtensionId, validateArchiveContents = false
        )
        android.util.Log.i("ExtensionInstallPerf", "VERIFY_DONE source=$sourceName ms=${(System.nanoTime() - installStartNs) / 1_000_000}")
        if (verification.verificationState == PackageVerificationState.REJECTED) {
            throw ExtensionError.InstallerError.InstallationRejected(
                verification.error?.message ?: "Extension package verification failed"
            )
        }
        if (verification.trustState == ExtensionTrustState.TRUSTED_CRX2) {
            throw ExtensionError.InstallerError.InstallationRejected(
                "Legacy CRX2 packages are not accepted; use a verified CRX3 or local ZIP package"
            )
        }
        if (verification.trustState == ExtensionTrustState.TRUSTED_CRX3 && !verification.isSignatureVerified) {
            throw ExtensionError.InstallerError.InstallationRejected("CRX3 signature verification failed")
        }

        // 1. Create isolated atomic staging directory
        val stagingId = UUID.randomUUID().toString()
        val stagingDir = File(context.cacheDir, "ext_staging_$stagingId")
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) {
            throw ExtensionError.InstallerError.StagingFailed("Failed to create staging directory at ${stagingDir.absolutePath}")
        }

        try {
            // 2. Safe streaming extraction from archive file into staging directory
            var archiveHeader = extractArchiveFileToStaging(archiveFile, stagingDir)
            if (verification.trustState == ExtensionTrustState.TRUSTED_CRX3) {
                archiveHeader = archiveHeader.copy(
                    extractedPublicKey = verification.publicKeyBytes,
                    rawSignature = verification.rawSignatureBytes
                )
            }
            android.util.Log.i("ExtensionInstallPerf", "EXTRACT_DONE source=$sourceName ms=${(System.nanoTime() - installStartNs) / 1_000_000}")
            val result = installFromExtractedStaging(stagingDir, archiveHeader, sourceName, expectedExistingVersion, expectedExtensionId)
            android.util.Log.i("ExtensionInstallPerf", "ARCHIVE_INSTALL_DONE id=${result.id} ms=${(System.nanoTime() - installStartNs) / 1_000_000}")
            return result
        } catch (e: Exception) {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            throw e
        }
    }

    private data class InstallTransaction(
        val extensionId: String,
        val newVersion: String,
        val targetPath: String,
        val backupPath: String?,
        val hadExistingPackage: Boolean,
        val status: String
    )

    private fun transactionDirectory(): File = File(context.filesDir, TRANSACTION_DIR).apply { mkdirs() }

    private fun transactionFile(extensionId: String): File =
        File(transactionDirectory(), "${extensionId.lowercase()}.json")

    private fun writeTransactionJournal(tx: InstallTransaction) {
        val file = transactionFile(tx.extensionId)
        val tmp = File(file.parentFile, file.name + ".tmp")
        val json = JSONObject().apply {
            put("version", TRANSACTION_VERSION)
            put("extensionId", tx.extensionId)
            put("newVersion", tx.newVersion)
            put("targetPath", tx.targetPath)
            put("backupPath", tx.backupPath ?: JSONObject.NULL)
            put("hadExistingPackage", tx.hadExistingPackage)
            put("status", tx.status)
            put("updatedAt", System.currentTimeMillis())
        }
        tmp.writeText(json.toString(), Charsets.UTF_8)
        if (file.exists() && !file.delete()) {
            tmp.delete()
            throw ExtensionError.InstallerError.StagingFailed("Unable to replace install transaction journal for ${tx.extensionId}")
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw ExtensionError.InstallerError.StagingFailed("Unable to commit install transaction journal for ${tx.extensionId}")
        }
    }

    private fun readTransaction(file: File): InstallTransaction? = runCatching {
        val json = JSONObject(file.readText(Charsets.UTF_8))
        if (json.optInt("version", 0) != TRANSACTION_VERSION) return@runCatching null
        InstallTransaction(
            extensionId = json.getString("extensionId"),
            newVersion = json.getString("newVersion"),
            targetPath = json.getString("targetPath"),
            backupPath = if (json.isNull("backupPath")) null else json.getString("backupPath"),
            hadExistingPackage = json.optBoolean("hadExistingPackage", false),
            status = json.optString("status", "PREPARED")
        )
    }.getOrNull()

    private fun deleteTransactionJournal(extensionId: String) {
        runCatching { transactionFile(extensionId).delete() }
    }

    /**
     * Called only after ExtensionEngineImpl has committed the same extension version to Room.
     * The backup/journal is intentionally retained until this point so a process death cannot
     * leave a new package paired with an older database manifest.
     */
    fun finalizeCommittedInstall(extensionId: String, expectedVersion: String) {
        val file = transactionFile(extensionId)
        val tx = readTransaction(file) ?: return
        if (tx.extensionId != extensionId || tx.newVersion != expectedVersion) return
        runCatching { tx.backupPath?.let { File(it).deleteRecursively() } }
        deleteTransactionJournal(extensionId)
    }

    /**
     * Recovers install/update transactions left behind by an abrupt process death.
     * `dbVersions` is the committed Room state observed after the process restarted.
     */
    fun recoverPendingTransactions(dbVersions: Map<String, String>) {
        val dir = transactionDirectory()
        val journals = dir.listFiles { f -> f.isFile && f.extension == "json" } ?: return
        for (journal in journals) {
            val tx = readTransaction(journal) ?: run { journal.delete(); continue }
            try {
                val target = File(tx.targetPath)
                val backup = tx.backupPath?.let(::File)
                val dbVersion = dbVersions[tx.extensionId]
                val targetManifest = File(target, "manifest.json")
                val targetVersion = if (targetManifest.isFile) {
                    runCatching { JSONObject(targetManifest.readText(Charsets.UTF_8)).optString("version") }.getOrNull()
                } else null
                val committed = dbVersion == tx.newVersion && targetVersion == tx.newVersion && target.isDirectory

                if (committed) {
                    backup?.deleteRecursively()
                    journal.delete()
                    android.util.Log.i("ExtensionInstallTxn", "RECOVERY_FINALIZE id=${tx.extensionId} version=${tx.newVersion}")
                } else {
                    // If we never reached BACKED_UP, an existing target is still the last known
                    // good package. Never delete it merely because the journal exists.
                    val backupReady = backup != null && backup.exists()
                    if (backupReady) {
                        if (target.exists()) target.deleteRecursively()
                        target.parentFile?.mkdirs()
                        if (!backup!!.renameTo(target)) {
                            backup!!.copyRecursively(target, overwrite = true)
                            backup!!.deleteRecursively()
                        }
                    } else if (!tx.hadExistingPackage) {
                        if (target.exists()) target.deleteRecursively()
                    } else if (tx.status == "BACKED_UP" || tx.status == "COMMITTED") {
                        // The journal says a backup should exist but it does not. Preserve an
                        // existing target rather than converting an interrupted update into an
                        // uninstall. A later integrity check can quarantine it explicitly.
                        android.util.Log.e("ExtensionInstallTxn", "RECOVERY_BACKUP_MISSING_PRESERVE_TARGET id=${tx.extensionId}")
                    }
                    journal.delete()
                    android.util.Log.w("ExtensionInstallTxn", "RECOVERY_ROLLBACK id=${tx.extensionId} target=${tx.targetPath} dbVersion=$dbVersion txVersion=${tx.newVersion} status=${tx.status}")
                }
            } catch (t: Throwable) {
                android.util.Log.e("ExtensionInstallTxn", "RECOVERY_FAILED id=${tx.extensionId}", t)
            }
        }
        // Never leave temporary copy backups behind after a crash. They are not referenced by journals.
        dir.listFiles { f -> f.isDirectory && f.name.endsWith(".tmp") }?.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun markTransactionStatus(tx: InstallTransaction, status: String) {
        writeTransactionJournal(tx.copy(status = status))
    }

    private fun installFromExtractedStaging(
        stagingDir: File,
        archiveHeader: StreamArchiveHeader,
        sourceName: String,
        expectedExistingVersion: String? = null,
        expectedExtensionId: String? = null
    ): ParsedExtension {
        var parsedExtension: ParsedExtension? = null
        var backupDir: File? = null
        var backupCreated = false
        var activeTransaction: InstallTransaction? = null

        try {
            // 3. Locate manifest.json in staging directory
            val manifestFile = findManifestInStaging(stagingDir)
                ?: throw ExtensionError.InstallerError.InvalidArchiveFormat("Missing manifest.json in package")

            val manifestContent = try {
                manifestFile.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                throw ExtensionError.InstallerError.FileSystemError(manifestFile.path, e)
            }
            android.util.Log.i("ExtensionInstallPerf", "MANIFEST_READ_DONE source=$sourceName")

            // 4. Parse and validate the manifest once. Resource existence is checked once
            // below after identity resolution; avoid a duplicate filesystem walk here.
            val validationReport = ExtensionValidationService.preflightValidateStagingPackage(
                rootDir = manifestFile.parentFile ?: stagingDir,
                manifestContent = manifestContent,
                sourceName = sourceName,
                checkDeclaredResources = false
            )

            if (!validationReport.isValid && validationReport.errors.any { it.contains("traversal", ignoreCase = true) }) {
                throw ExtensionError.InstallerError.InstallationRejected(
                    "Security check failed: ${validationReport.errors.joinToString("; ")}"
                )
            }

            // Write back sanitized manifest JSON if normalized (only for unsigned local developer packages)
            if (!archiveHeader.isCrx && archiveHeader.extractedPublicKey == null &&
                validationReport.sanitizedManifestJson.isNotBlank() &&
                validationReport.sanitizedManifestJson != manifestContent
            ) {
                try {
                    manifestFile.writeText(validationReport.sanitizedManifestJson, Charsets.UTF_8)
                } catch (_: Exception) {}
            }

            val validatedManifest = validationReport.validatedManifest ?: try {
                manifestParser.validateManifest(validationReport.sanitizedManifestJson.ifBlank { manifestContent }, sourceSeed = sourceName)
            } catch (e: ExtensionError.ManifestError) {
                throw e
            } catch (e: Exception) {
                throw ExtensionError.ManifestError.InvalidJson(manifestContent, e)
            }

            // Derivation of identity
            var identity = if (validatedManifest.key != null) {
                try {
                    ExtensionIdGenerator.generateFromPublicKey(validatedManifest.key)
                } catch (e: Exception) {
                    ExtensionIdGenerator.generateOrionLocalIdentity(sourceName, manifestContent)
                }
            } else if (!archiveHeader.isCrx && expectedExtensionId != null) {
                ExtensionIdentity(expectedExtensionId, IdentityType.ORION_LOCAL_IDENTITY)
            } else {
                ExtensionIdGenerator.generateOrionLocalIdentity(sourceName, manifestContent)
            }

            // If CRX package provided a public key, derive Chrome-compatible identity from CRX key
            if (archiveHeader.extractedPublicKey != null) {
                val crxIdentity = ExtensionIdGenerator.generateFromPublicKey(
                    android.util.Base64.encodeToString(archiveHeader.extractedPublicKey, android.util.Base64.NO_WRAP),
                    rawSignature = archiveHeader.rawSignature
                )
                if (validatedManifest.key != null) {
                    val manifestIdentity = ExtensionIdGenerator.generateFromPublicKey(validatedManifest.key)
                    if (!manifestIdentity.publicKeyBytes!!.contentEquals(crxIdentity.publicKeyBytes)) {
                        throw ExtensionError.InstallerError.InstallationRejected(
                            "Manifest public key does not match the verified CRX3 signing key"
                        )
                    }
                }
                identity = crxIdentity
            }

            if (expectedExtensionId != null && !identity.id.equals(expectedExtensionId, ignoreCase = true)) {
                throw ExtensionError.InstallerError.InstallationRejected(
                    "Extension ID mismatch: expected $expectedExtensionId, got ${identity.id}"
                )
            }

            parsedExtension = validatedManifest.toParsedExtension(identity)
            android.util.Log.i("ExtensionInstallPerf", "MANIFEST_VALIDATE_DONE id=${parsedExtension.id}")

            // The engine supplies the committed Room version observed BEFORE filesystem commit.
            // This prevents a stale/empty in-memory registry during early startup from allowing a
            // downgrade to overwrite the currently installed package.
            expectedExistingVersion?.let { existingVersion ->
                if (compareVersions(validatedManifest.version, existingVersion) < 0) {
                    throw ExtensionError.InstallerError.InstallationRejected(
                        "Update rejected: Version ${validatedManifest.version} is older than existing version $existingVersion: INSTALLATION_REJECTED"
                    )
                }
            }

            // Check version against existing extension in registry (reject downgrades)
            registry?.getExtension(parsedExtension.id)?.let { existing ->
                // Key-rotation protection
                if (existing.identity.publicKeyBytes != null && identity.publicKeyBytes != null &&
                    !existing.identity.publicKeyBytes.contentEquals(identity.publicKeyBytes)) {
                    throw ExtensionError.InstallerError.InstallationRejected("Key rotation rejected for extension '${parsedExtension.id}': Signing key mismatch")
                }

                if (compareVersions(validatedManifest.version, existing.version) < 0) {
                    throw ExtensionError.InstallerError.InstallationRejected("Update rejected: Version ${validatedManifest.version} is older than existing version ${existing.version}: INSTALLATION_REJECTED")
                }
            }

            val targetExtensionDir = ExtensionDirectoryResolver.getCanonicalExtensionDir(
                context,
                parsedExtension.id
            )

            // Journal the intended package transition BEFORE touching the live extension directory.
            // The backup path is predetermined so a process death during the backup operation can
            // always be reconciled at next startup.
            val plannedBackupDir = if (targetExtensionDir.exists()) {
                File(context.cacheDir, "ext_backup_${parsedExtension.id}_${System.currentTimeMillis()}_${UUID.randomUUID()}")
            } else null
            val transaction = InstallTransaction(
                extensionId = parsedExtension.id,
                newVersion = parsedExtension.version,
                targetPath = targetExtensionDir.absolutePath,
                backupPath = plannedBackupDir?.absolutePath,
                hadExistingPackage = targetExtensionDir.exists(),
                status = "PREPARED"
            )
            writeTransactionJournal(transaction)
            activeTransaction = transaction
            backupDir = plannedBackupDir

            // Prefer an atomic rename (O(1)). If the filesystem does not permit rename across
            // storage boundaries, copy into a temporary backup and atomically rename the temporary
            // directory into its final journaled backup path before removing the live target.
            if (targetExtensionDir.exists() && backupDir != null) {
                if (backupDir.exists()) backupDir.deleteRecursively()
                val renamed = targetExtensionDir.renameTo(backupDir)
                if (renamed) {
                    backupCreated = true
                    activeTransaction = activeTransaction?.copy(status = "BACKED_UP")
                    activeTransaction?.let { writeTransactionJournal(it) }
                } else {
                    val backupTemp = File(backupDir.parentFile, backupDir.name + ".tmp")
                    if (backupTemp.exists()) backupTemp.deleteRecursively()
                    targetExtensionDir.copyRecursively(backupTemp, overwrite = true)
                    if (!backupTemp.renameTo(backupDir)) {
                        backupTemp.deleteRecursively()
                        throw ExtensionError.InstallerError.StagingFailed(
                            "Unable to create atomic backup for extension ${parsedExtension.id}"
                        )
                    }
                    if (!targetExtensionDir.deleteRecursively()) {
                        backupDir.deleteRecursively()
                        throw ExtensionError.InstallerError.StagingFailed(
                            "Unable to replace existing extension ${parsedExtension.id}"
                        )
                    }
                    backupCreated = true
                    activeTransaction = activeTransaction?.copy(status = "BACKED_UP")
                    activeTransaction?.let { writeTransactionJournal(it) }
                }
            }

            // 5. Validate declared internal resources once in staging.
            verifyDeclaredResourcesExist(stagingDir, manifestFile.parentFile ?: stagingDir, parsedExtension)
            android.util.Log.i("ExtensionInstallPerf", "RESOURCE_VERIFY_DONE id=${parsedExtension.id}")

            // 6. Atomic installation: move staging directory contents to target directory
            commitStagingToTarget(stagingDir, manifestFile.parentFile ?: stagingDir, targetExtensionDir)
            activeTransaction?.let { markTransactionStatus(it, "COMMITTED") }
            android.util.Log.i("ExtensionInstallPerf", "COMMIT_DONE id=${parsedExtension.id}")

            // 7. Prepare final paths
            val finalManifestFile = File(targetExtensionDir, "manifest.json")
            val resolvedName = resolveLocaleString(targetExtensionDir, parsedExtension.name)
            val resolvedShortName = if (parsedExtension.shortName.isNotBlank()) {
                resolveLocaleString(targetExtensionDir, parsedExtension.shortName)
            } else resolvedName

            val resolvedDescription = resolveLocaleString(targetExtensionDir, parsedExtension.description)
            val resolvedIconPath = resolveIconPath(targetExtensionDir, manifestContent)
            val resolvedPopupPath = resolvePopupPath(targetExtensionDir, manifestContent)
            val resolvedBackgroundPath = resolveBackgroundPath(manifestContent)

            val finalParsed = parsedExtension.copy(
                name = resolvedName,
                shortName = resolvedShortName,
                description = resolvedDescription,
                iconPath = resolvedIconPath,
                installPath = targetExtensionDir.absolutePath,
                popupPath = resolvedPopupPath,
                manifestPath = finalManifestFile.absolutePath,
                backgroundPath = resolvedBackgroundPath,
                isEnabled = true
            )

            // Registration is owned solely by ExtensionEngineImpl to prevent duplicate state/generation increments
            // Write install audit log
            writeInstallAuditLog(finalParsed, targetExtensionDir)

            android.util.Log.i("ExtensionInstallPerf", "POSTPROCESS_DONE id=${finalParsed.id}")

            // Keep the backup/journal until ExtensionEngineImpl has persisted the same version
            // to Room. The engine calls finalizeCommittedInstall() after the DB commit.
            return finalParsed

        } catch (e: Exception) {
            // Rollback target directory if backup was created
            if (backupCreated && backupDir?.exists() == true) {
                try {
                    val targetDir = ExtensionDirectoryResolver.getCanonicalExtensionDir(
                        context,
                        parsedExtension?.id ?: ""
                    )
                    if (targetDir.exists()) targetDir.deleteRecursively()
                    if (!backupDir!!.renameTo(targetDir)) {
                        backupDir!!.copyRecursively(targetDir, overwrite = true)
                        backupDir!!.deleteRecursively()
                    }
                } catch (rollbackError: Exception) {
                    // Ignore secondary rollback error
                }
            }
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            parsedExtension?.id?.let { deleteTransactionJournal(it) }
            throw e
        } finally {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
        }
    }

    private fun extractArchiveFileToStaging(
        archiveFile: File,
        stagingDir: File
    ): StreamArchiveHeader {
        if (!archiveFile.exists() || archiveFile.length() == 0L) {
            throw ExtensionError.InstallerError.InvalidArchiveFormat("Archive contains 0 bytes")
        }

        var crxVersion = 0
        var extractedPublicKey: ByteArray? = null
        var rawSignature: ByteArray? = null
        var isCrx = false
        var zipOffset = 0L
        val fileLength = archiveFile.length()

        java.io.RandomAccessFile(archiveFile, "r").use { raf ->
            val headerBuf = ByteArray(16)
            val readHeader = raf.read(headerBuf)

            if (readHeader >= 12 &&
                headerBuf[0] == 0x43.toByte() && headerBuf[1] == 0x72.toByte() &&
                headerBuf[2] == 0x32.toByte() && headerBuf[3] == 0x34.toByte()
            ) {
                // CRX format (Cr24)
                isCrx = true
                val version = (headerBuf[4].toInt() and 0xFF) or
                        ((headerBuf[5].toInt() and 0xFF) shl 8) or
                        ((headerBuf[6].toInt() and 0xFF) shl 16) or
                        ((headerBuf[7].toInt() and 0xFF) shl 24)
                crxVersion = version

                if (version == 2 && readHeader >= 16) {
                    val pubKeyLen = (headerBuf[8].toInt() and 0xFF) or
                            ((headerBuf[9].toInt() and 0xFF) shl 8) or
                            ((headerBuf[10].toInt() and 0xFF) shl 16) or
                            ((headerBuf[11].toInt() and 0xFF) shl 24)

                    val sigLen = (headerBuf[12].toInt() and 0xFF) or
                            ((headerBuf[13].toInt() and 0xFF) shl 8) or
                            ((headerBuf[14].toInt() and 0xFF) shl 16) or
                            ((headerBuf[15].toInt() and 0xFF) shl 24)

                    if (pubKeyLen in 1..65536 && 16L + pubKeyLen <= fileLength) {
                        val pubKey = ByteArray(pubKeyLen)
                        raf.seek(16L)
                        raf.readFully(pubKey)
                        extractedPublicKey = pubKey
                    }
                    if (sigLen in 1..65536 && 16L + pubKeyLen + sigLen <= fileLength) {
                        val sig = ByteArray(sigLen)
                        raf.seek(16L + pubKeyLen)
                        raf.readFully(sig)
                        rawSignature = sig
                    }
                    zipOffset = 16L + pubKeyLen + sigLen
                } else if (version == 3 && readHeader >= 12) {
                    val headerLen = (headerBuf[8].toInt() and 0xFF) or
                            ((headerBuf[9].toInt() and 0xFF) shl 8) or
                            ((headerBuf[10].toInt() and 0xFF) shl 16) or
                            ((headerBuf[11].toInt() and 0xFF) shl 24)

                    zipOffset = 12L + headerLen
                    if (headerLen in 1..(256 * 1024) && 12L + headerLen <= fileLength) {
                        // The cryptographically verified public key is supplied by
                        // ExtensionPackageVerifier; do not guess it by byte-pattern scanning.
                    }
                }
            }

            // Never scan arbitrary bytes for a ZIP signature. The archive boundary must be
            // derived from the package format (0 for raw ZIP, 12+header for CRX3).
            if (zipOffset < 0L || zipOffset + 4L > fileLength) {
                throw ExtensionError.InstallerError.InvalidArchiveFormat("ZIP payload lies outside package boundary")
            }
            raf.seek(zipOffset)
            val magic = ByteArray(4)
            if (raf.read(magic) != 4 || magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte() ||
                !((magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()) ||
                  (magic[2] == 0x05.toByte() && magic[3] == 0x06.toByte()) ||
                  (magic[2] == 0x07.toByte() && magic[3] == 0x08.toByte()))) {
                throw ExtensionError.InstallerError.InvalidArchiveFormat("ZIP payload is not at the expected package boundary")
            }
        }

        // Extract archive entries
        var count = 0
        if (zipOffset == 0L) {
            count = extractWithZipFile(archiveFile, stagingDir)
        } else {
            val fis = java.io.FileInputStream(archiveFile)
            fis.skip(zipOffset)
            count = extractZipInputStreamToStaging(java.io.BufferedInputStream(fis, 64 * 1024), stagingDir)
            if (count == 0) {
                val slicedZip = File.createTempFile("ext_sliced_", ".zip", context.cacheDir)
                try {
                    java.io.FileInputStream(archiveFile).channel.use { srcChannel ->
                        java.io.FileOutputStream(slicedZip).channel.use { dstChannel ->
                            srcChannel.transferTo(zipOffset, fileLength - zipOffset, dstChannel)
                        }
                    }
                    count = extractWithZipFile(slicedZip, stagingDir)
                } finally {
                    if (slicedZip.exists()) slicedZip.delete()
                }
            }
        }

        if (count == 0) {
            throw ExtensionError.InstallerError.InvalidArchiveFormat("Archive contains 0 files")
        }

        return StreamArchiveHeader(
            crxVersion = crxVersion,
            extractedPublicKey = extractedPublicKey,
            rawSignature = rawSignature,
            isCrx = isCrx
        )
    }

    private fun extractWithZipFile(zipFileObj: File, stagingDir: File): Int {
        var count = 0
        var totalBytes = 0L
        var totalCompressedBytes = 0L
        val seenPaths = HashSet<String>()
        java.util.zip.ZipFile(zipFileObj).use { zipFile ->
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val rawName = entry.name ?: throw ExtensionError.InstallerError.InvalidArchiveFormat("ZIP entry has no name")
                if (rawName.isBlank()) throw ExtensionError.InstallerError.InvalidArchiveFormat("ZIP entry name is blank")
                if (rawName.startsWith("__MACOSX/") || rawName == "__MACOSX") continue
                val candidateName = if (entry.isDirectory) rawName.trimEnd('/', '\\') else rawName
                val sanitized = try {
                    PathSanitizer.sanitizeRelativePath(candidateName).replace('\\', '/')
                } catch (_: Exception) {
                    throw ExtensionError.SecurityError.PathTraversalDetected(rawName)
                }
                if (!seenPaths.add(PathSanitizer.canonicalCollisionKey(sanitized))) {
                    throw ExtensionError.InstallerError.InvalidArchiveFormat("Archive contains duplicate/colliding path: $rawName")
                }

                val destFile = File(stagingDir, sanitized)
                try {
                    PathSanitizer.checkCanonicalContainment(stagingDir, destFile)
                } catch (_: Exception) {
                    throw ExtensionError.SecurityError.PathTraversalDetected(rawName)
                }

                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    val entrySize = entry.size
                    val compressedSize = entry.compressedSize
                    if (compressedSize > 0L) {
                        totalCompressedBytes += compressedSize
                    }
                    if (entrySize > MAX_ENTRY_BYTES) {
                        throw ExtensionError.InstallerError.QuotaExceeded(entrySize, MAX_ENTRY_BYTES)
                    }
                    zipFile.getInputStream(entry).use { inStream ->
                        java.io.BufferedOutputStream(destFile.outputStream(), 64 * 1024).use { outStream ->
                            val buffer = ByteArray(64 * 1024)
                            var entryBytes = 0L
                            while (true) {
                                val r = inStream.read(buffer)
                                if (r == -1) break
                                entryBytes += r
                                totalBytes += r
                                if (entryBytes > MAX_ENTRY_BYTES) {
                                    throw ExtensionError.InstallerError.QuotaExceeded(entryBytes, MAX_ENTRY_BYTES)
                                }
                                if (totalBytes > MAX_PACKAGE_BYTES) {
                                    throw ExtensionError.InstallerError.QuotaExceeded(totalBytes, MAX_PACKAGE_BYTES)
                                }
                                outStream.write(buffer, 0, r)
                            }
                        }
                    }
                    count++
                }
            }
        }
        if (totalCompressedBytes > 0L &&
            totalBytes > MIN_RATIO_CHECK_BYTES &&
            totalBytes.toDouble() / totalCompressedBytes.toDouble() > MAX_COMPRESSION_RATIO) {
            throw ExtensionError.InstallerError.InvalidArchiveFormat(
                "Archive exceeds safety compression ratio (${totalBytes.toDouble() / totalCompressedBytes.toDouble()})"
            )
        }
        return count
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var total = 0
        while (total < buffer.size) {
            val r = input.read(buffer, total, buffer.size - total)
            if (r == -1) throw ExtensionError.InstallerError.InvalidArchiveFormat("Unexpected end of archive stream")
            total += r
        }
    }

    private fun tryExtractCrx3PublicKey(headerBytes: ByteArray): ByteArray? {
        try {
            // Search for RSA/Ed25519 public key patterns in CRX3 header payload
            if (headerBytes.size < 32) return null
            // Check for Ed25519 key length (32 bytes) or RSA 2048/4096 DER prefix (0x30, 0x82...)
            for (i in 0 until headerBytes.size - 32) {
                if (headerBytes[i] == 0x30.toByte() && headerBytes[i+1] == 0x82.toByte() && headerBytes[i+4] == 0x30.toByte()) {
                    val len = ((headerBytes[i+2].toInt() and 0xFF) shl 8) or (headerBytes[i+3].toInt() and 0xFF)
                    val totalDerLen = len + 4
                    if (i + totalDerLen <= headerBytes.size) {
                        return headerBytes.copyOfRange(i, i + totalDerLen)
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractZipInputStreamToStaging(inputStream: InputStream, stagingDir: File): Int {
        var count = 0
        var totalBytes = 0L
        val seenPaths = HashSet<String>()

        ZipInputStream(inputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                count++
                if (count > MAX_ZIP_ENTRIES) {
                    throw ExtensionError.InstallerError.QuotaExceeded(count.toLong(), MAX_ZIP_ENTRIES.toLong())
                }

                val rawName = entry.name ?: ""
                if (rawName.isBlank()) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                val candidateName = if (entry.isDirectory) rawName.trimEnd('/', '\\') else rawName
                val sanitized = try {
                    PathSanitizer.sanitizeRelativePath(candidateName)
                } catch (e: Exception) {
                    throw ExtensionError.SecurityError.PathTraversalDetected(rawName)
                }

                val normalized = sanitized.replace('\\', '/')
                if (normalized.isBlank() || normalized.split('/').any { it == ".." } ||
                    normalized.startsWith("/") || normalized.startsWith("../")) {
                    throw ExtensionError.SecurityError.PathTraversalDetected(rawName)
                }

                // Ignore common macOS metadata, but never silently ignore a duplicate real file.
                if (normalized.startsWith("__MACOSX/") || normalized == "__MACOSX") {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                if (!seenPaths.add(PathSanitizer.canonicalCollisionKey(normalized))) {
                    throw ExtensionError.InstallerError.InvalidArchiveFormat("Archive contains duplicate/colliding path: $rawName")
                }

                if (!entry.isDirectory) {
                    val declaredSize = entry.size
                    if (declaredSize > MAX_ENTRY_BYTES) {
                        throw ExtensionError.InstallerError.QuotaExceeded(
                            declaredSize,
                            MAX_ENTRY_BYTES
                        )
                    }

                    val destFile = File(stagingDir, normalized)
                    PathSanitizer.checkCanonicalContainment(stagingDir, destFile)
                    destFile.parentFile?.mkdirs()

                    java.io.BufferedOutputStream(destFile.outputStream(), 32 * 1024).use { out ->
                        val buffer = ByteArray(32 * 1024)
                        var entryBytes = 0L
                        while (true) {
                            val read = zis.read(buffer)
                            if (read == -1) break
                            entryBytes += read
                            totalBytes += read
                            if (entryBytes > MAX_ENTRY_BYTES) {
                                throw ExtensionError.InstallerError.QuotaExceeded(entryBytes, MAX_ENTRY_BYTES)
                            }
                            if (totalBytes > MAX_PACKAGE_BYTES) {
                                throw ExtensionError.InstallerError.QuotaExceeded(totalBytes, MAX_PACKAGE_BYTES)
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return count
    }

    private fun findManifestInStaging(stagingDir: File): File? {
        // Packed Chrome extension roots contain manifest.json at the package root.
        // Avoid an O(n) recursive filesystem scan on every install.
        val rootManifest = File(stagingDir, "manifest.json")
        return rootManifest.takeIf { it.isFile }
    }

    private fun verifyDeclaredResourcesExist(
        stagingDir: File,
        rootDir: File,
        extension: ParsedExtension
    ) {
        fun resolveResourceFile(path: String): File? {
            val clean = try { PathSanitizer.sanitizeRelativePath(path) } catch (_: Exception) { return null }
            val direct = File(rootDir, clean)
            return try {
                PathSanitizer.checkCanonicalContainment(rootDir, direct)
                if (direct.isFile) direct else null
            } catch (_: Exception) { null }
        }

        fun requireResource(path: String, field: String) {
            val file = resolveResourceFile(path)
            if (file == null || !file.isFile) {
                throw ExtensionError.InstallerError.InstallationRejected(
                    "Declared resource '$path' for $field was not found in the extension package"
                )
            }
        }

        extension.backgroundScripts.forEach { requireResource(it, "background") }
        if (extension.optionsPage.isNotBlank()) {
            requireResource(extension.optionsPage, "options_page")
        }
        if (extension.actionPopup.isNotBlank()) {
            requireResource(extension.actionPopup, "action.default_popup")
        }
        if (extension.sidePanelPath.isNotBlank()) {
            requireResource(extension.sidePanelPath, "side_panel.default_path")
        }
        if (extension.devtoolsPagePath.isNotBlank()) {
            requireResource(extension.devtoolsPagePath, "devtools_page")
        }
        extension.contentScripts.forEachIndexed { index, cs ->
            cs.js.forEach { requireResource(it, "content_scripts[$index].js") }
            cs.css.forEach { requireResource(it, "content_scripts[$index].css") }
        }

        val rootManifest = File(rootDir, "manifest.json")
        if (!rootManifest.isFile) {
            val found = findManifestInStaging(rootDir)
            if (found != null && found != rootManifest) {
                try {
                    found.copyTo(rootManifest, overwrite = true)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun isSafeRelativePath(path: String): Boolean {
        val raw = path.trim()
        if (raw.isEmpty() || raw.startsWith("/") || raw.startsWith("\\") || raw.contains("\\") || raw.contains("\u0000")) {
            return false
        }
        val normalized = raw.replace('\\', '/')
        return normalized.split('/').none { it == ".." } && !normalized.startsWith("../")
    }

    private fun readBounded(
        input: InputStream,
        maxBytes: Long,
        source: String
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                throw ExtensionError.InstallerError.QuotaExceeded(total, maxBytes)
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun commitStagingToTarget(stagingDir: File, sourceFolder: File, targetDir: File) {
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        val renamed = try {
            sourceFolder.renameTo(targetDir)
        } catch (_: Exception) {
            false
        }
        if (!renamed) {
            if (!targetDir.mkdirs() && !targetDir.exists()) {
                throw ExtensionError.InstallerError.FileSystemError(targetDir.absolutePath, Exception("Failed creating target directory"))
            }
            sourceFolder.copyRecursively(targetDir, overwrite = true)
        }

        if (!targetDir.exists() || (targetDir.listFiles()?.size ?: 0) == 0) {
            throw ExtensionError.InstallerError.StagingFailed("Commit failed: Target directory is empty after copy")
        }
    }

    private fun resolveLocaleString(extensionDir: File, manifestValue: String): String {
        if (!manifestValue.startsWith("__MSG_") || !manifestValue.endsWith("__")) {
            return manifestValue
        }
        val key = manifestValue.removePrefix("__MSG_").removeSuffix("__")
        val localesDir = File(extensionDir, "_locales")
        if (!localesDir.exists() || !localesDir.isDirectory) return manifestValue

        val currentLocale = java.util.Locale.getDefault()
        val langCode = currentLocale.language
        val country = currentLocale.country
        val fullCode = if (country.isNotBlank()) "${langCode}_$country" else langCode

        val candidateDirs = listOf(fullCode, fullCode.replace("_", "-"), langCode, "en", "en-US", "en_US")
        var messagesFile: File? = null
        for (cand in candidateDirs) {
            val f = File(localesDir, "$cand/messages.json")
            if (f.exists() && f.isFile) {
                messagesFile = f
                break
            }
        }

        if (messagesFile == null) {
            val subfolders = localesDir.listFiles { f -> f.isDirectory }
            if (subfolders != null) {
                for (sub in subfolders) {
                    val f = File(sub, "messages.json")
                    if (f.exists() && f.isFile) {
                        messagesFile = f
                        break
                    }
                }
            }
        }

        if (messagesFile != null) {
            try {
                val json = JSONObject(messagesFile.readText())
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k.equals(key, ignoreCase = true)) {
                        val obj = json.optJSONObject(k)
                        val message = obj?.optString("message", "") ?: ""
                        if (message.isNotBlank()) return message
                    }
                }
            } catch (e: Exception) {
                // Return default
            }
        }
        return manifestValue
    }

    private fun resolveIconPath(extensionDir: File, manifestJsonStr: String): String {
        try {
            val json = JSONObject(manifestJsonStr)
            val iconsObj = json.optJSONObject("icons")
            if (iconsObj != null) {
                val sizes = listOf("128", "96", "64", "48", "32", "16", "256", "512")
                for (size in sizes) {
                    val path = iconsObj.optString(size, "")
                    if (path.isNotBlank()) {
                        val clean = path.removePrefix("./").removePrefix("/")
                        if (File(extensionDir, clean).exists()) return clean
                    }
                }
            }
            val commons = listOf("icon128.png", "icon48.png", "icon16.png", "icon.png", "logo.png")
            for (com in commons) {
                if (File(extensionDir, com).exists()) return com
            }
        } catch (e: Exception) {
            // Ignore
        }
        return ""
    }

    private fun resolvePopupPath(extensionDir: File, manifestJsonStr: String): String {
        try {
            val json = JSONObject(manifestJsonStr)
            val actions = listOf("action", "browser_action", "page_action")
            for (actionKey in actions) {
                val actionObj = json.optJSONObject(actionKey) ?: continue
                val defaultPopup = actionObj.optString("default_popup", "")
                if (defaultPopup.isNotBlank()) {
                    val clean = defaultPopup.removePrefix("./").removePrefix("/")
                    if (File(extensionDir, clean).exists()) return clean
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return ""
    }

    private fun resolveBackgroundPath(manifestJsonStr: String): String {
        try {
            val root = JSONObject(manifestJsonStr)
            val backgroundObj = root.optJSONObject("background")
            if (backgroundObj != null) {
                val scriptsArray = backgroundObj.optJSONArray("scripts")
                if (scriptsArray != null && scriptsArray.length() > 0) {
                    return scriptsArray.getString(0)
                }
                val serviceWorker = backgroundObj.optString("service_worker", "")
                if (serviceWorker.isNotBlank()) return serviceWorker
            }
        } catch (e: Exception) {
            // Ignore
        }
        return ""
    }

    private fun writeInstallAuditLog(parsed: ParsedExtension, targetDir: File) {
        try {
            val auditObj = JSONObject().apply {
                put("extensionId", parsed.id)
                put("name", parsed.name)
                put("version", parsed.version)
                put("manifestVersion", parsed.manifestVersion)
                put("identityType", parsed.identity.identityType.name)
                put("installPath", targetDir.absolutePath)
                put("timestamp", System.currentTimeMillis())
            }
            val auditFile = File(context.cacheDir, "install_audit_${parsed.id}.json")
            auditFile.writeText(auditObj.toString(4))
        } catch (e: Exception) {
            // Non-fatal
        }
    }
}
