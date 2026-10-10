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

package com.verlintas.baic2

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.core.data.prefs.AppLocaleStore
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.core.model.AccentColor
import com.verlintas.baic2.core.model.AppVisibility
import com.verlintas.baic2.core.model.ThemeMode
import com.verlintas.baic2.designsystem.Baic2Theme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun attachBaseContext(newBase: Context) {
        val language = AppLocaleStore.readFrom(newBase)
        val tag = language.languageTag
        super.attachBaseContext(
            if (tag == null) newBase else newBase.withLocale(Locale.forLanguageTag(tag)),
        )
    }

    private val pendingRunId = androidx.compose.runtime.mutableStateOf<Long?>(null)
    private val pendingConversationId = androidx.compose.runtime.mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Rotation restores the same intent: only consume the deep link on a
        // fresh launch (or via onNewIntent), never twice.
        pendingRunId.value = if (savedInstanceState == null) {
            intent?.getLongExtra(EXTRA_OPEN_RUN_ID, -1L)?.takeIf { it >= 0L }
        } else {
            null
        }
        pendingConversationId.value = if (savedInstanceState == null) {
            intent?.getLongExtra(EXTRA_OPEN_CONVERSATION_ID, -1L)?.takeIf { it >= 0L }
        } else {
            null
        }
        setContent {
            val themeMode by settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val accent by settingsRepository.accentColor
                .collectAsStateWithLifecycle(initialValue = AccentColor.BLUE)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            Baic2Theme(darkTheme = darkTheme, accent = accent) {
                Baic2App(
                    initialRunId = pendingRunId.value,
                    onRunDeepLinkConsumed = { pendingRunId.value = null },
                    initialConversationId = pendingConversationId.value,
                    onConversationDeepLinkConsumed = { pendingConversationId.value = null },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mainHandler.removeCallbacks(backgroundRunnable)
        AppVisibility.foreground = true
    }

    override fun onStop() {
        mainHandler.removeCallbacks(backgroundRunnable)
        // Rotations, the speech recognizer dialog and the screen-capture
        // consent screen all call onStop without the user leaving the app:
        // flip to "background" only after a short grace period.
        mainHandler.postDelayed(backgroundRunnable, BACKGROUND_GRACE_MS)
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(backgroundRunnable)
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRunId.value = intent.getLongExtra(EXTRA_OPEN_RUN_ID, -1L).takeIf { it >= 0L }
        pendingConversationId.value = intent.getLongExtra(EXTRA_OPEN_CONVERSATION_ID, -1L)
            .takeIf { it >= 0L }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val backgroundRunnable = Runnable { AppVisibility.foreground = false }

    private companion object {
        const val BACKGROUND_GRACE_MS = 4_000L
    }
}

private fun Context.withLocale(locale: Locale): Context {
    Locale.setDefault(locale)
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return createConfigurationContext(configuration)
}

/** Mirrors RunService.EXTRA_OPEN_RUN_ID (device:impl cannot see app classes). */
private const val EXTRA_OPEN_RUN_ID = "open_run_id"

/** Mirrors RunService.EXTRA_OPEN_CONVERSATION_ID. */
private const val EXTRA_OPEN_CONVERSATION_ID = "open_conversation_id"
