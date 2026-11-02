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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
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
 * A mode defines tool access, per-call confirmation and the run budget.
 */
@Composable
fun ModeProfilesPage(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val modes by viewModel.modes.collectAsStateWithLifecycle()
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorMode by remember { mutableStateOf<ModeProfile?>(null) }
    var deleteTarget by remember { mutableStateOf<ModeProfile?>(null) }

    SettingsPage(
        title = stringResource(R.string.settings_modes_title),
        onBack = onBack,
    ) {
        item(key = "add") {
            OutlinedButton(
                onClick = {
                    editorMode = null
                    editorOpen = true
                },
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
                onEdit = {
                    editorMode = mode
                    editorOpen = true
                },
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

    if (editorOpen) {
        ModeEditorDialog(
            initial = editorMode,
            onDismiss = { editorOpen = false },
            onSave = { profile ->
                viewModel.saveMode(profile)
                editorOpen = false
            },
        )
    }

    deleteTarget?.let { mode ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.settings_mode_delete)) },
            text = { Text(stringResource(R.string.settings_mode_delete_confirm)) },
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
    parts += stringResource(
        R.string.settings_mode_budget_summary,
        mode.budget.maxRounds,
        mode.budget.maxToolCalls,
        mode.budget.maxWallClockMs / 60_000L,
    )
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeEditorDialog(
    initial: ModeProfile?,
    onDismiss: () -> Unit,
    onSave: (ModeProfile) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var toolAccess by rememberSaveable { mutableStateOf((initial?.toolAccess ?: ToolAccess.FULL).name) }
    var confirm by rememberSaveable { mutableStateOf(initial?.requiresConfirmation ?: true) }
    var rounds by rememberSaveable { mutableIntStateOf(initial?.budget?.maxRounds ?: 12) }
    var calls by rememberSaveable { mutableIntStateOf(initial?.budget?.maxToolCalls ?: 32) }
    var minutes by rememberSaveable {
        mutableIntStateOf(((initial?.budget?.maxWallClockMs ?: 600_000L) / 60_000L).toInt())
    }

    val access = ToolAccess.valueOf(toolAccess)
    val currentBudget = RunBudget(
        maxRounds = rounds,
        maxToolCalls = calls,
        maxWallClockMs = minutes * 60_000L,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) {
                        R.string.settings_mode_new_title
                    } else {
                        R.string.settings_mode_edit_title
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(stringResource(R.string.settings_mode_name)) },
                    supportingText = { Text(stringResource(R.string.settings_mode_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.settings_mode_rights),
                    style = Baic2Mono.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_mode_confirm),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (access == ToolAccess.FULL) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = confirm && access == ToolAccess.FULL,
                        enabled = access == ToolAccess.FULL,
                        onCheckedChange = { confirm = it },
                    )
                }
                if (access != ToolAccess.FULL) {
                    Text(
                        text = stringResource(R.string.settings_mode_confirm_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = stringResource(R.string.settings_mode_budget),
                    style = Baic2Mono.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    ).forEach { (labelRes, budget) ->
                        FilterChip(
                            selected = currentBudget == budget,
                            onClick = {
                                rounds = budget.maxRounds
                                calls = budget.maxToolCalls
                                minutes = (budget.maxWallClockMs / 60_000L).toInt()
                            },
                            shape = RoundedCornerShape(10.dp),
                            label = { Text(stringResource(labelRes)) },
                        )
                    }
                }

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
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        ModeProfile(
                            id = initial?.id ?: ModeProfile.newId(),
                            name = name.trim().ifBlank { null },
                            toolAccess = access,
                            // Confirmation is only meaningful with full tool access.
                            requiresConfirmation = confirm && access == ToolAccess.FULL,
                            budget = currentBudget,
                            stock = initial?.stock ?: false,
                        ),
                    )
                },
            ) {
                Text(stringResource(R.string.settings_mode_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
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
