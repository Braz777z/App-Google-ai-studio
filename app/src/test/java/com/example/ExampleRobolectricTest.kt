package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.StudioSection
import com.example.data.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Studio Portal", appName)
    }

    @Test
    fun `section matching maps AI Studio routes accurately`() {
        assertEquals(
            StudioSection.APPS,
            StudioSection.matchFromUrl("https://aistudio.google.com/apps")
        )
        assertEquals(
            StudioSection.API_KEYS,
            StudioSection.matchFromUrl("https://aistudio.google.com/apikey")
        )
        assertEquals(
            StudioSection.LIBRARY,
            StudioSection.matchFromUrl("https://aistudio.google.com/library")
        )
        assertEquals(
            StudioSection.STUDIO,
            StudioSection.matchFromUrl("https://aistudio.google.com/prompts/new_chat")
        )
    }

    @Test
    fun `theme preference parses safely`() {
        assertEquals(ThemePreference.DARK, ThemePreference.fromKey("dark"))
        assertEquals(ThemePreference.LIGHT, ThemePreference.fromKey("light"))
        assertEquals(ThemePreference.SYSTEM, ThemePreference.fromKey("unknown"))
    }
}
