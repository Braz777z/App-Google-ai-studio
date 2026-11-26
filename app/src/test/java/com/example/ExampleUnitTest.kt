package com.example

import com.example.compiler.ApkCompilerClient
import com.example.compiler.KeystoreVaultCrypto
import com.example.compiler.SavedKeystoreProfile
import com.example.update.AppUpdateChecker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator

class ExampleUnitTest {

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun normalizeRepoInput_handlesSlugAndUrl() {
        assertEquals(
            "owner/studio-portal",
            AppUpdateChecker.normalizeRepoInput("https://github.com/owner/studio-portal/releases")
        )
        assertEquals(
            "owner/studio-portal",
            AppUpdateChecker.normalizeRepoInput("owner/studio-portal")
        )
        assertNull(AppUpdateChecker.normalizeRepoInput("invalid repo"))
    }

    @Test
    fun isNewerVersion_comparesSemverProperly() {
        assertTrue(AppUpdateChecker.isNewerVersion("v1.1.0", "1.0"))
        assertTrue(AppUpdateChecker.isNewerVersion("2.0", "1.9.5"))
        assertFalse(AppUpdateChecker.isNewerVersion("v1.0", "1.0"))
        assertFalse(AppUpdateChecker.isNewerVersion("v0.9.9", "1.0"))
    }

    @Test
    fun normalizeServerUrl_requiresHttpsWithHost() {
        assertEquals(
            "https://build.example.com/compile",
            ApkCompilerClient.normalizeServerUrl("  https://build.example.com/compile ")
        )
        assertNull(ApkCompilerClient.normalizeServerUrl("http://build.example.com/compile"))
        assertNull(ApkCompilerClient.normalizeServerUrl("https://"))
    }

    @Test
    fun inspectProjectZip_detectsAndroidProjectStructure() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("app/build.gradle.kts"))
            zos.write("""android { defaultConfig { applicationId = "com.test.demo" } }""".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("app/src/main/AndroidManifest.xml"))
            zos.write("<manifest/>".toByteArray())
            zos.closeEntry()
        }

        val result = ApkCompilerClient.inspectProjectZip(baos.toByteArray(), "demo-app.zip")
        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertTrue(info.isValidAndroidProject)
        assertEquals("com.test.demo", info.detectedAppId)
        assertEquals(2, info.entryCount)
    }

    @Test
    fun keystoreVaultCrypto_encryptDecryptAndRoundTripProfiles() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val key = keyGen.generateKey()

        val sampleJks = byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x23, 0x45)
        val encryptedJks = KeystoreVaultCrypto.encrypt(sampleJks, key)
        val decryptedJks = KeystoreVaultCrypto.decrypt(encryptedJks, key)
        assertArrayEquals(sampleJks, decryptedJks)

        val profiles = listOf(
            SavedKeystoreProfile(
                id = "11111111-1111-4111-8111-111111111111",
                label = "Release Key",
                fileName = "my-upload-key.jks",
                storePassword = "store-secret",
                keyAlias = "upload",
                keyPassword = "key-secret",
                sizeBytes = 5L,
                savedAt = 1_700_000_000_000L
            )
        )
        val encryptedIndex = KeystoreVaultCrypto.encrypt(KeystoreVaultCrypto.encodeProfiles(profiles), key)
        val decoded = KeystoreVaultCrypto.decodeProfiles(KeystoreVaultCrypto.decrypt(encryptedIndex, key))
        assertEquals(1, decoded.size)
        assertEquals("upload", decoded.first().keyAlias)
        assertEquals("store-secret", decoded.first().storePassword)
        assertEquals("key-secret", decoded.first().keyPassword)
    }
}
