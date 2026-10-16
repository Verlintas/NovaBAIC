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

package com.verlintas.baic2.tools.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.verlintas.baic2.core.data.repository.AutomationRepository
import com.verlintas.baic2.core.model.Automation
import com.verlintas.baic2.core.model.AutomationTrigger
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Singleton
class AutomationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val automationRepository: AutomationRepository,
) {

    fun schedule(automation: Automation) {
        cancel(automation.id)
        if (!automation.enabled || automation.trigger != AutomationTrigger.TIME) return
        val triggerAt = nextOccurrence(automation) ?: return
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(automation.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(id: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.cancel(pendingIntent(id))
    }

    fun nextOccurrence(automation: Automation, from: Long = System.currentTimeMillis()): Long? {
        val parts = automation.timeOfDay?.split(":") ?: return null
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val days = automation.daysOfWeek
        for (offset in 0..7) {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = from
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (calendar.timeInMillis <= from) continue
            if (days.isNullOrEmpty()) return calendar.timeInMillis
            val isoDay = calendar.get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }
            if (isoDay in days) return calendar.timeInMillis
        }
        return null
    }

    private fun pendingIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.toInt(),
        Intent(context, AutomationAlarmReceiver::class.java)
            .putExtra(AutomationAlarmReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Fires a time automation and schedules its next occurrence. */
@AndroidEntryPoint
class AutomationAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var automationRepository: AutomationRepository

    @Inject
    lateinit var scheduler: AutomationScheduler

    @Inject
    lateinit var executor: AutomationExecutor

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id <= 0) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val automation = automationRepository.getById(id)
                if (automation != null && automation.enabled) {
                    executor.run(automation)
                    scheduler.schedule(automation)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "automationId"
    }
}

/** Re-arms time automations after a reboot. */
@AndroidEntryPoint
class AutomationBootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var automationRepository: AutomationRepository

    @Inject
    lateinit var scheduler: AutomationScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                automationRepository.getAll()
                    .filter { it.enabled && it.trigger == AutomationTrigger.TIME }
                    .forEach { scheduler.schedule(it) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** Registers the sticky battery watcher and re-arms alarms on app start. */
@Singleton
class AutomationBootstrap @Inject constructor(
    @ApplicationContext private val context: Context,
    private val automationRepository: AutomationRepository,
    private val scheduler: AutomationScheduler,
    private val executor: AutomationExecutor,
) {

    private var batteryReceiver: BroadcastReceiver? = null
    private var notificationListener: ((com.verlintas.baic2.device.api.CachedNotification) -> Unit)? = null
    private val notificationCooldown = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    fun start() {
        CoroutineScope(Dispatchers.IO).launch {
            automationRepository.getAll()
                .filter { it.enabled && it.trigger == AutomationTrigger.TIME }
                .forEach { scheduler.schedule(it) }
        }
        if (batteryReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
                    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                    if (level < 0 || scale <= 0) return
                    val percent = level * 100 / scale
                    CoroutineScope(Dispatchers.IO).launch {
                        val prefs = context.getSharedPreferences("automations", Context.MODE_PRIVATE)
                        automationRepository.getAll()
                            .filter {
                                it.enabled &&
                                    it.trigger == AutomationTrigger.BATTERY &&
                                    percent <= (it.batteryBelow ?: 20)
                            }
                            .forEach { automation ->
                                val key = "fired_${automation.id}"
                                val last = prefs.getLong(key, 0L)
                                if (System.currentTimeMillis() - last > 6 * 3_600_000L) {
                                    prefs.edit().putLong(key, System.currentTimeMillis()).apply()
                                    executor.run(automation)
                                }
                            }
                    }
                }
            }
            context.registerReceiver(receiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiver = receiver
        }
        if (notificationListener == null) {
            // Notification triggers: one serialized worker consumes the stream,
            // so a notification storm cannot spawn unbounded concurrent runs.
            val queue = kotlinx.coroutines.channels.Channel<com.verlintas.baic2.device.api.CachedNotification>(
                kotlinx.coroutines.channels.Channel.UNLIMITED,
            )
            val listener: (com.verlintas.baic2.device.api.CachedNotification) -> Unit = { notification ->
                queue.trySend(notification)
            }
            com.verlintas.baic2.device.api.NotificationCache.addListener(listener)
            notificationListener = listener
            CoroutineScope(Dispatchers.IO).launch {
                for (notification in queue) {
                    runCatching { onNotification(notification) }
                }
            }
        }
    }

    private suspend fun onNotification(notification: com.verlintas.baic2.device.api.CachedNotification) {
        val now = System.currentTimeMillis()
        automationRepository.getAll()
            .filter { automation ->
                automation.enabled &&
                    automation.trigger == AutomationTrigger.NOTIFICATION &&
                    matchesNotification(automation, notification)
            }
            .forEach { automation ->
                // Atomic check-and-reserve: a burst must not fire the same
                // automation twice.
                val reserved = notificationCooldown.compute(automation.id) { _, last ->
                    if (last == null || now - last >= NOTIFICATION_COOLDOWN_MS) now else last
                }
                if (reserved != now) return@forEach
                runCatching { executor.run(automation) }
            }
    }

    private fun matchesNotification(
        automation: Automation,
        notification: com.verlintas.baic2.device.api.CachedNotification,
    ): Boolean {
        val packages = automation.notificationPackages
        val keyword = automation.notificationKeyword?.trim().orEmpty()
        // At least one filter must exist, or the automation would fire on everything.
        if (packages.isEmpty() && keyword.isEmpty()) return false
        if (packages.isNotEmpty() && packages.none { pkg ->
                notification.packageName.equals(pkg.trim(), ignoreCase = true) ||
                    notification.packageName.contains(pkg.trim(), ignoreCase = true)
            }
        ) {
            return false
        }
        if (keyword.isNotEmpty() &&
            !notification.title.contains(keyword, ignoreCase = true) &&
            !notification.text.contains(keyword, ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    private companion object {
        const val NOTIFICATION_COOLDOWN_MS = 60_000L
    }
}
