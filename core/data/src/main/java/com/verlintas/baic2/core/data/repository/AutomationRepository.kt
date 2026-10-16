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

package com.verlintas.baic2.core.data.repository

import com.verlintas.baic2.core.data.db.Baic2Database
import com.verlintas.baic2.core.data.mapper.ChatMapper
import com.verlintas.baic2.core.model.Automation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class AutomationRepository @Inject constructor(
    private val db: Baic2Database,
    private val mapper: ChatMapper,
) {

    fun observeAll(): Flow<List<Automation>> =
        db.automationDao().observeAll().map { list -> list.map(mapper::automationToModel) }

    suspend fun getAll(): List<Automation> =
        db.automationDao().getAll().map(mapper::automationToModel)

    suspend fun getById(id: Long): Automation? =
        db.automationDao().getById(id)?.let(mapper::automationToModel)

    suspend fun save(automation: Automation): Long {
        val entity = mapper.automationToEntity(automation)
        return if (automation.id == 0L) {
            db.automationDao().insert(entity)
        } else {
            // Full-row update: editing an automation must not silently no-op.
            db.automationDao().update(entity)
            automation.id
        }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) =
        db.automationDao().setEnabled(id, enabled)

    suspend fun delete(id: Long) = db.automationDao().delete(id)
}
