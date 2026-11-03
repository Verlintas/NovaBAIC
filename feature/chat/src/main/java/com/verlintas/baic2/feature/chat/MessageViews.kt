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

package com.verlintas.baic2.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import com.verlintas.baic2.designsystem.component.pressScale
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.verlintas.baic2.core.model.Attachment
import com.verlintas.baic2.core.model.AttachmentKind
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.core.model.ToolCall
import com.verlintas.baic2.core.model.ToolCallStatus
import com.verlintas.baic2.designsystem.Baic2Mono
import com.verlintas.baic2.designsystem.Baic2Motion
import com.verlintas.baic2.designsystem.Baic2Spacing
import com.verlintas.baic2.designsystem.component.ThinkingOrb
import com.verlintas.baic2.designsystem.component.shimmerTextBrush

@Composable
fun MessageRow(
    message: ChatMessage,
    onCopy: (ChatMessage) -> Unit,
    onToggleStar: (ChatMessage) -> Unit,
    onEdit: (ChatMessage) -> Unit,
    onDelete: (ChatMessage) -> Unit,
    onSpeak: (ChatMessage) -> Unit,
    compactTools: Boolean = false,
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        ChatRole.USER, ChatRole.ASSISTANT -> MessageActionBox(
            message = message,
            onCopy = onCopy,
            onToggleStar = onToggleStar,
            onEdit = onEdit,
            onDelete = onDelete,
            onSpeak = onSpeak,
            modifier = modifier,
        ) { innerModifier ->
            when (message.role) {
                ChatRole.USER -> UserBubble(
                    text = message.content,
                    attachments = message.attachments,
                    modifier = innerModifier,
                )
                else -> AssistantMessage(
                    message = message,
                    compactTools = compactTools,
                    modifier = innerModifier,
                )
            }
        }

        ChatRole.TOOL, ChatRole.SYSTEM -> Unit
    }
}

@Composable
private fun MessageActionBox(
    message: ChatMessage,
    onCopy: (ChatMessage) -> Unit,
    onToggleStar: (ChatMessage) -> Unit,
    onEdit: (ChatMessage) -> Unit,
    onDelete: (ChatMessage) -> Unit,
    onSpeak: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {},
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            ),
    ) {
        content(Modifier)
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (message.content.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_action_copy)) },
                    onClick = {
                        menuOpen = false
                        onCopy(message)
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (message.starred) R.string.chat_action_unstar else R.string.chat_action_star,
                        ),
                    )
                },
                onClick = {
                    menuOpen = false
                    onToggleStar(message)
                },
            )
            if (message.role == ChatRole.ASSISTANT && message.content.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_action_speak)) },
                    onClick = {
                        menuOpen = false
                        onSpeak(message)
                    },
                )
            }
            if (message.role == ChatRole.USER) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_action_edit)) },
                    onClick = {
                        menuOpen = false
                        onEdit(message)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_action_delete)) },
                onClick = {
                    menuOpen = false
                    onDelete(message)
                },
            )
        }
    }
}

@Composable
private fun UserBubble(
    text: String,
    attachments: List<Attachment>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        val shape = RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomStart = 18.dp,
            bottomEnd = 6.dp,
        )
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), shape)
                .padding(horizontal = Baic2Spacing.lg, vertical = Baic2Spacing.md),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
        ) {
            attachments.filter { it.kind == AttachmentKind.IMAGE }.forEach { image ->
                image.localPath?.let { path ->
                    LocalImageThumbnail(
                        path = path,
                        modifier = Modifier
                            .widthIn(max = 260.dp)
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
            if (text.isNotBlank()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            attachments.filter { it.kind == AttachmentKind.TEXT }.forEach { attachment ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(Baic2Spacing.xs))
                    Text(
                        text = attachment.fileName ?: "attachment",
                        style = Baic2Mono.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ChatMessage,
    compactTools: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (message.starred) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = Baic2Spacing.xs),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        message.thinking?.takeIf { it.isNotBlank() }?.let { thinking ->
            ThinkingCard(text = thinking, streaming = false, durationMs = message.thinkingMs)
            Spacer(Modifier.size(Baic2Spacing.sm))
        }
        if (message.content.isNotBlank()) {
            MarkdownMessage(text = message.content)
        }
        if (!compactTools) {
            message.toolCalls.forEach { call ->
                Spacer(Modifier.size(Baic2Spacing.sm))
                ToolCard(call = call)
            }
        }
        message.attachments.filter { it.kind == AttachmentKind.IMAGE }.forEach { image ->
            image.localPath?.let { path ->
                Spacer(Modifier.size(Baic2Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_result_screenshot),
                    style = Baic2Mono.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(Baic2Spacing.xs))
                LocalImageThumbnail(
                    path = path,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
    }
}

@Composable
fun ThinkingCard(
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    durationMs: Long? = null,
) {
    // Thinking stays collapsed by default - the card shows status, the user
    // taps when they actually want to read the reasoning.
    var expanded by rememberSaveable(streaming) { mutableStateOf(false) }
    // Live elapsed seconds while the model is still thinking.
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(streaming) {
        if (!streaming) return@LaunchedEffect
        val startedAt = android.os.SystemClock.elapsedRealtime()
        while (true) {
            elapsedSeconds = ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Baic2Motion.spatialFast(),
        label = "thinking-chevron",
    )
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { expanded = !expanded },
                )
                .padding(horizontal = Baic2Spacing.md, vertical = Baic2Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    streaming -> stringResource(R.string.chat_thinking_streaming, elapsedSeconds)
                    durationMs != null && durationMs > 0 ->
                        stringResource(R.string.chat_thought_for, (durationMs / 1000).coerceAtLeast(1))
                    else -> stringResource(R.string.chat_thinking)
                },
                style = if (streaming) {
                    Baic2Mono.label.copy(brush = shimmerTextBrush())
                } else {
                    Baic2Mono.label
                },
                color = if (streaming) {
                    Color.Unspecified
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (!expanded && !streaming && text.isNotBlank()) {
                Spacer(Modifier.width(Baic2Spacing.md))
                Text(
                    text = text.replace(Regex("\\s+"), " ").trim(),
                    style = Baic2Mono.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (streaming) {
                ThinkingOrb(size = 14.dp)
                Spacer(Modifier.width(Baic2Spacing.sm))
            }
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = Baic2Motion.spatialDefault()) + fadeIn(),
            exit = shrinkVertically(animationSpec = Baic2Motion.effectsFast()) + fadeOut(),
        ) {
            Text(
                text = text.ifBlank { "…" },
                style = Baic2Mono.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = Baic2Spacing.md,
                    end = Baic2Spacing.md,
                    bottom = Baic2Spacing.md,
                ),
            )
        }
    }
}

@Composable
fun ActivityHintLine(
    hint: ActivityHint,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(vertical = Baic2Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThinkingOrb(size = 14.dp)
        Spacer(Modifier.width(Baic2Spacing.sm))
        Text(
            text = activityHintText(hint),
            style = Baic2Mono.label.copy(brush = shimmerTextBrush()),
        )
    }
}

@Composable
private fun activityHintText(hint: ActivityHint): String = when (hint.kind) {
    ActivityHint.Kind.USING_APP -> hint.appLabel?.let {
        stringResource(R.string.chat_activity_using, it)
    } ?: stringResource(R.string.chat_activity_working)
    ActivityHint.Kind.OPENING_APP -> hint.appLabel?.let {
        stringResource(R.string.chat_activity_opening, it)
    } ?: stringResource(R.string.chat_activity_working)
    ActivityHint.Kind.MANAGING_APP -> hint.appLabel?.let {
        stringResource(R.string.chat_activity_managing, it)
    } ?: stringResource(R.string.chat_activity_working)
    ActivityHint.Kind.READING_SCREEN -> stringResource(R.string.chat_activity_screen)
    ActivityHint.Kind.SEARCHING_WEB -> stringResource(R.string.chat_activity_web_search)
    ActivityHint.Kind.READING_WEB -> stringResource(R.string.chat_activity_web_read)
    ActivityHint.Kind.READING_FILES -> stringResource(R.string.chat_activity_files)
    ActivityHint.Kind.WORKING -> stringResource(R.string.chat_activity_working)
}

@Composable
fun ToolSummaryCard(
    calls: List<ToolCall>,
    modifier: Modifier = Modifier,
) {
    val actions = calls.count {
        it.status == ToolCallStatus.DONE || it.status == ToolCallStatus.FAILED
    }
    if (actions == 0) return
    val failures = calls.count { it.status == ToolCallStatus.FAILED }
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(failures) {
        if (failures > 0) expanded = true
    }
    val context = LocalContext.current
    val apps = remember(calls, context) {
        calls.mapNotNull { appLabelOf(context, it) }.distinct()
    }
    val web = calls.any { it.name == "web_search" || it.name == "web_read" }
    val files = calls.any { it.name == "files" || it.name == "file_write" || it.name == "ocr_file" }
    val summary = when {
        apps.isNotEmpty() -> if (apps.size == 1) {
            stringResource(R.string.chat_activity_used_app, apps.first())
        } else {
            stringResource(R.string.chat_activity_used_apps, apps.joinToString(" · "))
        }
        web -> stringResource(R.string.chat_activity_used_web)
        files -> stringResource(R.string.chat_activity_used_files)
        else -> stringResource(R.string.chat_activity_used_screen)
    }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Baic2Motion.spatialFast(),
        label = "activity-chevron",
    )
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { expanded = !expanded },
                )
                .padding(horizontal = Baic2Spacing.md, vertical = Baic2Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary,
                style = Baic2Mono.body,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Baic2Spacing.sm))
            Text(
                text = if (actions == 1) {
                    stringResource(R.string.chat_activity_action, actions)
                } else {
                    stringResource(R.string.chat_activity_actions, actions)
                },
                style = Baic2Mono.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (failures > 0) {
                Spacer(Modifier.width(Baic2Spacing.xs))
                Text(
                    text = stringResource(R.string.chat_activity_failed, failures),
                    style = Baic2Mono.label,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = Baic2Motion.spatialDefault()) + fadeIn(),
            exit = shrinkVertically(animationSpec = Baic2Motion.effectsFast()) + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Baic2Spacing.sm, end = Baic2Spacing.sm, bottom = Baic2Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
            ) {
                calls.forEach { ToolCard(call = it) }
            }
        }
    }
}

private fun appLabelOf(context: android.content.Context, call: ToolCall): String? {
    val args = runCatching { org.json.JSONObject(call.argumentsJson) }.getOrNull() ?: return null
    val raw = listOf("app", "package", "name")
        .firstNotNullOfOrNull { key ->
            args.optString(key).trim().takeIf { it.isNotBlank() }
        } ?: return null
    if (raw.contains('.')) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(raw, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.take(40) }
    }
    val leaf = if (raw.contains('.')) raw.substringAfterLast('.') else raw
    return leaf.replace('_', ' ')
        .take(40)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

@Composable
fun ToolCard(
    call: ToolCall,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(call.status == ToolCallStatus.FAILED) }
    // A failure that happens after first composition still surfaces: reveal it.
    LaunchedEffect(call.status) {
        if (call.status == ToolCallStatus.FAILED) expanded = true
    }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Baic2Motion.spatialFast(),
        label = "tool-chevron",
    )
    val shape = RoundedCornerShape(12.dp)
    val statusColor by animateColorAsState(
        targetValue = statusColor(call.status),
        animationSpec = tween(220),
        label = "tool-status-color",
    )
    val headerInteraction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(headerInteraction, pressedScale = 0.99f)
                .combinedClickable(
                    interactionSource = headerInteraction,
                    indication = null,
                    onClick = { expanded = !expanded },
                )
                .padding(horizontal = Baic2Spacing.md, vertical = Baic2Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status = call.status, color = statusColor)
            Spacer(Modifier.width(Baic2Spacing.sm))
            Text(
                text = call.name,
                style = Baic2Mono.body,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (call.autoConsent) {
                Text(
                    text = stringResource(R.string.chat_tool_auto),
                    style = Baic2Mono.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(Baic2Spacing.xs))
            }
            AnimatedContent(
                targetState = call.status,
                transitionSpec = {
                    fadeIn(tween(durationMillis = 160)) togetherWith fadeOut(tween(durationMillis = 120))
                },
                label = "tool-status",
            ) { status ->
                Text(
                    text = statusLabel(status),
                    style = Baic2Mono.label,
                    color = statusColor,
                )
            }
            Spacer(Modifier.width(Baic2Spacing.sm))
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = Baic2Motion.spatialDefault()) + fadeIn(),
            exit = shrinkVertically(animationSpec = Baic2Motion.effectsFast()) + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Baic2Spacing.md, end = Baic2Spacing.md, bottom = Baic2Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
            ) {
                if (call.argumentsJson.isNotBlank() && call.argumentsJson != "{}") {
                    CodeBlock(language = "args", code = call.argumentsJson)
                }
                call.result?.takeIf { it.isNotBlank() }?.let { result ->
                    CodeBlock(
                        language = if (call.status == ToolCallStatus.DONE) "result" else "error",
                        code = result,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(
    status: ToolCallStatus,
    color: Color,
) {
    val pulsing = status == ToolCallStatus.RUNNING || status == ToolCallStatus.PENDING
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "tool-pulse")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "tool-pulse-alpha",
        )
        value
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun statusColor(status: ToolCallStatus): Color = when (status) {
    ToolCallStatus.PENDING, ToolCallStatus.RUNNING -> MaterialTheme.colorScheme.primary
    ToolCallStatus.DONE -> MaterialTheme.colorScheme.tertiary
    ToolCallStatus.FAILED -> MaterialTheme.colorScheme.error
    ToolCallStatus.DENIED, ToolCallStatus.REJECTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun statusLabel(status: ToolCallStatus): String = stringResource(
    when (status) {
        ToolCallStatus.PENDING -> R.string.chat_tool_pending
        ToolCallStatus.RUNNING -> R.string.chat_tool_running
        ToolCallStatus.DONE -> R.string.chat_tool_done
        ToolCallStatus.FAILED -> R.string.chat_tool_failed
        ToolCallStatus.DENIED -> R.string.chat_tool_denied
        ToolCallStatus.REJECTED -> R.string.chat_tool_rejected
    },
)
