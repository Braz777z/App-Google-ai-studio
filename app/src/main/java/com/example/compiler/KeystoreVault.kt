package com.example.compiler

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedKeystoreProfile(
    val id: String,
    val label: String,
    val fileName: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
    val sizeBytes: Long,
    val savedAt: Long
)

object KeystoreVaultCrypto {
    private const val GCM_TAG_BITS = 128
    private const val IV_LENGTH_BYTES = 12
    private const val FORMAT_VERSION = 1

    fun encrypt(plainBytes: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plainBytes)
        val out = ByteArray(1 + iv.size + ciphertext.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(ciphertext, 0, out, 1 + iv.size, ciphertext.size)
        return out
    }

    fun decrypt(blob: ByteArray, key: SecretKey): ByteArray {
        require(blob.size > 1 + IV_LENGTH_BYTES) { "Données chiffrées invalides." }
        val ivLen = blob[0].toInt() and 0xFF
        require(ivLen in 12..16 && blob.size > 1 + ivLen) { "En-tête IV invalide." }
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ciphertext = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    fun encodeProfiles(profiles: List<SavedKeystoreProfile>): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeInt(FORMAT_VERSION)
            out.writeInt(profiles.size)
            profiles.forEach { p ->
                writeString(out, p.id)
                writeString(out, p.label)
                writeString(out, p.fileName)
                writeString(out, p.storePassword)
                writeString(out, p.keyAlias)
                writeString(out, p.keyPassword)
                out.writeLong(p.sizeBytes)
                out.writeLong(p.savedAt)
            }
        }
        return baos.toByteArray()
    }

    fun decodeProfiles(bytes: ByteArray): List<SavedKeystoreProfile> {
        if (bytes.isEmpty()) return emptyList()
        DataInputStream(ByteArrayInputStream(bytes)).use { inp ->
            val version = inp.readInt()
            if (version != FORMAT_VERSION) return emptyList()
            val count = inp.readInt()
            if (count <= 0 || count > 500) return emptyList()
            return buildList(count) {
                repeat(count) {
                    val id = readString(inp)
                    val label = readString(inp)
                    val fileName = readString(inp)
                    val storePassword = readString(inp)
                    val keyAlias = readString(inp)
                    val keyPassword = readString(inp)
                    val sizeBytes = inp.readLong()
                    val savedAt = inp.readLong()
                    if (id.isNotBlank() && fileName.isNotBlank()) {
                        add(
                            SavedKeystoreProfile(
                                id = id,
                                label = label.ifBlank { fileName },
                                fileName = fileName,
                                storePassword = storePassword,
                                keyAlias = keyAlias,
                                keyPassword = keyPassword,
                                sizeBytes = sizeBytes,
                                savedAt = savedAt
                            )
                        )
                    }
                }
            }
        }
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val raw = value.toByteArray(Charsets.UTF_8)
        out.writeInt(raw.size)
        out.write(raw)
    }

    private fun readString(inp: DataInputStream): String {
        val len = inp.readInt()
        require(len in 0..(1024 * 1024)) { "Champ invalide dans l’index." }
        val buf = ByteArray(len)
        inp.readFully(buf)
        return String(buf, Charsets.UTF_8)
    }
}

object KeystoreVault {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "studio_portal_keystore_vault_v1"
    private const val VAULT_DIR = "keystore_vault"
    private const val INDEX_FILE = "vault_index.enc"
    private val uuidRegex = Regex("^[a-fA-F0-9-]{36}$")

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun vaultDir(context: Context): File {
        return File(context.filesDir, VAULT_DIR).apply { mkdirs() }
    }

    private fun resolveProfileBlobFile(baseDir: File, id: String): File {
        require(uuidRegex.matches(id)) { "Identifiant de profil invalide." }
        val target = File(baseDir, "$id.jks.enc")
        val canonicalBase = baseDir.canonicalPath + File.separator
        require(target.canonicalPath.startsWith(canonicalBase)) { "Chemin non autorisé." }
        return target
    }

    suspend fun listProfiles(context: Context): List<SavedKeystoreProfile> = withContext(Dispatchers.IO) {
        val dir = vaultDir(context)
        val indexFile = File(dir, INDEX_FILE)
        if (!indexFile.exists()) return@withContext emptyList()
        runCatching {
            val key = getOrCreateKey()
            val decrypted = KeystoreVaultCrypto.decrypt(indexFile.readBytes(), key)
            KeystoreVaultCrypto.decodeProfiles(decrypted).sortedByDescending { it.savedAt }
        }.getOrDefault(emptyList())
    }

    suspend fun saveProfile(
        context: Context,
        label: String,
        fileName: String,
        keystoreBytes: ByteArray,
        storePassword: String,
        keyAlias: String,
        keyPassword: String
    ): Result<SavedKeystoreProfile> = withContext(Dispatchers.IO) {
        runCatching {
            require(keystoreBytes.isNotEmpty()) { "Le fichier keystore est vide." }
            require(storePassword.isNotEmpty()) { "Le mot de passe du keystore est requis." }
            require(keyAlias.isNotBlank()) { "L’alias de clé est requis." }
            require(keyPassword.isNotEmpty()) { "Le mot de passe de clé est requis." }

            val key = getOrCreateKey()
            val dir = vaultDir(context)
            val id = UUID.randomUUID().toString()
            val blobFile = resolveProfileBlobFile(dir, id)
            blobFile.writeBytes(KeystoreVaultCrypto.encrypt(keystoreBytes, key))

            val cleanLabel = label.trim().ifBlank { fileName.trim() }
            val profile = SavedKeystoreProfile(
                id = id,
                label = cleanLabel,
                fileName = fileName.trim().ifBlank { "keystore.jks" },
                storePassword = storePassword,
                keyAlias = keyAlias.trim(),
                keyPassword = keyPassword,
                sizeBytes = keystoreBytes.size.toLong(),
                savedAt = System.currentTimeMillis()
            )

            val current = listProfilesSync(dir, key).toMutableList()
            val replaced = current.filter { it.fileName == profile.fileName && it.keyAlias == profile.keyAlias }
            replaced.forEach { old ->
                runCatching { resolveProfileBlobFile(dir, old.id).delete() }
            }
            current.removeAll { it.fileName == profile.fileName && it.keyAlias == profile.keyAlias }
            current.add(0, profile)
            File(dir, INDEX_FILE).writeBytes(
                KeystoreVaultCrypto.encrypt(KeystoreVaultCrypto.encodeProfiles(current), key)
            )
            profile
        }
    }

    suspend fun loadKeystoreBytes(context: Context, profileId: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val key = getOrCreateKey()
            val dir = vaultDir(context)
            val blobFile = resolveProfileBlobFile(dir, profileId)
            require(blobFile.exists()) { "Fichier keystore introuvable dans le coffre." }
            KeystoreVaultCrypto.decrypt(blobFile.readBytes(), key)
        }
    }

    suspend fun deleteProfile(context: Context, profileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val key = getOrCreateKey()
            val dir = vaultDir(context)
            val blobFile = resolveProfileBlobFile(dir, profileId)
            if (blobFile.exists()) blobFile.delete()
            val updated = listProfilesSync(dir, key).filterNot { it.id == profileId }
            File(dir, INDEX_FILE).writeBytes(
                KeystoreVaultCrypto.encrypt(KeystoreVaultCrypto.encodeProfiles(updated), key)
            )
        }
    }

    private fun listProfilesSync(dir: File, key: SecretKey): List<SavedKeystoreProfile> {
        val indexFile = File(dir, INDEX_FILE)
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val decrypted = KeystoreVaultCrypto.decrypt(indexFile.readBytes(), key)
            KeystoreVaultCrypto.decodeProfiles(decrypted)
        }.getOrDefault(emptyList())
    }
}
