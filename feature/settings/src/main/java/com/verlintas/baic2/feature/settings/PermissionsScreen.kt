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

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.verlintas.baic2.core.data.prefs.SettingsRepository
import com.verlintas.baic2.designsystem.Baic2Mono
import com.verlintas.baic2.device.api.AccessibilityBridge
import com.verlintas.baic2.device.api.ScreenshotProvider
import com.verlintas.baic2.device.api.ShellBridge
import com.verlintas.baic2.device.api.ShellState
import com.verlintas.baic2.device.api.WirelessAdbBridge
import com.verlintas.baic2.device.api.WirelessAdbState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PermissionsState(
    val shizuku: ShellState = ShellState.Unavailable,
    val wirelessAdb: WirelessAdbState = WirelessAdbState.Unsupported,
    val wirelessAutoEnable: Boolean = true,
    val keepDisplayAlive: Boolean = false,
    val filesAccess: Boolean = false,
    val accessibility: Boolean = false,
    val screenCaptureReady: Boolean = false,
    val notificationListener: Boolean = false,
    val usageAccess: Boolean = false,
    val writeSettings: Boolean = false,
    val notifications: Boolean = false,
    val camera: Boolean = false,
    val microphone: Boolean = false,
    val contacts: Boolean = false,
    val location: Boolean = false,
    val sceneEnhancement: Boolean = false,
)

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    accessibilityBridge: AccessibilityBridge,
    screenshotProvider: ScreenshotProvider,
    private val shellBridge: ShellBridge,
    private val wirelessAdbBridge: WirelessAdbBridge,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)

    private val deviceState = combine(
        accessibilityBridge.connected,
        screenshotProvider.ready,
        shellBridge.state,
        refreshTick,
        settingsRepository.sceneEnhancement,
    ) { accessibility, capture, shizuku, _, sceneEnhancement ->
        PermissionsState(
            shizuku = shizuku,
            filesAccess = filesAccessEnabled(),
            accessibility = accessibility,
            screenCaptureReady = capture,
            notificationListener = notificationListenerEnabled(),
            usageAccess = usageAccessEnabled(),
            writeSettings = Settings.System.canWrite(context),
            notifications = runtimeGranted(Manifest.permission.POST_NOTIFICATIONS) ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU,
            camera = runtimeGranted(Manifest.permission.CAMERA),
            microphone = runtimeGranted(Manifest.permission.RECORD_AUDIO),
            contacts = runtimeGranted(Manifest.permission.READ_CONTACTS),
            location = runtimeGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                runtimeGranted(Manifest.permission.ACCESS_COARSE_LOCATION),
            sceneEnhancement = sceneEnhancement,
        )
    }

    val state: StateFlow<PermissionsState> = combine(
        deviceState,
        wirelessAdbBridge.state,
        settingsRepository.keepDisplayAlive,
    ) { permissions, wireless, keepDisplayAlive ->
        permissions.copy(
            wirelessAdb = wireless,
            wirelessAutoEnable = wirelessAdbBridge.autoEnable,
            keepDisplayAlive = keepDisplayAlive,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PermissionsState(),
    )

    fun refresh() {
        refreshTick.update { it + 1 }
    }

    fun requestShizukuPermission() {
        shellBridge.requestPermission()
    }

    fun connectWirelessAdb(port: Int? = null) {
        viewModelScope.launch {
            port?.let { wirelessAdbBridge.setManualPort(it) }
            wirelessAdbBridge.connect()
        }
    }

    fun setWirelessAutoEnable(enabled: Boolean) {
        wirelessAdbBridge.setAutoEnable(enabled)
        refresh()
    }

    fun disconnectWirelessAdb() {
        viewModelScope.launch { wirelessAdbBridge.disconnect() }
    }

    fun setKeepDisplayAlive(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setKeepDisplayAlive(enabled)
            val intent = Intent().setClassName(
                context,
                "com.verlintas.baic2.VirtualDisplayKeepAliveService",
            )
            if (enabled) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
            } else {
                context.stopService(intent)
            }
        }
    }

    fun setSceneEnhancement(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSceneEnhancement(enabled) }
    }

    private fun filesAccessEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun runtimeGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun notificationListenerEnabled(): Boolean =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            .orEmpty()
            .contains(context.packageName)

    private fun usageAccessEnabled(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

@Composable
fun PermissionsPage(
    onBack: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refresh()
    }
    val filesPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refresh()
    }

    LaunchedEffect(Unit) { viewModel.refresh() }
    var showWirelessAdb by remember { mutableStateOf(false) }

    // "All files access" and other special accesses are granted in system
    // settings; refresh when the user comes back so the row is not stale.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsPage(
        title = stringResource(R.string.settings_permissions_title),
        onBack = onBack,
    ) {
        item(key = "hint") {
            Text(
                text = stringResource(R.string.settings_permissions_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = "s-core") { SettingsSectionLabel(stringResource(R.string.settings_permissions_core)) }
        item(key = "accessibility") {
            PermissionRow(
                title = stringResource(R.string.settings_a11y_title),
                description = stringResource(R.string.settings_a11y_description),
                granted = state.accessibility,
                onGrant = { openSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) },
            )
        }
        item(key = "files-access") {
            PermissionRow(
                title = stringResource(R.string.settings_perm_files_title),
                description = stringResource(R.string.settings_perm_files_desc),
                granted = state.filesAccess,
                onGrant = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val opened = runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            true
                        }.getOrDefault(false)
                        if (!opened) {
                            // Some OEMs ignore the per-app page; fall back to
                            // the full "All files access" list.
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    } else {
                        filesPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                },
            )
        }
        item(key = "shizuku") {
            SettingsRow(
                title = stringResource(R.string.settings_perm_shizuku_title),
                summary = when (state.shizuku) {
                    ShellState.Ready -> stringResource(R.string.settings_perm_shizuku_ready)
                    ShellState.PermissionRequired -> stringResource(R.string.settings_perm_shizuku_denied)
                    ShellState.Unavailable -> stringResource(R.string.settings_perm_shizuku_unavailable)
                },
                onClick = if (state.shizuku == ShellState.PermissionRequired) {
                    { viewModel.requestShizukuPermission() }
                } else {
                    null
                },
                trailing = {
                    Text(
                        text = if (state.shizuku == ShellState.Ready) {
                            stringResource(R.string.settings_perm_granted)
                        } else {
                            stringResource(R.string.settings_perm_grant)
                        },
                        style = Baic2Mono.label,
                        color = if (state.shizuku == ShellState.Ready) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                },
            )
        }
        item(key = "wireless-adb") {
            SettingsRow(
                title = stringResource(R.string.settings_perm_wireless_title),
                summary = when (val wireless = state.wirelessAdb) {
                    WirelessAdbState.Unsupported ->
                        stringResource(R.string.settings_perm_wireless_unsupported)
                    WirelessAdbState.NotPaired ->
                        stringResource(R.string.settings_perm_wireless_not_paired)
                    WirelessAdbState.Disconnected ->
                        stringResource(R.string.settings_perm_wireless_disconnected)
                    WirelessAdbState.Pairing ->
                        stringResource(R.string.settings_perm_wireless_pairing)
                    WirelessAdbState.Connecting ->
                        stringResource(R.string.settings_perm_wireless_connecting)
                    WirelessAdbState.Connected ->
                        stringResource(R.string.settings_perm_wireless_connected)
                    is WirelessAdbState.Error -> wireless.message
                },
                onClick = if (state.wirelessAdb == WirelessAdbState.Unsupported) {
                    null
                } else {
                    { showWirelessAdb = true }
                },
                trailing = {
                    Text(
                        text = when (state.wirelessAdb) {
                            WirelessAdbState.Connected ->
                                stringResource(R.string.settings_perm_wireless_disconnect)
                            WirelessAdbState.Disconnected ->
                                stringResource(R.string.settings_perm_wireless_connect)
                            WirelessAdbState.NotPaired, is WirelessAdbState.Error ->
                                stringResource(R.string.settings_perm_wireless_pair)
                            WirelessAdbState.Pairing ->
                                stringResource(R.string.settings_perm_wireless_pairing)
                            WirelessAdbState.Connecting ->
                                stringResource(R.string.settings_perm_wireless_connecting)
                            WirelessAdbState.Unsupported -> ""
                        },
                        style = Baic2Mono.label,
                        color = if (state.wirelessAdb == WirelessAdbState.Connected) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                },
            )
        }
        item(key = "display-keepalive") {
            SettingsRow(
                title = stringResource(R.string.settings_perm_display_keep_title),
                summary = stringResource(R.string.settings_perm_display_keep_desc),
                onClick = { viewModel.setKeepDisplayAlive(!state.keepDisplayAlive) },
                trailing = {
                    Switch(
                        checked = state.keepDisplayAlive,
                        onCheckedChange = null,
                    )
                },
            )
        }
        item(key = "capture") {
            PermissionRow(
                title = stringResource(R.string.settings_perm_capture_title),
                description = stringResource(R.string.settings_perm_capture_desc),
                granted = state.screenCaptureReady,
                actionLabel = stringResource(R.string.settings_perm_capture_action),
                onGrant = { viewModel.refresh() },
            )
        }
        item(key = "notif-listener") {
            PermissionRow(
                title = stringResource(R.string.settings_perm_notif_listener_title),
                description = stringResource(R.string.settings_perm_notif_listener_desc),
                granted = state.notificationListener,
                onGrant = { openSettings(context, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
            )
        }
        item(key = "usage") {
            PermissionRow(
                title = stringResource(R.string.settings_perm_usage_title),
                description = stringResource(R.string.settings_perm_usage_desc),
                granted = state.usageAccess,
                onGrant = { openSettings(context, Settings.ACTION_USAGE_ACCESS_SETTINGS) },
            )
        }
        item(key = "write") {
            PermissionRow(
                title = stringResource(R.string.settings_perm_write_title),
                description = stringResource(R.string.settings_perm_write_desc),
                granted = state.writeSettings,
                onGrant = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                },
            )
        }
        item(key = "s-runtime") { SettingsSectionLabel(stringResource(R.string.settings_permissions_runtime)) }
        item(key = "notifications") {
            PermissionRow(
                title = stringResource(R.string.settings_perm_notifications_title),
                description = stringResource(R.string.settings_perm_notifications_desc),
                granted = state.notifications,
                onGrant = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
        }
        item(key = "camera") {
            PermissionRow(
                title = stringResource(R.string.settings_perm_camera_title),
                description = stringResource(R.string.settings_perm_camera_desc),
                granted = state.camera,
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            )
        }
        item(key = "microphone") {
            PermissionRow(
                title = stringResource(R.string.settings_perm_mic_title),
                description = stringResource(R.string.settings_perm_mic_desc),
                granted = state.microphone,
                onGrant = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            )
        }
        item(key = "contacts") {
            PermissionRow(
                title = stringResource(R.string.settings_perm_contacts_title),
                description = stringResource(R.string.settings_perm_contacts_desc),
                granted = state.contacts,
                onGrant = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) },
            )
        }
        item(key = "location") {
            PermissionRow(
                title = stringResource(R.string.settings_perm_location_title),
                description = stringResource(R.string.settings_perm_location_desc),
                granted = state.location,
                onGrant = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
            )
        }
        item(key = "scene-enhancement") {
            SettingsRow(
                title = stringResource(R.string.settings_perm_scene_title),
                summary = stringResource(R.string.settings_perm_scene_desc),
                onClick = { viewModel.setSceneEnhancement(!state.sceneEnhancement) },
                trailing = {
                    Switch(
                        checked = state.sceneEnhancement,
                        onCheckedChange = null,
                    )
                },
            )
        }
    }

    if (showWirelessAdb) {
        WirelessAdbDialog(
            state = state.wirelessAdb,
            autoEnable = state.wirelessAutoEnable,
            accessibilityEnabled = state.accessibility,
            onDismiss = { showWirelessAdb = false },
            onAutoPair = {
                ContextCompat.startForegroundService(
                    context,
                    Intent()
                        .setClassName(context, "com.verlintas.baic2.WirelessPairingService"),
                )
                openSettings(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                showWirelessAdb = false
            },
            onConnect = { port -> viewModel.connectWirelessAdb(port) },
            onAutoEnableChange = { viewModel.setWirelessAutoEnable(it) },
            onDisconnect = { viewModel.disconnectWirelessAdb() },
            onOpenDeveloperOptions = {
                openSettings(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            },
        )
    }
}

@Composable
private fun WirelessAdbDialog(
    state: WirelessAdbState,
    autoEnable: Boolean,
    accessibilityEnabled: Boolean,
    onDismiss: () -> Unit,
    onAutoPair: () -> Unit,
    onConnect: (Int?) -> Unit,
    onAutoEnableChange: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
) {
    val busy = state == WirelessAdbState.Pairing || state == WirelessAdbState.Connecting
    var portText by remember(state) { mutableStateOf("") }
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.settings_perm_wireless_dialog_title)) },
        text = {
            Column {
                when (state) {
                    WirelessAdbState.NotPaired, is WirelessAdbState.Error -> {
                        Text(stringResource(R.string.settings_perm_wireless_dialog_help))
                        if (!accessibilityEnabled) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.settings_perm_wireless_a11y_required),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (state is WirelessAdbState.Error) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_perm_wireless_auto_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onOpenDeveloperOptions) {
                            Text(stringResource(R.string.settings_perm_wireless_open_dev))
                        }
                    }
                    WirelessAdbState.Disconnected -> {
                        Text(stringResource(R.string.settings_perm_wireless_dialog_connect_help))
                    }
                    WirelessAdbState.Connected -> {
                        Text(stringResource(R.string.settings_perm_wireless_connected))
                    }
                    WirelessAdbState.Pairing -> {
                        Text(stringResource(R.string.settings_perm_wireless_pairing))
                    }
                    WirelessAdbState.Connecting -> {
                        Text(stringResource(R.string.settings_perm_wireless_connecting))
                    }
                    WirelessAdbState.Unsupported -> {
                        Text(stringResource(R.string.settings_perm_wireless_unsupported))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_perm_wireless_auto_reenable),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = autoEnable,
                        onCheckedChange = onAutoEnableChange,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_perm_wireless_keepalive_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state == WirelessAdbState.Disconnected || state is WirelessAdbState.Error) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { value ->
                            if (value.length <= 5 && value.all(Char::isDigit)) portText = value
                        },
                        label = { Text(stringResource(R.string.settings_perm_wireless_port_hint)) },
                        singleLine = true,
                    )
                    TextButton(
                        onClick = { onConnect(port) },
                        enabled = port != null && !busy,
                    ) {
                        Text(stringResource(R.string.settings_perm_wireless_port_connect))
                    }
                    if (state is WirelessAdbState.Error) {
                        TextButton(onClick = { onConnect(null) }, enabled = !busy) {
                            Text(stringResource(R.string.settings_perm_wireless_retry))
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                WirelessAdbState.NotPaired, is WirelessAdbState.Error -> {
                    Button(onClick = onAutoPair, enabled = accessibilityEnabled && !busy) {
                        Text(
                            if (accessibilityEnabled) {
                                stringResource(R.string.settings_perm_wireless_auto)
                            } else {
                                stringResource(R.string.settings_perm_wireless_a11y_required)
                            },
                        )
                    }
                }
                WirelessAdbState.Disconnected -> {
                    Button(onClick = { onConnect(null) }, enabled = !busy) {
                        Text(stringResource(R.string.settings_perm_wireless_connect))
                    }
                }
                WirelessAdbState.Connected -> {
                    Button(onClick = onDisconnect) {
                        Text(stringResource(R.string.settings_perm_wireless_disconnect))
                    }
                }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
    actionLabel: String? = null,
) {
    SettingsRow(
        title = title,
        summary = description,
        onClick = if (granted) null else onGrant,
        trailing = {
            Text(
                text = if (granted) {
                    stringResource(R.string.settings_perm_granted)
                } else {
                    actionLabel ?: stringResource(R.string.settings_perm_grant)
                },
                style = Baic2Mono.label,
                color = if (granted) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        },
    )
}

private fun openSettings(context: Context, action: String) {
    runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
