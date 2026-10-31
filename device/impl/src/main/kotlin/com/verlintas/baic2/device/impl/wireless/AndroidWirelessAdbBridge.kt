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

package com.verlintas.baic2.device.impl.wireless

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.verlintas.baic2.device.api.WirelessAdbBridge
import com.verlintas.baic2.device.api.WirelessAdbState
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.LocalServices
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.File
import java.net.Inet4Address
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Built-in Wireless debugging transport.
 *
 * Pairs with the device's own `adbd` through Android's standard pairing
 * protocol and then runs shell commands over ADB. It needs no Shizuku or
 * root: only Developer options → Wireless debugging, which is part of the OS.
 */
@Singleton
class AndroidWirelessAdbBridge @Inject constructor(
    @ApplicationContext private val context: Context,
) : WirelessAdbBridge {

    override val supported: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adbDir = File(context.filesDir, "wireless-adb").apply { mkdirs() }
    private val pairedMarker = File(adbDir, "paired")
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keys by lazy { AdbKeys.loadOrCreate(context) }

    private var manager: WirelessManager? = null

    @Volatile
    private var started = false

    @Volatile
    private var manualPort: Int? = null

    private val _state = MutableStateFlow(initialState())
    override val state: StateFlow<WirelessAdbState> = _state.asStateFlow()

    override val autoEnableAvailable: Boolean
        get() = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    override val autoEnable: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ENABLE, true)

    @SuppressLint("MissingPermission")
    override fun start() {
        if (started || !supported) return
        started = true
        if (pairedMarker.isFile && autoEnableWanted()) {
            scope.launch {
                runCatching { connect() }
            }
        }
        observeTransportChanges()
    }

    /**
     * Reconnect when the system setting changes or a Wi-Fi network appears.
     * Keeping both callbacks in one helper makes the retry policy explicit.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun observeTransportChanges() {
        val resolver = context.contentResolver
        resolver.registerContentObserver(
            Settings.Global.getUriFor(ADB_WIFI_ENABLED),
            false,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(self: Boolean) = reconnectIfWanted()
            },
        )
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val wifi = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching {
            connectivity.registerNetworkCallback(
                wifi,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(wifiNetwork: Network) = reconnectIfWanted()
                },
            )
        }
    }

    private fun reconnectIfWanted() {
        if (!shouldReconnect()) return
        scope.launch { runCatching { connect() } }
    }

    override fun setAutoEnable(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ENABLE, enabled).apply()
    }

    override fun setManualPort(port: Int?) {
        manualPort = port?.takeIf { it in 1..65535 }
    }

    override suspend fun pair(pairingCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        val result = mutex.withLock {
            pairLocked(pairingCode)
        }
        if (result.isSuccess) {
            // The paired ADB shell is the one chance to grant the app the
            // setting permission it needs to re-arm Wireless debugging later.
            runCatching { grantWriteSecureSettings() }
        }
        result
    }

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            connectLocked()
        }
    }

    override suspend fun ensureEnabled(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureEnabledLocked()
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { manager?.disconnect() }
            _state.value = if (pairedMarker.isFile) {
                WirelessAdbState.Disconnected
            } else {
                WirelessAdbState.NotPaired
            }
        }
    }

    override suspend fun exec(command: String, timeoutMs: Long): Result<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (command.isBlank()) {
                    return@withLock Result.failure(IllegalArgumentException("Empty command."))
                }
                val active = manager
                if (active == null || !active.isConnected) {
                    val connected = connectLocked()
                    if (connected.isFailure) {
                        return@withLock Result.failure(
                            connected.exceptionOrNull()
                                ?: IllegalStateException("Wireless ADB is not connected."),
                        )
                    }
                }
                val connection = manager
                    ?: return@withLock Result.failure(
                        IllegalStateException("Wireless ADB is not connected."),
                    )
                var result: Result<String> = Result.failure(
                    IllegalStateException("Wireless ADB shell failed."),
                )
                try {
                    var lastError: Throwable? = null
                    for (attempt in 0 until 2) {
                        val output = withTimeoutOrNull(timeoutMs.coerceAtLeast(1_000L)) {
                            runCatching {
                                runInterruptible(Dispatchers.IO) {
                                    // Use the interactive shell service and a
                                    // marker. Inline shell arguments are split
                                    // by adbd, while a written command keeps
                                    // spaces, quotes and backgrounding intact.
                                    val marker = "__BAIC2_DONE__"
                                    // Split the marker across a variable so
                                    // the shell's echoed input does not contain
                                    // the contiguous sentinel.
                                    val payload =
                                        "$command\nM=__BAIC2_; echo \${M}DONE__\n"
                                    connection.openStream(LocalServices.SHELL).use { stream ->
                                        val output = stream.openOutputStream()
                                        output.write(payload.toByteArray(Charsets.UTF_8))
                                        output.flush()
                                        val input = stream.openInputStream()
                                        val buffer = ByteArray(8192)
                                        val collected = java.io.ByteArrayOutputStream()
                                        while (true) {
                                            val read = input.read(buffer)
                                            if (read < 0) break
                                            collected.write(buffer, 0, read)
                                            if (collected.toString(Charsets.UTF_8.name())
                                                    .contains(marker)
                                            ) {
                                                break
                                            }
                                        }
                                        collected.toString(Charsets.UTF_8.name())
                                            .substringBefore(marker)
                                            .trimEnd()
                                    }
                                }
                            }
                        }
                        if (output != null && output.isSuccess) {
                            result = Result.success(output.getOrThrow())
                            break
                        }
                        if (output == null) {
                            result = Result.failure(
                                IllegalStateException(
                                    "Wireless ADB shell timed out after ${timeoutMs}ms.",
                                ),
                            )
                            break
                        }
                        lastError = output.exceptionOrNull()
                        if (attempt == 0) delay(400L)
                    }
                    if (result.isFailure) {
                        lastError?.let { result = Result.failure(it) }
                    }
                    result
                } finally {
                    // One command per connection is the reliable ADB shell
                    // pattern; the next exec reconnects with the same key.
                    delay(200L)
                    runCatching { connection.disconnect() }
                    manager = null
                }
            }
        }

    private suspend fun pairLocked(pairingCode: String): Result<Unit> {
        if (!supported) {
            _state.value = WirelessAdbState.Unsupported
            return Result.failure(
                IllegalStateException("Wireless debugging requires Android 11 or newer."),
            )
        }
        val code = pairingCode.trim()
        if (code.length != 6 || !code.all { it.isDigit() }) {
            return Result.failure(IllegalArgumentException("Enter the six-digit pairing code."))
        }
        _state.value = WirelessAdbState.Pairing
        val service = discoverPairingService()
        if (service == null) {
            val error = "Pairing service not found. Open Developer options → Wireless debugging → " +
                "Pair device with pairing code, then try again."
            _state.value = WirelessAdbState.Error(error)
            return Result.failure(IllegalStateException(error))
        }
        return try {
            runCatching { manager?.disconnect() }
            val active = ensureManager()
            if (!active.pair(service.first, service.second, code)) {
                val error = "The device rejected the pairing code."
                _state.value = WirelessAdbState.Error(error)
                return Result.failure(IllegalStateException(error))
            }
            pairedMarker.writeText("1")
            val connected = connectLocked()
            if (connected.isFailure) {
                _state.value = WirelessAdbState.Disconnected
                return Result.failure(
                    IllegalStateException(
                        "Paired, but could not connect. Keep Wireless debugging on, then tap Connect.",
                    ),
                )
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            val error = "Pairing failed: ${t.message ?: t.javaClass.simpleName}"
            _state.value = WirelessAdbState.Error(error)
            Result.failure(IllegalStateException(error))
        }
    }

    private suspend fun connectLocked(): Result<Unit> {
        if (!supported) {
            _state.value = WirelessAdbState.Unsupported
            return Result.failure(
                IllegalStateException("Wireless debugging requires Android 11 or newer."),
            )
        }
        if (!pairedMarker.isFile) {
            _state.value = WirelessAdbState.NotPaired
            return Result.failure(IllegalStateException("Pair Wireless debugging first."))
        }
        val enabled = ensureEnabledLocked()
        if (enabled.isFailure) {
            _state.value = WirelessAdbState.Error(
                enabled.exceptionOrNull()?.message ?: "Wireless debugging is off.",
            )
            return enabled
        }
        manager?.takeIf { it.isConnected }?.let {
            _state.value = WirelessAdbState.Connected
            return Result.success(Unit)
        }
        _state.value = WirelessAdbState.Connecting
        var lastError: Throwable? = null
        val direct = tryEndpoints(endpoints())
        if (direct.connected) return Result.success(Unit)
        if (direct.authFailed) {
            val error = "Wireless debugging rejected the saved key. Pair again."
            _state.value = WirelessAdbState.Error(error)
            return Result.failure(IllegalStateException(error, direct.error))
        }
        lastError = direct.error

        // Compatibility path: the library's own mDNS resolver.
        try {
            val active = ensureManager()
            if (active.connectTls(context, CONNECT_TIMEOUT_MS) && active.isConnected) {
                delay(600L)
                readTlsPort()?.let { prefs.edit().putInt(KEY_PORT, it).apply() }
                manualPort = null
                _state.value = WirelessAdbState.Connected
                return Result.success(Unit)
            }
            runCatching { active.disconnect() }
            manager = null
        } catch (t: Throwable) {
            lastError = t
            runCatching { manager?.disconnect() }
            manager = null
        }

        // Last resort: scan only this phone's own addresses for the random
        // adbd port. This is slow, so it never runs when the user supplied one.
        if (manualPort == null) {
            val scanned = tryEndpoints(scanLocalEndpoints())
            if (scanned.connected) return Result.success(Unit)
            if (scanned.authFailed) {
                val error = "Wireless debugging rejected the saved key. Pair again."
                _state.value = WirelessAdbState.Error(error)
                return Result.failure(IllegalStateException(error, scanned.error))
            }
            lastError = scanned.error ?: lastError
        }

        val error = buildString {
            append("Could not connect to Wireless debugging.")
            lastError?.message?.takeIf { it.isNotBlank() }?.let {
                append(' ').append(it.take(180))
            }
            append(" Keep Wi-Fi on, or enter the port shown in Developer options.")
        }
        _state.value = WirelessAdbState.Error(error)
        return Result.failure(IllegalStateException(error, lastError))
    }

    private suspend fun tryEndpoints(batch: List<Endpoint>): EndpointAttempt {
        var lastError: Throwable? = null
        for (endpoint in batch) {
            val active = ensureManager()
            try {
                if (active.connect(endpoint.host, endpoint.port) && active.isConnected) {
                    // Give the connection thread a moment before the first
                    // one-shot shell stream; otherwise that stream can block.
                    delay(600L)
                    prefs.edit().putInt(KEY_PORT, endpoint.port).apply()
                    manualPort = null
                    _state.value = WirelessAdbState.Connected
                    return EndpointAttempt(connected = true, error = null)
                }
            } catch (t: AdbAuthenticationFailedException) {
                runCatching { active.disconnect() }
                manager = null
                return EndpointAttempt(connected = false, authFailed = true, error = t)
            } catch (t: Throwable) {
                lastError = t
            }
            runCatching { active.disconnect() }
            manager = null
        }
        return EndpointAttempt(connected = false, error = lastError)
    }

    private suspend fun scanLocalEndpoints(): List<Endpoint> {
        val range = LocalPortScan.readRange()
        for (host in localHosts()) {
            val ports = LocalPortScan.openPorts(host, range, SCAN_BUDGET_MS)
            if (ports.isNotEmpty()) {
                return ports.take(MAX_SCAN_CANDIDATES).map { Endpoint(host, it) }
            }
        }
        return emptyList()
    }

    private suspend fun ensureEnabledLocked(): Result<Unit> {
        if (!supported) {
            return Result.failure(
                IllegalStateException("Wireless debugging requires Android 11 or newer."),
            )
        }
        val current = runCatching {
            Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0)
        }.getOrDefault(0)
        if (current == 1) return Result.success(Unit)
        if (!autoEnableWanted()) {
            return Result.failure(
                IllegalStateException(
                    "Wireless debugging is off. Turn it on in Developer options, then retry.",
                ),
            )
        }
        if (!autoEnableAvailable) {
            return Result.failure(
                IllegalStateException(
                    "Wireless debugging is off, and BAIC2 cannot turn it back on yet. " +
                        "Pair again once so the app can request the setting permission.",
                ),
            )
        }
        val written = runCatching {
            Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1)
        }.getOrDefault(false)
        if (!written) {
            return Result.failure(
                IllegalStateException(
                    "This phone refused to turn Wireless debugging back on. " +
                        "Open Developer options and enable it manually.",
                ),
            )
        }
        delay(600L)
        val after = runCatching {
            Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0)
        }.getOrDefault(0)
        return if (after == 1) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "The system immediately turned Wireless debugging back off. " +
                        "Allow it on this Wi-Fi, then retry.",
                ),
            )
        }
    }

    private suspend fun grantWriteSecureSettings(): Result<Unit> =
        exec(
            "pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}",
        ).map { Unit }

    private fun autoEnableWanted(): Boolean = prefs.getBoolean(KEY_AUTO_ENABLE, true)

    private fun shouldReconnect(): Boolean =
        pairedMarker.isFile &&
            autoEnableWanted() &&
            _state.value !in setOf(
                WirelessAdbState.Connected,
                WirelessAdbState.Connecting,
                WirelessAdbState.Pairing,
            )

    /**
     * Direct endpoints: last successful port, system TLS property, then the
     * manual port. The adbd may bind only localhost or only the Wi-Fi
     * address, so every port is tried on both. mDNS remains the fallback in
     * [connectTls], after these fast candidates.
     */
    private fun endpoints(): List<Endpoint> {
        val ports = LinkedHashSet<Int>()
        prefs.getInt(KEY_PORT, 0).takeIf { it in 1..65535 }?.let(ports::add)
        readTlsPort()?.let(ports::add)
        manualPort?.let(ports::add)

        val result = LinkedHashSet<Endpoint>()
        if (ports.isNotEmpty()) {
            for (port in ports) {
                for (host in localHosts()) {
                    result += Endpoint(host, port)
                }
            }
        } else {
            for (host in localHosts()) {
                result += Endpoint(host, LEGACY_ADB_PORT)
            }
        }
        return result.toList()
    }

    private fun readTlsPort(): Int? {
        val raw = runCatching {
            val process = ProcessBuilder("getprop", TLS_PORT_PROPERTY)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy()
                return null
            }
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
        return raw?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    @SuppressLint("MissingPermission")
    private fun localHosts(): List<String> {
        val hosts = linkedSetOf(LOCALHOST)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return hosts.toList()
        @Suppress("DEPRECATION")
        val networks = connectivity.allNetworks
        for (network in networks) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val link = connectivity.getLinkProperties(network) ?: continue
            for (linkAddress in link.linkAddresses) {
                val address = linkAddress.address as? Inet4Address ?: continue
                val host = address.hostAddress ?: continue
                hosts += host
            }
        }
        return hosts.toList()
    }

    private data class Endpoint(val host: String, val port: Int)

    private data class EndpointAttempt(
        val connected: Boolean,
        val authFailed: Boolean = false,
        val error: Throwable?,
    )

    private suspend fun discoverPairingService(): Pair<String, Int>? =
        withTimeoutOrNull(PAIR_DISCOVERY_TIMEOUT_MS) {
            val deferred = CompletableDeferred<Pair<String, Int>>()
            val mdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { address, port ->
                if (address != null && port > 0) {
                    deferred.complete(address.hostAddress.orEmpty() to port)
                }
            }
            mdns.start()
            try {
                deferred.await()
            } finally {
                mdns.stop()
            }
        }

    private fun ensureManager(): WirelessManager =
        manager ?: WirelessManager(keys).also { manager = it }

    private fun initialState(): WirelessAdbState = when {
        !supported -> WirelessAdbState.Unsupported
        pairedMarker.isFile -> WirelessAdbState.Disconnected
        else -> WirelessAdbState.NotPaired
    }

    private class WirelessManager(
        private val keys: AdbKeys,
    ) : AbsAdbConnectionManager() {
        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(8, TimeUnit.SECONDS)
            setThrowOnUnauthorised(false)
        }

        override fun getPrivateKey(): PrivateKey = keys.privateKey

        override fun getCertificate(): Certificate = keys.certificate

        override fun getDeviceName(): String = "BAIC2"
    }

    private companion object {
        const val PAIR_DISCOVERY_TIMEOUT_MS = 10_000L
        const val CONNECT_TIMEOUT_MS = 3_000L
        const val SCAN_BUDGET_MS = 4_500L
        const val MAX_SCAN_CANDIDATES = 24
        const val LEGACY_ADB_PORT = 5555
        const val LOCALHOST = "127.0.0.1"
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        const val TLS_PORT_PROPERTY = "service.adb.tls.port"
        const val PREFS = "wireless_adb_keeper"
        const val KEY_PORT = "last_port"
        const val KEY_AUTO_ENABLE = "auto_enable"
    }
}
