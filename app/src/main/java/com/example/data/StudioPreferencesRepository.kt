package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.studioDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "studio_portal_preferences"
)

class StudioPreferencesRepository(private val context: Context) {

    private object Keys {
        val DESKTOP_MODE = booleanPreferencesKey("desktop_mode")
        val IMMERSIVE_MODE = booleanPreferencesKey("immersive_mode")
        val TEXT_ZOOM = intPreferencesKey("text_zoom")
        val THEME_PREF = stringPreferencesKey("theme_pref")
        val LAST_URL = stringPreferencesKey("last_url")
        val PINNED_SHORTCUTS = stringPreferencesKey("pinned_shortcuts")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val NOTIFY_ONLY_BG = booleanPreferencesKey("notify_only_bg")
        val UPDATE_REPO_SLUG = stringPreferencesKey("update_repo_slug")
        val BUILD_SERVER_URL = stringPreferencesKey("build_server_url")
    }

    val preferencesFlow: Flow<StudioPreferences> = context.studioDataStore.data.map { prefs ->
        StudioPreferences(
            isDesktopMode = prefs[Keys.DESKTOP_MODE] ?: false,
            isImmersiveMode = prefs[Keys.IMMERSIVE_MODE] ?: false,
            textZoomPercent = (prefs[Keys.TEXT_ZOOM] ?: 100).coerceIn(80, 130),
            themePreference = ThemePreference.fromKey(prefs[Keys.THEME_PREF]),
            lastUrl = prefs[Keys.LAST_URL]?.takeIf { it.startsWith("https://") }
                ?: StudioSection.STUDIO.url,
            pinnedShortcuts = decodeShortcuts(prefs[Keys.PINNED_SHORTCUTS]),
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            notifyOnlyInBackground = prefs[Keys.NOTIFY_ONLY_BG] ?: false,
            updateRepoSlug = prefs[Keys.UPDATE_REPO_SLUG] ?: "Braz777z/App-Google-ai-studio",
            buildServerUrl = prefs[Keys.BUILD_SERVER_URL] ?: ""
        )
    }

    suspend fun setDesktopMode(enabled: Boolean) {
        context.studioDataStore.edit { it[Keys.DESKTOP_MODE] = enabled }
    }

    suspend fun setImmersiveMode(enabled: Boolean) {
        context.studioDataStore.edit { it[Keys.IMMERSIVE_MODE] = enabled }
    }

    suspend fun setTextZoom(percent: Int) {
        context.studioDataStore.edit { it[Keys.TEXT_ZOOM] = percent.coerceIn(80, 130) }
    }

    suspend fun setThemePreference(theme: ThemePreference) {
        context.studioDataStore.edit { it[Keys.THEME_PREF] = theme.key }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.studioDataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setNotifyOnlyInBackground(bgOnly: Boolean) {
        context.studioDataStore.edit { it[Keys.NOTIFY_ONLY_BG] = bgOnly }
    }

    suspend fun setUpdateRepoSlug(slug: String) {
        context.studioDataStore.edit { it[Keys.UPDATE_REPO_SLUG] = slug.trim() }
    }

    suspend fun setBuildServerUrl(url: String) {
        context.studioDataStore.edit { it[Keys.BUILD_SERVER_URL] = url.trim() }
    }

    suspend fun saveLastUrl(url: String) {
        if (url.startsWith("https://aistudio.google.com") || url.startsWith("https://ai.google.dev")) {
            context.studioDataStore.edit { it[Keys.LAST_URL] = url }
        }
    }

    suspend fun addPinnedShortcut(shortcut: PinnedShortcut) {
        context.studioDataStore.edit { prefs ->
            val current = decodeShortcuts(prefs[Keys.PINNED_SHORTCUTS]).toMutableList()
            current.removeAll { it.url == shortcut.url }
            current.add(0, shortcut)
            prefs[Keys.PINNED_SHORTCUTS] = encodeShortcuts(current)
        }
    }

    suspend fun removePinnedShortcut(id: String) {
        context.studioDataStore.edit { prefs ->
            val updated = decodeShortcuts(prefs[Keys.PINNED_SHORTCUTS]).filterNot { it.id == id }
            prefs[Keys.PINNED_SHORTCUTS] = encodeShortcuts(updated)
        }
    }

    private fun decodeShortcuts(raw: String?): List<PinnedShortcut> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val title = obj.optString("title")
                    val url = obj.optString("url")
                    val createdAt = obj.optLong("createdAt", 0L)
                    if (id.isNotBlank() && title.isNotBlank() && url.startsWith("https://")) {
                        add(PinnedShortcut(id = id, title = title, url = url, createdAt = createdAt))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeShortcuts(shortcuts: List<PinnedShortcut>): String {
        val array = JSONArray()
        shortcuts.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("url", item.url)
                put("createdAt", item.createdAt)
            }
            array.put(obj)
        }
        return array.toString()
    }
}
