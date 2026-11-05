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

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.core.model.ConversationPreview
import com.verlintas.baic2.core.model.resolve
import com.verlintas.baic2.designsystem.Baic2Mono
import com.verlintas.baic2.designsystem.Baic2Motion
import com.verlintas.baic2.designsystem.Baic2Spacing
import com.verlintas.baic2.designsystem.component.Baic2EmptyState
import com.verlintas.baic2.designsystem.component.Baic2ModeChip
import com.verlintas.baic2.designsystem.component.Baic2Skeleton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.verlintas.baic2.feature.agents.AgentWizardScreen
import com.verlintas.baic2.designsystem.component.shimmerBackground

@Composable
fun ConversationsScreen(
    onOpenConversation: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var setupOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.openConversation.collect(onOpenConversation)
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Baic2Spacing.lg,
                end = Baic2Spacing.lg,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 76.dp,
                bottom = 150.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
        ) {
            item(key = "header") {
                ConversationsHeader(state)
            }

            if (!state.loading && !state.hasAgent) {
                item(key = "agent-banner") {
                    AgentBanner(onConfigure = { setupOpen = true })
                }
            }

            when {
                state.loading -> items(4, key = { "skeleton-$it" }) {
                    ConversationSkeleton()
                }

                state.conversations.isEmpty() -> item(key = "empty") {
                    Baic2EmptyState(
                        title = stringResource(R.string.conversations_empty_title),
                        description = stringResource(R.string.conversations_empty_description),
                        icon = Icons.Outlined.MailOutline,
                        action = {
                            Button(
                                onClick = {
                                    if (state.hasAgent) viewModel.createConversation() else setupOpen = true
                                },
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(
                                    stringResource(
                                        if (state.hasAgent) R.string.conversations_start
                                        else R.string.agent_setup_action,
                                    ),
                                )
                            }
                        },
                    )
                }

                else -> items(state.conversations, key = { it.conversation.id }) { preview ->
                    ConversationRow(
                        preview = preview,
                        mode = state.modes.resolve(preview.conversation.mode),
                        onClick = { onOpenConversation(preview.conversation.id) },
                        onDelete = { viewModel.deleteConversation(preview.conversation.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !state.loading && state.conversations.isNotEmpty(),
            enter = scaleIn(animationSpec = Baic2Motion.spatialDefault()) + androidx.compose.animation.fadeIn(),
            exit = scaleOut(animationSpec = Baic2Motion.effectsFast()) + androidx.compose.animation.fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(Baic2Spacing.xl),
        ) {
            NewChatFab(
                onClick = {
                    if (state.hasAgent) viewModel.createConversation() else setupOpen = true
                },
            )
        }
    }

    if (setupOpen) {
        Dialog(
            onDismissRequest = { setupOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            AgentWizardScreen(agentId = null, onClose = { setupOpen = false })
        }
    }
}

@Composable
private fun ConversationsHeader(state: ConversationsUiState) {
    Column {
        Text(
            text = stringResource(R.string.feature_conversations_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (state.hasAgent) {
                stringResource(
                    R.string.conversations_subtitle,
                    state.conversations.size,
                    state.agentName.orEmpty(),
                )
            } else {
                stringResource(R.string.conversations_subtitle_no_agent, state.conversations.size)
            },
            style = Baic2Mono.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AgentBanner(onConfigure: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), shape)
            .padding(horizontal = Baic2Spacing.lg, vertical = Baic2Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(18.dp),
        )
        Spacer(Modifier.width(Baic2Spacing.md))
        Text(
            text = stringResource(R.string.agent_banner_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onConfigure) {
            Text(stringResource(R.string.agent_banner_action))
        }
    }
}

@Composable
private fun ConversationSkeleton() {
    Baic2Skeleton(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp),
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun ConversationRow(
    preview: ConversationPreview,
    mode: com.verlintas.baic2.core.model.ModeProfile,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val conversation = preview.conversation
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = Baic2Motion.spatialFast(),
        label = "conversation-press",
    )
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .padding(horizontal = Baic2Spacing.lg, vertical = Baic2Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.title.ifBlank { stringResource(R.string.conversations_untitled) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (mode.toolsVisible) {
                    Spacer(Modifier.width(Baic2Spacing.sm))
                    Baic2ModeChip(mode = mode, ambient = false)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = preview.lastMessage
                        ?.takeIf { it.isNotBlank() }
                        ?.replace('\n', ' ')
                        ?: stringResource(R.string.conversations_no_messages),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Baic2Spacing.sm))
                Text(
                    text = DateUtils.getRelativeTimeSpanString(
                        conversation.updatedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE,
                    ).toString(),
                    style = Baic2Mono.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.conversations_delete)) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun NewChatFab(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = Baic2Motion.spatialFast(),
        label = "fab-press",
    )
    val haptics = LocalHapticFeedback.current

    ExtendedFloatingActionButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(18.dp),
        interactionSource = interaction,
        modifier = Modifier
            .shadow(10.dp, RoundedCornerShape(18.dp))
            .scale(scale),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.width(20.dp),
            )
        },
        text = {
            Text(
                text = stringResource(R.string.conversations_new),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}
