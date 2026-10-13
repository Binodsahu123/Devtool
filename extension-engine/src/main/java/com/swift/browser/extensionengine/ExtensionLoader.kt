package com.swift.browser.extensionengine

import android.content.Context
import android.net.Uri
import java.io.File

class ExtensionLoader(
    private val context: Context,
    private val manifestParser: ManifestParser,
    private val database: ExtensionDatabase,
    private val registryProvider: (() -> ExtensionRegistry)? = null
) {

    private val extensionDao = database.extensionDao()
    private val zipInstaller by lazy {
        ZipExtensionInstaller(context, manifestParser, registryProvider?.invoke())
    }

    /**
     * Unpacks, extracts, parses, stages and indexes a ZIP or CRX extension archive.
     */
    suspend fun loadAndInstallFromZip(
        uri: Uri,
        expectedExtensionId: String? = null,
        expectedExistingVersion: String? = null
    ): ParsedExtension {
        // Package staging/commit belongs to ZipExtensionInstaller.  Persistence and activation
        // belong exclusively to ExtensionEngineImpl so registry + Room state cannot diverge
        // halfway through installation.
        return zipInstaller.installFromUri(uri, expectedExtensionId, expectedExistingVersion)
    }


    fun finalizeCommittedInstall(extensionId: String, version: String) {
        zipInstaller.finalizeCommittedInstall(extensionId, version)
    }

    fun recoverPendingInstallTransactions(dbVersions: Map<String, String>) {
        zipInstaller.recoverPendingTransactions(dbVersions)
    }

    /**
     * Loads an extension record from the local database.
     */
    suspend fun loadFromDatabase(entity: ExtensionEntity): ParsedExtension {
        val parsed = manifestParser.parse(entity.manifestJson)
        return parsed.copy(
            id = entity.extensionId,
            name = entity.name,
            shortName = entity.shortName,
            version = entity.version,
            description = entity.description,
            iconPath = entity.iconPath,
            installPath = entity.installPath,
            popupPath = entity.popupPath,
            manifestPath = entity.manifestPath,
            backgroundPath = entity.backgroundPath,
            isEnabled = entity.enabledState
        )
    }
}
