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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.core.model.ModeProfile
import com.verlintas.baic2.core.model.RunBudget
import com.verlintas.baic2.core.model.ToolAccess
import com.verlintas.baic2.designsystem.Baic2Mono
import com.verlintas.baic2.designsystem.Baic2Spacing
import com.verlintas.baic2.designsystem.component.modeProfileLabel

// ---------------------------------------------------------------- modes

/**
 * Mode manager: every mode, stock or custom, can be edited or removed here.
 * A mode defines tool access, per-call confirmation, the run budget, the
 * long-task protocol and its own extra instructions.
 */
@Composable
fun ModeProfilesPage(
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val modes by viewModel.modes.collectAsStateWithLifecycle()
    val tasks by viewModel.scheduledTasks.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<ModeProfile?>(null) }

    SettingsPage(
        title = stringResource(R.string.settings_modes_title),
        onBack = onBack,
    ) {
        item(key = "add") {
            OutlinedButton(
                onClick = { onEdit(null) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Baic2Spacing.sm))
                Text(stringResource(R.string.settings_modes_add))
            }
        }
        items(modes.size, key = { modes[it].id }) { index ->
            val mode = modes[index]
            ModeRow(
                mode = mode,
                deletable = modes.size > 1,
                onEdit = { onEdit(mode.id) },
                onDelete = { deleteTarget = mode },
            )
        }
        item(key = "restore") {
            OutlinedButton(
                onClick = { viewModel.restoreModes() },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_modes_restore))
            }
        }
        item(key = "hint") {
            Text(
                text = stringResource(R.string.settings_modes_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = Baic2Spacing.xs,
                    top = Baic2Spacing.md,
                ),
            )
        }
    }

    deleteTarget?.let { mode ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.settings_mode_delete)) },
            text = {
                Text(stringResource(R.string.settings_mode_delete_confirm))
                if (tasks.any { it.mode == mode.id }) {
                    Spacer(Modifier.height(Baic2Spacing.sm))
                    Text(stringResource(R.string.settings_mode_delete_confirm_tasks))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteMode(mode.id)
                        deleteTarget = null
                    },
                ) {
                    Text(stringResource(R.string.settings_mode_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun ModeRow(
    mode: ModeProfile,
    deletable: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    SettingsRow(
        title = modeProfileLabel(mode),
        summary = modeSummary(mode),
        onClick = onEdit,
        showChevron = true,
        trailing = {
            IconButton(onClick = onDelete, enabled = deletable) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.settings_mode_delete),
                    tint = if (deletable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                )
            }
        },
    )
}

@Composable
private fun modeSummary(mode: ModeProfile): String {
    val rights = stringResource(
        when (mode.toolAccess) {
            ToolAccess.NONE -> R.string.settings_mode_rights_none
            ToolAccess.READ_ONLY -> R.string.settings_mode_rights_read
            ToolAccess.FULL -> R.string.settings_mode_rights_full
        },
    )
    val parts = mutableListOf(rights)
    if (mode.toolAccess == ToolAccess.FULL) {
        parts += stringResource(
            if (mode.requiresConfirmation) {
                R.string.settings_mode_confirm_short
            } else {
                R.string.settings_mode_confirm_off_short
            },
        )
    }
    if (mode.isTask) {
        parts += stringResource(R.string.settings_mode_task_short)
    }
    if (mode.fullConsent) {
        parts += stringResource(R.string.settings_mode_full_consent_short)
    }
    if (!mode.prompt.isNullOrBlank()) {
        parts += stringResource(R.string.settings_mode_prompt_short)
    }
    parts += stringResource(
        R.string.settings_mode_budget_summary,
        mode.budget.maxRounds,
        mode.budget.maxToolCalls,
        mode.budget.maxWallClockMs / 60_000L,
    )
    return parts.joinToString(" · ")
}

// ---------------------------------------------------------------- editor

/**
 * Full-screen mode editor. Covers every mode knob the engine supports:
 * tool access, per-call confirmation, the long-task protocol, per-mode
 * instructions and the run budget. Stock modes can be reset or deleted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModeEditorPage(
    modeId: String?,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val modes by viewModel.modes.collectAsStateWithLifecycle()
    val tasks by viewModel.scheduledTasks.collectAsStateWithLifecycle()
    val existing = modes.firstOrNull { it.id == modeId }
    val isCustom = existing?.stock != true
    val stateKey = modeId ?: "new"

    var name by rememberSaveable(stateKey) { mutableStateOf(existing?.name.orEmpty()) }
    var toolAccess by rememberSaveable(stateKey) {
        mutableStateOf((existing?.toolAccess ?: ToolAccess.FULL).name)
    }
    var confirm by rememberSaveable(stateKey) {
        // A legacy profile may carry both switches; full consent wins, so the
        // editor opens it with per-call confirmation already off.
        mutableStateOf((existing?.requiresConfirmation ?: true) && existing?.fullConsent != true)
    }
    var taskMode by rememberSaveable(stateKey) {
        mutableStateOf(existing?.isTask ?: false)
    }
    var fullConsent by rememberSaveable(stateKey) {
        mutableStateOf(existing?.fullConsent ?: false)
    }
    var prompt by rememberSaveable(stateKey) { mutableStateOf(existing?.prompt.orEmpty()) }
    var rounds by rememberSaveable(stateKey) {
        mutableIntStateOf(existing?.budget?.maxRounds ?: 12)
    }
    var calls by rememberSaveable(stateKey) {
        mutableIntStateOf(existing?.budget?.maxToolCalls ?: 32)
    }
    var minutes by rememberSaveable(stateKey) {
        mutableIntStateOf(((existing?.budget?.maxWallClockMs ?: 600_000L) / 60_000L).toInt())
    }
    var deleteAsk by rememberSaveable(stateKey) { mutableStateOf(false) }
    var resetAsk by rememberSaveable(stateKey) { mutableStateOf(false) }

    val access = ToolAccess.valueOf(toolAccess)
    val nameError = isCustom && name.isBlank()

    fun save() {
        if (nameError) return
        viewModel.saveMode(
            ModeProfile(
                id = existing?.id ?: ModeProfile.newId(),
                name = name.trim().ifBlank { null },
                toolAccess = access,
                // Confirmation only makes sense with full tool access, and
                // full consent is meaningless while every call is confirmed.
                requiresConfirmation = confirm && !fullConsent && access == ToolAccess.FULL,
                budget = RunBudget(
                    maxRounds = rounds,
                    maxToolCalls = calls,
                    maxWallClockMs = minutes * 60_000L,
                ),
                stock = existing?.stock ?: false,
                // Long tasks drive plan_update, which needs tool access.
                taskMode = taskMode && access != ToolAccess.NONE,
                prompt = prompt.trim().ifBlank { null },
                // Full consent only makes sense with full tool access.
                fullConsent = fullConsent && access == ToolAccess.FULL,
            ),
        )
        onBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = Baic2Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(
                    if (existing == null) {
                        R.string.settings_mode_new_title
                    } else {
                        R.string.settings_mode_edit_title
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            TextButton(onClick = ::save, enabled = !nameError) {
                Text(stringResource(R.string.settings_mode_save))
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Baic2Spacing.lg,
                end = Baic2Spacing.lg,
                top = Baic2Spacing.sm,
                bottom = Baic2Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
        ) {
            item(key = "name") {
                SettingsSectionLabel(stringResource(R.string.settings_mode_name))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = nameError,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(stringResource(R.string.settings_mode_name)) },
                    supportingText = {
                        Text(
                            if (nameError) {
                                stringResource(R.string.settings_mode_name_error)
                            } else {
                                stringResource(R.string.settings_mode_name_hint)
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item(key = "rights") {
                SettingsSectionLabel(stringResource(R.string.settings_mode_rights))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Baic2Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Baic2Spacing.xs),
                ) {
                    listOf(
                        ToolAccess.NONE to R.string.settings_mode_rights_none,
                        ToolAccess.READ_ONLY to R.string.settings_mode_rights_read,
                        ToolAccess.FULL to R.string.settings_mode_rights_full,
                    ).forEach { (accessValue, labelRes) ->
                        FilterChip(
                            selected = access == accessValue,
                            onClick = { toolAccess = accessValue.name },
                            shape = RoundedCornerShape(10.dp),
                            label = { Text(stringResource(labelRes)) },
                        )
                    }
                }
            }

            item(key = "behavior") {
                SettingsSectionLabel(stringResource(R.string.settings_mode_behavior))
                SettingsCard {
                    Column(modifier = Modifier.padding(Baic2Spacing.lg)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.settings_mode_confirm),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (access == ToolAccess.FULL) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    },
                                )
                                if (access != ToolAccess.FULL) {
                                    Text(
                                        text = stringResource(R.string.settings_mode_confirm_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = confirm && access == ToolAccess.FULL,
                                enabled = access == ToolAccess.FULL,
                                onCheckedChange = { confirm = it },
                            )
                        }
                        Spacer(Modifier.height(Baic2Spacing.md))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.settings_mode_task_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (access != ToolAccess.NONE) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    },
                                )
                                Text(
                                    text = stringResource(R.string.settings_mode_task_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = taskMode && access != ToolAccess.NONE,
                                enabled = access != ToolAccess.NONE,
                                onCheckedChange = { taskMode = it },
                            )
                        }
                        Spacer(Modifier.height(Baic2Spacing.md))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.settings_mode_full_consent),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (access == ToolAccess.FULL) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    },
                                )
                                Text(
                                    text = stringResource(R.string.settings_mode_full_consent_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = fullConsent && access == ToolAccess.FULL,
                                enabled = access == ToolAccess.FULL,
                                onCheckedChange = {
                                    fullConsent = it
                                    // Per-call confirmation would ask anyway,
                                    // so full consent switches it off.
                                    if (it) confirm = false
                                },
                            )
                        }
                    }
                }
            }

            item(key = "prompt") {
                SettingsSectionLabel(stringResource(R.string.settings_mode_prompt_title))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(stringResource(R.string.settings_mode_prompt_title)) },
                    supportingText = { Text(stringResource(R.string.settings_mode_prompt_hint)) },
                    placeholder = { Text(stringResource(R.string.settings_mode_prompt_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item(key = "budget") {
                // Built inside this item so slider ticks recompose only it,
                // not the whole editor page.
                val budget = RunBudget(
                    maxRounds = rounds,
                    maxToolCalls = calls,
                    maxWallClockMs = minutes * 60_000L,
                )
                SettingsSectionLabel(stringResource(R.string.settings_mode_budget))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Baic2Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Baic2Spacing.xs),
                ) {
                    listOf(
                        R.string.settings_mode_preset_light to RunBudget(
                            maxRounds = 5,
                            maxToolCalls = 10,
                            maxWallClockMs = 300_000L,
                        ),
                        R.string.settings_mode_preset_standard to RunBudget(
                            maxRounds = 12,
                            maxToolCalls = 32,
                            maxWallClockMs = 600_000L,
                        ),
                        R.string.settings_mode_preset_long to RunBudget(
                            maxRounds = 30,
                            maxToolCalls = 80,
                            maxWallClockMs = 1_800_000L,
                        ),
                        R.string.settings_mode_preset_max to RunBudget(
                            maxRounds = 60,
                            maxToolCalls = 160,
                            maxWallClockMs = 3_600_000L,
                        ),
                    ).forEach { (labelRes, preset) ->
                        FilterChip(
                            selected = budget == preset,
                            onClick = {
                                rounds = preset.maxRounds
                                calls = preset.maxToolCalls
                                minutes = (preset.maxWallClockMs / 60_000L).toInt()
                            },
                            shape = RoundedCornerShape(10.dp),
                            label = { Text(stringResource(labelRes)) },
                        )
                    }
                }
                Spacer(Modifier.height(Baic2Spacing.sm))
                BudgetSliderRow(
                    label = stringResource(R.string.settings_mode_rounds),
                    value = rounds,
                    range = 1..200,
                    onChange = { rounds = it },
                )
                BudgetSliderRow(
                    label = stringResource(R.string.settings_mode_calls),
                    value = calls,
                    range = 1..500,
                    onChange = { calls = it },
                )
                BudgetSliderRow(
                    label = stringResource(R.string.settings_mode_minutes),
                    value = minutes,
                    range = 1..240,
                    onChange = { minutes = it },
                )
            }

            item(key = "actions") {
                SettingsSectionLabel(stringResource(R.string.settings_mode_actions))
                OutlinedButton(
                    onClick = { resetAsk = true },
                    enabled = existing?.stock == true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_mode_reset))
                }
                Spacer(Modifier.height(Baic2Spacing.sm))
                OutlinedButton(
                    onClick = { deleteAsk = true },
                    enabled = existing != null && modes.size > 1,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Baic2Spacing.sm))
                    Text(
                        text = stringResource(R.string.settings_mode_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (existing != null && modes.size <= 1) {
                    Text(
                        text = stringResource(R.string.settings_mode_delete_last),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Baic2Spacing.xs),
                    )
                }
            }
        }
    }

    if (deleteAsk && existing != null) {
        AlertDialog(
            onDismissRequest = { deleteAsk = false },
            title = { Text(stringResource(R.string.settings_mode_delete)) },
            text = {
                Text(stringResource(R.string.settings_mode_delete_confirm))
                if (tasks.any { it.mode == existing.id }) {
                    Spacer(Modifier.height(Baic2Spacing.sm))
                    Text(stringResource(R.string.settings_mode_delete_confirm_tasks))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteAsk = false
                        viewModel.deleteMode(existing.id)
                        onBack()
                    },
                ) {
                    Text(stringResource(R.string.settings_mode_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteAsk = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    if (resetAsk && existing?.stock == true) {
        AlertDialog(
            onDismissRequest = { resetAsk = false },
            title = { Text(stringResource(R.string.settings_mode_reset)) },
            text = { Text(stringResource(R.string.settings_mode_reset_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetAsk = false
                        viewModel.resetMode(existing.id)
                        onBack()
                    },
                ) {
                    Text(stringResource(R.string.settings_mode_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { resetAsk = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun BudgetSliderRow(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value.toString(),
                style = Baic2Mono.body,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(64.dp),
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
