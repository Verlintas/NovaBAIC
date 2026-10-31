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

package com.verlintas.baic2.tools.context

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.verlintas.baic2.core.model.GazetteerPlace
import com.verlintas.baic2.tools.geo.Gazetteer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SceneSnapshot(
    val location: String? = null,
    val weather: String? = null,
)

/**
 * The opt-in "scene enhancement" facts attached to a chat turn.
 *
 * Time is always local and free. Location is the last known fix only -- never
 * a background request; weather is a short-lived snapshot remembered after a
 * deliberate get_weather call, never a network call on every message.
 */
@Singleton
class SceneContextProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gazetteer: Gazetteer,
) {

    private data class WeatherSnapshot(val text: String, val at: Long)

    private val weather = AtomicReference<WeatherSnapshot?>(null)

    suspend fun snapshot(now: Long = System.currentTimeMillis()): SceneSnapshot =
        SceneSnapshot(
            location = locationText(now),
            weather = cachedWeather(now),
        )

    fun rememberWeather(city: String, result: String, now: Long = System.currentTimeMillis()) {
        val line = result.lineSequence().firstOrNull()?.trim().orEmpty()
        if (line.isBlank()) return
        val text = if (line.startsWith("$city:", ignoreCase = true)) line else "$city: $line"
        weather.set(WeatherSnapshot(text, now))
    }

    @SuppressLint("MissingPermission")
    private suspend fun locationText(now: Long): String? = withContext(Dispatchers.IO) {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) return@withContext null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null
        val fix = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?: return@withContext null

        val nearest = runCatching {
            gazetteer.nearest(
                fix.latitude,
                fix.longitude,
                limit = 1,
                kinds = GazetteerPlace.KIND_PROVINCE..GazetteerPlace.KIND_COUNTY,
            ).firstOrNull()
        }.getOrNull()

        buildString {
            append(
                "%.5f, %.5f (WGS-84) · ±%.0fm · %s · %s".format(
                    Locale.ROOT,
                    fix.latitude,
                    fix.longitude,
                    fix.accuracy,
                    fix.provider ?: "unknown",
                    age(now - fix.time),
                ),
            )
            nearest?.let { append(" · near ").append(it.name) }
        }
    }

    private fun cachedWeather(now: Long): String? {
        val snapshot = weather.get() ?: return null
        val ageMs = now - snapshot.at
        if (ageMs !in 0..WEATHER_FRESH_MS) return null
        return "${snapshot.text} (${age(ageMs)})"
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun age(ageMs: Long): String = when {
        ageMs < 60_000L -> "just now"
        ageMs < 3_600_000L -> "${ageMs / 60_000L}m ago"
        else -> "${ageMs / 3_600_000L}h ago"
    }

    private companion object {
        const val WEATHER_FRESH_MS = 30 * 60_000L
    }
}
