package com.example

import com.example.compiler.ApkCompilerClient
import com.example.update.AppUpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
}
