package com.example.data

import androidx.annotation.StringRes
import com.example.R

enum class StudioSection(
    val id: String,
    @param:StringRes val labelRes: Int,
    val url: String,
    val pathHint: String
) {
    STUDIO(
        id = "studio",
        labelRes = R.string.nav_studio,
        url = "https://aistudio.google.com/prompts/new_chat",
        pathHint = "/prompts/new_chat"
    ),
    APPS(
        id = "apps",
        labelRes = R.string.nav_apps,
        url = "https://aistudio.google.com/apps",
        pathHint = "/apps"
    ),
    API_KEYS(
        id = "apikeys",
        labelRes = R.string.nav_apikeys,
        url = "https://aistudio.google.com/apikey",
        pathHint = "/apikey"
    ),
    LIBRARY(
        id = "library",
        labelRes = R.string.nav_library,
        url = "https://aistudio.google.com/library",
        pathHint = "/library"
    );

    companion object {
        fun matchFromUrl(url: String): StudioSection? {
            return when {
                url.contains("/apps") || url.contains("/app/") -> APPS
                url.contains("/apikey") -> API_KEYS
                url.contains("/library") -> LIBRARY
                url.contains("aistudio.google.com") -> STUDIO
                else -> null
            }
        }
    }
}

data class QuickDestination(
    val id: String,
    val title: String,
    val subtitle: String,
    val url: String
)

val DefaultQuickDestinations = listOf(
    QuickDestination(
        id = "new_chat",
        title = "Nouveau Chat",
        subtitle = "Démarrer un prompt avec Gemini",
        url = "https://aistudio.google.com/prompts/new_chat"
    ),
    QuickDestination(
        id = "live_stream",
        title = "Stream Temps Réel",
        subtitle = "Voix, vidéo et partage en direct",
        url = "https://aistudio.google.com/live"
    ),
    QuickDestination(
        id = "build_apps",
        title = "AI Studio Build",
        subtitle = "Générer et prévisualiser des apps",
        url = "https://aistudio.google.com/apps"
    ),
    QuickDestination(
        id = "usage",
        title = "Utilisation & Quotas",
        subtitle = "Suivi de consommation et limites",
        url = "https://aistudio.google.com/usage"
    ),
    QuickDestination(
        id = "docs",
        title = "Documentation Gemini API",
        subtitle = "Référence SDK, modèles et exemples",
        url = "https://ai.google.dev/gemini-api/docs"
    )
)

data class PinnedShortcut(
    val id: String,
    val title: String,
    val url: String,
    val createdAt: Long
)

enum class ThemePreference(val key: String) {
    SYSTEM("system"),
    DARK("dark"),
    LIGHT("light");

    companion object {
        fun fromKey(key: String?): ThemePreference {
            return entries.firstOrNull { it.key == key } ?: SYSTEM
        }
    }
}

data class StudioPreferences(
    val isDesktopMode: Boolean = false,
    val isImmersiveMode: Boolean = false,
    val textZoomPercent: Int = 100,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val lastUrl: String = StudioSection.STUDIO.url,
    val pinnedShortcuts: List<PinnedShortcut> = emptyList(),
    val notificationsEnabled: Boolean = true,
    val notifyOnlyInBackground: Boolean = false,
    val updateRepoSlug: String = "",
    val buildServerUrl: String = ""
)
