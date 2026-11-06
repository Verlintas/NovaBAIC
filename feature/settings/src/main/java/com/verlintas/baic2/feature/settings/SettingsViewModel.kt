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

package com.verlintas.baic2.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verlintas.baic2.core.data.prefs.AppLocaleStore
import com.verlintas.baic2.core.data.prefs.ModeProfileStore
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.data.repository.ConversationRepository
import com.verlintas.baic2.core.data.repository.RunRepository
import com.verlintas.baic2.core.data.repository.ScheduledTaskRepository
import com.verlintas.baic2.core.data.storage.AppStorage
import com.verlintas.baic2.core.data.storage.StorageUsage
import com.verlintas.baic2.core.model.AccentColor
import com.verlintas.baic2.core.model.Agent
import com.verlintas.baic2.core.model.AppLanguage
import com.verlintas.baic2.core.model.ModeProfile
import com.verlintas.baic2.core.model.ModeProfiles
import com.verlintas.baic2.core.model.ScheduledTask
import com.verlintas.baic2.core.model.ThemeMode
import com.verlintas.baic2.core.model.isVersionNewer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val agents: List<Agent> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentColor = AccentColor.BLUE,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val autoSaveSkills: Boolean = false,
    val keepExecutionScreenshots: Boolean = true,
    val storage: StorageUsage? = null,
    val stats: UsageStats? = null,
)

data class UsageStats(
    val conversations: Int,
    val messages: Int,
    val runs: Int,
    val toolCalls: Int,
    val tokensIn: Long,
    val tokensOut: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val tag: String, val url: String) : UpdateState
    data object Failed : UpdateState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val settingsRepository: SettingsRepository,
    private val localeStore: AppLocaleStore,
    private val appStorage: AppStorage,
    private val conversationRepository: ConversationRepository,
    private val runRepository: RunRepository,
    private val modeStore: ModeProfileStore,
    private val scheduledTaskRepository: ScheduledTaskRepository,
) : ViewModel() {

    val modes: StateFlow<List<ModeProfile>> = modeStore.profiles

    val scheduledTasks: StateFlow<List<ScheduledTask>> = scheduledTaskRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val storage = MutableStateFlow<StorageUsage?>(null)
    private val stats = MutableStateFlow<UsageStats?>(null)
    private val updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)

    init {
        refreshStorage()
        refreshStats()
    }

    fun updateState(): StateFlow<UpdateState> = updateState.asStateFlow()

    fun checkForUpdates(currentVersion: String) {
        if (updateState.value == UpdateState.Checking) return
        viewModelScope.launch {
            updateState.value = UpdateState.Checking
            val latest = UpdateChecker.fetchLatest()
            updateState.value = when {
                latest == null -> UpdateState.Failed
                isVersionNewer(latest.tag, currentVersion) ->
                    UpdateState.Available(latest.tag, latest.url)

                else -> UpdateState.UpToDate
            }
        }
    }

    fun refreshStats() {
        viewModelScope.launch {
            val (tokensIn, tokensOut) = conversationRepository.tokenTotals()
            stats.value = UsageStats(
                conversations = conversationRepository.conversationCount(),
                messages = conversationRepository.messageCount(),
                runs = runRepository.count(),
                toolCalls = runRepository.totalToolCalls(),
                tokensIn = tokensIn,
                tokensOut = tokensOut,
            )
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            agentRepository.observeAgents(),
            settingsRepository.themeMode,
            settingsRepository.accentColor,
            localeStore.language,
        ) { agents, themeMode, accent, language ->
            SettingsUiState(
                agents = agents,
                themeMode = themeMode,
                accent = accent,
                language = language,
            )
        },
        storage,
        stats,
        settingsRepository.autoSaveSkills,
        settingsRepository.keepExecutionScreenshots,
    ) { base, storageUsage, usageStats, autoSaveSkills, keepScreenshots ->
        base.copy(
            storage = storageUsage,
            stats = usageStats,
            autoSaveSkills = autoSaveSkills,
            keepExecutionScreenshots = keepScreenshots,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setAccent(accent: AccentColor) {
        viewModelScope.launch { settingsRepository.setAccentColor(accent) }
    }

    fun setLanguage(language: AppLanguage) {
        localeStore.set(language)
    }

    fun setAutoSaveSkills(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoSaveSkills(enabled) }
    }

    fun setKeepExecutionScreenshots(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setKeepExecutionScreenshots(enabled) }
    }

    fun setDefaultAgent(id: Long) {
        viewModelScope.launch { agentRepository.setDefault(id) }
    }

    fun deleteAgent(id: Long) {
        viewModelScope.launch { agentRepository.delete(id) }
    }

    fun saveMode(profile: ModeProfile) {
        modeStore.upsert(profile)
    }

    fun deleteMode(id: String) {
        modeStore.delete(id)
    }

    fun restoreModes() {
        modeStore.restoreStocks()
    }

    fun resetMode(id: String) {
        ModeProfiles.stocks.firstOrNull { it.id == id }?.let { modeStore.upsert(it) }
    }

    fun refreshStorage() {
        viewModelScope.launch { storage.value = appStorage.usage() }
    }

    fun clearAttachments(onDone: () -> Unit) {
        viewModelScope.launch {
            appStorage.clearAttachments()
            refreshStorage()
            onDone()
        }
    }

    fun clearScreenshots(onDone: () -> Unit) {
        viewModelScope.launch {
            appStorage.clearScreenshots()
            refreshStorage()
            onDone()
        }
    }

    fun clearConversations(onDone: () -> Unit) {
        viewModelScope.launch {
            appStorage.clearConversations()
            refreshStorage()
            onDone()
        }
    }
}
