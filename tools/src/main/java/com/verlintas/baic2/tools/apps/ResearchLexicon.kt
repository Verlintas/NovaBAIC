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

package com.verlintas.baic2.tools.apps

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.verlintas.baic2.tools.R

/**
 * Locale-dependent UI words used by app research. Resource IDs remain the
 * primary way to find controls; this is only the readable fallback.
 */
data class ResearchLexicon(
    val searchTerms: List<String>,
    val chrome: Set<String>,
    val loginTerms: List<String>,
    val captchaTerms: List<String>,
    val riskTerms: List<String>,
) {
    fun isChrome(label: String): Boolean = label.trim().lowercase() in chrome

    fun blockedReason(text: String): String? {
        val lower = text.lowercase()
        return when {
            captchaTerms.any { it in lower } -> "captcha"
            loginTerms.any { it in lower } -> "login"
            riskTerms.any { it in lower } -> "risk control"
            else -> null
        }
    }

    companion object {
        fun from(context: Context): ResearchLexicon {
            // The target app normally follows the system language, while
            // BAIC2's own UI may be set to another language. Merge both so a
            // Chinese app still gets Chinese fallback words in an English UI
            // (and vice versa). Resource ids remain the primary signal.
            val systemConfig = Configuration(context.resources.configuration)
            systemConfig.setLocales(Resources.getSystem().configuration.locales)
            val systemResources = context.createConfigurationContext(systemConfig).resources
            fun array(id: Int): List<String> {
                val app = context.resources.getStringArray(id)
                val system = systemResources.getStringArray(id)
                return (app.asSequence() + system.asSequence())
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .toList()
            }
            return ResearchLexicon(
                searchTerms = array(R.array.app_research_search_terms),
                chrome = array(R.array.app_research_chrome).toSet(),
                loginTerms = array(R.array.app_research_login_terms),
                captchaTerms = array(R.array.app_research_captcha_terms),
                riskTerms = array(R.array.app_research_risk_terms),
            )
        }
    }
}
