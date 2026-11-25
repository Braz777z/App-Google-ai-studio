package com.example.compiler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import java.util.zip.ZipInputStream

data class InspectedProjectZip(
    val fileName: String,
    val sizeBytes: Long,
    val entryCount: Int,
    val hasGradleConfig: Boolean,
    val hasAndroidManifest: Boolean,
    val detectedAppId: String?
) {
    val isValidAndroidProject: Boolean
        get() = hasGradleConfig && hasAndroidManifest
}

data class KeystoreInspection(
    val fileName: String,
    val sizeBytes: Long,
    val aliases: List<String>,
    val isLocallyVerified: Boolean,
    val statusMessage: String
)

data class CompiledApkResult(
    val fileName: String,
    val apkBytes: ByteArray
)

sealed interface ApkCompileState {
    data object Idle : ApkCompileState
    data class Compiling(val stageMessage: String) : ApkCompileState
    data class Success(val fileName: String, val sizeBytes: Long) : ApkCompileState
    data class Error(val message: String) : ApkCompileState
}

object ApkCompilerClient {

    private val appIdRegex = Regex("""applicationId\s*=?\s*["']([a-zA-Z0-9_.]+)["']""")

    val BUILD_RUNNER_INFO = """
        #!/usr/bin/env python3
        # Serveur minimal de compilation Gradle pour Studio Portal (à placer derrière HTTPS)
        # Reçoit un POST multipart/form-data :
        #   - project_zip (fichier .zip du projet Android)
        #   - keystore_file (fichier .jks / .p12)
        #   - store_password, key_alias, key_password
        # Exécute : gradle :app:assembleRelease + apksigner sign
        # Renvoie : l'APK signé (application/vnd.android.package-archive)
        from http.server import HTTPServer, BaseHTTPRequestHandler
        import cgi, os, shutil, subprocess, tempfile, zipfile

        class CompileHandler(BaseHTTPRequestHandler):
            def do_POST(self):
                form = cgi.FieldStorage(fp=self.rfile, headers=self.headers,
                    environ={'REQUEST_METHOD': 'POST', 'CONTENT_TYPE': self.headers['Content-Type']})
                with tempfile.TemporaryDirectory() as tmp:
                    zip_path = os.path.join(tmp, 'project.zip')
                    ks_path = os.path.join(tmp, 'upload.jks')
                    with open(zip_path, 'wb') as f:
                        f.write(form['project_zip'].file.read())
                    with open(ks_path, 'wb') as f:
                        f.write(form['keystore_file'].file.read())
                    proj_dir = os.path.join(tmp, 'src')
                    with zipfile.ZipFile(zip_path) as zf:
                        zf.extractall(proj_dir)
                    subprocess.run(['gradle', ':app:assembleRelease'], cwd=proj_dir, check=True)
                    unsigned_apk = os.path.join(proj_dir, 'app/build/outputs/apk/release/app-release-unsigned.apk')
                    signed_apk = os.path.join(tmp, 'app-release-signed.apk')
                    subprocess.run([
                        'apksigner', 'sign', '--ks', ks_path,
                        '--ks-key-alias', form.getvalue('key_alias'),
                        '--ks-pass', 'pass:' + form.getvalue('store_password'),
                        '--key-pass', 'pass:' + form.getvalue('key_password'),
                        '--out', signed_apk, unsigned_apk
                    ], check=True)
                    data = open(signed_apk, 'rb').read()
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/vnd.android.package-archive')
                    self.send_header('Content-Disposition', 'attachment; filename="app-release-signed.apk"')
                    self.send_header('Content-Length', str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)

        HTTPServer(('0.0.0.0', 8443), CompileHandler).serve_forever()
    """.trimIndent()

    fun normalizeServerUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) return null
        val parsed = runCatching { URL(trimmed) }.getOrNull() ?: return null
        if (parsed.host.isNullOrBlank()) return null
        return trimmed
    }

    fun inspectProjectZip(bytes: ByteArray, fileName: String): Result<InspectedProjectZip> {
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
            return Result.failure(IllegalArgumentException("Le fichier sélectionné n’est pas une archive ZIP valide."))
        }
        return runCatching {
            var entryCount = 0
            var hasGradle = false
            var hasManifest = false
            var detectedAppId: String? = null

            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    if (!entry.isDirectory) {
                        entryCount++
                        val lower = name.lowercase()
                        if (lower.endsWith("build.gradle.kts") || lower.endsWith("build.gradle") ||
                            lower.endsWith("settings.gradle.kts") || lower.endsWith("settings.gradle")
                        ) {
                            hasGradle = true
                            if (detectedAppId == null && (lower.endsWith("app/build.gradle.kts") || lower.endsWith("app/build.gradle"))) {
                                val snippet = zis.readBytesLimited(64 * 1024).toString(Charsets.UTF_8)
                                detectedAppId = appIdRegex.find(snippet)?.groupValues?.getOrNull(1)
                            }
                        }
                        if (lower.endsWith("androidmanifest.xml")) {
                            hasManifest = true
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (entryCount == 0) {
                throw IllegalArgumentException("L’archive ZIP est vide.")
            }

            InspectedProjectZip(
                fileName = fileName,
                sizeBytes = bytes.size.toLong(),
                entryCount = entryCount,
                hasGradleConfig = hasGradle,
                hasAndroidManifest = hasManifest,
                detectedAppId = detectedAppId
            )
        }
    }

    fun inspectKeystore(
        bytes: ByteArray,
        fileName: String,
        storePassword: String,
        alias: String,
        keyPassword: String
    ): KeystoreInspection {
        if (bytes.isEmpty()) {
            return KeystoreInspection(
                fileName = fileName,
                sizeBytes = 0L,
                aliases = emptyList(),
                isLocallyVerified = false,
                statusMessage = "Fichier keystore vide."
            )
        }

        if (storePassword.isEmpty()) {
            return KeystoreInspection(
                fileName = fileName,
                sizeBytes = bytes.size.toLong(),
                aliases = emptyList(),
                isLocallyVerified = false,
                statusMessage = "Keystore chargé — saisissez le mot de passe pour vérifier."
            )
        }

        return try {
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(ByteArrayInputStream(bytes), storePassword.toCharArray())
            val aliases = ks.aliases().toList()
            val cleanAlias = alias.trim()
            val message = when {
                aliases.isEmpty() -> "Keystore PKCS12 valide, mais aucune clé trouvée."
                cleanAlias.isBlank() -> "Mot de passe OK — alias trouvés : ${aliases.joinToString(", ")}"
                !ks.containsAlias(cleanAlias) -> "Alias « $cleanAlias » introuvable (disponibles : ${aliases.joinToString(", ")})."
                keyPassword.isEmpty() -> "Alias « $cleanAlias » trouvé — saisissez le mot de passe de la clé."
                else -> {
                    ks.getKey(cleanAlias, keyPassword.toCharArray())
                    "Keystore, alias « $cleanAlias » et mot de passe de clé vérifiés."
                }
            }
            val fullyVerified = cleanAlias.isNotBlank() &&
                ks.containsAlias(cleanAlias) &&
                keyPassword.isNotEmpty() &&
                runCatching { ks.getKey(cleanAlias, keyPassword.toCharArray()) != null }.getOrDefault(false)

            KeystoreInspection(
                fileName = fileName,
                sizeBytes = bytes.size.toLong(),
                aliases = aliases,
                isLocallyVerified = fullyVerified,
                statusMessage = message
            )
        } catch (_: Exception) {
            // Android's JCA supports PKCS12 natively; legacy JKS format will be verified by apksigner/Gradle on the build server.
            KeystoreInspection(
                fileName = fileName,
                sizeBytes = bytes.size.toLong(),
                aliases = emptyList(),
                isLocallyVerified = false,
                statusMessage = "Format JKS classique ou mot de passe non vérifiable localement — sera vérifié à la signature."
            )
        }
    }

    suspend fun compileSignedApk(
        serverUrl: String,
        zipBytes: ByteArray,
        zipFileName: String,
        keystoreBytes: ByteArray,
        keystoreFileName: String,
        storePassword: String,
        keyAlias: String,
        keyPassword: String
    ): Result<CompiledApkResult> = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeServerUrl(serverUrl)
            ?: return@withContext Result.failure(
                IllegalArgumentException("L’URL du serveur doit commencer par https:// (trafic chiffré obligatoire).")
            )

        val boundary = "----StudioPortalBoundary${UUID.randomUUID().toString().replace("-", "")}"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                useCaches = false
                connectTimeout = 20_000
                readTimeout = 300_000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("User-Agent", "StudioPortal-Android")
            }

            DataOutputStream(connection.outputStream).use { out ->
                writeFormField(out, boundary, "store_password", storePassword)
                writeFormField(out, boundary, "key_alias", keyAlias.trim())
                writeFormField(out, boundary, "key_password", keyPassword)
                writeFileField(out, boundary, "project_zip", zipFileName, "application/zip", zipBytes)
                writeFileField(out, boundary, "keystore_file", keystoreFileName, "application/octet-stream", keystoreBytes)
                out.writeBytes("--$boundary--\r\n")
                out.flush()
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                val errMsg = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull().orEmpty().take(220)
                return@withContext Result.failure(
                    IllegalStateException(
                        if (errMsg.isNotBlank()) "Erreur serveur HTTP $code : $errMsg"
                        else "Le serveur de compilation a répondu avec l’erreur HTTP $code."
                    )
                )
            }

            val apkBytes = connection.inputStream.use { it.readBytes() }
            if (apkBytes.size < 4 || apkBytes[0] != 0x50.toByte() || apkBytes[1] != 0x4B.toByte()) {
                return@withContext Result.failure(
                    IllegalStateException("La réponse du serveur n’est pas un fichier APK valide.")
                )
            }

            val baseName = zipFileName.removeSuffix(".zip").ifBlank { "app" }
            val outName = "${baseName}-signed.apk"
            Result.success(CompiledApkResult(fileName = outName, apkBytes = apkBytes))
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException("Échec de la compilation distante : ${e.message ?: "connexion impossible"}")
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun writeFormField(
        out: DataOutputStream,
        boundary: String,
        name: String,
        value: String
    ) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.write(value.toByteArray(Charsets.UTF_8))
        out.writeBytes("\r\n")
    }

    private fun writeFileField(
        out: DataOutputStream,
        boundary: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        data: ByteArray
    ) {
        val safeName = fileName.replace("\"", "_")
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$safeName\"\r\n")
        out.writeBytes("Content-Type: $mimeType\r\n\r\n")
        out.write(data)
        out.writeBytes("\r\n")
    }

    private fun ZipInputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val buf = ByteArray(4096)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (total < maxBytes) {
            val read = read(buf, 0, minOf(buf.size, maxBytes - total))
            if (read <= 0) break
            out.write(buf, 0, read)
            total += read
        }
        return out.toByteArray()
    }
}
