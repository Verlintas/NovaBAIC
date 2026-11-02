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

package com.verlintas.baic2.feature.agents

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verlintas.baic2.core.model.ModelCatalog
import com.verlintas.baic2.core.model.ProviderError
import com.verlintas.baic2.designsystem.Baic2Mono
import com.verlintas.baic2.designsystem.Baic2Motion
import com.verlintas.baic2.designsystem.Baic2Spacing
import kotlinx.coroutines.delay

/**
 * Four-step agent setup with key-prefix automation and automatic model
 * discovery: Provider -> Connection -> Tuning -> Prompt.
 */
@Composable
fun AgentWizardScreen(
    agentId: Long?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(agentId) {
        if (agentId == null) viewModel.startForNew() else viewModel.startForEdit(agentId)
    }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            delay(900)
            onClose()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsTopPadding()
                .imePadding(),
        ) {
            WizardTopBar(
                editing = state.editingId != 0L,
                step = state.step,
                onClose = onClose,
            )
            StepIndicator(step = state.step)

            AnimatedContent(
                targetState = state.step,
                transitionSpec = { stepTransition(targetState.ordinal > initialState.ordinal) },
                label = "wizard-step",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { step ->
                when (step) {
                    WizardStep.PROVIDER -> ProviderStep(state, viewModel)
                    WizardStep.CONNECTION -> ConnectionStep(state, viewModel)
                    WizardStep.TUNING -> TuningStep(state, viewModel)
                    WizardStep.PROMPT -> PromptStep(state, viewModel)
                }
            }

            WizardBottomBar(state = state, viewModel = viewModel)
        }

        if (state.saved) {
            SuccessOverlay()
        }
    }
}

private fun stepTransition(forward: Boolean) =
    (slideInHorizontally(animationSpec = spring(stiffness = 380f)) { if (forward) it / 6 else -it / 6 } +
        fadeIn(tween(200)))
        .togetherWith(
            slideOutHorizontally(animationSpec = spring(stiffness = 380f)) { if (forward) -it / 6 else it / 6 } +
                fadeOut(tween(140)),
        )

@Composable
private fun Modifier.windowInsetsTopPadding(): Modifier =
    this.padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())

@Composable
private fun WizardTopBar(
    editing: Boolean,
    step: WizardStep,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Baic2Spacing.sm, vertical = Baic2Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.wizard_close),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    if (editing) R.string.wizard_title_edit else R.string.wizard_title_new,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(step.titleRes()),
                style = Baic2Mono.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun WizardStep.titleRes(): Int = when (this) {
    WizardStep.PROVIDER -> R.string.wizard_step_provider
    WizardStep.CONNECTION -> R.string.wizard_step_connection
    WizardStep.TUNING -> R.string.wizard_step_tuning
    WizardStep.PROMPT -> R.string.wizard_step_prompt
}

@Composable
private fun StepIndicator(step: WizardStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Baic2Spacing.xl, vertical = Baic2Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Baic2Spacing.xs),
    ) {
        WizardStep.entries.forEach { entry ->
            val active = entry.ordinal <= step.ordinal
            Box(
                modifier = Modifier
                    .weight(if (entry == step) 2f else 1f)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun ProviderStep(state: AgentWizardUiState, viewModel: AgentWizardViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Baic2Spacing.xl,
            end = Baic2Spacing.xl,
            top = Baic2Spacing.md,
            bottom = Baic2Spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
    ) {
        item {
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::updateApiKey,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                label = { Text(stringResource(R.string.agent_setup_api_key)) },
                placeholder = { Text("sk-…", style = Baic2Mono.body) },
                visualTransformation = PasswordVisualTransformation(),
                textStyle = Baic2Mono.body,
                supportingText = {
                    if (state.detectedByKey) {
                        Text(
                            text = stringResource(R.string.wizard_detected, presetLabel(state.preset)),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(stringResource(R.string.wizard_key_hint))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                text = stringResource(R.string.wizard_or_pick),
                style = Baic2Mono.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Baic2Spacing.md),
            )
        }
        items(AgentPreset.entries) { preset ->
            PresetRow(
                preset = preset,
                selected = state.preset == preset,
                onClick = { viewModel.selectPreset(preset) },
            )
        }
        item {
            Spacer(Modifier.height(Baic2Spacing.sm))
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::updateBaseUrl,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                label = { Text(stringResource(R.string.agent_setup_base_url)) },
                textStyle = Baic2Mono.body,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.error?.let { error ->
            item { WizardErrorCard(setupErrorText(error)) }
        }
    }
}

@Composable
private fun PresetRow(
    preset: AgentPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            )
            .border(
                width = 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                },
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Baic2Spacing.lg, vertical = Baic2Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = presetLabel(preset),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (preset.baseUrl.isNotBlank()) {
                Text(
                    text = preset.baseUrl,
                    style = Baic2Mono.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ConnectionStep(state: AgentWizardUiState, viewModel: AgentWizardViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Baic2Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
    ) {
        ConnectionStatusCard(state = state, onRetry = viewModel::fetchModels)

        val recommended = remember(state.preset) {
            state.preset.family?.let { family ->
                ModelCatalog.modelsFor(state.preset.provider, family)
            }.orEmpty()
        }

        val apiModels = state.models
        var modelFilter by rememberSaveable { mutableStateOf("") }
        val visibleModels = remember(apiModels, modelFilter) {
            if (modelFilter.isBlank()) {
                apiModels
            } else {
                apiModels.filter { it.contains(modelFilter.trim(), ignoreCase = true) }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Baic2Spacing.sm),
        ) {
            Text(
                text = if (apiModels.isEmpty() && state.modelsLoading) {
                    stringResource(R.string.wizard_models_loading)
                } else {
                    stringResource(R.string.wizard_models_from_api, apiModels.size)
                },
                style = Baic2Mono.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state.modelsLoading) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(Baic2Spacing.sm))
            }
            IconButton(onClick = viewModel::fetchModels) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.wizard_models_retry),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (apiModels.size > 10) {
            OutlinedTextField(
                value = modelFilter,
                onValueChange = { modelFilter = it },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text(stringResource(R.string.wizard_models_search)) },
                textStyle = Baic2Mono.body,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (visibleModels.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Baic2Spacing.xs)) {
                visibleModels.take(40).forEach { model ->
                    val selected = state.model.equals(model, ignoreCase = true)
                    val shape = RoundedCornerShape(10.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                            )
                            .border(
                                1.dp,
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                },
                                shape,
                            )
                            .clickable { viewModel.updateModel(model) }
                            .padding(horizontal = Baic2Spacing.md, vertical = Baic2Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = model,
                            style = Baic2Mono.body,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                if (visibleModels.size > 40) {
                    Text(
                        text = stringResource(R.string.wizard_models_more, visibleModels.size - 40),
                        style = Baic2Mono.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (apiModels.isNotEmpty() && visibleModels.isEmpty()) {
            Text(
                text = stringResource(R.string.wizard_models_no_match),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (apiModels.isEmpty() && !state.modelsLoading && recommended.isNotEmpty()) {
            Text(
                text = stringResource(R.string.wizard_models_recommended),
                style = Baic2Mono.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Baic2Spacing.xs),
            )
            Column(verticalArrangement = Arrangement.spacedBy(Baic2Spacing.xs)) {
                recommended.forEach { entry ->
                    val selected = state.model == entry.id
                    val shape = RoundedCornerShape(10.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                            )
                            .border(
                                1.dp,
                                if (selected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                },
                                shape,
                            )
                            .clickable { viewModel.pickCatalogModel(entry) }
                            .padding(horizontal = Baic2Spacing.md, vertical = Baic2Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = entry.id,
                                style = Baic2Mono.label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = formatContextWindow(entry.contextWindow),
                            style = Baic2Mono.label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (selected) {
                            Spacer(Modifier.width(Baic2Spacing.sm))
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = state.model,
            onValueChange = viewModel::updateModel,
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            label = { Text(stringResource(R.string.wizard_model_manual)) },
            textStyle = Baic2Mono.body,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Baic2Spacing.sm),
        )

        state.error?.let { error ->
            WizardErrorCard(setupErrorText(error))
        }
        Spacer(Modifier.height(Baic2Spacing.xl))
    }
}

@Composable
private fun ConnectionStatusCard(
    state: AgentWizardUiState,
    onRetry: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(Baic2Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            state.modelsLoading -> {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(Baic2Spacing.md))
                Text(
                    text = stringResource(R.string.wizard_models_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            state.modelsError != null -> {
                val missingKey = state.modelsError == "missing_credentials"
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Baic2Spacing.md))
                Text(
                    text = if (missingKey) {
                        stringResource(R.string.wizard_models_need_key)
                    } else {
                        stringResource(R.string.wizard_models_error, state.modelsError)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (!missingKey) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.wizard_retry))
                    }
                }
            }

            state.models.isNotEmpty() -> {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Baic2Spacing.md))
                Text(
                    text = stringResource(R.string.wizard_models_found, state.models.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.wizard_retry))
                }
            }

            else -> {
                Text(
                    text = stringResource(R.string.wizard_models_idle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.wizard_retry))
                }
            }
        }
    }
}

@Composable
private fun TuningStep(state: AgentWizardUiState, viewModel: AgentWizardViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Baic2Spacing.xl),
    ) {
        Text(
            text = stringResource(R.string.agent_setup_name),
            style = Baic2Mono.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::updateName,
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Baic2Spacing.xs),
        )

        Spacer(Modifier.height(Baic2Spacing.lg))
        Text(
            text = stringResource(R.string.wizard_temperature, (state.temperature * 100).toInt()),
            style = Baic2Mono.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = state.temperature,
            onValueChange = viewModel::updateTemperature,
            valueRange = 0f..2f,
            steps = 19,
        )

        Spacer(Modifier.height(Baic2Spacing.sm))
        Text(
            text = stringResource(R.string.wizard_max_tokens),
            style = Baic2Mono.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.maxTokens?.toString().orEmpty(),
            onValueChange = viewModel::updateMaxTokens,
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            placeholder = { Text("4096", style = Baic2Mono.body) },
            textStyle = Baic2Mono.body,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Baic2Spacing.xs),
        )

        Spacer(Modifier.height(Baic2Spacing.lg))
        val switchShape = RoundedCornerShape(14.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(switchShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), switchShape)
                .padding(horizontal = Baic2Spacing.lg, vertical = Baic2Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.wizard_reasoning),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.wizard_reasoning_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.reasoning, onCheckedChange = viewModel::updateReasoning)
        }
        Spacer(Modifier.height(Baic2Spacing.xl))
    }
}

@Composable
private fun PromptStep(state: AgentWizardUiState, viewModel: AgentWizardViewModel) {
    val templates = listOf(
        stringResource(R.string.wizard_prompt_tpl_general),
        stringResource(R.string.wizard_prompt_tpl_translate),
        stringResource(R.string.wizard_prompt_tpl_code),
        stringResource(R.string.wizard_prompt_tpl_concise),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Baic2Spacing.xl),
    ) {
        Text(
            text = stringResource(R.string.wizard_prompt_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Baic2Spacing.sm))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Baic2Spacing.sm)) {
            items(templates) { template ->
                FilterChip(
                    selected = state.systemPrompt == template,
                    onClick = { viewModel.updateSystemPrompt(template) },
                    shape = RoundedCornerShape(10.dp),
                    label = { Text(template.take(10)) },
                )
            }
        }
        OutlinedTextField(
            value = state.systemPrompt,
            onValueChange = viewModel::updateSystemPrompt,
            shape = RoundedCornerShape(12.dp),
            placeholder = { Text(stringResource(R.string.agent_setup_system_prompt_hint)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp)
                .padding(top = Baic2Spacing.md),
        )
        Spacer(Modifier.height(Baic2Spacing.xl))
    }
}

@Composable
private fun WizardBottomBar(
    state: AgentWizardUiState,
    viewModel: AgentWizardViewModel,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Baic2Spacing.xl, vertical = Baic2Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
    ) {
        if (state.step != WizardStep.PROVIDER) {
            OutlinedButton(
                onClick = viewModel::back,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.wizard_back))
            }
        }
        Button(
            onClick = viewModel::next,
            enabled = !state.saving,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(2f),
        ) {
            if (state.saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    text = stringResource(
                        if (state.step == WizardStep.PROMPT) {
                            R.string.agent_setup_save
                        } else {
                            R.string.wizard_next
                        },
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SuccessOverlay() {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 300f),
        label = "wizard-success",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(36.dp)
                        .animateScale(scale),
                )
            }
            Spacer(Modifier.height(Baic2Spacing.lg))
            Text(
                text = stringResource(R.string.wizard_saved),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Modifier.animateScale(scale: Float): Modifier =
    this.then(Modifier.size((36 * scale).dp))

@Composable
private fun WizardErrorCard(text: String) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f), shape)
            .padding(Baic2Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Baic2Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun presetLabel(preset: AgentPreset): String = stringResource(
    when (preset) {
        AgentPreset.DEEPSEEK -> R.string.preset_deepseek
        AgentPreset.OPENAI -> R.string.preset_openai
        AgentPreset.SILICONFLOW -> R.string.preset_siliconflow
        AgentPreset.MOONSHOT -> R.string.preset_moonshot
        AgentPreset.QWEN -> R.string.preset_qwen
        AgentPreset.CLAUDE -> R.string.preset_claude
        AgentPreset.GEMINI -> R.string.preset_gemini
        AgentPreset.CUSTOM -> R.string.preset_custom
    },
)

@Composable
internal fun setupErrorText(error: SetupError): String = when (error) {
    SetupError.MissingKey -> stringResource(R.string.agent_setup_error_key)
    SetupError.MissingUrl -> stringResource(R.string.agent_setup_error_url)
    SetupError.InsecureUrl -> stringResource(R.string.agent_setup_error_insecure_url)
    SetupError.MissingModel -> stringResource(R.string.agent_setup_error_model)
    SetupError.KeyStore -> stringResource(R.string.agent_setup_error_keystore)
    is SetupError.Request -> when (error.kind) {
        ProviderError.Kind.AUTH -> stringResource(R.string.agent_setup_error_auth)
        ProviderError.Kind.NETWORK, ProviderError.Kind.TIMEOUT ->
            stringResource(R.string.agent_setup_error_network)
        ProviderError.Kind.RATE_LIMIT -> stringResource(R.string.agent_setup_error_rate_limit)
        else -> stringResource(R.string.agent_setup_error_generic, error.detail)
    }
}

private fun formatContextWindow(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
    tokens >= 1_000 -> "${tokens / 1_000}K"
    else -> tokens.toString()
}
