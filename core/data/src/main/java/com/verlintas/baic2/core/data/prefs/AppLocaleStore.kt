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

import android.content.Context
import com.verlintas.baic2.core.model.AppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Language lives in SharedPreferences (synchronous) because the locale must be
 * applied in `attachBaseContext` before DataStore can be read.
 */
@Singleton
class AppLocaleStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences("locale", Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(read())
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    fun read(): AppLanguage =
        when (prefs.getString(KEY_LANGUAGE, null)) {
            AppLanguage.CHINESE.name -> AppLanguage.CHINESE
            AppLanguage.ENGLISH.name -> AppLanguage.ENGLISH
            AppLanguage.RUSSIAN.name -> AppLanguage.RUSSIAN
            else -> AppLanguage.SYSTEM
        }

    fun set(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.name).apply()
        _language.value = language
    }

    companion object {
        private const val KEY_LANGUAGE = "app_language"

        /** Synchronous read for attachBaseContext. */
        fun readFrom(context: Context): AppLanguage =
            when (context.getSharedPreferences("locale", Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, null)) {
                AppLanguage.CHINESE.name -> AppLanguage.CHINESE
                AppLanguage.ENGLISH.name -> AppLanguage.ENGLISH
                AppLanguage.RUSSIAN.name -> AppLanguage.RUSSIAN
                else -> AppLanguage.SYSTEM
            }
    }
}
