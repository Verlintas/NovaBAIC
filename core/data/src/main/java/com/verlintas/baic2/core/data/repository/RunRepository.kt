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

import androidx.room.withTransaction
import com.verlintas.baic2.core.data.db.Baic2Database
import com.verlintas.baic2.core.data.db.RunEntity
import com.verlintas.baic2.core.data.mapper.ChatMapper
import com.verlintas.baic2.core.model.Run
import com.verlintas.baic2.core.model.RunState
import com.verlintas.baic2.core.model.RunSummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class RunRepository @Inject constructor(
    private val db: Baic2Database,
    private val mapper: ChatMapper,
) {

    fun observeSummaries(): Flow<List<RunSummary>> =
        db.runDao().observeSummaries().map { rows -> rows.map(mapper::runSummaryToModel) }

    fun observeRun(runId: Long): Flow<Run?> =
        db.runDao().observeById(runId).map { entity -> entity?.let(mapper::runToModel) }

    suspend fun delete(runId: Long) = db.runDao().deleteById(runId)

    suspend fun count(): Int = db.runDao().countAll()

    suspend fun totalToolCalls(): Int = db.runDao().totalToolCalls()

    /**
     * A run lives in the app process, so after a cold start nothing can still
     * be running: mark leftover RUNNING rows as cancelled for Tasks.
     */
    suspend fun cancelStaleRuns() = db.runDao().cancelStale(System.currentTimeMillis())

    suspend fun start(conversationId: Long, mode: String): Long {
        val now = System.currentTimeMillis()
        return db.runDao().insert(
            RunEntity(
                conversationId = conversationId,
                mode = mode,
                state = RunState.RUNNING.name,
                startedAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun finish(
        runId: Long,
        state: RunState,
        roundsUsed: Int = 0,
        toolCallsUsed: Int = 0,
    ) = db.withTransaction {
        db.runDao().finish(runId, state.name, roundsUsed, toolCallsUsed, System.currentTimeMillis())
    }
}
