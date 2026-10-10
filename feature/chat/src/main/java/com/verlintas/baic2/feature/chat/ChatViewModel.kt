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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verlintas.baic2.core.data.attachment.AttachmentProcessor
import com.verlintas.baic2.core.data.repository.AgentRepository
import com.verlintas.baic2.core.data.repository.ApiKeyUnavailableException
import com.verlintas.baic2.core.data.repository.ConversationRepository
import com.verlintas.baic2.core.data.repository.MemoryRepository
import com.verlintas.baic2.core.data.repository.PlanRepository
import com.verlintas.baic2.core.data.repository.RunControlBus
import com.verlintas.baic2.core.data.repository.RunRepository
import com.verlintas.baic2.core.engine.AgentEvent
import com.verlintas.baic2.core.engine.AgentFailure
import com.verlintas.baic2.core.engine.AgentLoop
import com.verlintas.baic2.core.engine.AuxiliaryTasks
import com.verlintas.baic2.core.engine.ConfirmationQueue
import com.verlintas.baic2.core.engine.ToolCatalog
import com.verlintas.baic2.core.engine.renderSystemPrompt
import com.verlintas.baic2.core.model.Agent
import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.core.model.AppVisibility
import com.verlintas.baic2.core.model.Attachment
import com.verlintas.baic2.core.model.AttachmentKind
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.core.model.CoreMemory
import com.verlintas.baic2.core.model.CuratorRun
import com.verlintas.baic2.core.model.DocumentTextCodec
import com.verlintas.baic2.core.model.MemoryPrompt
import com.verlintas.baic2.core.model.MemoryText
import com.verlintas.baic2.core.model.MessageSnapshot
import com.verlintas.baic2.core.model.ModelCatalog
import com.verlintas.baic2.core.model.ModelContextWindows
import com.verlintas.baic2.core.model.Note
import com.verlintas.baic2.core.model.NoteKind
import com.verlintas.baic2.core.model.NoteSource
import com.verlintas.baic2.core.model.Plan
import com.verlintas.baic2.core.model.ProviderConfig
import com.verlintas.baic2.core.model.ProviderId
import com.verlintas.baic2.core.model.RunState
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.core.model.TokenEstimator
import com.verlintas.baic2.core.model.ToolCall
import com.verlintas.baic2.core.model.ToolCallStatus
import com.verlintas.baic2.core.model.ToolTrust
import com.verlintas.baic2.device.api.PdfTextExtractor
import com.verlintas.baic2.device.api.RunNotifier
import com.verlintas.baic2.device.api.ScreenshotProvider
import com.verlintas.baic2.core.data.prefs.AppLocaleStore
import com.verlintas.baic2.core.model.AppLanguage
import com.verlintas.baic2.device.api.SpeechFailure
import com.verlintas.baic2.device.api.SpeechInputBridge
import com.verlintas.baic2.device.api.SpeechSession
import com.verlintas.baic2.device.api.SpeechSessionEvent
import com.verlintas.baic2.device.api.SpeechOutput
import com.verlintas.baic2.tools.skills.SkillRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class StreamingState(
    val text: String = "",
    val thinking: String = "",
)

data class ChatUiState(
    val title: String = "",
    val mode: AppMode = AppMode.CHAT,
    val messages: List<ChatMessage> = emptyList(),
    val streamingText: String = "",
    val streamingThinking: String = "",
    val isRunning: Boolean = false,
    val error: ChatError? = null,
    val contextUsedTokens: Long? = null,
    val contextWindowTokens: Long? = null,
    val contextEstimated: Boolean = false,
    val auxBusy: Boolean = false,
    val pendingAttachments: List<Attachment> = emptyList(),
    val plan: Plan? = null,
    val confirmRequest: ToolCall? = null,
    val compressionSnapshot: MessageSnapshot? = null,
)

enum class AttachmentError {
    TOO_LARGE,
    UNSUPPORTED,
    READ_FAILED,
}

data class ChatError(
    val kind: Kind,
    val detail: String? = null,
) {
    enum class Kind {
        PROVIDER,
        BUDGET,
        NO_AGENT,
        API_KEY,
        UNSUPPORTED,
        INTERNAL,
        SCREEN_CAPTURE,
    }
}

data class ExportLabels(
    val you: String,
    val assistant: String,
    val toolCall: String,
    val thinking: String,
    val emptyConversation: String,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val agentRepository: AgentRepository,
    private val runRepository: RunRepository,
    private val runControlBus: RunControlBus,
    private val memoryRepository: MemoryRepository,
    private val agentLoop: AgentLoop,
    private val toolCatalog: ToolCatalog,
    private val auxiliaryTasks: AuxiliaryTasks,
    private val attachmentProcessor: AttachmentProcessor,
    private val speechOutput: SpeechOutput,
    private val speechInput: SpeechInputBridge,
    private val screenshotProvider: ScreenshotProvider,
    private val pdfTextExtractor: PdfTextExtractor,
    private val runNotifier: RunNotifier,
    private val confirmationQueue: ConfirmationQueue,
    private val planRepository: PlanRepository,
    private val skillRepository: SkillRepository,
    private val settingsRepository: SettingsRepository,
    private val localeStore: AppLocaleStore,
    private val json: Json,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val conversationId: Long = checkNotNull(savedStateHandle[ARG_CONVERSATION_ID])

    private val streaming = MutableStateFlow(StreamingState())
    private val running = MutableStateFlow(false)
    private val error = MutableStateFlow<ChatError?>(null)
    private val auxBusy = MutableStateFlow(false)
    private val pendingAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    private val attachmentError = MutableStateFlow<AttachmentError?>(null)
    private val confirmation = MutableStateFlow<ToolCall?>(null)
    private val notice = MutableStateFlow<String?>(null)

    val notices: StateFlow<String?> = notice.asStateFlow()

    /** Popup shown when a MAX run finishes or is interrupted. */
    data class RunCompletion(
        val completed: Boolean,
        val title: String,
        val reason: String,
        val rounds: Int,
        val toolCalls: Int,
        val durationMs: Long,
    )

    private val _runCompletion = MutableStateFlow<RunCompletion?>(null)
    val runCompletion: StateFlow<RunCompletion?> = _runCompletion.asStateFlow()

    fun dismissRunCompletion() {
        _runCompletion.value = null
    }

    /** Live dictation state for the composer. */
    data class VoiceState(
        val listening: Boolean = false,
        val partial: String = "",
        val level: Float = 0f,
    )

    private val _voiceState = MutableStateFlow(VoiceState())
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _voiceResults = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val voiceResults: SharedFlow<String> = _voiceResults.asSharedFlow()

    private val _voiceFailures = MutableSharedFlow<SpeechFailure>(extraBufferCapacity = 4)
    val voiceFailures: SharedFlow<SpeechFailure> = _voiceFailures.asSharedFlow()

    private var voiceSession: SpeechSession? = null
    private var voiceJob: Job? = null

    /** True while TTS is reading a reply (hands-free pauses listening then). */
    fun isSpeaking(): Boolean = speechOutput.isSpeaking

    /**
     * Starts in-app dictation. Returns false when no recognition service is
     * available so the caller can fall back to the system dialog.
     */
    fun startVoiceSession(): Boolean {
        if (_voiceState.value.listening) return true
        val session = speechInput.startSession(languageTag()) ?: return false
        voiceSession = session
        _voiceState.value = VoiceState(listening = true)
        voiceJob = viewModelScope.launch {
            session.events.collect { event ->
                when (event) {
                    is SpeechSessionEvent.Ready ->
                        _voiceState.update { it.copy(listening = true) }

                    is SpeechSessionEvent.Partial ->
                        _voiceState.update { it.copy(partial = event.text) }

                    is SpeechSessionEvent.Level ->
                        _voiceState.update { it.copy(level = event.level) }

                    is SpeechSessionEvent.Final -> {
                        _voiceState.value = VoiceState()
                        _voiceResults.tryEmit(event.text)
                        // Terminal event: release the recognizer and end the
                        // collector so dictation cannot leak jobs or the mic.
                        voiceSession = null
                        voiceJob?.cancel()
                    }

                    is SpeechSessionEvent.Error -> {
                        _voiceState.value = VoiceState()
                        _voiceFailures.tryEmit(event.failure)
                        voiceSession = null
                        voiceJob?.cancel()
                    }
                }
            }
            _voiceState.value = VoiceState()
        }
        return true
    }

    /** Finishes dictation now; the final text arrives via [voiceResults]. */
    fun stopVoiceSession() {
        voiceSession?.stop()
    }

    /** Discards the current dictation without emitting a result. */
    fun cancelVoiceSession() {
        voiceSession?.cancel()
        voiceSession = null
        voiceJob?.cancel()
        voiceJob = null
        _voiceState.value = VoiceState()
    }

    private fun languageTag(): String? = when (localeStore.language.value) {
        AppLanguage.CHINESE -> "zh-CN"
        AppLanguage.ENGLISH -> "en"
        AppLanguage.SYSTEM -> null
    }

    /** Hands-free loop toggle (settle -> listen -> auto-send). */
    val handsFree: StateFlow<Boolean> = settingsRepository.handsFreeVoice.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setHandsFree(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHandsFreeVoice(enabled) }
    }

    private data class SessionInfo(
        val isRunning: Boolean,
        val error: ChatError?,
        val agents: List<Agent>,
    )

    private data class ViewExtras(
        val auxBusy: Boolean,
        val pendingAttachments: List<Attachment>,
        val confirmRequest: ToolCall?,
        val plan: Plan?,
        val snapshot: MessageSnapshot?,
        val core: CoreMemory,
    )

    val attachmentErrors: StateFlow<AttachmentError?> = attachmentError.asStateFlow()

    val uiState: StateFlow<ChatUiState> = combine(
        conversationRepository.observeConversation(conversationId),
        conversationRepository.observeMessages(conversationId),
        streaming,
        combine(running, error, agentRepository.observeAgents()) { isRunning, currentError, agents ->
            SessionInfo(isRunning, currentError, agents)
        },
        combine(
            auxBusy,
            pendingAttachments,
            confirmation,
            combine(
                planRepository.observePlan(conversationId),
                conversationRepository.observeSnapshots(conversationId),
                memoryRepository.observeCore(),
            ) { plan, snapshots, core -> Triple(plan, snapshots, core) },
        ) { busy, attachments, confirmRequest, planSnapshotsCore ->
            val (plan, snapshots, core) = planSnapshotsCore
            ViewExtras(busy, attachments, confirmRequest, plan, snapshots.firstOrNull(), core)
        },
    ) { conversation, messages, stream, session, extras ->
        val mode = conversation?.mode ?: AppMode.CHAT
        val agent = session.agents.firstOrNull { it.id == conversation?.agentId }
            ?: session.agents.firstOrNull { it.isDefault }
        val estimated = TokenEstimator.estimate(
            messages = messages,
            systemPrompt = renderSystemPrompt(
                mode,
                agent?.systemPrompt.orEmpty(),
                extras.plan?.render(),
                MemoryPrompt.context(extras.core, emptyList(), System.currentTimeMillis()),
            ),
            toolSpecs = toolCatalog.specs(mode),
            streamingText = stream.text,
        )
        val reported = messages.asReversed()
            .firstOrNull { it.usageInput != null }
            ?.usageInput
            ?.takeIf { it > 0 }
        ChatUiState(
            title = conversation?.title.orEmpty(),
            mode = mode,
            messages = messages,
            streamingText = stream.text,
            streamingThinking = stream.thinking,
            isRunning = session.isRunning,
            error = session.error,
            contextUsedTokens = (reported ?: estimated).takeIf { it > 0 },
            contextWindowTokens = contextWindowFor(agent?.provider, agent?.model ?: conversationModel(messages)),
            contextEstimated = reported == null,
            auxBusy = extras.auxBusy,
            pendingAttachments = extras.pendingAttachments,
            plan = extras.plan,
            confirmRequest = extras.confirmRequest,
            compressionSnapshot = extras.snapshot,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState(),
    )

    private var runJob: Job? = null
    private var titleGenerated = false
    private var lastCuratedAssistantCount = -1
    private val turnGate = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        viewModelScope.launch {
            confirmationQueue.requests.collect { call -> confirmation.value = call }
        }
        runNotifier.setStopHandler { runJob?.cancel() }
        viewModelScope.launch {
            runControlBus.stops.collect { target ->
                if (target == conversationId) runJob?.cancel()
            }
        }
        viewModelScope.launch {
            // Sleep-time consolidation: replay and solidify when the app
            // leaves the foreground, not in the middle of a conversation.
            var wasForeground = AppVisibility.foreground
            AppVisibility.foregroundFlow.collect { foreground ->
                if (!foreground && wasForeground) {
                    providerConfigQuiet()?.let { config ->
                        maybeCurate(IDLE_CURATE_MESSAGES, config, trigger = "idle")
                    }
                }
                wasForeground = foreground
            }
        }
    }

    /** Config lookup that never raises UI errors (background tasks). */
    private suspend fun providerConfigQuiet(): ProviderConfig? {
        val conversation = conversationRepository.get(conversationId) ?: return null
        return runCatching { agentRepository.resolveConfig(conversation.agentId) }.getOrNull()
    }

    fun dismissNotice() {
        notice.value = null
    }

    /** Records the newest completed tool sequence as a replayable skill. */
    fun saveLastRunAsSkill(name: String, savedTemplate: String, emptyLabel: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val calls = conversationRepository.getMessages(conversationId)
                .lastOrNull { it.role == ChatRole.ASSISTANT && it.toolCalls.isNotEmpty() }
                ?.toolCalls
                ?.filter { it.status == ToolCallStatus.DONE }
            if (calls.isNullOrEmpty()) {
                notice.value = emptyLabel
                return@launch
            }
            runCatching { skillRepository.saveFromToolCalls(trimmed, calls) }
                .onSuccess { skill -> notice.value = savedTemplate.format(skill.name) }
                .onFailure { failure -> notice.value = failure.message }
        }
    }

    fun respondConfirmation(allow: Boolean) {
        val call = confirmation.value ?: return
        confirmationQueue.respond(call.id, allow)
        confirmation.value = null
    }

    private fun conversationModel(messages: List<ChatMessage>): String =
        messages.lastOrNull { it.model != null }?.model.orEmpty()

    private fun contextWindowFor(provider: ProviderId?, model: String): Long? =
        ModelCatalog.entryFor(provider ?: ProviderId.OPENAI_COMPATIBLE, model)?.contextWindow
            ?: ModelContextWindows.forModel(model)

    fun send(text: String) {
        val trimmed = text.trim()
        val attachments = pendingAttachments.value
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        // Atomic admission: two taps (or voice auto-send + tap) must not start
        // two concurrent turns inside the pre-`running` suspension window.
        if (!turnGate.compareAndSet(false, true)) return
        pendingAttachments.value = emptyList()
        runJob = viewModelScope.launch {
            try {
                executeTurn(trimmed, attachments = attachments)
            } finally {
                turnGate.set(false)
            }
        }
    }

    fun importImages(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val room = (MAX_IMAGE_ATTACHMENTS - pendingAttachments.value.size).coerceAtLeast(0)
            val imported = uris.take(room).mapNotNull { uri ->
                attachmentProcessor.importImage(uri).getOrElse { failure ->
                    attachmentError.value = failure.toAttachmentError()
                    null
                }
            }
            pendingAttachments.update { it + imported }
        }
    }

    fun importTextFile(uri: android.net.Uri) {
        viewModelScope.launch {
            attachmentProcessor.importTextFile(uri)
                .onSuccess { attachment ->
                    pendingAttachments.update { it + attachment }
                }
                .onFailure { failure ->
                    attachmentError.value = failure.toAttachmentError()
                }
        }
    }

    /** Routes a picked file by type: plain text inline, documents extracted. */
    fun importFile(uri: android.net.Uri) {
        viewModelScope.launch {
            val document = attachmentProcessor.readDocument(uri).getOrElse { failure ->
                attachmentError.value = failure.toAttachmentError()
                return@launch
            }
            val name = document.fileName
            val text = when {
                document.mimeType.startsWith("text/") || document.mimeType in TEXT_LIKE_MIMES ->
                    attachmentProcessor.importTextFile(uri)
                        .onSuccess { attachment -> pendingAttachments.update { it + attachment } }
                        .onFailure { failure -> attachmentError.value = failure.toAttachmentError() }
                        .let { return@launch }

                document.mimeType.contains("pdf") || name.endsWith(".pdf", ignoreCase = true) ->
                    pdfTextExtractor.extract(document.bytes).getOrElse { failure ->
                        attachmentError.value = failure.toAttachmentError()
                        return@launch
                    }

                else -> DocumentTextCodec.extract(document.bytes, document.mimeType, name)
            } ?: run {
                attachmentError.value = AttachmentError.UNSUPPORTED
                return@launch
            }
            pendingAttachments.update {
                it + attachmentProcessor.importExtractedText(
                    mimeType = document.mimeType,
                    fileName = name,
                    text = text,
                    sizeBytes = document.bytes.size.toLong(),
                )
            }
        }
    }

    fun removePendingAttachment(id: String) {
        val removed = pendingAttachments.value.firstOrNull { it.id == id }
        pendingAttachments.update { list -> list.filterNot { it.id == id } }
        removed?.let { attachment ->
            viewModelScope.launch { attachmentProcessor.delete(attachment) }
        }
    }

    fun dismissAttachmentError() {
        attachmentError.value = null
    }

    val screenCaptureReady: StateFlow<Boolean> = screenshotProvider.ready

    fun createScreenCaptureIntent(): android.content.Intent = screenshotProvider.createPermissionIntent()

    fun onScreenPermissionResult(resultCode: Int, data: android.content.Intent): Boolean =
        screenshotProvider.onPermissionResult(resultCode, data)

    /** Captures the screen and sends it to the model as an image attachment. */
    fun analyzeScreen(prompt: String) {
        if (!turnGate.compareAndSet(false, true)) return
        runJob = viewModelScope.launch {
            try {
                auxBusy.value = true
                val bytes = try {
                    screenshotProvider.capture().getOrElse { failure ->
                        error.value = ChatError(ChatError.Kind.SCREEN_CAPTURE, failure.message)
                        return@launch
                    }
                } finally {
                    auxBusy.value = false
                }
                val attachment = attachmentProcessor.importImageBytes(bytes).getOrElse { failure ->
                    error.value = ChatError(ChatError.Kind.SCREEN_CAPTURE, failure.message)
                    return@launch
                }
                executeTurn(prompt, attachments = listOf(attachment))
            } finally {
                turnGate.set(false)
            }
        }
    }

    fun speakMessage(text: String) {
        speechOutput.speak(text)
    }

    fun stopSpeaking() {
        speechOutput.stop()
    }

    override fun onCleared() {
        cancelVoiceSession()
        speechOutput.stop()
        runNotifier.setStopHandler(null)
        runNotifier.stopRunning()
        super.onCleared()
    }

    private fun Throwable.toAttachmentError(): AttachmentError = when (message) {
        "file_too_large" -> AttachmentError.TOO_LARGE
        "unsupported_type", "no_text_found" -> AttachmentError.UNSUPPORTED
        else -> AttachmentError.READ_FAILED
    }

    fun stop() {
        runJob?.cancel()
    }

    /** Drops everything after the last user message and runs that turn again. */
    fun retryLast() {
        if (!turnGate.compareAndSet(false, true)) return
        runJob = viewModelScope.launch {
            try {
                val messages = conversationRepository.getMessages(conversationId)
                val lastUser = messages.lastOrNull { it.role == ChatRole.USER } ?: return@launch
                conversationRepository.deleteMessagesAfter(conversationId, lastUser.id)
                executeTurn(lastUser.content, appendUserMessage = false)
            } finally {
                turnGate.set(false)
            }
        }
    }

    fun dismissError() {
        error.value = null
    }

    fun setMode(mode: AppMode) {
        viewModelScope.launch {
            val conversation = conversationRepository.get(conversationId) ?: return@launch
            conversationRepository.updateMeta(conversationId, conversation.agentId, mode)
        }
    }

    fun toggleStar(messageId: Long) {
        viewModelScope.launch {
            val message = uiState.value.messages.firstOrNull { it.id == messageId } ?: return@launch
            conversationRepository.setStarred(messageId, !message.starred)
        }
    }

    fun deleteMessage(messageId: Long) {
        if (running.value) return
        viewModelScope.launch { conversationRepository.deleteMessage(messageId) }
    }

    fun editAndResend(messageId: Long, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return
        if (!turnGate.compareAndSet(false, true)) return
        runJob = viewModelScope.launch {
            try {
                conversationRepository.updateMessageContent(messageId, trimmed)
                conversationRepository.deleteMessagesAfter(conversationId, messageId)
                executeTurn(trimmed, appendUserMessage = false)
            } finally {
                turnGate.set(false)
            }
        }
    }

    /** Summarizes older history, keeping recent turns verbatim. */
    fun compressContext(tooShortHint: String) {
        if (running.value || auxBusy.value) return
        viewModelScope.launch {
            val messages = conversationRepository.getMessages(conversationId)
            if (messages.size <= KEEP_RECENT_MESSAGES) {
                notice.value = tooShortHint
                return@launch
            }
            runCompression()
        }
    }

    /** Brings back the messages the last compression replaced. */
    fun restoreCompression() {
        val snapshot = uiState.value.compressionSnapshot ?: return
        if (running.value || auxBusy.value) return
        viewModelScope.launch {
            notice.value = null
            conversationRepository.restoreSnapshot(snapshot.id)
        }
    }

    /** Keeps the summarized history and drops the backup. */
    fun discardCompression() {
        val snapshot = uiState.value.compressionSnapshot ?: return
        if (running.value || auxBusy.value) return
        viewModelScope.launch { conversationRepository.discardSnapshot(snapshot.id) }
    }

    /** Sleep-time consolidation: turns recent episodes into durable notes. */
    fun reflectMemory(savedTemplate: String, noneLabel: String, failedLabel: String) {
        if (running.value || auxBusy.value) return
        viewModelScope.launch {
            val config = resolveConfig() ?: return@launch
            auxBusy.value = true
            try {
                val outcome = runCurator(config, trigger = "manual")
                if (outcome != null) {
                    lastCuratedAssistantCount = conversationRepository.getMessages(conversationId)
                        .count { it.role == ChatRole.ASSISTANT }
                    val changed = outcome.added + outcome.revised + outcome.forgotten
                    notice.value = if (changed > 0) {
                        savedTemplate.format(outcome.added, outcome.revised, outcome.forgotten)
                    } else {
                        noneLabel
                    }
                } else {
                    // Failed runs must say so; the Library log has the details.
                    notice.value = failedLabel
                }
            } finally {
                auxBusy.value = false
            }
        }
    }

    fun buildExportText(labels: ExportLabels): String {
        val state = uiState.value
        if (state.messages.isEmpty()) return labels.emptyConversation
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        return buildString {
            append("# ").append(state.title.ifBlank { labels.emptyConversation }).append("\n\n")
            append("> ").append(timestamp).append("\n\n")
            state.messages.forEach { message ->
                when (message.role) {
                    ChatRole.USER -> {
                        append("## ").append(labels.you).append("\n\n")
                        append(message.content).append("\n\n")
                    }

                    ChatRole.ASSISTANT -> {
                        if (message.content.isNotBlank()) {
                            append("## ").append(labels.assistant).append("\n\n")
                            append(message.content).append("\n\n")
                        }
                        message.thinking?.takeIf { it.isNotBlank() }?.let { thinking ->
                            append("<details><summary>").append(labels.thinking).append("</summary>\n\n")
                            append(thinking).append("\n\n</details>\n\n")
                        }
                        message.toolCalls.forEach { call ->
                            append("### ").append(labels.toolCall).append(": `").append(call.name).append("`\n\n")
                            append("```json\n").append(call.argumentsJson).append("\n```\n\n")
                            call.result?.takeIf { it.isNotBlank() }?.let { result ->
                                append("```\n").append(result).append("\n```\n\n")
                            }
                        }
                    }

                    else -> Unit
                }
            }
        }.trim() + "\n"
    }

    private suspend fun executeTurn(
        text: String,
        appendUserMessage: Boolean = true,
        attachments: List<Attachment> = emptyList(),
    ) {
        val conversation = conversationRepository.get(conversationId) ?: return
        val agent = conversation.agentId?.let { agentRepository.getAgent(it) }
            ?: agentRepository.getDefaultAgent()

        val config = try {
            agentRepository.resolveConfig(conversation.agentId)
        } catch (e: ApiKeyUnavailableException) {
            error.value = ChatError(ChatError.Kind.API_KEY)
            return
        }
        if (config == null) {
            error.value = ChatError(ChatError.Kind.NO_AGENT)
            return
        }

        val isFirstTurn = conversationRepository.getMessages(conversationId)
            .count { it.role == ChatRole.USER } == 0

        if (appendUserMessage) {
            conversationRepository.append(
                ChatMessage(
                    conversationId = conversationId,
                    role = ChatRole.USER,
                    content = text,
                    attachments = attachments,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            if (conversation.title.isBlank()) {
                conversationRepository.updateTitle(conversationId, text.take(TITLE_MAX_CHARS))
            }
        }

        val rawHistory = conversationRepository.getMessages(conversationId)
        val history = prepareHistory(rawHistory)
        // Priming: notes cued by this message are woken and ride along in the
        // system prompt; everything else stays out of the window until asked.
        // The status line tells the agent how much memory exists, when it was
        // last consolidated and how long ago the user last spoke.
        val turnNow = System.currentTimeMillis()
        val previousActivity = conversationRepository.lastUserMessageAtBefore(
            rawHistory.lastOrNull()?.createdAt ?: turnNow,
        )
        val memoryContext = MemoryPrompt.context(
            core = memoryRepository.getCore(),
            primed = primedNotes(text, rawHistory, conversation.title),
            now = turnNow,
            status = memoryRepository.memoryStatus(previousActivity),
        )
        // Tasks record agentic work only: plain Chat / Chat+ turns are not runs.
        val agentic = conversation.mode.requiresConfirmation || conversation.mode == AppMode.MAX
        val runId: Long? = if (agentic) {
            runRepository.start(conversationId, conversation.mode)
        } else {
            null
        }
        if (agentic) {
            runNotifier.startRunning(conversation.title.ifBlank { "BAIC2" }, runId)
        }
        _runCompletion.value = null

        running.value = true
        error.value = null
        auxBusy.value = false
        var assistantMessageId: Long? = null
        var pendingCalls: List<ToolCall> = emptyList()
        var failed = false
        var roundsUsed = 0
        var toolCallsUsed = 0
        val runStartedAt = System.currentTimeMillis()

        try {
            agentLoop.run(
                config = config,
                mode = conversation.mode,
                customSystemPrompt = agent?.systemPrompt.orEmpty(),
                history = history,
                conversationId = conversationId,
                planContext = planRepository.getPlan(conversationId)?.render(),
                memoryContext = memoryContext,
                triggerMessageId = history.lastOrNull { it.role == ChatRole.USER }?.id,
            ).collect { event ->
                when (event) {
                    is AgentEvent.RoundStarted -> {
                        if (event.round > 1) streaming.value = StreamingState()
                        roundsUsed = maxOf(roundsUsed, event.round)
                        pendingCalls = emptyList()
                    }

                    is AgentEvent.TextDelta ->
                        streaming.update { it.copy(text = it.text + event.text) }

                    is AgentEvent.ThinkingDelta ->
                        streaming.update { it.copy(thinking = it.thinking + event.text) }

                    is AgentEvent.AssistantMessage -> {
                        val storedId = conversationRepository.append(
                            event.message.copy(
                                conversationId = conversationId,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                        assistantMessageId = storedId
                        pendingCalls = event.message.toolCalls
                        streaming.value = StreamingState()
                    }

                    is AgentEvent.ToolCallStarted -> {
                        pendingCalls = pendingCalls.map { call ->
                            if (call.id == event.call.id) {
                                event.call.copy(status = ToolCallStatus.RUNNING)
                            } else {
                                call
                            }
                        }
                        assistantMessageId?.let { id ->
                            conversationRepository.updateToolCalls(id, pendingCalls)
                        }
                    }

                    is AgentEvent.ToolCallFinished -> {
                        // Budget ledger parity with the engine: only calls that
                        // actually ran (done/failed) are counted.
                        if (event.call.status == ToolCallStatus.DONE ||
                            event.call.status == ToolCallStatus.FAILED
                        ) {
                            toolCallsUsed++
                        }
                        pendingCalls = pendingCalls.map { call ->
                            if (call.id == event.call.id) event.call else call
                        }
                        assistantMessageId?.let { id ->
                            conversationRepository.updateToolCalls(id, pendingCalls)
                        }
                        conversationRepository.append(
                            ChatMessage(
                                conversationId = conversationId,
                                role = ChatRole.TOOL,
                                content = if (event.untrusted) {
                                    // Persist the marker so the taint survives
                                    // into later turns and replayed history.
                                    ToolTrust.wrap(event.call.result.orEmpty())
                                } else {
                                    event.call.result.orEmpty()
                                },
                                toolCallId = event.call.id,
                                toolName = event.call.name,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    }

                    is AgentEvent.Usage -> Unit

                    AgentEvent.Completed -> {
                        runId?.let { id ->
                            runRepository.finish(id, RunState.COMPLETED, roundsUsed, toolCallsUsed)
                            if (agentic) {
                                announceRunEnd(
                                    completed = true,
                                    reason = "",
                                    title = conversation.title,
                                    rounds = roundsUsed,
                                    toolCalls = toolCallsUsed,
                                    durationMs = System.currentTimeMillis() - runStartedAt,
                                    runId = id,
                                    showDialog = conversation.mode == AppMode.MAX,
                                )
                            }
                        } ?: run {
                            // A plain reply finished while the user was elsewhere:
                            // let them know instead of silently holding it.
                            if (!AppVisibility.foreground) {
                                runNotifier.notifyConversationFinished(
                                    conversation.title.ifBlank { "BAIC2" },
                                    conversationId,
                                )
                            }
                        }
                    }

                    is AgentEvent.Failed -> {
                        // Keep what the user already saw before the failure.
                        val partial = streaming.value.text
                        if (partial.isNotBlank()) {
                            runCatching {
                                conversationRepository.append(
                                    ChatMessage(
                                        conversationId = conversationId,
                                        role = ChatRole.ASSISTANT,
                                        content = partial,
                                        model = config.model,
                                        createdAt = System.currentTimeMillis(),
                                    ),
                                )
                            }
                            streaming.value = StreamingState()
                        }
                        failed = true
                        error.value = event.error.toChatError()
                        runId?.let { id ->
                            runRepository.finish(id, RunState.FAILED, roundsUsed, toolCallsUsed)
                            if (agentic) {
                                announceRunEnd(
                                    completed = false,
                                    reason = event.error.message,
                                    title = conversation.title,
                                    rounds = roundsUsed,
                                    toolCalls = toolCallsUsed,
                                    durationMs = System.currentTimeMillis() - runStartedAt,
                                    runId = id,
                                    showDialog = conversation.mode == AppMode.MAX,
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val partial = streaming.value.text
                if (partial.isNotBlank()) {
                    conversationRepository.append(
                        ChatMessage(
                            conversationId = conversationId,
                            role = ChatRole.ASSISTANT,
                            content = partial,
                            model = config.model,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                rejectPendingToolCalls()
                runId?.let { runRepository.finish(it, RunState.CANCELLED, roundsUsed, toolCallsUsed) }
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                // A crash between an assistant tool call and its result must
                // not poison the next request: repair the transcript.
                runCatching { rejectPendingToolCalls() }
            }
            failed = true
            error.value = ChatError(ChatError.Kind.INTERNAL, e.message)
            runId?.let { runRepository.finish(it, RunState.FAILED, roundsUsed, toolCallsUsed) }
        } finally {
            running.value = false
            streaming.value = StreamingState()
            confirmation.value = null
            runNotifier.stopRunning()
        }

        if (!failed) {
            if (isFirstTurn && !titleGenerated) {
                maybeGenerateTitle(config)
            }
            // Consolidation is sleep-time work: prefer the background edge,
            // with an overflow fallback for marathon foreground sessions.
            val foregroundNow = AppVisibility.foreground
            val threshold = if (foregroundNow) OVERFLOW_CURATE_MESSAGES else IDLE_CURATE_MESSAGES
            // Best-effort upkeep: a memory failure must never take the turn down.
            quietly {
                maybeCurate(
                    threshold = threshold,
                    config = config,
                    trigger = if (foregroundNow) "overflow" else "idle",
                )
            }
            quietly { maybeAutoCompress(config) }
        }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Intentionally ignored: background upkeep is best-effort.
        }
    }

    /**
     * MAX is autonomous: tell the user how it ended, with an in-app dialog
     * when they are around and a system notification when they are not.
     */
    private fun announceRunEnd(
        completed: Boolean,
        reason: String,
        title: String,
        rounds: Int,
        toolCalls: Int,
        durationMs: Long,
        runId: Long,
        showDialog: Boolean,
    ) {
        if (showDialog) {
            _runCompletion.value = RunCompletion(
                completed = completed,
                title = title.ifBlank { "MAX" },
                reason = reason,
                rounds = rounds,
                toolCalls = toolCalls,
                durationMs = durationMs,
            )
        }
        if (!AppVisibility.foreground) {
            runNotifier.notifyFinished(title.ifBlank { "BAIC2" }, completed, runId)
        }
    }

    /**
     * Images are only materialized for the newest user turn; older image and
     * file attachments are replaced by a short marker so history stays cheap
     * (the assistant already answered them). Stale tool results are stamped
     * with their age, so a measurement from hours ago is never mistaken for
     * the present.
     */
    private suspend fun prepareHistory(messages: List<ChatMessage>): List<ChatMessage> {
        val lastUserId = messages.lastOrNull { it.role == ChatRole.USER }?.id
        val now = System.currentTimeMillis()
        return messages.map { message ->
            val aged = if (message.role == ChatRole.TOOL && message.createdAt > 0 &&
                now - message.createdAt > STALE_TOOL_RESULT_MS
            ) {
                message.copy(
                    content = "[tool result from ${MemoryText.relativeTime(now, message.createdAt)} - " +
                        "re-check before relying on it]\n" + message.content,
                )
            } else {
                message
            }
            if (aged.attachments.isEmpty()) return@map aged
            if (aged.id == lastUserId) {
                aged.copy(
                    attachments = aged.attachments.map { attachmentProcessor.withBase64(it) },
                )
            } else {
                val marker = aged.attachments.joinToString(" ") { attachment ->
                    "[附件: ${attachment.fileName ?: attachment.kind.name}]"
                }
                aged.copy(
                    content = aged.content.ifBlank { marker },
                    attachments = emptyList(),
                )
            }
        }
    }

    /**
     * Priming: prospective notes (plans/events whose time is near) surface on
     * their own, entity recall answers "about this person/project", and
     * cue-driven recall wakes keyword-matched notes - cues come from the
     * current message, the recent user turns and the conversation title, so
     * recall is context-dependent like a person's. Retrieval reconsolidates
     * and wires them; everything else stays out of the window.
     */
    private suspend fun primedNotes(
        text: String,
        history: List<ChatMessage>,
        title: String,
    ): List<Note> {
        val cueText = buildString {
            if (title.isNotBlank()) append(title).append('\n')
            history.filter { it.role == ChatRole.USER }.takeLast(2).forEach {
                append(it.content.take(240)).append('\n')
            }
            append(text)
        }
        val terms = MemoryText.terms(cueText)
        val cued = if (terms.isEmpty()) {
            emptyList()
        } else {
            memoryRepository.recall(
                terms = terms,
                limit = PREFETCH_LIMIT,
                minHits = if (terms.size == 1) 1 else 2,
                excludeKinds = setOf(NoteKind.SUMMARY),
            ).map { it.note }
        }
        val names = memoryRepository.knownEntities()
            .filter { name -> cueText.contains(name, ignoreCase = true) }
            .take(3)
        val byEntity = if (names.isEmpty()) {
            emptyList()
        } else {
            memoryRepository.recallByEntity(names, limit = UPCOMING_LIMIT).map { it.note }
        }
        val upcoming = memoryRepository.upcoming(limit = UPCOMING_LIMIT)
        return (upcoming + byEntity + cued).distinctBy { it.id }.take(PREFETCH_LIMIT + UPCOMING_LIMIT)
    }

    private suspend fun maybeGenerateTitle(config: ProviderConfig) {
        val messages = conversationRepository.getMessages(conversationId)
        val firstUser = messages.firstOrNull { it.role == ChatRole.USER } ?: return
        val firstAssistant = messages.firstOrNull { it.role == ChatRole.ASSISTANT && it.content.isNotBlank() }
        titleGenerated = true
        runCatching {
            val title = auxiliaryTasks.complete(
                config = config,
                systemPrompt = AuxiliaryTasks.TITLE_SYSTEM,
                userPrompt = buildString {
                    append(firstUser.content.take(400))
                    firstAssistant?.content?.takeIf { it.isNotBlank() }?.let {
                        append("\n\nAssistant replied: ").append(it.take(200))
                    }
                },
                maxTokens = 32,
                temperature = 0.3,
            ).lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.trim('"', '\'', '。', '.', '：', ':')
                ?.take(40)
            if (!title.isNullOrBlank()) {
                conversationRepository.updateTitle(conversationId, title)
            }
        }
    }

    private suspend fun maybeCurate(threshold: Int, config: ProviderConfig, trigger: String) {
        val messages = conversationRepository.getMessages(conversationId)
        val assistantCount = messages.count { it.role == ChatRole.ASSISTANT }
        val lastRunAt = memoryRepository.lastCuratorRunAt()
        val newestAt = messages.lastOrNull()?.createdAt ?: 0L
        // A marathon foreground session must still consolidate: activity after
        // a long gap is due on time, regardless of the turn counter.
        val timeDue = lastRunAt > 0L && System.currentTimeMillis() - lastRunAt >= TIME_CURATE_MS &&
            newestAt > lastRunAt
        if (lastCuratedAssistantCount < 0) {
            // First observation baselines, unless a timed pass is already due.
            lastCuratedAssistantCount = assistantCount
            if (!timeDue) return
        } else if (!timeDue && assistantCount - lastCuratedAssistantCount < threshold) {
            return
        }
        // A single agentic turn can add many assistant rows, so a modulo check
        // would jump past exact multiples and never fire; keep a high-water mark.
        val outcome = runCurator(config, if (timeDue) "time" else trigger)
        if (outcome != null) {
            lastCuratedAssistantCount = assistantCount
        }
    }

    private data class CuratorOutcome(val added: Int, val revised: Int, val forgotten: Int)

    /** Returns null when the request failed; counts otherwise. Always logged. */
    private suspend fun runCurator(config: ProviderConfig, trigger: String): CuratorOutcome? {
        val messages = conversationRepository.getMessages(conversationId)
            .filter { it.role == ChatRole.USER || (it.role == ChatRole.ASSISTANT && it.content.isNotBlank()) }
            .takeLast(30)
        if (messages.none { it.role == ChatRole.USER }) return null
        val notes = memoryRepository.listActive(CURATOR_NOTES)
        val core = memoryRepository.getCore()
        val now = System.currentTimeMillis()
        val fading = memoryRepository.rehearsalCandidates(now)
        val holds = memoryRepository.listHolds()
        val raw = try {
            auxiliaryTasks.complete(
                config = config,
                systemPrompt = AuxiliaryTasks.CURATOR_SYSTEM,
                userPrompt = AuxiliaryTasks.renderCuratorPrompt(
                    transcript = AuxiliaryTasks.renderTranscript(messages, perMessageLimit = 400),
                    inventory = MemoryPrompt.inventory(notes),
                    coreBlocks = MemoryPrompt.coreBlocks(core),
                    fading = MemoryPrompt.fading(fading, now),
                    holds = MemoryPrompt.holds(holds),
                ),
                maxTokens = 900,
                temperature = 0.2,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logCuratorRun(
                trigger = trigger,
                messages = messages,
                notesScanned = notes.size,
                parsed = false,
                error = e.message ?: "consolidation request failed",
            )
            return null
        }
        val plan = AuxiliaryTasks.parseCuratorPlanOrNull(raw)
        if (plan == null) {
            // A reply without a plan is a failure, not an empty plan.
            logCuratorRun(
                trigger = trigger,
                messages = messages,
                notesScanned = notes.size,
                parsed = false,
                error = "the model replied without a plan JSON",
            )
            return null
        }
        var added = 0
        var revised = 0
        plan.remember.forEach { item ->
            val outcome = memoryRepository.addNote(
                kind = NoteKind.fromWire(item.kind),
                content = item.content,
                importance = item.importance,
                conversationId = conversationId,
                messageId = messages.lastOrNull()?.id,
                whenAt = MemoryText.parseWhen(item.whenRaw),
                source = item.source?.let(NoteSource::fromWire) ?: NoteSource.USER,
                entities = item.entities,
                expiresAt = MemoryText.parseExpiry(item.expiresRaw),
            )
            when (outcome) {
                is MemoryRepository.AddOutcome.Saved -> added++
                // Reconsolidated near-duplicates are revisions, not additions.
                is MemoryRepository.AddOutcome.Merged -> revised++
                is MemoryRepository.AddOutcome.Duplicate,
                is MemoryRepository.AddOutcome.Suppressed,
                is MemoryRepository.AddOutcome.Held,
                -> Unit
            }
        }
        plan.revise.forEach { revision ->
            val existing = memoryRepository.noteById(revision.id) ?: return@forEach
            memoryRepository.updateNote(
                id = revision.id,
                content = revision.content ?: existing.content,
                importance = revision.importance ?: existing.importance,
            )
            revised++
        }
        // Sleep maintenance: synaptic pruning first, then rehearsal.
        var forgotten = memoryRepository.pruneStaleNotes(now)
        plan.forget.forEach { id ->
            if (memoryRepository.noteById(id) != null) {
                memoryRepository.archive(id)
                forgotten++
            }
        }
        var rehearsed = 0
        plan.rehearseKeep.forEach { id ->
            if (memoryRepository.noteById(id) != null) {
                memoryRepository.touch(listOf(id))
                rehearsed++
            }
        }
        if (plan.coreUser != null || plan.coreContext != null) {
            memoryRepository.setCore(user = plan.coreUser, context = plan.coreContext)
        }
        logCuratorRun(
            trigger = trigger,
            messages = messages,
            notesScanned = notes.size,
            parsed = true,
            added = added,
            revised = revised,
            forgotten = forgotten,
            rehearsed = rehearsed,
        )
        return CuratorOutcome(added, revised, forgotten)
    }

    /** Best-effort consolidation log; logging must never break a run. */
    private suspend fun logCuratorRun(
        trigger: String,
        messages: List<ChatMessage>,
        notesScanned: Int,
        parsed: Boolean,
        error: String? = null,
        added: Int = 0,
        revised: Int = 0,
        forgotten: Int = 0,
        rehearsed: Int = 0,
    ) {
        runCatching {
            memoryRepository.recordCuratorRun(
                CuratorRun(
                    ranAt = System.currentTimeMillis(),
                    trigger = trigger,
                    conversationId = conversationId,
                    messages = messages.size,
                    windowFrom = messages.firstOrNull()?.createdAt ?: 0L,
                    windowTo = messages.lastOrNull()?.createdAt ?: 0L,
                    notesScanned = notesScanned,
                    added = added,
                    revised = revised,
                    forgotten = forgotten,
                    rehearsed = rehearsed,
                    parsed = parsed,
                    error = error,
                ),
            )
        }
    }

    private suspend fun maybeAutoCompress(config: ProviderConfig) {
        val window = ModelCatalog.entryFor(config.provider, config.model)?.contextWindow
            ?: ModelContextWindows.forModel(config.model)
            ?: return
        val messages = conversationRepository.getMessages(conversationId)
        val mode = conversationRepository.get(conversationId)?.mode ?: AppMode.CHAT
        val estimated = TokenEstimator.estimate(
            messages = messages,
            systemPrompt = renderSystemPrompt(
                mode,
                "",
                planRepository.getPlan(conversationId)?.render(),
            ),
            toolSpecs = toolCatalog.specs(mode),
        )
        val reported = messages.asReversed().firstOrNull { it.usageInput != null }?.usageInput
        val used = reported ?: estimated
        if (used < window * AUTO_COMPRESS_THRESHOLD) return
        // Attempted at most once per completed turn; a later turn may retry,
        // so a conversation that regrows can be compressed again.
        runCompression(config)
    }

    private suspend fun runCompression(config: ProviderConfig? = null) {
        val resolved = config ?: resolveConfig() ?: return
        val messages = conversationRepository.getMessages(conversationId)
        if (messages.size <= KEEP_RECENT_MESSAGES) return

        val boundary = messages.takeLast(KEEP_RECENT_MESSAGES).firstOrNull { it.role == ChatRole.USER }
            ?: return
        val older = messages.takeWhile { it.id < boundary.id }
        if (older.none { it.role == ChatRole.USER }) return

        auxBusy.value = true
        try {
            val summary = auxiliaryTasks.complete(
                config = resolved,
                systemPrompt = AuxiliaryTasks.COMPRESS_SYSTEM,
                userPrompt = AuxiliaryTasks.renderTranscript(older),
                maxTokens = 600,
                temperature = 0.2,
            )
            if (summary.isBlank()) return
            val carrier = older.first()
            conversationRepository.applyCompression(
                conversationId = conversationId,
                summaryCarrierId = carrier.id,
                keepFromMessageId = boundary.id,
                summary = summary,
            )
            memoryRepository.addNote(NoteKind.SUMMARY, summary, conversationId = conversationId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = ChatError(ChatError.Kind.INTERNAL, e.message)
        } finally {
            auxBusy.value = false
        }
    }

    private suspend fun resolveConfig(): ProviderConfig? {
        val conversation = conversationRepository.get(conversationId) ?: return null
        return try {
            agentRepository.resolveConfig(conversation.agentId)
        } catch (e: ApiKeyUnavailableException) {
            error.value = ChatError(ChatError.Kind.API_KEY)
            null
        }
    }

    /**
     * A stop between rounds must not leave assistant tool calls without
     * results — the next request would violate the provider protocol.
     */
    private suspend fun rejectPendingToolCalls() {
        val messages = conversationRepository.getMessages(conversationId)
        val answeredIds = messages.filter { it.role == ChatRole.TOOL }
            .mapNotNull { it.toolCallId }
            .toSet()

        messages.filter { it.role == ChatRole.ASSISTANT && it.toolCalls.isNotEmpty() }
            .forEach { message ->
                val pending = message.toolCalls.filter { call ->
                    call.status == ToolCallStatus.PENDING && call.id !in answeredIds
                }
                if (pending.isNotEmpty()) {
                    pending.forEach { call ->
                        conversationRepository.append(
                            ChatMessage(
                                conversationId = conversationId,
                                role = ChatRole.TOOL,
                                content = "ERROR: cancelled by user",
                                toolCallId = call.id,
                                toolName = call.name,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    val pendingIds = pending.map { it.id }.toSet()
                    val updated = message.toolCalls.map { call ->
                        if (call.id in pendingIds) {
                            call.copy(status = ToolCallStatus.REJECTED, result = "ERROR: cancelled by user")
                        } else {
                            call
                        }
                    }
                    conversationRepository.updateToolCalls(message.id, updated)
                }
            }
    }

    private fun AgentFailure.toChatError(): ChatError = when (kind) {
        AgentFailure.Kind.PROVIDER -> ChatError(ChatError.Kind.PROVIDER, message)
        AgentFailure.Kind.BUDGET -> ChatError(ChatError.Kind.BUDGET, message)
        AgentFailure.Kind.UNSUPPORTED_PROVIDER -> ChatError(ChatError.Kind.UNSUPPORTED, message)
        AgentFailure.Kind.INTERNAL -> ChatError(ChatError.Kind.INTERNAL, message)
    }

    companion object {
        const val ARG_CONVERSATION_ID = "conversationId"
        private const val TITLE_MAX_CHARS = 24
        private const val KEEP_RECENT_MESSAGES = 6
        private val TEXT_LIKE_MIMES = setOf(
            "application/json",
            "application/xml",
            "application/javascript",
            "application/x-yaml",
        )
        private const val IDLE_CURATE_MESSAGES = 4
        private const val OVERFLOW_CURATE_MESSAGES = 24
        /** A marathon foreground session still consolidates after this gap. */
        private const val TIME_CURATE_MS = 12 * 3_600_000L
        private const val CURATOR_NOTES = 60
        private const val PREFETCH_LIMIT = 4
        private const val UPCOMING_LIMIT = 2
        private const val STALE_TOOL_RESULT_MS = 3_600_000L
        private const val AUTO_COMPRESS_THRESHOLD = 0.85
        private const val MAX_IMAGE_ATTACHMENTS = 4
    }
}
