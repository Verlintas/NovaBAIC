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
import android.util.Log
import com.verlintas.baic2.core.model.ModeProfile
import com.verlintas.baic2.core.model.ModeProfiles
import com.verlintas.baic2.core.model.RunBudget
import com.verlintas.baic2.core.model.ToolAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The mode list: stock profiles plus user-defined ones, all editable and
 * deletable. The stored list is the single source of truth, so deleting a
 * stock mode sticks; [resolve] degrades missing ids to plain conversation.
 */
@Singleton
class ModeProfileStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences("modes", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _profiles = MutableStateFlow(read())
    val profiles: StateFlow<List<ModeProfile>> = _profiles.asStateFlow()

    private fun read(): List<ModeProfile> {
        val raw = prefs.getString(KEY_MODES, null) ?: return ModeProfiles.stocks
        return runCatching {
            json.decodeFromString(ListSerializer(ModeProfile.serializer()), raw)
        }.getOrElse {
            // Losing custom modes silently would be worse than losing them
            // loudly: at least the log says where they went.
            Log.w("ModeProfileStore", "stored modes are corrupt, falling back to stock", it)
            ModeProfiles.stocks
        }.map { profile ->
            // A legacy profile may carry both switches; full consent wins, so
            // per-call confirmation is normalized off at load time.
            if (profile.fullConsent && profile.requiresConfirmation) {
                profile.copy(requiresConfirmation = false)
            } else {
                profile
            }
        }
    }

    private fun write(profiles: List<ModeProfile>) {
        prefs.edit()
            .putString(KEY_MODES, json.encodeToString(ListSerializer(ModeProfile.serializer()), profiles))
            .apply()
        _profiles.value = profiles
    }

    /** Stored profile for an id; deleted ids degrade to plain conversation. */
    fun resolve(id: String): ModeProfile =
        _profiles.value.firstOrNull { it.id == id } ?: ModeProfiles.fallback(id)

    /** Create a user mode and add it to the list. */
    fun create(
        name: String,
        toolAccess: ToolAccess,
        requiresConfirmation: Boolean,
        budget: RunBudget,
    ): ModeProfile {
        val profile = ModeProfile(
            id = ModeProfile.newId(),
            name = name,
            toolAccess = toolAccess,
            requiresConfirmation = requiresConfirmation,
            budget = budget,
        )
        write(_profiles.value + profile)
        return profile
    }

    /** Insert or replace by id (also used to edit stock profiles). */
    fun upsert(profile: ModeProfile) {
        val current = _profiles.value
        val index = current.indexOfFirst { it.id == profile.id }
        write(
            if (index >= 0) current.toMutableList().also { it[index] = profile }
            else current + profile,
        )
    }

    /** Remove by id; the last remaining mode cannot be removed. */
    fun delete(id: String) {
        val current = _profiles.value
        if (current.size <= 1) return
        write(current.filterNot { it.id == id })
    }

    /** Restore the stock set ahead of user modes; user modes are kept. */
    fun restoreStocks() {
        val custom = _profiles.value.filterNot { it.stock }
        write(ModeProfiles.stocks + custom)
    }

    private companion object {
        const val KEY_MODES = "mode_profiles"
    }
}
