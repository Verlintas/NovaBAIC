/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.verlintas.baic2.core.model.AccentColor
import com.verlintas.baic2.core.model.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        when (prefs[THEME_MODE]) {
            ThemeMode.LIGHT.name -> ThemeMode.LIGHT
            ThemeMode.DARK.name -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[THEME_MODE] = mode.name
        }
    }

    val accentColor: Flow<AccentColor> = dataStore.data.map { prefs ->
        AccentColor.fromId(prefs[ACCENT_COLOR])
    }

    suspend fun setAccentColor(accent: AccentColor) {
        dataStore.edit { prefs ->
            prefs[ACCENT_COLOR] = accent.id
        }
    }

    /** Hands-free loop: listen and auto-send after every assistant reply. */
    val handsFreeVoice: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[HANDS_FREE_VOICE] ?: false
    }

    suspend fun setHandsFreeVoice(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[HANDS_FREE_VOICE] = enabled
        }
    }

    /**
     * Opt-in consent for carrying local context in every chat request:
     * timestamp plus the latest location fix (and fresh weather cache).
     * Off by default; never fetched for scheduled or unattended runs.
     */
    val sceneEnhancement: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SCENE_ENHANCEMENT] ?: false
    }

    suspend fun setSceneEnhancement(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[SCENE_ENHANCEMENT] = enabled
        }
    }

    /**
     * Opt-in: after a successful Act/Max multi-step UI flow, record it as a
     * replayable skill. Off by default; identical flows are deduplicated by
     * the skill repository and old auto-saved entries are pruned.
     */
    val autoSaveSkills: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[AUTO_SAVE_SKILLS] ?: false
    }

    suspend fun setAutoSaveSkills(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[AUTO_SAVE_SKILLS] = enabled
        }
    }

    /**
     * Opt-in: keep the resident virtual-display helper alive across Wi-Fi/ADB
     * drops. The helper is a local Binder process, so an existing session can
     * survive transport loss; a foreground service prevents the idle timer
     * from releasing it.
     */
    val keepDisplayAlive: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEEP_DISPLAY_ALIVE] ?: false
    }

    suspend fun setKeepDisplayAlive(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEEP_DISPLAY_ALIVE] = enabled
        }
    }

    /** Attach the final virtual-display frame to a successful Act/Max run. */
    val keepExecutionScreenshots: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEEP_EXECUTION_SCREENSHOTS] ?: true
    }

    suspend fun setKeepExecutionScreenshots(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEEP_EXECUTION_SCREENSHOTS] = enabled
        }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val HANDS_FREE_VOICE = booleanPreferencesKey("hands_free_voice")
        val SCENE_ENHANCEMENT = booleanPreferencesKey("scene_enhancement")
        val AUTO_SAVE_SKILLS = booleanPreferencesKey("auto_save_skills")
        val KEEP_DISPLAY_ALIVE = booleanPreferencesKey("keep_display_alive")
        val KEEP_EXECUTION_SCREENSHOTS = booleanPreferencesKey("keep_execution_screenshots")
    }
}
