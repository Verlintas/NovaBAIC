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

package com.verlintas.baic2.feature.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verlintas.baic2.core.data.prefs.ModeProfileStore
import com.verlintas.baic2.core.data.repository.ConversationRepository
import com.verlintas.baic2.core.data.repository.PlanRepository
import com.verlintas.baic2.core.data.repository.RunControlBus
import com.verlintas.baic2.core.data.repository.RunRepository
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.core.model.ImpactSummarizer
import com.verlintas.baic2.core.model.ModeProfile
import com.verlintas.baic2.core.model.ModeProfiles
import com.verlintas.baic2.core.model.Plan
import com.verlintas.baic2.core.model.Run
import com.verlintas.baic2.core.model.RunState
import com.verlintas.baic2.core.model.RunSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TaskFilter { ALL, RUNNING, COMPLETED, FAILED }

/** Run states behind each tab; the filtering itself runs in SQL. */
private fun TaskFilter.toStates(): List<String> = when (this) {
    TaskFilter.ALL -> listOf(
        RunState.RUNNING.name,
        RunState.COMPLETED.name,
        RunState.FAILED.name,
        RunState.CANCELLED.name,
    )
    TaskFilter.RUNNING -> listOf(RunState.RUNNING.name)
    TaskFilter.COMPLETED -> listOf(RunState.COMPLETED.name)
    TaskFilter.FAILED -> listOf(RunState.FAILED.name, RunState.CANCELLED.name)
}

data class TasksUiState(
    val runs: List<RunSummary> = emptyList(),
    val modes: List<ModeProfile> = emptyList(),
    val filter: TaskFilter = TaskFilter.ALL,
    val totalCount: Int = 0,
    val runningCount: Int = 0,
)

data class RunDetailState(
    val run: Run,
    val mode: ModeProfile,
    val conversationTitle: String,
    val plan: Plan?,
    val messages: List<ChatMessage>,
    val impact: List<String>,
)

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val runRepository: RunRepository,
    private val modeStore: ModeProfileStore,
) : ViewModel() {

    private val filter = MutableStateFlow(TaskFilter.ALL)

    val uiState: StateFlow<TasksUiState> = combine(
        filter.flatMapLatest { selected -> runRepository.observeSummaries(selected.toStates()) },
        filter,
        modeStore.profiles,
        runRepository.observeTotalCount(),
        runRepository.observeRunningCount(),
    ) { runs, selected, modes, totalCount, runningCount ->
        // The run journal keeps every execution: agent work plus scheduled
        // task runs in any mode. Ordinary chat sends are not runs at all.
        TasksUiState(
            runs = runs,
            modes = modes,
            filter = selected,
            totalCount = totalCount,
            runningCount = runningCount,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TasksUiState(),
    )

    fun setFilter(value: TaskFilter) {
        filter.value = value
    }

    fun deleteRun(runId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            runRepository.delete(runId)
            onDone()
        }
    }
}

@HiltViewModel
class RunDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val runRepository: RunRepository,
    private val runControlBus: RunControlBus,
    private val modeStore: ModeProfileStore,
    planRepository: PlanRepository,
    conversationRepository: ConversationRepository,
) : ViewModel() {

    val runId: Long = savedStateHandle.get<Long>("runId") ?: 0L

    val detail: StateFlow<RunDetailState?> = runRepository.observeRun(runId)
        .flatMapLatest { run ->
            if (run == null) {
                flowOf(null)
            } else {
                combine(
                    conversationRepository.observeConversation(run.conversationId),
                    planRepository.observePlan(run.conversationId),
                    conversationRepository.observeMessages(run.conversationId),
                    modeStore.profiles,
                ) { conversation, plan, messages, modes ->
                    val runMessages = messagesForRun(run, messages)
                    RunDetailState(
                        run = run,
                        // Deleted modes keep the tool class the run was made
                        // under, so its history is still labeled truthfully.
                        mode = modes.firstOrNull { it.id == run.mode }
                            ?: ModeProfiles.fallback(run.mode, run.toolAccess),
                        conversationTitle = conversation?.title.orEmpty(),
                        plan = plan,
                        messages = runMessages,
                        impact = ImpactSummarizer.summarize(runMessages),
                    )
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    fun deleteRun(onDone: () -> Unit) {
        viewModelScope.launch {
            runRepository.delete(runId)
            onDone()
        }
    }

    /** Asks the chat screen that owns this run to cancel it. */
    fun stopRun(conversationId: Long) {
        runControlBus.requestStop(conversationId)
    }
}

/**
 * Reconstructs the messages that belong to a run from timestamps: the prompt is
 * persisted right after the run starts, and the run's `updatedAt` is stamped
 * again when it finishes.
 */
private fun messagesForRun(run: Run, messages: List<ChatMessage>): List<ChatMessage> {
    val from = run.startedAt - 2_000
    val to = if (run.state == RunState.RUNNING) Long.MAX_VALUE else run.updatedAt + 2_000
    return messages.filter { message ->
        message.role != ChatRole.SYSTEM && message.createdAt in from..to
    }
}
