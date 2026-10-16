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

package com.verlintas.baic2.core.model

import kotlin.math.abs

/** One offline place: administrative name, coordinates and a size hint. */
data class GazetteerPlace(
    val name: String,
    val pinyin: String,
    val latitude: Double,
    val longitude: Double,
    val kind: Int,
    val population: Long,
    val distanceMeters: Double = 0.0,
    val bearingDegrees: Double = 0.0,
) {
    val kindLabel: String
        get() = when (kind) {
            KIND_PROVINCE -> "province"
            KIND_CITY -> "city"
            KIND_COUNTY -> "county"
            KIND_TOWN -> "town"
            else -> "village"
        }

    val kindLabelZh: String
        get() = when (kind) {
            KIND_PROVINCE -> "省级"
            KIND_CITY -> "市级"
            KIND_COUNTY -> "县级"
            KIND_TOWN -> "乡镇级"
            else -> "村级"
        }

    companion object {
        const val KIND_PROVINCE = 1
        const val KIND_CITY = 2
        const val KIND_COUNTY = 3
        const val KIND_TOWN = 4
        const val KIND_VILLAGE = 5
    }
}

/**
 * Nearest-neighbour lookup over an in-memory gazetteer. Grid cells keep the
 * scan small: rings are visited outward and the search stops as soon as no
 * unvisited cell can hold anything closer.
 */
class GazetteerIndex(places: List<GazetteerPlace>) {

    private val places = places
    private val grid = HashMap<Long, MutableList<Int>>()

    init {
        places.forEachIndexed { index, place ->
            grid.getOrPut(cellKey(place.latitude, place.longitude)) { ArrayList() }.add(index)
        }
    }

    val size: Int get() = places.size

    fun nearest(
        latitude: Double,
        longitude: Double,
        limit: Int = 3,
        kinds: IntRange = GazetteerPlace.KIND_PROVINCE..GazetteerPlace.KIND_VILLAGE,
    ): List<GazetteerPlace> {
        if (places.isEmpty() || limit <= 0) return emptyList()
        val origin = GeoPoint(latitude, longitude)
        val latCell = cellOf(latitude + 90.0)
        val lngCell = cellOf(longitude + 180.0)
        val best = ArrayList<Scored>()
        val seen = HashSet<Int>()
        var converged = false
        var ring = 0
        while (ring <= MAX_GRID_RING) {
            for (dlat in -ring..ring) {
                for (dlng in -ring..ring) {
                    if (ring > 0 && maxOf(abs(dlat), abs(dlng)) != ring) continue
                    val bucket = grid[cellKeyOf(latCell + dlat, lngCell + dlng)] ?: continue
                    for (index in bucket) {
                        if (!seen.add(index)) continue
                        val place = places[index]
                        if (place.kind !in kinds) continue
                        best += Scored(
                            place = place,
                            distance = GeoCoordinates.distanceMeters(origin, GeoPoint(place.latitude, place.longitude)),
                        )
                    }
                }
            }
            best.sortBy { it.distance }
            if (best.size > limit) best.subList(limit, best.size).clear()
            if (best.size >= limit) {
                // Anything not yet visited is at least `ring` cells away
                // (loose but always-safe lower bound: east-west degrees shrink
                // by cos(latitude), so the floor must shrink with them).
                val unvisitedFloor = ring * CELL_DEGREES * METERS_PER_DEGREE *
                    kotlin.math.cos(Math.toRadians(latitude)).coerceAtLeast(0.05)
                if (best[limit - 1].distance <= unvisitedFloor) {
                    converged = true
                    break
                }
            }
            ring++
        }
        if (!converged) {
            // Sparse area, edge of coverage or roaming abroad: exact full scan.
            best.clear()
            places.forEachIndexed { index, place ->
                if (place.kind !in kinds) return@forEachIndexed
                best += Scored(
                    place = place,
                    distance = GeoCoordinates.distanceMeters(origin, GeoPoint(place.latitude, place.longitude)),
                )
            }
            best.sortBy { it.distance }
            if (best.size > limit) best.subList(limit, best.size).clear()
        }
        return best.map { scored ->
            scored.place.copy(
                distanceMeters = scored.distance,
                bearingDegrees = GeoCoordinates.bearingDegrees(
                    origin,
                    GeoPoint(scored.place.latitude, scored.place.longitude),
                ),
            )
        }
    }

    private data class Scored(val place: GazetteerPlace, val distance: Double)

    private fun cellOf(shifted: Double): Int = kotlin.math.floor(shifted / CELL_DEGREES).toInt()

    private fun cellKey(latitude: Double, longitude: Double): Long =
        cellKeyOf(cellOf(latitude + 90.0), cellOf(longitude + 180.0))

    private fun cellKeyOf(latCell: Int, lngCell: Int): Long =
        latCell.toLong() * 4096L + lngCell

    private companion object {
        const val CELL_DEGREES = 0.25
        const val METERS_PER_DEGREE = 111_320.0
        const val MAX_GRID_RING = 12
    }
}
