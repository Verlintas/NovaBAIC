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

package com.verlintas.baic2.tools.geo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.GeoCoordinates
import com.verlintas.baic2.core.model.GeoPoint
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import com.verlintas.baic2.tools.DeviceTool
import com.verlintas.baic2.tools.ToolContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Hands the drawing to an installed map app via a `geo:` intent. BAIC2 ships
 * no map: no tiles, no third-party SDK, and no surveying/approval obligations.
 * WGS-84 input is converted to GCJ-02 because Chinese map apps expect it.
 */
class OpenMapTool @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "open_map",
        description = "Open a location in the user's installed map app with a geo: intent. Pass raw " +
            "WGS-84 latitude/longitude (e.g. from get_location); GCJ-02 conversion for Chinese map " +
            "apps happens inside. Optional label and zoom. No map is rendered by BAIC2 itself.",
        parametersJson = """{"type":"object","properties":{"latitude":{"type":"number"},"longitude":{"type":"number"},"label":{"type":"string","description":"optional marker label"},"zoom":{"type":"integer","description":"1-21, default 15"},"bd09":{"type":"boolean","description":"set true if the input is already BD-09 (Baidu) coordinates"}},"required":["latitude","longitude"]}""",
        readOnly = true,
        danger = DangerLevel.MEDIUM,
        parallelSafe = false,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val rawLat = (arguments["latitude"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            ?: return ToolResult.Failure("Missing or invalid 'latitude'")
        val rawLng = (arguments["longitude"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            ?: return ToolResult.Failure("Missing or invalid 'longitude'")
        if (rawLat !in -90.0..90.0 || rawLng !in -180.0..180.0) {
            return ToolResult.Failure("Coordinates out of range: $rawLat, $rawLng")
        }
        val label = (arguments["label"] as? JsonPrimitive)?.contentOrNull
            ?.trim()?.takeIf { it.isNotBlank() }?.take(80)
        val zoom = ((arguments["zoom"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 15)
            .coerceIn(1, 21)
        val alreadyBd09 = (arguments["bd09"] as? JsonPrimitive)?.booleanOrNull == true
        val wgs84 = if (alreadyBd09) {
            GeoCoordinates.gcj02ToWgs84(GeoCoordinates.bd09ToGcj02(GeoPoint(rawLat, rawLng)))
        } else {
            GeoPoint(rawLat, rawLng)
        }
        val gcj = GeoCoordinates.wgs84ToGcj02(wgs84)
        val encodedLabel = label?.let { Uri.encode(it) }
        val query = if (encodedLabel.isNullOrEmpty()) {
            "${gcj.latitude},${gcj.longitude}"
        } else {
            "${gcj.latitude},${gcj.longitude}($encodedLabel)"
        }
        val uri = Uri.parse("geo:${gcj.latitude},${gcj.longitude}?q=$query&z=$zoom")
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            ToolResult.Success(
                "Opened ${label ?: "the location"} in a map app " +
                    "(GCJ-02 ${"%.5f".format(Locale.ROOT, gcj.latitude)}, ${"%.5f".format(Locale.ROOT, gcj.longitude)}).",
            )
        } catch (e: ActivityNotFoundException) {
            ToolResult.Failure(
                "No installed app handles geo: links. Coordinates are " +
                    "GCJ-02 ${"%.6f".format(Locale.ROOT, gcj.latitude)}, ${"%.6f".format(Locale.ROOT, gcj.longitude)} " +
                    "(WGS-84 ${"%.6f".format(Locale.ROOT, wgs84.latitude)}, ${"%.6f".format(Locale.ROOT, wgs84.longitude)}).",
            )
        }
    }
}
