package com.yuezhou.reader

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSettingsTest {
    @Test
    fun separateEditsFromTheSameSnapshotBothSurvive() {
        val theme = stringPreferencesKey("theme")
        val fontSize = intPreferencesKey("font_size")
        val brightness = floatPreferencesKey("brightness")
        val stored = mutablePreferencesOf(brightness to 0.7f)
        val displayed = ReaderPreferences()
        ReaderSettings.applyDelta(stored, displayed, displayed.copy(theme = "night"))
        ReaderSettings.applyDelta(stored, displayed, displayed.copy(fontSize = 22))
        assertEquals("night", stored[theme])
        assertEquals(22, stored[fontSize])
        assertEquals(0.7f, stored[brightness])
    }
}
