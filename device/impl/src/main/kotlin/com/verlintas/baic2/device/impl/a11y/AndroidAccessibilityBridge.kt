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

package com.verlintas.baic2.device.impl.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.verlintas.baic2.device.api.AccessibilityBridge
import com.verlintas.baic2.device.api.TextNode
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@Singleton
class AndroidAccessibilityBridge @Inject constructor() : AccessibilityBridge {

    @Volatile
    private var service: Baic2AccessibilityService? = null

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * Bumped by the service for UI events. Waiters only need a cheap hint that
     * the tree may have moved before taking another snapshot.
     */
    private val contentTick = AtomicLong()

    fun notifyContentEvent() {
        contentTick.incrementAndGet()
    }

    fun attach(active: Baic2AccessibilityService) {
        service = active
        _connected.value = true
    }

    fun detach(active: Baic2AccessibilityService) {
        if (service === active) {
            service = null
            _connected.value = false
        }
    }

    override suspend fun tap(x: Int, y: Int): Result<Unit> = withContext(Dispatchers.Main) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        dispatch(path, durationMs = 60)
    }

    override suspend fun longPress(x: Int, y: Int): Result<Unit> = withContext(Dispatchers.Main) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        dispatch(path, durationMs = 650)
    }

    override suspend fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): Result<Unit> = withContext(Dispatchers.Main) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        dispatch(path, durationMs = durationMs.coerceIn(50, 2_000))
    }

    override suspend fun typeText(text: String): Result<Unit> = withContext(Dispatchers.Main) {
        val active = service ?: return@withContext notEnabled()
        // Search fields can live in a separate window from the active app
        // (Settings delegates to its intelligence package, IMEs add their own
        // window). Try every window on the default display before failing.
        val direct = setTextOnDisplay(0, text)
        if (direct.isSuccess) return@withContext direct

        // Some OEM fields reject ACTION_SET_TEXT even while focused. Focus the
        // active field explicitly and retry; if it still refuses, paste through
        // the clipboard and restore the previous clip afterwards.
        val target = active.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: active.rootInActiveWindow?.firstEditable()
            ?: return@withContext direct
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            return@withContext Result.success(Unit)
        }

        val clipboard = active.getSystemService(AccessibilityService.CLIPBOARD_SERVICE)
            as? ClipboardManager
        if (clipboard == null) return@withContext direct
        val old = runCatching { clipboard.primaryClip }.getOrNull()
        val set = runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("BAIC2 input", text))
        }
        if (set.isFailure) return@withContext direct
        val pasted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        runCatching { old?.let { clipboard.setPrimaryClip(it) } }
        if (pasted) Result.success(Unit) else direct
    }

    override suspend fun pressKey(key: String): Result<Unit> = withContext(Dispatchers.Main) {
        val active = service ?: return@withContext notEnabled()
        if (key.lowercase() == "enter") {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                return@withContext Result.failure(
                    IllegalStateException("enter_requires_android_11: use press_key 'back' or tap the send button"),
                )
            }
            val target = active.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: active.rootInActiveWindow?.firstEditable()
                ?: return@withContext Result.failure(
                    IllegalStateException("no_focused_input: tap a text field first"),
                )
            val enterAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            return@withContext if (target.performAction(enterAction)) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("enter_rejected: the field refused the action"))
            }
        }
        val action = when (key.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications", "shade" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return@withContext Result.failure(
                IllegalArgumentException("unknown_key: use back|home|recents|notifications|enter"),
            )
        }
        if (active.performGlobalAction(action)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("global_action_rejected"))
        }
    }

    override fun findText(query: String): List<TextNode> {
        val root = service?.rootInActiveWindow ?: return emptyList()
        return matchingNodes(root, query)
    }

    override fun findTextOnDisplay(displayId: Int, query: String): List<TextNode> {
        val root = rootOnDisplay(displayId) ?: return emptyList()
        return matchingNodes(root, query)
    }

    override fun findSearchNode(
        hints: List<String>,
        preferredViewId: String?,
    ): TextNode? = searchNodeIn(listOfNotNull(rootOnDisplay(0)), hints, preferredViewId)

    override fun findSearchNodeOnDisplay(
        displayId: Int,
        hints: List<String>,
        preferredViewId: String?,
    ): TextNode? = searchNodeIn(
        listOfNotNull(rootOnDisplay(displayId)),
        hints,
        preferredViewId,
    )

    private fun searchNodeIn(
        roots: List<AccessibilityNodeInfo>,
        hints: List<String>,
        preferredViewId: String?,
    ): TextNode? {
        val normalizedHints = hints.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val preferred = preferredViewId?.trim()?.lowercase()
        val screen = screenSize()
        val screenWidth = screen?.first ?: 0
        val screenHeight = screen?.second ?: 0
        var best: TextNode? = null
        var bestScore = 0
        var editableCount = 0
        var searchSpecificCount = 0

        for (root in roots) {
            root.walk { node ->
                if (!node.isVisibleToUser || node.isPassword) return@walk
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (bounds.isEmpty) return@walk
                val viewId = node.viewIdResourceName.orEmpty()
                val lowerViewId = viewId.lowercase()
                val className = node.className?.toString().orEmpty()
                val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.hintText?.toString()?.takeIf { it.isNotBlank() }
                    ?: ""
                val text = label.lowercase()
                val clickable = node.isClickable ||
                    clickableSelfOrAncestor(node)?.isClickable == true
                val preferredMatch = preferred != null && lowerViewId.contains(preferred)
                val hasViewIdSearch = "search" in lowerViewId
                val hasClassSearch = "searchview" in className.lowercase()
                val hasHintSearch = normalizedHints.any { it in text }
                val specific = preferredMatch || hasViewIdSearch || hasClassSearch || hasHintSearch
                if (node.isEditable) editableCount++
                if (specific) searchSpecificCount++
                if (!specific && !node.isEditable) return@walk

                var score = 0
                if (preferredMatch) score += 200
                if (hasViewIdSearch) score += 100
                if (hasClassSearch) score += 60
                if (hasHintSearch) score += 40
                if (node.isEditable) score += 30
                if (clickable) score += 10
                if (screenWidth > 0 && bounds.width() > screenWidth / 2) score += 10
                if (screenHeight > 0 && bounds.top < screenHeight / 3) score += 10
                if (node.isPassword) score -= 100

                if (score > bestScore) {
                    bestScore = score
                    best = TextNode(
                        label.ifBlank { viewId.substringAfterLast('/') },
                        bounds.left,
                        bounds.top,
                        bounds.right,
                        bounds.bottom,
                        editable = node.isEditable,
                        viewId = viewId.takeIf { it.isNotBlank() },
                        className = node.className?.toString(),
                        clickableClassName = clickableSelfOrAncestor(node)?.className?.toString(),
                    )
                }
            }
        }

        // A lone editable field is usually the search box. With several
        // fields and no search-specific signal, do not guess a login form.
        if (bestScore < 50 || (searchSpecificCount == 0 && editableCount != 1)) return null
        return best
    }

    override fun screenTextOnDisplay(displayId: Int, maxNodes: Int): String {
        val root = rootOnDisplay(displayId) ?: return ""
        return labels(root, maxNodes)
    }

    override fun findTextAllWindows(query: String, maxNodes: Int): List<TextNode> {
        val active = service ?: return emptyList()
        val windows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            active.windowsOnAllDisplays.get(0).orEmpty()
        } else {
            active.windows
        }
        val results = LinkedHashMap<String, TextNode>()
        windows.sortedByDescending { it.layer }
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .forEach { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@forEach
            for (node in matchingNodes(root, query)) {
                if (results.size >= maxNodes) return@forEach
                results.putIfAbsent("${node.text}@${node.centerX},${node.centerY}", node)
            }
        }
        return results.values.toList()
    }

    override fun snapshotNodes(maxNodes: Int): List<TextNode> {
        val active = service ?: return emptyList()
        val windows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            active.windowsOnAllDisplays.get(0).orEmpty()
        } else {
            active.windows
        }
        val results = LinkedHashMap<String, TextNode>()
        val metrics = active.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        windows.sortedByDescending { it.layer }
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .forEach { window ->
                if (results.size >= maxNodes) return@forEach
                val root = runCatching { window.root }.getOrNull() ?: return@forEach
                root.walk { node ->
                    if (results.size >= maxNodes) return@walk
                    // API 30 reports isVisibleToUser=false for rows inside a
                    // populated RecyclerView; bounds are the reliable signal.
                    if (node.isPassword) return@walk
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    if (bounds.isEmpty || bounds.width() < 8 || bounds.height() < 8) return@walk
                    if (bounds.bottom <= 0 || bounds.top >= screenHeight ||
                        bounds.right <= 0 || bounds.left >= screenWidth
                    ) {
                        return@walk
                    }
                    val clickTarget = clickableSelfOrAncestor(node)
                    val interactive = node.isEditable || node.isClickable || node.isCheckable ||
                        clickTarget != null
                    if (!interactive) return@walk
                    val raw = node.text?.toString()?.takeIf { it.isNotBlank() }
                        ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                        ?: node.hintText?.toString()?.takeIf { it.isNotBlank() }
                        // A bare view id cannot be resolved back to a node by
                        // text, so only use it to label an input field (which
                        // ref resolution matches by position).
                        ?: node.viewIdResourceName?.substringAfterLast('/')
                            ?.takeIf { it.isNotBlank() && node.isEditable }
                        ?: return@walk
                    val label = raw.trim().replace(Regex("\\s+"), " ").take(160)
                    if (label.isBlank()) return@walk
                    val key = "$label@${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
                    results.putIfAbsent(
                        key,
                        TextNode(
                            text = label,
                            left = bounds.left,
                            top = bounds.top,
                            right = bounds.right,
                            bottom = bounds.bottom,
                            editable = node.isEditable,
                            viewId = node.viewIdResourceName,
                            className = node.className?.toString(),
                            clickableClassName = clickTarget?.className?.toString(),
                            hasToggle = node.isCheckable ||
                                clickTarget?.let(::containerHasToggle) == true,
                        ),
                    )
                }
            }
        return results.values.toList()
    }

    override fun screenTextAllWindows(maxNodes: Int): String {
        val active = service ?: return ""
        val windows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            active.windowsOnAllDisplays.get(0).orEmpty()
        } else {
            active.windows
        }
        val lines = LinkedHashSet<String>()
        windows.sortedByDescending { it.layer }
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .forEach { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@forEach
            root.walk { node ->
                if (lines.size < maxNodes) {
                    val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                        ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    label?.let { lines += it }
                }
            }
        }
        return lines.joinToString("\n")
    }

    override fun editableNode(): TextNode? {
        val root = service?.rootInActiveWindow ?: return null
        return editableNode(root)
    }

    override fun editableNodeOnDisplay(displayId: Int): TextNode? {
        val root = rootOnDisplay(displayId) ?: return null
        return editableNode(root)
    }

    override fun clickTextOnDisplay(displayId: Int, query: String): Result<Unit> {
        val root = rootOnDisplay(displayId)
            ?: return Result.failure(IllegalStateException("display_unavailable"))
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return Result.failure(IllegalArgumentException("empty_query"))
        var target: AccessibilityNodeInfo? = null
        root.walk { node ->
            if (target == null) {
                val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                if (label != null && label.lowercase().contains(needle)) {
                    target = clickableSelfOrAncestor(node)
                        ?: node.takeIf { candidate ->
                            candidate.actionList.any {
                                it.id == AccessibilityNodeInfo.ACTION_CLICK
                            }
                        }
                }
            }
        }
        val node = target ?: return Result.failure(IllegalStateException("no_clickable_match"))
        return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("click_rejected"))
        }
    }

    override fun clickEditableOnDisplay(displayId: Int): Result<Unit> {
        val root = rootOnDisplay(displayId)
            ?: return Result.failure(IllegalStateException("display_unavailable"))
        val node = editableNodeOrNull(root)
            ?: return Result.failure(IllegalStateException("no_editable_field"))
        val target = clickableSelfOrAncestor(node)
            ?: node.takeIf { candidate ->
                candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            }
            ?: return Result.failure(IllegalStateException("editable_not_clickable"))
        return if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("click_rejected"))
        }
    }

    override fun setTextOnDisplay(displayId: Int, text: String): Result<Unit> {
        val roots = rootsOnDisplay(displayId)
        if (roots.isEmpty()) return Result.failure(IllegalStateException("display_unavailable"))
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        var sawEditable = false
        for (root in roots) {
            val candidates = buildList {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { add(it) }
                addAll(editableNodes(root))
            }
            for (target in candidates) {
                if (!target.isEditable) continue
                sawEditable = true
                if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                    return Result.success(Unit)
                }
            }
        }
        return Result.failure(
            IllegalStateException(if (sawEditable) "set_text_rejected" else "no_editable_field"),
        )
    }

    override fun pressEnterOnDisplay(displayId: Int): Result<Unit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Result.failure(IllegalStateException("enter_requires_android_11"))
        }
        val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        var sawEditable = false
        for (root in rootsOnDisplay(displayId)) {
            val candidates = buildList {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { add(it) }
                addAll(editableNodes(root))
            }
            for (target in candidates) {
                if (!target.isEditable) continue
                sawEditable = true
                if (target.performAction(action)) return Result.success(Unit)
            }
        }
        return Result.failure(
            IllegalStateException(if (sawEditable) "enter_rejected" else "no_editable_field"),
        )
    }

    override fun scrollOnDisplay(displayId: Int, forward: Boolean): Result<Unit> {
        val root = rootOnDisplay(displayId)
            ?: return Result.failure(IllegalStateException("display_unavailable"))
        var target: AccessibilityNodeInfo? = null
        root.walk { node ->
            if (target == null && node.isScrollable && node.isEnabled) target = node
        }
        val node = target ?: return Result.failure(IllegalStateException("no_scrollable_view"))
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return if (node.performAction(action)) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("scroll_rejected"))
        }
    }

    override suspend fun awaitContentChange(
        displayId: Int,
        timeoutMs: Long,
        previousText: String?,
    ): Boolean = withContext(Dispatchers.Default) {
        val baseline = previousText ?: screenTextOnDisplay(displayId).trim()
        val deadline = SystemClock.uptimeMillis() + timeoutMs.coerceIn(100L, 10_000L)
        var lastTick = contentTick.get()
        var lastPoll = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            val now = SystemClock.uptimeMillis()
            val tick = contentTick.get()
            // Events wake the wait early; the periodic snapshot keeps apps
            // that do not emit useful events from stalling it.
            if (tick == lastTick && now - lastPoll < FALLBACK_POLL_MS) continue
            lastTick = tick
            lastPoll = now
            if (screenTextOnDisplay(displayId).trim() != baseline) return@withContext true
        }
        screenTextOnDisplay(displayId).trim() != baseline
    }

    override fun editableFocusedOnDisplay(displayId: Int): Boolean = runCatching {
        val root = rootOnDisplay(displayId) ?: return@runCatching false
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != null || root.firstEditable() != null
    }.getOrDefault(false)

    /**
     * The accessibility service can read windows from every display. Prefer
     * the active application window on the requested display; the IME and
     * system overlays are not useful for research text.
     */
    private fun rootOnDisplay(displayId: Int): AccessibilityNodeInfo? =
        rootsOnDisplay(displayId).firstOrNull()

    private fun rootsOnDisplay(displayId: Int): List<AccessibilityNodeInfo> {
        val active = service ?: return emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return listOfNotNull(active.rootInActiveWindow)
        }
        return runCatching {
            // getWindows() is scoped to the service's own display; the
            // per-display map is what exposes windows on virtual displays.
            active.windowsOnAllDisplays[displayId].orEmpty()
                .sortedWith(
                    compareByDescending<AccessibilityWindowInfo> { it.isActive }
                        .thenByDescending { it.layer },
                )
                .mapNotNull { window -> runCatching { window.root }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    private fun editableNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        root.walk { node ->
            if (found.size < 4 && node.isEditable && node.isEnabled) found += node
        }
        return found
    }

    private fun editableNodeOrNull(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        root.walk { node ->
            if (found == null && node.isEditable && node.isEnabled) found = node
        }
        return found
    }

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val value = current ?: return null
            if (value.isClickable) return value
            current = runCatching { value.parent }.getOrNull()
        }
        return null
    }

    private fun editableNode(root: AccessibilityNodeInfo): TextNode? {
        var found: TextNode? = null
        root.walk { node ->
            if (found == null && node.isEditable && node.isEnabled) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (!bounds.isEmpty) {
                    val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                        ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                        ?: node.hintText?.toString()?.takeIf { it.isNotBlank() }
                        ?: ""
                    found = TextNode(
                        label,
                        bounds.left,
                        bounds.top,
                        bounds.right,
                        bounds.bottom,
                        editable = true,
                        viewId = node.viewIdResourceName,
                        className = node.className?.toString(),
                        clickableClassName = clickableSelfOrAncestor(node)?.className?.toString(),
                    )
                }
            }
        }
        return found
    }

    private fun containerHasToggle(node: AccessibilityNodeInfo): Boolean {
        var found = false
        node.walk { child ->
            if (found) return@walk
            val cls = child.className?.toString().orEmpty().lowercase()
            if (child.isCheckable ||
                "switch" in cls ||
                "checkbox" in cls ||
                "togglebutton" in cls ||
                "radiobutton" in cls
            ) {
                found = true
            }
        }
        return found
    }

    private fun matchingNodes(root: AccessibilityNodeInfo, query: String): List<TextNode> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val results = mutableListOf<TextNode>()
        root.walk { node ->
            val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (label != null && label.lowercase().contains(needle)) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (!bounds.isEmpty) {
                    val clickTarget = clickableSelfOrAncestor(node)
                    results += TextNode(
                        label,
                        bounds.left,
                        bounds.top,
                        bounds.right,
                        bounds.bottom,
                        editable = node.isEditable,
                        viewId = node.viewIdResourceName,
                        className = node.className?.toString(),
                        clickableClassName = clickTarget?.className?.toString(),
                        hasToggle = clickTarget?.let(::containerHasToggle) == true,
                    )
                }
            }
        }
        return results
    }

    private fun labels(root: AccessibilityNodeInfo, maxNodes: Int): String {
        val lines = mutableListOf<String>()
        root.walk { node ->
            if (lines.size < maxNodes) {
                val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                label?.let { lines += it }
            }
        }
        return lines.joinToString("\n")
    }

    override fun windowTitle(): String? = runCatching {
        service?.rootInActiveWindow?.window?.title?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    override fun screenSize(): Pair<Int, Int>? = runCatching {
        service?.resources?.displayMetrics?.let { it.widthPixels to it.heightPixels }
    }.getOrNull()

    override fun editableFocused(): Boolean = runCatching {
        val root = service?.rootInActiveWindow ?: return@runCatching false
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != null || root.firstEditable() != null
    }.getOrDefault(false)

    override fun screenText(maxNodes: Int): String {
        val root = service?.rootInActiveWindow ?: return ""
        val lines = mutableListOf<String>()
        root.walk { node ->
            if (lines.size < maxNodes) {
                val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                label?.let { lines += it }
            }
        }
        return lines.joinToString("\n")
    }

    override fun foregroundPackage(): String? =
        runCatching { service?.rootInActiveWindow?.packageName?.toString() }.getOrNull()

    private suspend fun dispatch(path: Path, durationMs: Int): Result<Unit> {
        val active = service ?: return notEnabled()
        return suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong()))
                .build()
            val accepted = active.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(Result.success(Unit))
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resume(
                                Result.failure(IllegalStateException("gesture_cancelled")),
                            )
                        }
                    }
                },
                null,
            )
            if (!accepted && continuation.isActive) {
                continuation.resume(Result.failure(IllegalStateException("gesture_rejected")))
            }
        }
    }

    private fun notEnabled(): Result<Unit> = Result.failure(
        IllegalStateException(
            "accessibility_not_enabled: ask the user to enable the BAIC2 accessibility service " +
                "in 设置 → 无障碍, then retry.",
        ),
    )

    private inline fun AccessibilityNodeInfo.walk(visit: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(this)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            runCatching { visit(node) }
            for (index in 0 until runCatching { node.childCount }.getOrDefault(0)) {
                runCatching { node.getChild(index) }.getOrNull()?.let { queue.add(it) }
            }
        }
    }

    private fun AccessibilityNodeInfo.firstEditable(): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk { node ->
            if (found == null && node.isEditable && node.isEnabled) {
                found = node
            }
        }
        return found
    }

    private companion object {
        const val MAX_NODES = 800
        const val POLL_INTERVAL_MS = 120L
        const val FALLBACK_POLL_MS = 400L
    }
}
