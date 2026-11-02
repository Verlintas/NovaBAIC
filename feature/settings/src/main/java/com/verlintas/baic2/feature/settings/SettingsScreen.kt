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

import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.verlintas.baic2.core.model.AccentColor
import com.verlintas.baic2.core.model.ThemeMode
import com.verlintas.baic2.designsystem.Baic2Mono
import com.verlintas.baic2.designsystem.Baic2Spacing

data class BuildInfo(
    val versionName: String,
    val versionCode: Int,
    val applicationId: String,
    val buildType: String,
)

private object SettingsRoute {
    const val ROOT = "settings_root"
    const val AGENTS = "settings_agents"
    const val AGENT_WIZARD = "settings_agent_wizard?agentId={agentId}"
    const val PERMISSIONS = "settings_permissions"
    const val SKILLS = "settings_skills"
    const val MODES = "settings_modes"
    const val MODE_EDITOR = "settings_mode_editor?modeId={modeId}"

    fun modeEditor(modeId: String?) = "settings_mode_editor?modeId=${modeId ?: ""}"
    const val APPEARANCE = "settings_appearance"
    const val LANGUAGE = "settings_language"
    const val STORAGE = "settings_storage"
    const val ABOUT = "settings_about"
    const val LICENSES = "settings_licenses"

    fun agentWizard(agentId: Long? = null) = "settings_agent_wizard?agentId=${agentId ?: -1L}"
}

@Composable
fun SettingsScreen(
    buildInfo: BuildInfo,
    onInnerRouteChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val onInnerRoute = backStackEntry?.destination?.route != SettingsRoute.ROOT

    LaunchedEffect(onInnerRoute) { onInnerRouteChanged(onInnerRoute) }

    NavHost(
        navController = navController,
        startDestination = SettingsRoute.ROOT,
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
        composable(SettingsRoute.ROOT) {
            SettingsRoot(
                buildInfo = buildInfo,
                onOpenAgents = { navController.navigate(SettingsRoute.AGENTS) },
                onOpenPermissions = { navController.navigate(SettingsRoute.PERMISSIONS) },
                onOpenSkills = { navController.navigate(SettingsRoute.SKILLS) },
                onOpenModes = { navController.navigate(SettingsRoute.MODES) },
                onOpenAppearance = { navController.navigate(SettingsRoute.APPEARANCE) },
                onOpenLanguage = { navController.navigate(SettingsRoute.LANGUAGE) },
                onOpenStorage = { navController.navigate(SettingsRoute.STORAGE) },
                onOpenAbout = { navController.navigate(SettingsRoute.ABOUT) },
            )
        }
        composable(SettingsRoute.AGENTS) {
            AgentsPage(
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigate(SettingsRoute.agentWizard()) },
                onEdit = { id -> navController.navigate(SettingsRoute.agentWizard(id)) },
            )
        }
        composable(
            route = SettingsRoute.AGENT_WIZARD,
            arguments = listOf(navArgument("agentId") { type = NavType.LongType }),
        ) { entry ->
            val agentId = entry.arguments?.getLong("agentId")?.takeIf { it >= 0 }
            com.verlintas.baic2.feature.agents.AgentWizardScreen(
                agentId = agentId,
                onClose = { navController.popBackStack() },
            )
        }
        composable(SettingsRoute.PERMISSIONS) {
            PermissionsPage(onBack = { navController.popBackStack() })
        }
        composable(SettingsRoute.SKILLS) {
            SkillsPage(onBack = { navController.popBackStack() })
        }
        composable(SettingsRoute.MODES) {
            ModeProfilesPage(
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(SettingsRoute.modeEditor(id)) },
            )
        }
        composable(
            route = SettingsRoute.MODE_EDITOR,
            arguments = listOf(
                navArgument("modeId") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("modeId").takeUnless { it.isNullOrBlank() }
            ModeEditorPage(
                modeId = id,
                onBack = { navController.popBackStack() },
            )
        }
        composable(SettingsRoute.APPEARANCE) {
            AppearancePage(onBack = { navController.popBackStack() })
        }
        composable(SettingsRoute.LANGUAGE) {
            LanguagePage(onBack = { navController.popBackStack() })
        }
        composable(SettingsRoute.STORAGE) {
            StoragePage(onBack = { navController.popBackStack() })
        }
        composable(SettingsRoute.ABOUT) {
            AboutPage(
                buildInfo = buildInfo,
                onBack = { navController.popBackStack() },
                onOpenLicenses = { navController.navigate(SettingsRoute.LICENSES) },
            )
        }
        composable(SettingsRoute.LICENSES) {
            LicensesPage(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun SettingsRoot(
    buildInfo: BuildInfo,
    onOpenAgents: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenModes: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
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
                    text = stringResource(R.string.feature_settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "v${buildInfo.versionName} · ${buildInfo.buildType}",
                    style = Baic2Mono.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "s-services") { SettingsSectionLabel(stringResource(R.string.settings_section_services)) }
        item(key = "agents") {
            SettingsRow(
                title = stringResource(R.string.settings_agents_title),
                summary = if (state.agents.isEmpty()) {
                    stringResource(R.string.settings_agents_none)
                } else {
                    stringResource(
                        R.string.settings_agents_summary,
                        state.agents.size,
                        state.agents.firstOrNull { it.isDefault }?.name ?: "-",
                    )
                },
                icon = Icons.Outlined.Person,
                showChevron = true,
                onClick = onOpenAgents,
            )
        }
        item(key = "permissions") {
            SettingsRow(
                title = stringResource(R.string.settings_permissions_title),
                summary = stringResource(R.string.settings_permissions_summary),
                icon = Icons.Outlined.Lock,
                showChevron = true,
                onClick = onOpenPermissions,
            )
        }
        item(key = "skills") {
            SettingsRow(
                title = stringResource(R.string.settings_skills_title),
                summary = stringResource(R.string.settings_skills_summary),
                icon = Icons.Outlined.Build,
                showChevron = true,
                onClick = onOpenSkills,
            )
        }
        item(key = "modes") {
            SettingsRow(
                title = stringResource(R.string.settings_modes_title),
                summary = stringResource(R.string.settings_modes_summary),
                icon = Icons.Outlined.Settings,
                showChevron = true,
                onClick = onOpenModes,
            )
        }

        item(key = "s-appearance") { SettingsSectionLabel(stringResource(R.string.settings_section_appearance)) }
        item(key = "appearance") {
            SettingsRow(
                title = stringResource(R.string.settings_appearance_title),
                summary = "${themeLabel(state.themeMode)} · ${accentLabel(state.accent)}",
                icon = Icons.Outlined.Star,
                showChevron = true,
                onClick = onOpenAppearance,
            )
        }
        item(key = "language") {
            SettingsRow(
                title = stringResource(R.string.settings_language_title),
                summary = languageLabel(state.language),
                icon = Icons.Outlined.Face,
                showChevron = true,
                onClick = onOpenLanguage,
            )
        }

        item(key = "s-data") { SettingsSectionLabel(stringResource(R.string.settings_section_data)) }
        item(key = "storage") {
            SettingsRow(
                title = stringResource(R.string.settings_storage_title),
                summary = state.storage?.let {
                    stringResource(R.string.settings_storage_summary, formatBytes(it.totalBytes))
                },
                icon = Icons.Outlined.Info,
                showChevron = true,
                onClick = onOpenStorage,
            )
        }

        item(key = "s-about") { SettingsSectionLabel(stringResource(R.string.settings_section_about)) }
        item(key = "about") {
            SettingsRow(
                title = stringResource(R.string.settings_about_title),
                summary = stringResource(R.string.settings_about_summary, buildInfo.versionName),
                icon = Icons.Outlined.Info,
                showChevron = true,
                onClick = onOpenAbout,
            )
        }
        item(key = "developer") {
        }
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)

@Composable
private fun accentLabel(accent: AccentColor): String = stringResource(
    when (accent) {
        AccentColor.ORANGE -> R.string.accent_orange
        AccentColor.RED -> R.string.accent_red
        AccentColor.PINK -> R.string.accent_pink
        AccentColor.INDIGO -> R.string.accent_indigo
        AccentColor.BLUE -> R.string.accent_blue
        AccentColor.PURPLE -> R.string.accent_purple
        AccentColor.GREEN -> R.string.accent_green
        AccentColor.TEAL -> R.string.accent_teal
    },
)

@Composable
private fun languageLabel(language: com.verlintas.baic2.core.model.AppLanguage): String =
    stringResource(
        when (language) {
            com.verlintas.baic2.core.model.AppLanguage.SYSTEM -> R.string.language_system
            com.verlintas.baic2.core.model.AppLanguage.CHINESE -> R.string.language_chinese
            com.verlintas.baic2.core.model.AppLanguage.ENGLISH -> R.string.language_english
            com.verlintas.baic2.core.model.AppLanguage.RUSSIAN -> R.string.language_russian
        },
    )

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
