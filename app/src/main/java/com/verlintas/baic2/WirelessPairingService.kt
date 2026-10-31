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

package com.verlintas.baic2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.verlintas.baic2.device.api.AccessibilityBridge
import com.verlintas.baic2.device.api.WirelessAdbBridge
import com.verlintas.baic2.device.api.WirelessAdbState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground pairing helper.
 *
 * The system's "Pair device with pairing code" screen stops advertising its
 * pairing service as soon as another activity takes the foreground. This
 * service therefore stays in the background, reads the six-digit code from
 * the Settings screen with the accessibility service, and pairs while that
 * screen is still visible. No external app and no typing.
 */
@AndroidEntryPoint
class WirelessPairingService : Service() {

    @Inject
    lateinit var wireless: WirelessAdbBridge

    @Inject
    lateinit var accessibility: AccessibilityBridge

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(getString(R.string.wireless_pair_notification_waiting))
        if (job == null) {
            job = scope.launch { pairLoop() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun pairLoop() {
        try {
            if (!accessibility.connected.value) {
                update(getString(R.string.wireless_pair_notification_no_a11y))
                delay(6_000L)
                return
            }
            val deadline = System.currentTimeMillis() + PAIR_WINDOW_MS
            var lastAttempt: String? = null
            while (System.currentTimeMillis() < deadline &&
                wireless.state.value != WirelessAdbState.Connected
            ) {
                // The pairing screen can be a system dialog above Settings;
                // read every window instead of only the active app root.
                val text = accessibility.screenTextAllWindows(250)
                val code = extractPairingCode(text)
                if (code != null && code != lastAttempt) {
                    lastAttempt = code
                    update(getString(R.string.wireless_pair_notification_pairing))
                    val result = wireless.pair(code)
                    if (result.isSuccess) {
                        update(getString(R.string.wireless_pair_notification_connected))
                        delay(2_000L)
                        return
                    }
                }
                delay(500L)
            }
            update(getString(R.string.wireless_pair_notification_failed))
            delay(4_000L)
        } finally {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun extractPairingCode(text: String): String? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        if ("pairing code" !in lower && "配对码" !in text && "配對碼" !in text) return null
        return Regex("(?m)^\\s*(\\d{6})\\s*$")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun startForegroundCompat(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    private fun update(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.wireless_pair_notification_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .build()

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.wireless_pair_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "baic2_wireless_pairing"
        const val NOTIFICATION_ID = 43
        const val PAIR_WINDOW_MS = 180_000L
    }
}
