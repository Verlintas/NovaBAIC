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

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.core.model.Attachment
import com.verlintas.baic2.core.model.AttachmentKind
import com.verlintas.baic2.core.model.ChatMessage
import com.verlintas.baic2.core.model.ChatRole
import com.verlintas.baic2.designsystem.Baic2Mono
import com.verlintas.baic2.designsystem.Baic2Motion
import com.verlintas.baic2.designsystem.Baic2Spacing
import com.verlintas.baic2.designsystem.component.AuroraSurface
import com.verlintas.baic2.device.api.SpeechFailure
import com.verlintas.baic2.designsystem.component.VoiceLevelBars
import com.verlintas.baic2.designsystem.component.Baic2ModeChip
import com.verlintas.baic2.designsystem.component.ThinkingOrb
import com.verlintas.baic2.designsystem.component.pressScale

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenStarred: () -> Unit,
    modifier: Modifier = Modifier,
    retryRequested: Boolean = false,
    onRetryHandled: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Arrived from the Tasks center with "retry": re-run the failed turn once.
    // If a run is active, keep the request pending until it can be consumed.
    LaunchedEffect(retryRequested, state.isRunning) {
        if (!retryRequested || state.isRunning) return@LaunchedEffect
        viewModel.retryLast()
        onRetryHandled()
    }
    var input by rememberSaveable { mutableStateOf("") }
    var modePickerOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var matchIndex by remember { mutableIntStateOf(0) }
    var editTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var skillDialogOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val matches = remember(searchQuery, state.messages) {
        if (searchQuery.isBlank()) {
            emptyList()
        } else {
            state.messages.filter { it.content.contains(searchQuery, ignoreCase = true) }
        }
    }

    val exportLabels = ExportLabels(
        you = stringResource(R.string.chat_export_you),
        assistant = stringResource(R.string.chat_export_assistant),
        toolCall = stringResource(R.string.chat_export_tool),
        thinking = stringResource(R.string.chat_export_thinking),
        emptyConversation = stringResource(R.string.chat_export_empty),
    )
    val exportChooserTitle = stringResource(R.string.chat_export_chooser)
    val attachmentError by viewModel.attachmentErrors.collectAsStateWithLifecycle()
    val notice by viewModel.notices.collectAsStateWithLifecycle()
    val runCompletion by viewModel.runCompletion.collectAsStateWithLifecycle()

    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(3_000)
            viewModel.dismissNotice()
        }
    }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(4),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importImages(uris)
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.importFile(it) }
    }

    var voiceHint by remember { mutableStateOf<String?>(null) }
    val deniedHint = stringResource(R.string.chat_voice_denied)
    val unavailableHint = stringResource(R.string.chat_voice_unavailable)
    val voiceNoMatchHint = stringResource(R.string.chat_voice_no_match)
    val voiceBusyHint = stringResource(R.string.chat_voice_busy)
    val voiceNetworkHint = stringResource(R.string.chat_voice_network)
    val voicePrompt = stringResource(R.string.chat_voice_input)
    val handsFree by viewModel.handsFree.collectAsStateWithLifecycle()
    val voice by viewModel.voiceState.collectAsStateWithLifecycle()
    var voiceBase by remember { mutableStateOf("") }
    var voiceAutoSend by remember { mutableStateOf(false) }
    var pendingVoiceAutoSend by remember { mutableStateOf(false) }
    var handsFreeHandledMessageId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Layout-driven bottom follow: react to layout growth instead of polling.
    var wasAtBottom by remember { mutableStateOf(true) }
    var forceFollow by remember { mutableStateOf(false) }
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val recognized = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else {
            null
        }
        val auto = voiceAutoSend
        voiceAutoSend = false
        if (recognized.isNullOrBlank()) return@rememberLauncherForActivityResult
        if (auto) {
            forceFollow = true
            viewModel.send(recognized)
        } else {
            input = recognized
        }
    }

    fun launchRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, voicePrompt)
        }
        runCatching { speechLauncher.launch(intent) }
            .onFailure { voiceHint = unavailableHint }
    }

    val compressTooShortHint = stringResource(R.string.chat_compress_too_short)
    val memorySavedTemplate = stringResource(R.string.chat_memory_saved)
    val memoryNoneLabel = stringResource(R.string.chat_memory_none)
    val memoryFailedLabel = stringResource(R.string.chat_memory_failed)
    val screenPrompt = stringResource(R.string.chat_screen_prompt)
    val screenReady by viewModel.screenCaptureReady.collectAsStateWithLifecycle()
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null &&
            viewModel.onScreenPermissionResult(result.resultCode, data)
        ) {
            viewModel.analyzeScreen(screenPrompt)
        }
    }

    fun continueVoiceStart(autoSend: Boolean) {
        voiceBase = if (autoSend) "" else input
        voiceAutoSend = autoSend
        voiceHint = null
        if (!viewModel.startVoiceSession()) {
            // No on-device recogniser: fall back to the system dialog.
            launchRecognizer()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            continueVoiceStart(pendingVoiceAutoSend)
        } else {
            voiceHint = deniedHint
        }
        pendingVoiceAutoSend = false
    }

    fun startVoiceInput(autoSend: Boolean = false) {
        if (voice.listening) {
            viewModel.stopVoiceSession()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            continueVoiceStart(autoSend)
        } else {
            pendingVoiceAutoSend = autoSend
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Live dictation results: dictation fills the composer, hands-free sends.
    LaunchedEffect(Unit) {
        viewModel.voiceResults.collect { text ->
            val combined = listOf(voiceBase, text)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            voiceBase = ""
            if (voiceAutoSend) {
                voiceAutoSend = false
                if (combined.isNotBlank()) {
                    forceFollow = true
                    viewModel.send(combined)
                }
            } else {
                input = combined
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.voiceFailures.collect { failure ->
            voiceHint = when (failure) {
                SpeechFailure.NO_MATCH, SpeechFailure.TIMEOUT -> voiceNoMatchHint
                SpeechFailure.BUSY -> voiceBusyHint
                SpeechFailure.NETWORK -> voiceNetworkHint
                SpeechFailure.NO_SERVICE -> unavailableHint
                SpeechFailure.PERMISSION -> deniedHint
                SpeechFailure.UNKNOWN -> unavailableHint
            }
            voiceAutoSend = false
            voiceBase = ""
        }
    }

    LaunchedEffect(voiceHint) {
        if (voiceHint != null) {
            kotlinx.coroutines.delay(3_000)
            voiceHint = null
        }
    }

    LaunchedEffect(attachmentError) {
        if (attachmentError != null) {
            kotlinx.coroutines.delay(3_000)
            viewModel.dismissAttachmentError()
        }
    }

    // Enabling hands-free baselines the current last message, so the loop
    // starts with the *next* reply instead of swallowing the first one.
    LaunchedEffect(handsFree) {
        handsFreeHandledMessageId = if (handsFree) {
            state.messages.lastOrNull()?.id ?: 0L
        } else {
            null
        }
    }

    // Hands-free loop: after every *new* assistant reply, reopen the mic and
    // auto-send what was said. Entering a conversation must not fire it, and
    // it stays quiet while a run is active or a dialog is already open.
    LaunchedEffect(
        handsFree,
        state.isRunning,
        state.messages.lastOrNull()?.id,
    ) {
        if (!handsFree) {
            return@LaunchedEffect
        }
        if (state.isRunning || voice.listening) return@LaunchedEffect
        val last = state.messages.lastOrNull() ?: return@LaunchedEffect
        if (last.role != ChatRole.ASSISTANT || last.content.isBlank()) return@LaunchedEffect
        val handled = handsFreeHandledMessageId
        if (handled == null || handled == last.id) return@LaunchedEffect
        handsFreeHandledMessageId = last.id
        kotlinx.coroutines.delay(650)
        // Do not listen while the reply is being read aloud.
        var waited = 0
        while (viewModel.isSpeaking() && waited < 30_000) {
            kotlinx.coroutines.delay(200)
            waited += 200
        }
        if (!state.isRunning && !voice.listening) {
            startVoiceInput(autoSend = true)
        }
    }

    LaunchedEffect(searchQuery, matches.size) {
        matchIndex = 0
    }
    LaunchedEffect(matchIndex, matches) {
        val target = matches.getOrNull(matchIndex) ?: return@LaunchedEffect
        val index = state.messages.indexOfFirst { it.id == target.id }
        if (index >= 0) {
            runCatching { listState.animateScrollToItem(index) }
        }
    }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                wasAtBottom = false
                forceFollow = false
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull { it.index == info.totalItemsCount - 1 }
                    val pinned = last != null && last.offset >= 0 &&
                        last.offset + last.size <= info.viewportEndOffset + 1
                    if (pinned) wasAtBottom = true
                }
            }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .collect { info ->
                val total = info.totalItemsCount
                if (total == 0 || searchOpen) return@collect
                if (!forceFollow && !wasAtBottom) return@collect
                if (listState.isScrollInProgress) return@collect
                val last = info.visibleItemsInfo.lastOrNull { it.index == total - 1 }
                if (last == null || last.offset + last.size > info.viewportEndOffset + 2) {
                    runCatching { listState.scrollToItem(total - 1, Int.MAX_VALUE) }
                }
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(
                WindowInsets.ime.union(WindowInsets.navigationBars),
            ),
    ) {
        ChatTopBar(
            title = state.title.ifBlank { stringResource(R.string.chat_untitled) },
            mode = state.mode,
            running = state.isRunning,
            usageLabel = usageLabel(state),
            usageLevel = usageLevel(state),
            searchOpen = searchOpen,
            searchQuery = searchQuery,
            matchPosition = if (matches.isEmpty()) 0 else matchIndex + 1,
            matchCount = matches.size,
            onBack = onBack,
            onModeClick = { modePickerOpen = true },
            onStop = viewModel::stop,
            onMenuClick = { menuOpen = true },
            menuOpen = menuOpen,
            onMenuDismiss = { menuOpen = false },
            menuContent = {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_menu_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    searchOpen = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_menu_starred)) },
                onClick = {
                    menuOpen = false
                    onOpenStarred()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_menu_export)) },
                onClick = {
                    menuOpen = false
                    val text = viewModel.buildExportText(exportLabels)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/markdown"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, exportChooserTitle))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_menu_save_skill)) },
                onClick = {
                    menuOpen = false
                    skillDialogOpen = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_menu_screen_analysis)) },
                onClick = {
                    menuOpen = false
                    if (screenReady) {
                        viewModel.analyzeScreen(screenPrompt)
                    } else {
                        runCatching { screenCaptureLauncher.launch(viewModel.createScreenCaptureIntent()) }
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_menu_compress)) },
                onClick = {
                    menuOpen = false
                    viewModel.compressContext(compressTooShortHint)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_menu_distill)) },
                onClick = {
                    menuOpen = false
                    viewModel.reflectMemory(
                        savedTemplate = memorySavedTemplate,
                        noneLabel = memoryNoneLabel,
                        failedLabel = memoryFailedLabel,
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_menu_hands_free)) },
                trailingIcon = {
                    if (handsFree) {
                        Icon(Icons.Outlined.Check, contentDescription = null)
                    }
                },
                onClick = {
                    menuOpen = false
                    viewModel.setHandsFree(!handsFree)
                },
            )
            if (state.compressionSnapshot != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_restore_compression)) },
                    onClick = {
                        menuOpen = false
                        viewModel.restoreCompression()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_discard_compression)) },
                    onClick = {
                        menuOpen = false
                        viewModel.discardCompression()
                    },
                )
            }
            },
            onSearchQueryChange = { searchQuery = it },
            onSearchClose = {
                searchOpen = false
                searchQuery = ""
            },
            onSearchPrev = {
                if (matches.isNotEmpty()) matchIndex = (matchIndex - 1 + matches.size) % matches.size
            },
            onSearchNext = {
                if (matches.isNotEmpty()) matchIndex = (matchIndex + 1) % matches.size
            },
        )

        AnimatedVisibility(visible = state.auxBusy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = Baic2Spacing.lg,
                end = Baic2Spacing.lg,
                top = Baic2Spacing.lg,
                bottom = Baic2Spacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(Baic2Spacing.md),
        ) {
            if (state.messages.isEmpty() && !state.isRunning) {
                item(key = "welcome") {
                    WelcomePanel(onSuggestion = { input = it })
                }
            }

            state.plan?.takeIf { it.steps.isNotEmpty() }?.let { plan ->
                item(key = "plan") {
                    PlanCard(plan = plan, modifier = Modifier.animateItem())
                }
            }

            items(state.messages, key = { it.id }) { message ->
                MessageRow(
                    message = message,
                    onCopy = { copied ->
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("baic2", copied.content))
                    },
                    onToggleStar = { viewModel.toggleStar(it.id) },
                    onEdit = { editTarget = it },
                    onDelete = { viewModel.deleteMessage(it.id) },
                    onSpeak = { viewModel.speakMessage(it.content) },
                    modifier = Modifier.animateItem(),
                )
            }

            if (state.isRunning) {
                item(key = "streaming") {
                    Column(modifier = Modifier.animateItem()) {
                        when {
                            state.streamingThinking.isNotBlank() -> {
                                ThinkingCard(text = state.streamingThinking, streaming = true)
                                if (state.streamingText.isNotBlank()) {
                                    Spacer(Modifier.size(Baic2Spacing.sm))
                                }
                            }

                            state.streamingText.isBlank() -> {
                                Row(
                                    modifier = Modifier.padding(vertical = Baic2Spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ThinkingOrb(size = 20.dp)
                                }
                            }
                        }
                        if (state.streamingText.isNotBlank()) {
                            MarkdownMessage(text = state.streamingText, streaming = true)
                        }
                    }
                }
            }

            state.error?.let { error ->
                item(key = "error") {
                    ErrorCard(
                        error = error,
                        canRetry = state.messages.any { it.role == ChatRole.USER },
                        onRetry = {
                            viewModel.dismissError()
                            viewModel.retryLast()
                        },
                        onDismiss = viewModel::dismissError,
                    )
                    }
                }
            }

            val atBottom by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                    last == null ||
                        (last.index >= info.totalItemsCount - 1 &&
                            last.offset + last.size <= info.viewportEndOffset + 24)
                }
            }
            val fabVisible = !atBottom && state.messages.isNotEmpty()
            val fabProgress by animateFloatAsState(
                targetValue = if (fabVisible) 1f else 0f,
                animationSpec = Baic2Motion.effectsFast(),
                label = "jump-fab",
            )
            if (fabProgress > 0.01f) {
                val jumpInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = Baic2Spacing.lg, bottom = Baic2Spacing.lg)
                        .graphicsLayer {
                            scaleX = 0.8f + 0.2f * fabProgress
                            scaleY = 0.8f + 0.2f * fabProgress
                            alpha = fabProgress
                        },
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            forceFollow = true
                            wasAtBottom = true
                            scope.launch {
                                val total = listState.layoutInfo.totalItemsCount
                                if (total > 0) {
                                    runCatching { listState.animateScrollToItem(total - 1) }
                                }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                        interactionSource = jumpInteraction,
                        modifier = Modifier.pressScale(jumpInteraction, pressedScale = 0.92f),
                    ) {
                        Box {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.chat_jump_latest),
                            )
                            if (state.isRunning) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.tertiary),
                                )
                            }
                        }
                    }
                }
            }
        }

        InputBar(
            value = if (voice.listening) {
                listOf(voiceBase, voice.partial).filter { it.isNotBlank() }.joinToString(" ")
            } else {
                input
            },
            onValueChange = {
                if (voice.listening) {
                    viewModel.cancelVoiceSession()
                    voiceBase = ""
                    voiceAutoSend = false
                }
                input = it
            },
            isRunning = state.isRunning,
            pendingAttachments = state.pendingAttachments,
            attachmentError = attachmentError,
            voiceHint = voiceHint ?: notice,
            voiceListening = voice.listening,
            voiceLevel = voice.level,
            onVoiceCancel = {
                viewModel.cancelVoiceSession()
                voiceBase = ""
                voiceAutoSend = false
            },
            onVoiceInput = { startVoiceInput() },
            onAttachImages = {
                imagePicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                    ),
                )
            },
            onAttachFile = {
                filePicker.launch(
                    arrayOf(
                        "text/*",
                        "application/json",
                        "application/xml",
                        "application/javascript",
                        "application/pdf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    ),
                )
            },
            onRemoveAttachment = viewModel::removePendingAttachment,
            onDismissAttachmentError = viewModel::dismissAttachmentError,
            onSend = {
                val text = input
                input = ""
                forceFollow = true
                viewModel.cancelVoiceSession()
                viewModel.send(text)
            },
            onStop = viewModel::stop,
        )
    }



    if (modePickerOpen) {
        ModePickerSheet(
            current = state.mode,
            onSelect = { mode ->
                viewModel.setMode(mode)
                modePickerOpen = false
            },
            onDismiss = { modePickerOpen = false },
        )
    }

    runCompletion?.let { completion ->
        val duration = remember(completion.durationMs) {
            val seconds = (completion.durationMs / 1000).coerceAtLeast(1)
            if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissRunCompletion,
            title = {
                Text(
                    stringResource(
                        if (completion.completed) {
                            R.string.chat_run_done_title
                        } else {
                            R.string.chat_run_stopped_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    if (completion.completed) {
                        stringResource(
                            R.string.chat_run_done_body,
                            completion.title,
                            completion.rounds,
                            completion.toolCalls,
                            duration,
                        )
                    } else {
                        stringResource(
                            R.string.chat_run_stopped_body,
                            completion.title,
                            completion.reason.ifBlank { "—" },
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissRunCompletion) {
                    Text(stringResource(R.string.chat_run_dismiss))
                }
            },
        )
    }

    if (skillDialogOpen) {
        var skillName by remember { mutableStateOf("") }
        val savedTemplate = stringResource(R.string.chat_skill_saved)
        val emptyLabel = stringResource(R.string.chat_skill_none)
        AlertDialog(
            onDismissRequest = { skillDialogOpen = false },
            title = { Text(stringResource(R.string.chat_skill_name_title)) },
            text = {
                OutlinedTextField(
                    value = skillName,
                    onValueChange = { skillName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveLastRunAsSkill(skillName, savedTemplate, emptyLabel)
                        skillDialogOpen = false
                    },
                    enabled = skillName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.chat_skill_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { skillDialogOpen = false }) {
                    Text(stringResource(R.string.chat_dismiss))
                }
            },
        )
    }

    state.confirmRequest?.let { call ->
        AlertDialog(
            onDismissRequest = { viewModel.respondConfirmation(false) },
            title = { Text(stringResource(R.string.chat_confirm_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.chat_confirm_body, call.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (call.argumentsJson.isNotBlank() && call.argumentsJson != "{}") {
                        Spacer(Modifier.size(Baic2Spacing.sm))
                        CodeBlock(language = "args", code = call.argumentsJson)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.respondConfirmation(true) }) {
                    Text(stringResource(R.string.chat_confirm_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.respondConfirmation(false) }) {
                    Text(stringResource(R.string.chat_confirm_reject))
                }
            },
        )
    }

    editTarget?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.content) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text(stringResource(R.string.chat_edit_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.editAndResend(target.id, text)
                        editTarget = null
                    },
                ) {
                    Text(stringResource(R.string.chat_edit_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) {
                    Text(stringResource(R.string.chat_dismiss))
                }
            },
        )
    }
}

private fun usageLabel(state: ChatUiState): String? {
    val used = state.contextUsedTokens ?: return null
    val window = state.contextWindowTokens
    val prefix = if (state.contextEstimated) "~" else ""
    return if (window != null && window > 0) {
        val percent = (used * 100 / window).coerceIn(0, 999)
        "$prefix${formatTokens(used)}/${formatTokens(window)} · $percent%"
    } else {
        "$prefix${formatTokens(used)}"
    }
}

/** Percent of the context window in use, or null when the window is unknown. */
private fun usageLevel(state: ChatUiState): Int? {
    val used = state.contextUsedTokens ?: return null
    val window = state.contextWindowTokens ?: return null
    if (window <= 0) return null
    return (used * 100 / window).coerceIn(0, 999).toInt()
}

private fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1fK", tokens / 1_000.0)
    else -> tokens.toString()
}

@Composable
private fun ChatTopBar(
    title: String,
    mode: AppMode,
    running: Boolean,
    usageLabel: String?,
    usageLevel: Int?,
    searchOpen: Boolean,
    searchQuery: String,
    matchPosition: Int,
    matchCount: Int,
    onBack: () -> Unit,
    onModeClick: () -> Unit,
    onStop: () -> Unit,
    onMenuClick: () -> Unit,
    menuOpen: Boolean,
    onMenuDismiss: () -> Unit,
    menuContent: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onSearchPrev: () -> Unit,
    onSearchNext: () -> Unit,
) {
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchOpen) {
        if (searchOpen) runCatching { searchFocus.requestFocus() }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = Baic2Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (searchOpen) {
                IconButton(onClick = onSearchClose) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.chat_search_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(searchFocus),
                    decorationBox = { inner ->
                        Box {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.chat_search_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                            inner()
                        }
                    },
                )
                Text(
                    text = "$matchPosition/$matchCount",
                    style = Baic2Mono.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Baic2Spacing.sm),
                )
                IconButton(onClick = onSearchPrev) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.chat_search_prev),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onSearchNext) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.chat_search_next),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val backInteraction = remember { MutableInteractionSource() }
                IconButton(
                    onClick = onBack,
                    interactionSource = backInteraction,
                    modifier = Modifier.pressScale(backInteraction),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.chat_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                usageLabel?.let { label ->
                    val usageColor = when {
                        usageLevel == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        usageLevel >= 90 -> MaterialTheme.colorScheme.error
                        usageLevel >= 70 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = label,
                        style = Baic2Mono.label,
                        color = usageColor.copy(alpha = 0.85f),
                        modifier = Modifier.padding(end = Baic2Spacing.sm),
                    )
                }
                val modeInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .pressScale(modeInteraction, pressedScale = 0.92f)
                        .clickable(
                            interactionSource = modeInteraction,
                            indication = null,
                            onClick = onModeClick,
                        ),
                ) {
                    Baic2ModeChip(
                        mode = mode,
                        modifier = Modifier.padding(horizontal = Baic2Spacing.xs),
                    )
                }
                AnimatedVisibility(
                    visible = running,
                    enter = scaleIn(animationSpec = Baic2Motion.spatialFast()) + fadeIn(),
                    exit = scaleOut(animationSpec = Baic2Motion.effectsFast()) + fadeOut(),
                ) {
                    val stopInteraction = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = onStop,
                        interactionSource = stopInteraction,
                        modifier = Modifier.pressScale(stopInteraction),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.chat_stop),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Box {
                    val menuInteraction = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = onMenuClick,
                        interactionSource = menuInteraction,
                        modifier = Modifier.pressScale(menuInteraction),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.chat_menu),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Anchored to this Box, so the popup always unfolds right
                    // under the ⋮ button no matter the host layout.
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = onMenuDismiss,
                    ) {
                        menuContent()
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun WelcomePanel(onSuggestion: (String) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Baic2Spacing.xxl, bottom = Baic2Spacing.lg)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), shape),
    ) {
        AuroraSurface(
            modifier = Modifier.matchParentSize(),
            shape = shape,
            particleCount = 16,
            intensity = 0.55f,
        )
        Column(
            modifier = Modifier.padding(Baic2Spacing.lg),
        ) {
            Text(
                text = stringResource(R.string.chat_welcome_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Baic2Spacing.xs))
            Text(
                text = stringResource(R.string.chat_welcome_subtitle),
                style = Baic2Mono.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Baic2Spacing.xl))
            listOf(
                R.string.chat_suggestion_1,
                R.string.chat_suggestion_2,
                R.string.chat_suggestion_3,
            ).forEach { res ->
                val text = stringResource(res)
                val itemShape = RoundedCornerShape(14.dp)
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val pressScale by animateFloatAsState(
                    targetValue = if (pressed) 0.97f else 1f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
                    label = "suggestion-press",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Baic2Spacing.sm)
                        .scale(pressScale)
                        .clip(itemShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            itemShape,
                        )
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = { onSuggestion(text) },
                        )
                        .padding(horizontal = Baic2Spacing.lg, vertical = Baic2Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(
    error: ChatError,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f), shape)
            .padding(Baic2Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Baic2Spacing.sm))
            Text(
                text = errorText(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Baic2Spacing.xs),
            horizontalArrangement = Arrangement.End,
        ) {
            if (canRetry) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.chat_retry))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_dismiss))
            }
        }
    }
}

@Composable
private fun errorText(error: ChatError): String = when (error.kind) {
    ChatError.Kind.PROVIDER -> stringResource(R.string.chat_error_provider, error.detail.orEmpty())
    ChatError.Kind.BUDGET -> stringResource(R.string.chat_error_budget)
    ChatError.Kind.NO_AGENT -> stringResource(R.string.chat_error_no_agent)
    ChatError.Kind.API_KEY -> stringResource(R.string.chat_error_api_key)
    ChatError.Kind.UNSUPPORTED -> stringResource(R.string.chat_error_unsupported)
    ChatError.Kind.INTERNAL -> stringResource(R.string.chat_error_internal, error.detail.orEmpty())
    ChatError.Kind.SCREEN_CAPTURE -> stringResource(R.string.chat_error_screen_capture)
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isRunning: Boolean,
    pendingAttachments: List<Attachment>,
    attachmentError: AttachmentError?,
    voiceHint: String?,
    voiceListening: Boolean = false,
    voiceLevel: Float = 0f,
    onVoiceCancel: (() -> Unit)? = null,
    onVoiceInput: () -> Unit,
    onAttachImages: () -> Unit,
    onAttachFile: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onDismissAttachmentError: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = Baic2Spacing.md, vertical = Baic2Spacing.sm),
    ) {
        if (pendingAttachments.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
                modifier = Modifier.padding(bottom = Baic2Spacing.sm),
            ) {
                items(pendingAttachments, key = { it.id }) { attachment ->
                    PendingAttachmentChip(
                        attachment = attachment,
                        onRemove = { onRemoveAttachment(attachment.id) },
                    )
                }
            }
        }

        attachmentError?.let { error ->
            val shape = RoundedCornerShape(10.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Baic2Spacing.sm)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                    .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f), shape)
                    .clickable(onClick = onDismissAttachmentError)
                    .padding(horizontal = Baic2Spacing.md, vertical = Baic2Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(Baic2Spacing.sm))
                Text(
                    text = when (error) {
                        AttachmentError.TOO_LARGE -> stringResource(R.string.chat_attachment_too_large)
                        AttachmentError.UNSUPPORTED -> stringResource(R.string.chat_attachment_unsupported)
                        AttachmentError.READ_FAILED -> stringResource(R.string.chat_attachment_failed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        when {
            voiceListening -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Baic2Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VoiceLevelBars(level = voiceLevel, modifier = Modifier.width(40.dp))
                    Spacer(Modifier.width(Baic2Spacing.sm))
                    Text(
                        text = stringResource(R.string.chat_voice_listening),
                        style = Baic2Mono.label,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    onVoiceCancel?.let { cancel ->
                        Text(
                            text = stringResource(R.string.chat_voice_cancel),
                            style = Baic2Mono.label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = cancel)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            voiceHint != null -> {
                Text(
                    text = voiceHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = Baic2Spacing.sm),
                )
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Box {
                val attachShape = RoundedCornerShape(16.dp)
                val attachInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pressScale(attachInteraction, pressedScale = 0.92f)
                        .clip(attachShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, attachShape)
                        .clickable(
                            interactionSource = attachInteraction,
                            indication = null,
                            onClick = { attachMenuOpen = true },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.chat_attach),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = attachMenuOpen,
                    onDismissRequest = { attachMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_attach_images)) },
                        onClick = {
                            attachMenuOpen = false
                            onAttachImages()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_attach_file)) },
                        onClick = {
                            attachMenuOpen = false
                            onAttachFile()
                        },
                    )
                }
            }

            Spacer(Modifier.width(Baic2Spacing.sm))

            val voiceShape = RoundedCornerShape(16.dp)
            val voiceInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .pressScale(voiceInteraction, pressedScale = 0.92f)
                    .clip(voiceShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, voiceShape)
                    .clickable(
                        interactionSource = voiceInteraction,
                        indication = null,
                        onClick = onVoiceInput,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = stringResource(R.string.chat_voice_input),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(Baic2Spacing.sm))

            val shape = RoundedCornerShape(20.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(
                        width = 1.dp,
                        color = if (focused) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = shape,
                    )
                    .padding(horizontal = Baic2Spacing.lg, vertical = Baic2Spacing.sm),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.chat_input_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            Spacer(Modifier.width(Baic2Spacing.sm))
            SendButton(
                isRunning = isRunning,
                enabled = value.isNotBlank() || pendingAttachments.isNotEmpty(),
                onSend = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSend()
                },
                onStop = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStop()
                },
            )
        }
    }
}

@Composable
private fun PendingAttachmentChip(
    attachment: Attachment,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                start = if (attachment.kind == AttachmentKind.IMAGE) 2.dp else Baic2Spacing.md,
                end = 2.dp,
                top = 2.dp,
                bottom = 2.dp,
            ),
        ) {
            if (attachment.kind == AttachmentKind.IMAGE) {
                attachment.localPath?.let { path ->
                    LocalImageThumbnail(
                        path = path,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Baic2Spacing.sm))
                Text(
                    text = attachment.fileName ?: stringResource(R.string.chat_attach_file),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.chat_attachment_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
internal fun LocalImageThumbnail(
    path: String,
    modifier: Modifier = Modifier,
) {
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
        }
    }
    bitmap?.let { image ->
        androidx.compose.foundation.Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = modifier,
        )
    }
}

@Composable
private fun SendButton(
    isRunning: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(stiffness = 1200f),
        label = "send-scale",
    )
    val shape = RoundedCornerShape(16.dp)
    val container = when {
        isRunning -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when {
        isRunning -> MaterialTheme.colorScheme.error
        enabled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .scale(scale)
            .clip(shape)
            .background(container)
            .border(
                width = 1.dp,
                color = if (isRunning) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                } else {
                    Color.Transparent
                },
                shape = shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = isRunning || enabled,
            ) {
                if (isRunning) onStop() else onSend()
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = isRunning,
            transitionSpec = {
                (fadeIn() + scaleIn(initialScale = 0.7f))
                    .togetherWith(fadeOut() + scaleOut(targetScale = 0.7f))
            },
            label = "send-morph",
        ) { running ->
            Icon(
                imageVector = if (running) Icons.Outlined.Close else Icons.Outlined.Send,
                contentDescription = stringResource(
                    if (running) R.string.chat_stop else R.string.chat_send,
                ),
                tint = content,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModePickerSheet(
    current: AppMode,
    onSelect: (AppMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Baic2Spacing.xl)
                .padding(bottom = Baic2Spacing.xxl),
        ) {
            Text(
                text = stringResource(R.string.chat_mode_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Baic2Spacing.lg))
            AppMode.entries.forEach { mode ->
                val selected = mode == current
                val shape = RoundedCornerShape(14.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Baic2Spacing.sm)
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
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            },
                            shape = shape,
                        )
                        .clickable { onSelect(mode) }
                        .padding(horizontal = Baic2Spacing.lg, vertical = Baic2Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Baic2ModeChip(mode = mode)
                    Spacer(Modifier.width(Baic2Spacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = modeTitle(mode),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = modeDescription(mode),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun modeTitle(mode: AppMode): String = stringResource(
    when (mode) {
        AppMode.CHAT -> R.string.chat_mode_chat_title
        AppMode.CHAT_PLUS -> R.string.chat_mode_chat_plus_title
        AppMode.ACT -> R.string.chat_mode_act_title
        AppMode.MAX -> R.string.chat_mode_max_title
    },
)

@Composable
private fun modeDescription(mode: AppMode): String = stringResource(
    when (mode) {
        AppMode.CHAT -> R.string.chat_mode_chat_description
        AppMode.CHAT_PLUS -> R.string.chat_mode_chat_plus_description
        AppMode.ACT -> R.string.chat_mode_act_description
        AppMode.MAX -> R.string.chat_mode_max_description
    },
)
