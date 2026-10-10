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

package com.verlintas.baic2.device.impl.run

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.verlintas.baic2.device.impl.R

/** Holds the stop callback registered by the active run. */
object RunControl {
    @Volatile
    var stopHandler: (() -> Unit)? = null
}

class RunService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                RunControl.stopHandler?.invoke()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                ensureChannel()
                val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
                val runId = intent?.getLongExtra(EXTRA_RUN_ID, -1L)?.takeIf { it >= 0L }
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(title, runId),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        0
                    },
                )
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun buildNotification(title: String, runId: Long?): android.app.Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RunService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runId != null) launch.putExtra(EXTRA_OPEN_RUN_ID, runId)
            PendingIntent.getActivity(
                this,
                2,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title.ifBlank { "BAIC2" })
            .setContentText(getString(R.string.run_running))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                android.app.Notification.Action.Builder(
                    null,
                    getString(R.string.run_stop),
                    stopIntent,
                ).build(),
            )
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.run_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val ACTION_STOP = "com.verlintas.baic2.run.STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_RUN_ID = "run_id"

        /** Read by MainActivity to deep-link into the Tasks run detail. */
        const val EXTRA_OPEN_RUN_ID = "open_run_id"

        /** Read by MainActivity to deep-link into a specific conversation. */
        const val EXTRA_OPEN_CONVERSATION_ID = "open_conversation_id"

        internal const val CHANNEL_ID = "baic2_runs"
        private const val NOTIFICATION_ID = 43

        fun start(context: Context, title: String, runId: Long?) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RunService::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_RUN_ID, runId ?: -1L)
                    .setAction("com.verlintas.baic2.run.START"),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RunService::class.java))
        }
    }
}
