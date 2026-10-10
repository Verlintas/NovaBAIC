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

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.verlintas.baic2.device.api.RunNotifier
import com.verlintas.baic2.device.impl.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidRunNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : RunNotifier {

    override fun setStopHandler(handler: (() -> Unit)?) {
        RunControl.stopHandler = handler
    }

    override fun startRunning(title: String, runId: Long?) {
        RunService.start(context, title, runId)
    }

    override fun stopRunning() {
        RunService.stop(context)
    }

    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS is checked below.
    override fun notifyFinished(title: String, success: Boolean, runId: Long?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // A dedicated DEFAULT-importance channel so the completion pops as a
        // heads-up; the running-progress channel stays silent.
        if (manager.getNotificationChannel(DONE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    DONE_CHANNEL_ID,
                    context.getString(R.string.run_channel_done),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (runId != null) putExtra(RunService.EXTRA_OPEN_RUN_ID, runId)
            } ?: return
        val contentIntent = PendingIntent.getActivity(
            context,
            runId?.toInt() ?: 0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, DONE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(
                context.getString(
                    if (success) R.string.run_finished else R.string.run_interrupted,
                ),
            )
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        val canNotify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (canNotify) {
            runCatching { NotificationManagerCompat.from(context).notify(FINISHED_NOTIFICATION_ID, notification) }
        }
    }

    private companion object {
        const val DONE_CHANNEL_ID = "baic2_runs_done"
        const val FINISHED_NOTIFICATION_ID = 46
        const val CONVERSATION_NOTIFICATION_ID = 47
    }

    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS is checked below.
    override fun notifyConversationFinished(title: String, conversationId: Long) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(DONE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    DONE_CHANNEL_ID,
                    context.getString(R.string.run_channel_done),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(RunService.EXTRA_OPEN_CONVERSATION_ID, conversationId)
            } ?: return
        val contentIntent = PendingIntent.getActivity(
            context,
            CONVERSATION_NOTIFICATION_ID,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, DONE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(context.getString(R.string.run_reply_ready))
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        val canNotify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (canNotify) {
            runCatching {
                NotificationManagerCompat.from(context).notify(CONVERSATION_NOTIFICATION_ID, notification)
            }
        }
    }
}
