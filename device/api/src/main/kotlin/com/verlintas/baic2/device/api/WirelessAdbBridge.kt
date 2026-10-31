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

package com.verlintas.baic2.device.api

import kotlinx.coroutines.flow.StateFlow

/** Built-in Wireless debugging transport; no external app is involved. */
sealed interface WirelessAdbState {
    data object Unsupported : WirelessAdbState
    data object NotPaired : WirelessAdbState
    data object Disconnected : WirelessAdbState
    data object Pairing : WirelessAdbState
    data object Connecting : WirelessAdbState
    data object Connected : WirelessAdbState
    data class Error(val message: String) : WirelessAdbState
}

/**
 * Pairs with Android's own Wireless debugging daemon and runs shell
 * commands over the ADB protocol. It is the no-Shizuku route to the
 * resident virtual display.
 */
interface WirelessAdbBridge {
    val state: StateFlow<WirelessAdbState>

    val supported: Boolean

    /** True once the app holds WRITE_SECURE_SETTINGS, so it can re-arm Wireless debugging. */
    val autoEnableAvailable: Boolean

    /** Whether the user opted into automatically turning Wireless debugging back on. */
    val autoEnable: Boolean

    /** Start observers and best-effort reconnect. Safe to call more than once. */
    fun start()

    /** Opt in/out of automatically turning Wireless debugging back on. */
    fun setAutoEnable(enabled: Boolean)

    /** A one-shot port supplied by the user when automatic discovery fails. */
    fun setManualPort(port: Int?)

    /** Discovers the pairing service and pairs with the six-digit code. */
    suspend fun pair(pairingCode: String): Result<Unit>

    /** Connects using an already-paired key. */
    suspend fun connect(): Result<Unit>

    /** Re-enable Wireless debugging when permitted; otherwise explain what is missing. */
    suspend fun ensureEnabled(): Result<Unit>

    suspend fun disconnect()

    /** Executes one shell command and returns its combined output. */
    suspend fun exec(command: String, timeoutMs: Long = 20_000L): Result<String>
}
