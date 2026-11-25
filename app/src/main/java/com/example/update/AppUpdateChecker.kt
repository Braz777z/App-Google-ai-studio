package com.example.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpToDate(val currentVersion: String, val latestTag: String) : UpdateCheckState
    data class UpdateAvailable(
        val currentVersion: String,
        val latestTag: String,
        val releaseNotes: String,
        val releaseUrl: String
    ) : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}

object AppUpdateChecker {

    private val repoRegex = Regex("^[a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+$")

    fun normalizeRepoInput(raw: String): String? {
        var cleaned = raw.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removePrefix("github.com/")
            .trim('/')
            .removeSuffix(".git")
        if (cleaned.contains("/releases")) {
            cleaned = cleaned.substringBefore("/releases")
        }
        return cleaned.takeIf { repoRegex.matches(it) }
    }

    fun isNewerVersion(latestTag: String, currentVersion: String): Boolean {
        val latestParts = parseVersionParts(latestTag)
        val currentParts = parseVersionParts(currentVersion)
        if (latestParts.isEmpty() || currentParts.isEmpty()) {
            return latestTag.trim().removePrefix("v").removePrefix("V") !=
                currentVersion.trim().removePrefix("v").removePrefix("V")
        }
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }

    private fun parseVersionParts(version: String): List<Int> {
        val cleaned = version.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        if (cleaned.isBlank()) return emptyList()
        return cleaned.split('.').mapNotNull { it.toIntOrNull() }
    }

    suspend fun checkLatestGitHubRelease(
        rawRepo: String,
        currentVersion: String
    ): UpdateCheckState = withContext(Dispatchers.IO) {
        val repo = normalizeRepoInput(rawRepo)
            ?: return@withContext UpdateCheckState.Error(
                "Format invalide. Utilisez propriétaire/dépôt (ex. : utilisateur/studio-portal)."
            )

        var connection: HttpURLConnection? = null
        try {
            val endpoint = URL("https://api.github.com/repos/$repo/releases/latest")
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "StudioPortal-Android")
            }

            val code = connection.responseCode
            if (code == 404) {
                return@withContext UpdateCheckState.Error(
                    "Aucune release publiée trouvée sur github.com/$repo."
                )
            }
            if (code !in 200..299) {
                return@withContext UpdateCheckState.Error(
                    "Impossible de vérifier les mises à jour (HTTP $code)."
                )
            }

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            val tagName = json.optString("tag_name").trim()
            val htmlUrl = json.optString("html_url").trim()
            val notes = json.optString("body").trim()

            if (tagName.isBlank() || !htmlUrl.startsWith("https://")) {
                return@withContext UpdateCheckState.Error("Réponse de release GitHub incomplète.")
            }

            // Prefer direct .apk asset link if present in release assets, otherwise release page
            var targetUrl = htmlUrl
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    val downloadUrl = asset.optString("browser_download_url")
                    if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.startsWith("https://")) {
                        targetUrl = downloadUrl
                        break
                    }
                }
            }

            if (isNewerVersion(tagName, currentVersion)) {
                UpdateCheckState.UpdateAvailable(
                    currentVersion = currentVersion,
                    latestTag = tagName,
                    releaseNotes = notes.take(300),
                    releaseUrl = targetUrl
                )
            } else {
                UpdateCheckState.UpToDate(
                    currentVersion = currentVersion,
                    latestTag = tagName
                )
            }
        } catch (_: Exception) {
            UpdateCheckState.Error("Connexion impossible pour vérifier les mises à jour.")
        } finally {
            connection?.disconnect()
        }
    }
}
