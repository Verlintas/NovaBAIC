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

package com.verlintas.baic2.feature.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verlintas.baic2.core.data.prefs.ModeProfileStore
import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.data.repository.ConversationRepository
import com.verlintas.baic2.core.model.Conversation
import com.verlintas.baic2.core.model.ConversationPreview
import com.verlintas.baic2.core.model.ModeProfile
import com.verlintas.baic2.core.model.ModeProfiles
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationsUiState(
    val loading: Boolean = true,
    val conversations: List<ConversationPreview> = emptyList(),
    val modes: List<ModeProfile> = emptyList(),
    val agentName: String? = null,
    val hasAgent: Boolean = false,
)

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val agentRepository: AgentRepository,
    private val modeStore: ModeProfileStore,
) : ViewModel() {

    val uiState: StateFlow<ConversationsUiState> = combine(
        conversationRepository.observeConversations(),
        agentRepository.observeDefaultAgent(),
        modeStore.profiles,
    ) { conversations, agent, modes ->
        ConversationsUiState(
            loading = false,
            conversations = conversations,
            modes = modes,
            agentName = agent?.name,
            hasAgent = agent != null,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ConversationsUiState(loading = true),
    )

    private val _openConversation = MutableSharedFlow<Long>()
    val openConversation: SharedFlow<Long> = _openConversation

    fun createConversation() {
        viewModelScope.launch {
            val id = conversationRepository.create(
                agentId = null,
                mode = ModeProfiles.CHAT.id,
                title = "",
            )
            _openConversation.emit(id)
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch { conversationRepository.delete(id) }
    }
}
