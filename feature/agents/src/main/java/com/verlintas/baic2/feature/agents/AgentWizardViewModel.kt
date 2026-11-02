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

import com.verlintas.baic2.core.model.NetworkEndpointPolicy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.model.Agent
import com.verlintas.baic2.core.model.ModelCatalog
import com.verlintas.baic2.core.model.ModelEntry
import com.verlintas.baic2.core.model.ProviderConfig
import com.verlintas.baic2.core.model.ProviderError
import com.verlintas.baic2.core.model.ProviderId
import com.verlintas.baic2.core.network.provider.ProviderException
import com.verlintas.baic2.core.network.provider.ProviderFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A provider service preset; labels live in the UI layer. */
enum class AgentPreset(
    val id: String,
    val provider: ProviderId,
    val baseUrl: String,
    val model: String,
    val defaultName: String,
    val family: String?,
) {
    DEEPSEEK(
        "deepseek",
        ProviderId.OPENAI_COMPATIBLE,
        "https://api.deepseek.com/v1",
        "deepseek-flash",
        "DeepSeek",
        "deepseek",
    ),
    OPENAI("openai", ProviderId.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-5.6-luna", "OpenAI", "openai"),
    SILICONFLOW(
        "siliconflow",
        ProviderId.OPENAI_COMPATIBLE,
        "https://api.siliconflow.cn/v1",
        "deepseek-ai/DeepSeek-V4-Flash",
        "SiliconFlow",
        "deepseek",
    ),
    MOONSHOT(
        "moonshot",
        ProviderId.OPENAI_COMPATIBLE,
        "https://api.moonshot.cn/v1",
        "kimi-k3",
        "Kimi",
        "moonshot",
    ),
    QWEN(
        "qwen",
        ProviderId.OPENAI_COMPATIBLE,
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "qwen3.7-plus",
        "Qwen",
        "qwen",
    ),
    CLAUDE("claude", ProviderId.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-5-5", "Claude", "anthropic"),
    GEMINI(
        "gemini",
        ProviderId.GEMINI,
        "https://generativelanguage.googleapis.com",
        "gemini-3.8-flash",
        "Gemini",
        "gemini",
    ),
    CUSTOM("custom", ProviderId.OPENAI_COMPATIBLE, "", "", "", null),
}

enum class WizardStep {
    PROVIDER,
    CONNECTION,
    TUNING,
    PROMPT,
}

data class AgentWizardUiState(
    val editingId: Long = 0L,
    val step: WizardStep = WizardStep.PROVIDER,
    val preset: AgentPreset = AgentPreset.DEEPSEEK,
    val name: String = AgentPreset.DEEPSEEK.defaultName,
    val baseUrl: String = AgentPreset.DEEPSEEK.baseUrl,
    val apiKey: String = "",
    val keyPrefilled: Boolean = false,
    val detectedByKey: Boolean = false,
    val model: String = AgentPreset.DEEPSEEK.model,
    val models: List<String> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
    val temperature: Float = 0.7f,
    val maxTokens: Int? = null,
    val reasoning: Boolean = false,
    val systemPrompt: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: SetupError? = null,
)

sealed interface SetupError {
    data object MissingKey : SetupError

    data object MissingUrl : SetupError

    data object InsecureUrl : SetupError

    data object MissingModel : SetupError

    data object KeyStore : SetupError

    data class Request(val kind: ProviderError.Kind, val detail: String) : SetupError
}

@HiltViewModel
class AgentWizardViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val providerFactory: ProviderFactory,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentWizardUiState())
    val uiState: StateFlow<AgentWizardUiState> = _uiState.asStateFlow()

    private var autoFetchJob: Job? = null
    private var modelEdited = false

    fun startForNew() {
        _uiState.value = AgentWizardUiState()
        modelEdited = false
        scheduleAutoFetch()
    }

    fun startForEdit(agentId: Long) {
        viewModelScope.launch {
            val agent = agentRepository.getAgent(agentId) ?: return@launch
            val preset = AgentPreset.entries.firstOrNull {
                it.provider == agent.provider && it.baseUrl == agent.baseUrl
            } ?: AgentPreset.CUSTOM
            _uiState.value = AgentWizardUiState(
                editingId = agent.id,
                preset = preset,
                name = agent.name,
                baseUrl = agent.baseUrl,
                model = agent.model,
                temperature = agent.temperature.toFloat(),
                maxTokens = agent.maxTokens,
                reasoning = agent.reasoning,
                systemPrompt = agent.systemPrompt,
                keyPrefilled = true,
            )
            modelEdited = true
            scheduleAutoFetch()
        }
    }

    // --- step 1: provider & key ---

    fun selectPreset(preset: AgentPreset) {
        _uiState.update { current ->
            current.copy(
                preset = preset,
                name = preset.defaultName.ifBlank { current.name },
                baseUrl = preset.baseUrl.ifBlank { current.baseUrl },
                model = preset.model.ifBlank { current.model },
                models = emptyList(),
                modelsError = null,
                error = null,
            )
        }
        modelEdited = false
        scheduleAutoFetch()
    }

    fun updateApiKey(value: String) {
        val detected = detectPreset(value)
        _uiState.update { current ->
            if (detected != null) {
                current.copy(
                    apiKey = value,
                    preset = detected,
                    detectedByKey = true,
                    baseUrl = detected.baseUrl,
                    name = if (current.editingId == 0L) detected.defaultName else current.name,
                    model = if (current.model.isBlank() || current.detectedByKey) {
                        detected.model
                    } else {
                        current.model
                    },
                    models = emptyList(),
                    error = null,
                )
            } else {
                current.copy(apiKey = value, error = null)
            }
        }
        modelEdited = detected == null && modelEdited
        scheduleAutoFetch()
    }

    /** Key prefix automation: sk-ant- -> Claude, AIza -> Gemini, sk- -> OpenAI family. */
    private fun detectPreset(key: String): AgentPreset? {
        val trimmed = key.trim()
        return when {
            trimmed.startsWith("sk-ant-") -> AgentPreset.CLAUDE
            trimmed.startsWith("AIza") -> AgentPreset.GEMINI
            trimmed.startsWith("sk-or-") -> AgentPreset.OPENAI
            trimmed.startsWith("sk-") && _uiState.value.preset.provider != ProviderId.OPENAI_COMPATIBLE ->
                AgentPreset.DEEPSEEK
            else -> null
        }
    }

    fun updateBaseUrl(value: String) {
        _uiState.update { it.copy(baseUrl = value, error = null) }
        autoFetchJob?.cancel()
        autoFetchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(900)
            val state = _uiState.value
            if (hasUsableKey(state) && state.baseUrl.isNotBlank()) {
                if (NetworkEndpointPolicy.validateBaseUrl(state.baseUrl)) {
                    fetchModels()
                } else {
                    _uiState.update { it.copy(error = SetupError.InsecureUrl) }
                }
            }
        }
    }

    fun updateName(value: String) = _uiState.update { it.copy(name = value) }

    fun next() {
        val current = _uiState.value
        when (current.step) {
            WizardStep.PROVIDER -> {
                if (current.baseUrl.isBlank()) {
                    _uiState.update { it.copy(error = SetupError.MissingUrl) }
                    return
                }
                if (!NetworkEndpointPolicy.validateBaseUrl(current.baseUrl)) {
                    _uiState.update { it.copy(error = SetupError.InsecureUrl) }
                    return
                }
                if (current.apiKey.isBlank() && !current.keyPrefilled) {
                    _uiState.update { it.copy(error = SetupError.MissingKey) }
                    return
                }
                _uiState.update { it.copy(step = WizardStep.CONNECTION, error = null) }
                scheduleAutoFetch(0)
            }

            WizardStep.CONNECTION -> {
                if (current.model.isBlank()) {
                    _uiState.update { it.copy(error = SetupError.MissingModel) }
                    return
                }
                _uiState.update { it.copy(step = WizardStep.TUNING, error = null) }
            }

            WizardStep.TUNING -> _uiState.update { it.copy(step = WizardStep.PROMPT, error = null) }

            WizardStep.PROMPT -> save()
        }
    }

    fun back() {
        val previous = when (_uiState.value.step) {
            WizardStep.PROVIDER -> null
            WizardStep.CONNECTION -> WizardStep.PROVIDER
            WizardStep.TUNING -> WizardStep.CONNECTION
            WizardStep.PROMPT -> WizardStep.TUNING
        }
        if (previous != null) _uiState.update { it.copy(step = previous, error = null) }
    }

    // --- step 2: connection & models ---

    /** Debounced auto-pull of the account's model list whenever creds change. */
    private fun scheduleAutoFetch(delayMs: Long = 700) {
        autoFetchJob?.cancel()
        autoFetchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(delayMs)
            val state = _uiState.value
            if (hasUsableKey(state) && state.baseUrl.isNotBlank()) {
                if (NetworkEndpointPolicy.validateBaseUrl(state.baseUrl)) {
                    fetchModels()
                } else {
                    _uiState.update { it.copy(error = SetupError.InsecureUrl) }
                }
            }
        }
    }

    fun fetchModels() {
        val current = _uiState.value
        if (current.modelsLoading) return
        if (current.baseUrl.isBlank() || !hasUsableKey(current)) {
            _uiState.update { it.copy(modelsError = "missing_credentials") }
            return
        }
        if (!NetworkEndpointPolicy.validateBaseUrl(current.baseUrl)) {
            _uiState.update { it.copy(error = SetupError.InsecureUrl, modelsError = null) }
            return
        }
        _uiState.update { it.copy(modelsLoading = true, modelsError = null) }
        viewModelScope.launch {
            try {
                // Editing an existing agent: the field is intentionally empty,
                // so authenticate with the stored (decrypted) key instead of
                // sending a placeholder that 401s every fetch.
                val storedKey = if (current.editingId != 0L) {
                    runCatching { agentRepository.resolveConfig(current.editingId) }.getOrNull()?.apiKey
                } else {
                    null
                }
                val apiKey = current.apiKey.trim().ifBlank { storedKey.orEmpty() }
                if (apiKey.isBlank()) {
                    _uiState.update { it.copy(modelsLoading = false, modelsError = "missing_credentials") }
                    return@launch
                }
                val fetched = providerFactory.create(current.preset.provider)
                    .listModels(current.toConfig().copy(apiKey = apiKey))
                val models = fetched.filter(::isLikelyChatModel).ifEmpty { fetched }.sorted()
                _uiState.update { state ->
                    val selected = pickInitialModel(models, state.model, modelEdited)
                    val entry = ModelCatalog.entryFor(state.preset.provider, selected)
                    state.copy(
                        modelsLoading = false,
                        models = models,
                        modelsError = null,
                        model = selected,
                        temperature = if (!modelEdited && entry != null) {
                            entry.temperature.toFloat()
                        } else {
                            state.temperature
                        },
                        maxTokens = if (!modelEdited && entry != null) entry.maxTokens else state.maxTokens,
                        reasoning = if (!modelEdited && entry != null) {
                            entry.supportsReasoning
                        } else {
                            state.reasoning
                        },
                    )
                }
            } catch (e: ProviderException) {
                _uiState.update {
                    it.copy(
                        modelsLoading = false,
                        modelsError = e.error.message,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(modelsLoading = false, modelsError = e.message ?: "request_failed")
                }
            }
        }
    }

    private fun hasUsableKey(state: AgentWizardUiState): Boolean =
        state.apiKey.isNotBlank() || state.keyPrefilled

    /** Applies a curated catalog entry: model id plus its tuned defaults. */
    fun pickCatalogModel(entry: ModelEntry) {
        modelEdited = true
        _uiState.update {
            it.copy(
                model = entry.id,
                temperature = entry.temperature.toFloat(),
                maxTokens = entry.maxTokens,
                reasoning = entry.supportsReasoning,
                error = null,
            )
        }
    }

    fun updateModel(value: String) {
        modelEdited = true
        _uiState.update { it.copy(model = value, error = null) }
    }

    // --- step 3: tuning ---

    fun updateTemperature(value: Float) = _uiState.update { it.copy(temperature = value) }

    fun updateMaxTokens(value: String) {
        val parsed = value.filter { it.isDigit() }.takeIf { it.isNotBlank() }?.toIntOrNull()
        _uiState.update { it.copy(maxTokens = parsed) }
    }

    fun updateReasoning(value: Boolean) = _uiState.update { it.copy(reasoning = value) }

    // --- step 4: prompt & save ---

    fun updateSystemPrompt(value: String) = _uiState.update { it.copy(systemPrompt = value) }

    private fun save() {
        val current = _uiState.value
        if (current.model.isBlank()) {
            _uiState.update { it.copy(error = SetupError.MissingModel) }
            return
        }
        if (!NetworkEndpointPolicy.validateBaseUrl(current.baseUrl)) {
            _uiState.update { it.copy(error = SetupError.InsecureUrl) }
            return
        }
        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val agent = Agent(
                    id = current.editingId,
                    name = current.name.ifBlank { current.model },
                    provider = current.preset.provider,
                    baseUrl = current.baseUrl.trim().trimEnd('/'),
                    model = current.model.trim(),
                    temperature = current.temperature.toDouble(),
                    maxTokens = current.maxTokens,
                    reasoning = current.reasoning,
                    systemPrompt = current.systemPrompt.trim(),
                    isDefault = current.editingId == 0L,
                )
                if (current.editingId == 0L) {
                    agentRepository.save(agent, current.apiKey.trim())
                } else {
                    agentRepository.update(agent, current.apiKey.trim().takeIf { it.isNotBlank() })
                }
                _uiState.update { it.copy(saving = false, saved = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(saving = false, error = SetupError.KeyStore) }
            }
        }
    }

    private fun AgentWizardUiState.toConfig() = ProviderConfig(
        provider = preset.provider,
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiKey = apiKey.trim(),
        model = model.trim(),
    )
}

/**
 * Model lists from gateways include embeddings, TTS and image endpoints; the
 * wizard only offers chat-capable ids (with a fallback to the raw list when
 * the filter would hide everything).
 */
internal fun isLikelyChatModel(id: String): Boolean {
    val normalized = id.lowercase()
    return NON_CHAT_MARKERS.none { normalized.contains(it) }
}

/**
 * Keeps the saved/manual choice when the user touched the model, otherwise
 * prefers the model already selected when it exists on the account and falls
 * back to the first entry the API returned.
 */
internal fun pickInitialModel(models: List<String>, current: String, edited: Boolean): String {
    if (models.isEmpty()) return current
    if (edited) return current
    return models.firstOrNull { it.equals(current, ignoreCase = true) } ?: models.first()
}

private val NON_CHAT_MARKERS = listOf(
    "embedding", "embed-", "bge-", "gte-", "e5-", "m3e",
    "rerank", "whisper", "tts", "text-to-speech", "speech",
    "transcribe", "audio", "realtime", "moderation",
    "dall-e", "stable-diffusion", "sdxl", "flux", "upscaler",
    "clip-", "image", "codec",
)
