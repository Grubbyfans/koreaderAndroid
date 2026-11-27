package com.yuezhou.reader

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yuezhou.reader.core.speech.SpeechEngineType
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSettingsTest {
    @Test
    fun legacyKokoroPreferenceUsesSystemTtsWhenEngineIsUnavailable() {
        val stored = ReaderPreferences(speechEngine = "kokoro", speechVoice = "system-voice")

        assertEquals(SpeechEngineType.SYSTEM_TTS, stored.effectiveSpeechEngine(kokoroAvailable = false, networkTtsAvailable = false))
        assertEquals(SpeechEngineType.KOKORO, stored.effectiveSpeechEngine(kokoroAvailable = true, networkTtsAvailable = false))
        assertEquals("system-voice", stored.speechVoice)
    }

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

    @Test
    fun rippleAnimationPersistsThroughTheDelta() {
        val key = stringPreferencesKey("page_animation")
        val stored = mutablePreferencesOf(key to "slide")
        ReaderSettings.applyDelta(stored, ReaderPreferences(), ReaderPreferences(pageAnimation = "ripple"))
        assertEquals("ripple", stored[key])
    }

    @Test
    fun rippleParamsPersistThroughTheDelta() {
        val durationKey = intPreferencesKey("ripple_duration_ms")
        val ratioKey = intPreferencesKey("ripple_ratio")
        val stored = mutablePreferencesOf()
        ReaderSettings.applyDelta(stored, ReaderPreferences(), ReaderPreferences(rippleDurationMs = 420, rippleRatio = 60))
        assertEquals(420, stored[durationKey])
        assertEquals(60, stored[ratioKey])
    }
}
