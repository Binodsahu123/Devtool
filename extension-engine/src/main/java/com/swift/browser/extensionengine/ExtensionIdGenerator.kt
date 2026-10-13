package com.swift.browser.extensionengine

import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec

enum class IdentityType {
    CHROME_COMPATIBLE_IDENTITY,
    ORION_LOCAL_IDENTITY
}

data class ExtensionIdentity(
    val id: String,
    val identityType: IdentityType,
    val publicKeyBytes: ByteArray? = null,
    val rawSignatureBytes: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ExtensionIdentity
        if (id != other.id) return false
        if (identityType != other.identityType) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + identityType.hashCode()
        return result
    }
}

object ExtensionIdGenerator {

    /**
     * Generates a 32-character Chrome-compatible extension ID from a Base64-encoded public key (RSA / Ed25519)
     * as found in manifest `key` field or CRX3 headers.
     *
     * Algorithm:
     * 1. Decode base64 public key bytes.
     * 2. SHA-256 hash of public key DER bytes.
     * 3. Take first 16 bytes (128 bits = 32 nibbles).
     * 4. Map each nibble (0-15) to character 'a'..'p' (Chromium base16 mapping).
     */
    fun generateFromPublicKey(base64PublicKey: String, rawSignature: ByteArray? = null): ExtensionIdentity {
        val cleanKey = base64PublicKey
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()

        val keyBytes = try {
            Base64.decode(cleanKey, Base64.DEFAULT)
        } catch (e: Exception) {
            throw ExtensionError.IdentityError.InvalidKey(base64PublicKey, e)
        }

        if (keyBytes.isEmpty() || keyBytes.size > 65536) {
            throw ExtensionError.IdentityError.InvalidKey("Public key bytes are empty or too large")
        }

        var parsed = false
        for (algorithm in listOf("RSA", "EC")) {
            try {
                KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(keyBytes))
                parsed = true
                break
            } catch (_: Exception) { }
        }
        if (!parsed) throw ExtensionError.IdentityError.InvalidKey("Public key is not a supported X.509 RSA/EC public key")

        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        val id = ExtensionIdCodec.encodeNibblesToAlphabet(digest, 16)
        return ExtensionIdentity(id, IdentityType.CHROME_COMPATIBLE_IDENTITY, keyBytes, rawSignature)
    }

    /**
     * Generates a deterministic Orion local extension identity for unsigned local ZIP archives.
     */
    fun generateOrionLocalIdentity(packageNameOrSeed: String, manifestContent: String): ExtensionIdentity {
        // Local unsigned packages do not have a Chrome signing key. Keep the local
        // identity stable across manifest/version changes by deriving it only from the
        // caller-provided stable package/source seed, not the mutable manifest contents.
        val seed = packageNameOrSeed.trim().ifBlank { "orion-local-extension" }
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        val id = ExtensionIdCodec.encodeNibblesToAlphabet(digest, 16)
        return ExtensionIdentity(id, IdentityType.ORION_LOCAL_IDENTITY)
    }

}
