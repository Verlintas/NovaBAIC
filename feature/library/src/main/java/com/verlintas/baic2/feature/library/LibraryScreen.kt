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

package com.verlintas.baic2.feature.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.verlintas.baic2.core.data.repository.MemoryRepository
import com.verlintas.baic2.core.model.Automation
import com.verlintas.baic2.core.model.CuratorRun
import com.verlintas.baic2.core.model.McpServer
import com.verlintas.baic2.core.model.MemoryHold
import com.verlintas.baic2.core.model.MemoryText
import com.verlintas.baic2.core.model.Note
import com.verlintas.baic2.core.model.NoteKind
import com.verlintas.baic2.core.model.NoteRevision
import com.verlintas.baic2.core.model.NoteSource
import com.verlintas.baic2.core.model.Skill
import com.verlintas.baic2.mcp.McpServerStatus
import com.verlintas.baic2.designsystem.Baic2Mono
import com.verlintas.baic2.designsystem.Baic2Spacing
import com.verlintas.baic2.designsystem.component.AuroraSurface
import com.verlintas.baic2.designsystem.component.MemoryRing
import com.verlintas.baic2.designsystem.component.pressScale
import kotlinx.coroutines.launch

private object LibraryRoute {
    const val ROOT = "library_root"
    const val MEMORY = "library_memory"
    const val AUTOMATIONS = "library_automations"
    const val SKILLS = "library_skills"
    const val MCP = "library_mcp"
}

@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    onInnerRouteChanged: (Boolean) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val onInnerRoute = backStackEntry?.destination?.route != LibraryRoute.ROOT

    LaunchedEffect(onInnerRoute) { onInnerRouteChanged(onInnerRoute) }

    NavHost(
        navController = navController,
        startDestination = LibraryRoute.ROOT,
        enterTransition = {
            slideInHorizontally(animationSpec = spring(stiffness = 380f)) { it / 5 } +
                fadeIn(tween(220))
        },
        exitTransition = { fadeOut(tween(120)) },
        popEnterTransition = {
            slideInHorizontally(animationSpec = spring(stiffness = 380f)) { -it / 5 } +
                fadeIn(tween(220))
        },
        popExitTransition = {
            slideOutHorizontally(tween(200)) { it / 5 } + fadeOut(tween(120))
        },
        modifier = modifier.fillMaxSize(),
    ) {
        composable(LibraryRoute.ROOT) {
            LibraryHomePage(
                viewModel = viewModel,
                onOpen = { route -> navController.navigate(route) },
            )
        }
        composable(LibraryRoute.MEMORY) {
            MemoryPage(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(LibraryRoute.AUTOMATIONS) {
            AutomationsPage(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(LibraryRoute.SKILLS) {
            SkillsPage(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(LibraryRoute.MCP) {
            McpPage(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun LibraryHomePage(
    viewModel: LibraryViewModel,
    onOpen: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Baic2Spacing.lg,
            end = Baic2Spacing.lg,
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 76.dp,
            bottom = 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
    ) {
        item(key = "header") {
            Column {
                Text(
                    text = stringResource(R.string.feature_library_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.library_subtitle,
                        state.automations.size,
                        state.notes.size,
                    ),
                    style = Baic2Mono.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "entry-memory") {
            LibraryEntryCard(
                title = stringResource(R.string.library_memory_title),
                summary = stringResource(R.string.library_entry_memory_summary, state.notes.size),
                memoryRing = true,
                aurora = true,
                onClick = { onOpen(LibraryRoute.MEMORY) },
            )
        }
        item(key = "entry-automations") {
            LibraryEntryCard(
                icon = Icons.Outlined.PlayArrow,
                title = stringResource(R.string.library_automations_title),
                summary = stringResource(
                    R.string.library_entry_automations_summary,
                    state.automations.size,
                ),
                onClick = { onOpen(LibraryRoute.AUTOMATIONS) },
            )
        }
        item(key = "entry-skills") {
            LibraryEntryCard(
                icon = Icons.Outlined.List,
                title = stringResource(R.string.library_skills_title),
                summary = stringResource(R.string.library_entry_skills_summary, state.skills.size),
                onClick = { onOpen(LibraryRoute.SKILLS) },
            )
        }
        item(key = "entry-mcp") {
            LibraryEntryCard(
                icon = Icons.Outlined.Share,
                title = stringResource(R.string.library_mcp_title),
                summary = stringResource(R.string.library_entry_mcp_summary, state.mcpServers.size),
                onClick = { onOpen(LibraryRoute.MCP) },
            )
        }
    }
}

@Composable
private fun LibraryEntryCard(
    title: String,
    summary: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    memoryRing: Boolean = false,
    aurora: Boolean = false,
) {
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape),
    ) {
        if (aurora) {
            AuroraSurface(
                modifier = Modifier.matchParentSize(),
                shape = shape,
                particleCount = 10,
                intensity = 0.45f,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = Baic2Spacing.lg, vertical = Baic2Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (memoryRing) {
                MemoryRing(
                    modifier = Modifier.size(40.dp),
                    dotCount = 10,
                )
            } else if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(Baic2Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = Baic2Mono.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- memory

@Composable
private fun MemoryPage(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editingNote by remember { mutableStateOf<Note?>(null) }
    var editingCoreSlot by remember { mutableStateOf<String?>(null) }
    var hardDeleteTarget by remember { mutableStateOf<Note?>(null) }
    var importResult by remember { mutableStateOf<MemoryRepository.ImportResult?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Memory as a safety feature: notes + core + holds export to a readable
    // JSON file, and a backup merges back through the normal write protocol.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = viewModel.exportMemory()
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                importResult = if (text == null) {
                    MemoryRepository.ImportResult(valid = false)
                } else {
                    viewModel.importMemory(text)
                }
            }
        }
    }

    importResult?.let { result ->
        AlertDialog(
            onDismissRequest = { importResult = null },
            title = { Text(stringResource(R.string.library_import_title)) },
            text = {
                Text(
                    if (!result.valid) {
                        stringResource(R.string.library_import_failed)
                    } else {
                        stringResource(
                            R.string.library_import_result,
                            result.imported,
                            result.merged,
                            result.skipped,
                            result.holds,
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { importResult = null }) {
                    Text(stringResource(R.string.library_import_ok))
                }
            },
        )
    }

    editingNote?.let { note ->
        NoteEditDialog(
            note = note,
            onDismiss = { editingNote = null },
            revisionsFor = viewModel::revisionsFor,
            onSave = { text ->
                viewModel.updateNote(note.id, text, note.importance)
                editingNote = null
            },
        )
    }
    hardDeleteTarget?.let { note ->
        AlertDialog(
            onDismissRequest = { hardDeleteTarget = null },
            title = { Text(stringResource(R.string.library_hard_delete_title)) },
            text = { Text(stringResource(R.string.library_hard_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.hardDeleteNote(note.id)
                        hardDeleteTarget = null
                    },
                ) {
                    Text(stringResource(R.string.library_hard_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { hardDeleteTarget = null }) {
                    Text(stringResource(R.string.library_cancel))
                }
            },
        )
    }
    editingCoreSlot?.let { slot ->
        val isUser = slot == LibraryViewModel.CORE_USER_SLOT
        CoreEditDialog(
            title = stringResource(
                if (isUser) R.string.library_core_user else R.string.library_core_context,
            ),
            initial = if (isUser) state.core.user else state.core.context,
            onDismiss = { editingCoreSlot = null },
            onSave = { text ->
                viewModel.saveCore(slot, text)
                editingCoreSlot = null
            },
        )
    }

    LibraryPageScaffold(title = stringResource(R.string.library_memory_title), onBack = onBack) {
        item(key = "memory-hero") {
            val heroShape = RoundedCornerShape(20.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(heroShape)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f),
                        heroShape,
                    ),
            ) {
                AuroraSurface(
                    modifier = Modifier.matchParentSize(),
                    shape = heroShape,
                    particleCount = 18,
                    intensity = 0.85f,
                )
                Column(
                    modifier = Modifier.padding(
                        horizontal = Baic2Spacing.lg,
                        vertical = Baic2Spacing.xl,
                    ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.library_memory_tagline),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(
                                    R.string.library_memory_page_subtitle,
                                    state.notes.size,
                                ),
                                style = Baic2Mono.label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(Baic2Spacing.md))
                        MemoryRing(
                            modifier = Modifier.size(64.dp),
                            dotCount = 12,
                        )
                    }
                }
            }
        }
        item(key = "memory-io") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = {
                        exportLauncher.launch(
                            "baic2-memory-${MemoryText.dateOnly(System.currentTimeMillis())}.json",
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.library_memory_export))
                }
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.library_memory_import))
                }
            }
        }
        item(key = "core-user") {
            CoreMemoryCard(
                label = stringResource(R.string.library_core_user),
                content = state.core.user,
                onClick = { editingCoreSlot = LibraryViewModel.CORE_USER_SLOT },
            )
        }
        item(key = "core-context") {
            CoreMemoryCard(
                label = stringResource(R.string.library_core_context),
                content = state.core.context,
                onClick = { editingCoreSlot = LibraryViewModel.CORE_CONTEXT_SLOT },
            )
        }
        if (state.holds.isNotEmpty()) {
            item(key = "holds-title") {
                Text(
                    text = stringResource(R.string.library_holds_title),
                    style = Baic2Mono.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Baic2Spacing.xs, top = Baic2Spacing.sm),
                )
            }
            items(state.holds, key = { "hold-${it.id}" }) { hold ->
                HoldRow(
                    hold = hold,
                    onLift = { viewModel.liftHold(hold.id) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (state.curatorRuns.isNotEmpty()) {
            item(key = "curator-title") {
                Text(
                    text = stringResource(R.string.library_curator_title),
                    style = Baic2Mono.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Baic2Spacing.xs, top = Baic2Spacing.sm),
                )
            }
            items(state.curatorRuns, key = { "curator-${it.id}" }) { run ->
                CuratorRunRow(run)
            }
        }
        item(key = "memory-search") {
            OutlinedTextField(
                value = state.noteQuery,
                onValueChange = viewModel::setNoteQuery,
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text(stringResource(R.string.library_notes_search)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.notes.isEmpty()) {
            item(key = "memory-empty") {
                HintCard(
                    if (state.noteQuery.isBlank()) {
                        stringResource(R.string.library_memory_empty)
                    } else {
                        stringResource(R.string.library_notes_no_match)
                    },
                )
            }
        } else {
            items(state.notes, key = { "note-${it.id}" }) { note ->
                NoteRow(
                    note = note,
                    onPin = { viewModel.setNotePinned(note.id, !note.pinned) },
                    onEdit = { editingNote = note },
                    onDelete = { viewModel.deleteNote(note.id) },
                    onLongPress = { hardDeleteTarget = note },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- automations

@Composable
private fun AutomationsPage(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LibraryPageScaffold(title = stringResource(R.string.library_automations_title), onBack = onBack) {
        if (state.automations.isEmpty()) {
            item(key = "automations-empty") {
                HintCard(stringResource(R.string.library_automations_empty))
            }
        } else {
            items(state.automations, key = { "auto-${it.id}" }) { automation ->
                AutomationRow(
                    automation = automation,
                    onToggle = { enabled -> viewModel.setAutomationEnabled(automation.id, enabled) },
                    onDelete = { viewModel.deleteAutomation(automation.id) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- skills

@Composable
private fun SkillsPage(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val skillPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            viewModel.importSkill(it, it.lastPathSegment?.substringAfterLast('/'))
        }
    }

    LibraryPageScaffold(title = stringResource(R.string.library_skills_title), onBack = onBack) {
        item(key = "skills-import") {
            OutlinedButton(
                onClick = {
                    skillPicker.launch(
                        arrayOf("application/yaml", "application/x-yaml", "text/yaml", "text/plain"),
                    )
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.library_skills_import))
            }
        }
        if (state.skills.isEmpty()) {
            item(key = "skills-empty") {
                HintCard(stringResource(R.string.library_skills_empty))
            }
        } else {
            items(state.skills, key = { "skill-${it.id}" }) { skill ->
                SkillRow(
                    skill = skill,
                    onDelete = { viewModel.deleteSkill(skill.id) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- mcp

@Composable
private fun McpPage(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var mcpDialogOpen by rememberSaveable { mutableStateOf(false) }

    if (mcpDialogOpen) {
        var name by rememberSaveable { mutableStateOf("") }
        var url by rememberSaveable { mutableStateOf("https://") }
        AlertDialog(
            onDismissRequest = { mcpDialogOpen = false },
            title = { Text(stringResource(R.string.library_mcp_add)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.library_mcp_name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Baic2Spacing.sm))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.library_mcp_url)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addMcpServer(name, url)
                        mcpDialogOpen = false
                    },
                    enabled = name.isNotBlank() && url.startsWith("http"),
                ) {
                    Text(stringResource(R.string.library_mcp_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { mcpDialogOpen = false }) {
                    Text(stringResource(R.string.library_cancel))
                }
            },
        )
    }

    LibraryPageScaffold(title = stringResource(R.string.library_mcp_title), onBack = onBack) {
        item(key = "mcp-add") {
            OutlinedButton(
                onClick = { mcpDialogOpen = true },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.library_mcp_add))
            }
        }
        if (state.mcpServers.isEmpty()) {
            item(key = "mcp-empty") {
                HintCard(stringResource(R.string.library_mcp_empty))
            }
        } else {
            items(state.mcpServers, key = { "mcp-${it.id}" }) { server ->
                McpRow(
                    server = server,
                    status = state.mcpStatus[server.id],
                    onToggle = { enabled -> viewModel.setMcpEnabled(server.id, enabled) },
                    onDelete = { viewModel.deleteMcpServer(server.id) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- shared chrome

@Composable
private fun LibraryPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Baic2Spacing.sm, vertical = Baic2Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.library_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Baic2Spacing.lg,
                end = Baic2Spacing.lg,
                top = Baic2Spacing.xs,
                bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Baic2Spacing.sm),
            content = content,
        )
    }
}

@Composable
private fun HintCard(text: String) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(horizontal = Baic2Spacing.lg, vertical = Baic2Spacing.md),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- rows

@Composable
private fun AutomationRow(
    automation: Automation,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(start = Baic2Spacing.lg, end = Baic2Spacing.sm, top = Baic2Spacing.sm, bottom = Baic2Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = automation.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = automation.scheduleLabel() + " · " +
                    automation.actions.joinToString(", ") { it.tool },
                style = Baic2Mono.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = automation.enabled,
            onCheckedChange = onToggle,
        )
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.library_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun McpRow(
    server: McpServer,
    status: McpServerStatus?,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(start = Baic2Spacing.lg, end = Baic2Spacing.sm, top = Baic2Spacing.sm, bottom = Baic2Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val statusText = when (status) {
                is McpServerStatus.Connected ->
                    stringResource(R.string.library_mcp_connected, status.toolCount)

                is McpServerStatus.Failed ->
                    stringResource(R.string.library_mcp_failed, status.reason)

                McpServerStatus.Disabled -> stringResource(R.string.library_mcp_disabled)
                null -> server.url
            }
            Text(
                text = statusText,
                style = Baic2Mono.label,
                color = if (status is McpServerStatus.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = server.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.library_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SkillRow(
    skill: Skill,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(start = Baic2Spacing.lg, end = Baic2Spacing.sm, top = Baic2Spacing.md, bottom = Baic2Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = skill.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = skill.description.ifBlank { skill.id } +
                    " · " + stringResource(R.string.library_skill_tools, skill.tools.size),
                style = Baic2Mono.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.library_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun CoreMemoryCard(
    label: String,
    content: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Baic2Spacing.lg, vertical = Baic2Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = Baic2Mono.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.library_core_edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = content.ifBlank { stringResource(R.string.library_core_empty_line) },
            style = MaterialTheme.typography.bodyMedium,
            color = if (content.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun NoteRow(
    note: Note,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val accent = note.kind.accent()
    val interaction = remember { MutableInteractionSource() }
    val strength by animateFloatAsState(
        targetValue = (note.strength / 5.0).toFloat().coerceIn(0.08f, 1f),
        animationSpec = tween(durationMillis = 700),
        label = "note-strength",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
            )
            .padding(start = Baic2Spacing.lg, end = Baic2Spacing.xs, top = Baic2Spacing.sm, bottom = Baic2Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.width(Baic2Spacing.sm))
            Text(
                text = buildString {
                    append(noteKindLabel(note.kind))
                    if (note.source != NoteSource.USER) {
                        append(" · ").append(note.source.wire())
                    }
                    if (note.entities.isNotEmpty()) {
                        append(" · ").append(note.entities.joinToString(" ") { "@$it" })
                    }
                    append(" · ").append(
                        android.text.format.DateUtils.getRelativeTimeSpanString(note.updatedAt),
                    )
                },
                style = Baic2Mono.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onPin) {
                Icon(
                    imageVector = if (note.pinned) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = stringResource(
                        if (note.pinned) R.string.library_note_unpin else R.string.library_note_pin,
                    ),
                    tint = if (note.pinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.library_note_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.library_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = note.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = Baic2Spacing.md),
        )
        Spacer(Modifier.height(Baic2Spacing.sm))
        Box(
            modifier = Modifier
                .padding(end = Baic2Spacing.md)
                .fillMaxWidth()
                .height(2.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(strength)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.85f)),
            )
        }
    }
}

@Composable
private fun NoteEditDialog(
    note: Note,
    onDismiss: () -> Unit,
    revisionsFor: suspend (Long) -> List<NoteRevision>,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable(note.id) { mutableStateOf(note.content) }
    var showHistory by remember { mutableStateOf(false) }
    var revisions by remember { mutableStateOf<List<NoteRevision>>(emptyList()) }
    LaunchedEffect(showHistory) {
        if (showHistory) revisions = revisionsFor(note.id)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_note_edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showHistory) {
                    Spacer(Modifier.height(Baic2Spacing.sm))
                    Text(
                        text = stringResource(R.string.library_revisions, revisions.size),
                        style = Baic2Mono.label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (revisions.isEmpty()) {
                        Text(
                            text = stringResource(R.string.library_revisions_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        revisions.forEach { revision ->
                            Text(
                                text = MemoryText.dateOnly(revision.replacedAt) + " · " +
                                    revision.content.replace('\n', ' ').take(140),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.library_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { showHistory = !showHistory }) {
                    Text(stringResource(R.string.library_revisions_button))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.library_cancel))
                }
            }
        },
    )
}

@Composable
private fun HoldRow(
    hold: MemoryHold,
    onLift: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(start = Baic2Spacing.lg, end = Baic2Spacing.xs, top = Baic2Spacing.md, bottom = Baic2Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hold.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            hold.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = reason,
                    style = Baic2Mono.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onLift) {
            Text(stringResource(R.string.library_hold_lift))
        }
    }
}

@Composable
private fun CoreEditDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable(title) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text(stringResource(R.string.library_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_cancel))
            }
        },
    )
}

@Composable
private fun CuratorRunRow(run: CuratorRun) {
    val trigger = stringResource(
        when (run.trigger) {
            "idle" -> R.string.library_curator_trigger_idle
            "overflow" -> R.string.library_curator_trigger_overflow
            "time" -> R.string.library_curator_trigger_time
            "manual" -> R.string.library_curator_trigger_manual
            else -> R.string.library_curator_trigger_other
        },
    )
    val base = if (run.succeeded) {
        stringResource(
            R.string.library_curator_run,
            MemoryText.formatDateTime(run.ranAt),
            trigger,
            run.messages,
            run.added,
            run.revised,
            run.forgotten,
        )
    } else {
        stringResource(
            R.string.library_curator_run_failed,
            MemoryText.formatDateTime(run.ranAt),
            trigger,
            run.error ?: "unparseable plan",
        )
    }
    val text = base + if (run.reviewed > 0) {
        stringResource(R.string.library_curator_reviewed, run.reviewed)
    } else {
        ""
    }
    Text(
        text = text,
        style = Baic2Mono.label,
        color = if (run.succeeded) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
        modifier = Modifier.padding(horizontal = Baic2Spacing.xs, vertical = 2.dp),
    )
}

@Composable
private fun noteKindLabel(kind: NoteKind): String = stringResource(
    when (kind) {
        NoteKind.FACT -> R.string.library_note_kind_fact
        NoteKind.PROFILE -> R.string.library_note_kind_profile
        NoteKind.PREFERENCE -> R.string.library_note_kind_preference
        NoteKind.EVENT -> R.string.library_note_kind_event
        NoteKind.PLAN -> R.string.library_note_kind_plan
        NoteKind.AGREEMENT -> R.string.library_note_kind_agreement
        NoteKind.SUMMARY -> R.string.library_note_kind_summary
    },
)

private fun NoteKind.accent(): Color = when (this) {
    NoteKind.PROFILE -> Color(0xFF8AB4F8)
    NoteKind.PREFERENCE -> Color(0xFFF2B8C6)
    NoteKind.EVENT -> Color(0xFF8ECAE6)
    NoteKind.PLAN -> Color(0xFFFFD166)
    NoteKind.AGREEMENT -> Color(0xFFA5D6A7)
    NoteKind.FACT -> Color(0xFFB8A9E8)
    NoteKind.SUMMARY -> Color(0xFF9AA4B2)
}
