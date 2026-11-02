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

package com.verlintas.baic2.tools.apps

import android.content.Intent
import android.content.pm.ApplicationInfo
import com.verlintas.baic2.device.api.TextNode
import com.verlintas.baic2.device.api.VirtualDisplayBridge
import com.verlintas.baic2.device.api.VirtualDisplaySession
import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import com.verlintas.baic2.tools.DeviceTool
import com.verlintas.baic2.tools.ToolContext
import com.verlintas.baic2.tools.uiBestMatches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A read-only research verb: open an installed app, search, scroll and read.
 * It only taps a result when the caller names one through `open`; it never
 * sends a message, posts, likes, orders or pays. The model gets visible text
 * only -- the low-level tap/type/swipe tools stay hidden.
 *
 * The resident virtual display is preferred; the foreground accessibility
 * path is the built-in fallback, with the same read-only contract.
 */
class AppResearchTool @Inject constructor(
    private val displays: VirtualDisplayBridge,
    private val sessions: AppResearchSessions,
    private val knowledge: AppKnowledgeRepository,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "app_research",
        untrustedOutput = true,
        description = "Read-only in-app research: open an installed app by name or package, " +
            "type a query into its search field, read and scroll the visible results, and return " +
            "the text. The output starts with 'Search state: results | empty | not-found | " +
            "not-submitted' so you know whether the page is a real result page. Pass 'open' with " +
            "a visible *result* title, or 'open_top' to open the " +
            "first query-matching result, and read its detail in the same call. The agent " +
            "must not use open/open_top to press Send, Pay, Buy, Like, Follow or Delete: " +
            "those are state changes, not results; use Act/Max with ui_control and " +
            "confirmation instead. A virtual-display call returns a " +
            "session id; pass it back to read the current page or open another result " +
            "without relaunching and searching again. Never sends, posts, likes, follows, " +
            "orders or pays. Use it when the answer " +
            "lives inside an app and has no usable web equivalent. It searches, scrolls and " +
            "reads in one call; do not wrap it with screen_ocr or ui_control unless it fails. " +
            "When Shizuku, root, or the built-in Wireless debugging transport is available it " +
            "runs on a resident virtual display; otherwise it falls back to opening the app on " +
            "the phone screen and using the built-in accessibility service, so no external app " +
            "is required. If the app asks for login or a captcha, it stops and tells you to hand " +
            "that step to the user.",
        parametersJson = """{"type":"object","properties":{"app":{"type":"string","description":"app label (partial ok) or package name"},"query":{"type":"string","description":"search query"},"max_screens":{"type":"integer","description":"1-3 result screens to read, default 1"},"open":{"type":"string","description":"optional visible *result* title/text to open and read; not a button/action control"},"open_top":{"type":"boolean","description":"open the first query-matching result row and read its detail"},"session":{"type":"string","description":"optional session id from a previous virtual-display app_research call; reuse the open app/search, including a new query (retargets the results page or relaunches on the same display)"}},"required":["app","query"]}""",
        readOnly = true,
        danger = DangerLevel.LOW,
        parallelSafe = false,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        return try {
            RESEARCH_MUTEX.withLock { executeLocked(arguments, context) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ToolResult.Failure(
                "app_research stopped on an internal error (${error.javaClass.simpleName}: " +
                    "${error.message ?: "no detail"}). The call did not finish cleanly; earlier " +
                    "UI actions may already have happened. Retry once; if it repeats, use " +
                    "screen_ocr or ui_control to inspect the app manually.",
            )
        }
    }

    private suspend fun executeLocked(arguments: JsonObject, context: ToolContext): ToolResult {
        if (context.run.unattended) {
            return ToolResult.Denied(
                "app_research needs a person watching the screen; it is not available in " +
                    "unattended runs.",
            )
        }
        val appQuery = (arguments["app"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val query = (arguments["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (appQuery.isEmpty()) return ToolResult.Failure("Missing 'app' argument")
        if (query.isEmpty()) return ToolResult.Failure("Missing 'query' argument")
        val lexicon = ResearchLexicon.from(context.appContext)
        val open = (arguments["open"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val openTop = (arguments["open_top"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (openTop && open.isNotBlank()) {
            return ToolResult.Failure("Use either 'open' or 'open_top', not both.")
        }
        val sessionId = (arguments["session"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val maxScreens = ((arguments["max_screens"] as? JsonPrimitive)?.intOrNull ?: 1)
            .coerceIn(1, 3)

        // Resolve first, then check launchability per candidate. Filtering the
        // whole package list by launch intent can hide apps on OEMs that
        // restrict package visibility for freshly installed test builds.
        val matches = AppResolver.resolve(context.appContext, appQuery, launchableOnly = false)
        val app = matches.firstOrNull {
            AppResolver.launchIntent(context.appContext, it.packageName) != null
        } ?: return ToolResult.Failure(
            "No launchable app matches '$appQuery'. Ask the user which app to use.",
        )
        val pm = context.appContext.packageManager
        val launch = AppResolver.launchIntent(context.appContext, app.packageName)
            ?: return ToolResult.Failure("'${app.label}' has no launchable activity.")
        val preferredViewId = knowledge.get(app.packageName)?.searchViewId
        // User-installed apps often draw their own search UI and expose little
        // or no accessibility text. Prefer OCR there, but never require it:
        // system apps still use the cheaper accessibility tree first.
        val preferOcr = !isSystemApp(context, app.packageName)

        val previousSession = sessions.get(sessionId)
        if (previousSession != null && previousSession.packageName == app.packageName) {
            val virtual = displays.ensure()
            val resumed = virtual.getOrNull()
            if (resumed != null && resumed.displayId == previousSession.displayId) {
                sessions.touch(previousSession.id)
                val queryChanged = previousSession.query != query
                return researchOnVirtualDisplay(
                    appLabel = app.label,
                    packageName = app.packageName,
                    component = launch.component?.flattenToShortString()
                        ?: pm.resolveActivity(launch, 0)?.activityInfo?.let {
                            "${it.packageName}/${it.name}"
                        },
                    query = query,
                    maxScreens = maxScreens,
                    open = open,
                    openTop = openTop,
                    sessionId = previousSession.id,
                    previousText = previousSession.lastText.takeUnless { queryChanged },
                    reuse = true,
                    queryChanged = queryChanged,
                    wasDetail = previousSession.opened,
                    display = resumed,
                    context = context,
                    lexicon = lexicon,
                    preferredViewId = preferredViewId,
                    preferOcr = preferOcr,
                )
            }
        }

        // The virtual display is optional. Without Shizuku/root the built-in
        // foreground accessibility path is used, so no external app is ever
        // required just to research inside another app.
        if (displays.available()) {
            val virtual = displays.ensure()
            if (virtual.isSuccess) {
                val virtualResult = researchOnVirtualDisplay(
                    appLabel = app.label,
                    packageName = app.packageName,
                    component = launch.component?.flattenToShortString()
                        ?: pm.resolveActivity(launch, 0)?.activityInfo?.let {
                            "${it.packageName}/${it.name}"
                        },
                    query = query,
                    maxScreens = maxScreens,
                    open = open,
                    openTop = openTop,
                    sessionId = null,
                    previousText = null,
                    reuse = false,
                    display = virtual.getOrThrow(),
                    context = context,
                    lexicon = lexicon,
                    preferredViewId = preferredViewId,
                    preferOcr = preferOcr,
                )
                // Some OEM Settings builds and self-drawn apps expose a search
                // page that is not visible to accessibility or OCR on a virtual
                // display. Fall back to the foreground path instead of failing
                // the task, but keep login/captcha/blocked pages as failures.
                val searchUnavailable = (virtualResult as? ToolResult.Failure)?.reason?.let { reason ->
                    val openedOnPhone = reason.contains("opened on the phone", ignoreCase = true)
                    val blankDisplay = preferOcr &&
                        reason.contains("No readable text", ignoreCase = true)
                    !openedOnPhone && !blankDisplay && (
                        reason.contains("search state: not-found", ignoreCase = true) ||
                            reason.contains("search state: not-submitted", ignoreCase = true) ||
                            reason.contains("No readable text", ignoreCase = true) ||
                            reason.contains("search field", ignoreCase = true) ||
                            reason.contains("Could not launch", ignoreCase = true)
                        )
                } ?: false
                if (!searchUnavailable) return virtualResult
                displays.release()
            }
        }

        if (context.accessibility.foregroundPackage() != app.packageName) {
            try {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                context.appContext.startActivity(launch)
            } catch (e: Exception) {
                return ToolResult.Failure("Could not open ${app.label}: ${e.message}")
            }
            // Some apps hand search to a separate package (Settings uses
            // com.google.android.settings.intelligence). Do not insist on the
            // launcher package: wait until our own activity is no longer the
            // foreground, then let the search-field probe decide.
            for (attempt in 0 until 24) {
                val current = context.accessibility.foregroundPackage()
                if (current != null && current != context.appContext.packageName) break
                delay(250)
            }
        }

        val searchNode = awaitSearchEntry(context, lexicon, preferredViewId)
            ?: return searchFailure(
                app.label,
                SearchState.NOT_FOUND,
                "No search field was visible in the foreground window (foreground: " +
                    "${context.accessibility.foregroundPackage() ?: "unknown"}).",
            )
        context.accessibility.tap(searchNode.centerX, searchNode.centerY).getOrElse {
            return searchFailure(
                app.label,
                SearchState.NOT_SUBMITTED,
                "The search field could not be tapped: ${it.message}.",
            )
        }
        // Settings and several OEM apps open search as a separate activity.
        // Wait for its editable field before typing; a tap on the home search
        // bar can otherwise race the activity transition.
        for (attempt in 0 until 20) {
            if (context.accessibility.editableNode() != null) break
            delay(250)
        }
        // Third-party apps often open their search page slowly or use a
        // self-drawn field. Try SET_TEXT repeatedly instead of gating on a
        // focus flag: some OEM windows expose the field only to SET_TEXT.
        var typed = false
        var typeError: String? = null
        val typeAttempts = if (preferOcr) 6 else 12
        for (attempt in 0 until typeAttempts) {
            val set = context.accessibility.typeText(query)
            if (set.isSuccess) {
                typed = true
                break
            }
            typeError = set.exceptionOrNull()?.message
            delay(300)
        }
        if (!typed) {
            return searchFailure(
                app.label,
                SearchState.NOT_SUBMITTED,
                "The search field did not accept the query" +
                    (typeError?.let { ": $it" } ?: "") + ".",
            )
        }
        val beforeSubmit = readVisibleText(context, includeOcr = preferOcr)
        var submitted = context.accessibility.pressKey("enter").isSuccess
        if (!submitted) {
            val screenHeight = context.accessibility.screenSize()?.second ?: Int.MAX_VALUE
            val maxY = (screenHeight * 0.7f).toInt()
            submitted = SEARCH_ACTION_LABELS.any { label ->
                context.accessibility.findText(label)
                    .filter { it.centerY <= maxY }
                    .any { node ->
                        context.accessibility.tap(node.centerX, node.centerY).isSuccess
                    }
            }
        }
        if (!submitted) {
            return searchFailure(
                app.label,
                SearchState.NOT_SUBMITTED,
                "The query was typed but no submit action was available.",
            )
        }
        searchNode.viewId?.let { knowledge.recordSearch(app.packageName, it) }
        context.accessibility.awaitContentChange(
            0,
            1_500,
            beforeSubmit.takeIf { it.isNotEmpty() },
        )
        val searchState = awaitForegroundSearchState(
            context = context,
            query = query,
            before = beforeSubmit,
            lexicon = lexicon,
            preferOcr = preferOcr,
        )
        if (searchState == SearchState.NOT_SUBMITTED) {
            return searchFailure(
                app.label,
                searchState,
                "The query was typed but the page never changed.",
            )
        }

        val seen = LinkedHashSet<String>()
        var screens = 0
        var previous = ""
        var opened: OpenAttempt.Opened? = null
        var openNote: String? = null
        var pageNote: String? = null
        for (index in 0 until maxScreens) {
            val text = readVisibleText(context, includeOcr = preferOcr)
            if (text.isBlank()) {
                return searchFailure(
                    app.label,
                    searchState,
                    "Could not read the app's screen; accessibility and screen OCR returned " +
                        "nothing.",
                )
            }
            screens++
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length <= 300 }
                .forEach { seen += it }
            lexicon.blockedReason(text)?.let { reason ->
                pageNote = "This page looks like $reason. Nothing was sent or changed; " +
                    "hand this step to the user instead of trying to bypass it."
                break
            }
            if (open.isNotBlank() && opened == null) {
                when (val attempt = tryOpenForeground(context, open, text, lexicon)) {
                    is OpenAttempt.Opened -> {
                        opened = attempt
                        break
                    }
                    is OpenAttempt.Blocked -> return ToolResult.Failure(attempt.reason)
                    is OpenAttempt.Failed -> {
                        openNote = attempt.reason
                        break
                    }
                    is OpenAttempt.NotVisible -> Unit
                }
            }
            if (openTop && opened == null) {
                when (
                    val attempt = tryOpenTopForeground(
                        context = context,
                        lines = seen,
                        query = query,
                        before = text,
                        lexicon = lexicon,
                    )
                ) {
                    is OpenAttempt.Opened -> {
                        opened = attempt
                        break
                    }
                    is OpenAttempt.Blocked -> return ToolResult.Failure(attempt.reason)
                    is OpenAttempt.Failed -> {
                        openNote = attempt.reason
                        break
                    }
                    is OpenAttempt.NotVisible -> Unit
                }
            }
            if (index == maxScreens - 1) break
            if (text == previous) break
            previous = text
            val beforeScroll = readVisibleText(context, includeOcr = preferOcr)
            if (!scroll(context)) break
            context.accessibility.awaitContentChange(
                0,
                750,
                beforeScroll.takeIf { it.isNotEmpty() },
            )
        }
        if (open.isNotBlank() && opened == null && openNote == null) {
            openNote = "Open target '$open' was not visible in the scanned results."
        }
        if (openTop && opened == null && openNote == null) {
            openNote = "No safe result row was visible to open."
        }
        pageNote?.let { openNote = it }
        opened?.warning?.let { openNote = it }
        if (opened != null && opened.warning == null && !opened.changed) {
            openNote = "Opened '${opened.label}' but the screen did not change; it may not be " +
                "a result row, or the app is still loading."
        }

        val body = buildOutput(
            app.label,
            app.packageName,
            query,
            screens,
            seen,
            opened = opened?.label,
            detail = opened?.detail,
            openNote = openNote,
            chrome = lexicon.chrome,
            searchState = searchState,
        )
        return ToolResult.Success(body)
    }

    private suspend fun researchOnVirtualDisplay(
        appLabel: String,
        packageName: String,
        component: String?,
        query: String,
        maxScreens: Int,
        open: String,
        openTop: Boolean,
        sessionId: String?,
        previousText: String?,
        reuse: Boolean,
        queryChanged: Boolean = false,
        wasDetail: Boolean = false,
        display: VirtualDisplaySession,
        context: ToolContext,
        lexicon: ResearchLexicon,
        preferredViewId: String?,
        preferOcr: Boolean,
    ): ToolResult {
        var searchState: SearchState? = null
        if (reuse && queryChanged) {
            // A detail page usually owns the back stack; navigating back on a
            // virtual display can blank the window on some OEM builds. Relaunch
            // on the same display whenever the results page cannot be
            // retargeted; this still avoids recreating the helper/display.
            val retargeted = if (!wasDetail) {
                searchByNode(
                    context = context,
                    displayId = display.displayId,
                    query = query,
                    lexicon = lexicon,
                    preferredViewId = preferredViewId,
                    preferOcr = preferOcr,
                )
            } else {
                null
            }
            if (retargeted != null && retargeted.state != SearchState.NOT_SUBMITTED) {
                searchState = retargeted.state
                retargeted.viewId?.let { knowledge.recordSearch(packageName, it) }
                sessionId?.let {
                    sessions.updateQueryAndText(it, query, null, opened = false)
                }
            } else {
                return researchOnVirtualDisplay(
                    appLabel = appLabel,
                    packageName = packageName,
                    component = component,
                    query = query,
                    maxScreens = maxScreens,
                    open = open,
                    openTop = openTop,
                    sessionId = sessionId,
                    previousText = null,
                    reuse = false,
                    display = display,
                    context = context,
                    lexicon = lexicon,
                    preferredViewId = preferredViewId,
                    preferOcr = preferOcr,
                )
            }
        }
        if (!reuse) {
            if (component.isNullOrBlank()) {
                return ToolResult.Failure(
                    "'$appLabel' has no explicit launcher component for the display.",
                )
            }
            val beforeLaunch = context.accessibility.screenTextOnDisplay(display.displayId).trim()
            displays.launch(component).getOrElse { failure ->
                return if (preferOcr) {
                    searchFailure(
                        appLabel,
                        SearchState.NOT_SUBMITTED,
                        "Could not launch the app on the virtual display: ${failure.message}. " +
                            "It was not opened on the phone, to avoid taking over the screen.",
                    )
                } else {
                    ToolResult.Failure(
                        "Could not launch $appLabel on the virtual display: ${failure.message}",
                    )
                }
            }
            context.accessibility.awaitContentChange(
                display.displayId,
                1_200,
                beforeLaunch.takeIf { it.isNotEmpty() },
            )
            var onDisplay = context.accessibility.foregroundPackageOnDisplay(display.displayId)
            for (attempt in 0 until 8) {
                if (onDisplay != null) break
                delay(300)
                onDisplay = context.accessibility.foregroundPackageOnDisplay(display.displayId)
            }
            if (onDisplay != null &&
                onDisplay != packageName &&
                !onDisplay.startsWith("$packageName.")
            ) {
                return searchFailure(
                    appLabel,
                    SearchState.NOT_SUBMITTED,
                    "The app opened on the phone instead of the virtual display " +
                        "($onDisplay). Nothing was typed or tapped on the phone.",
                )
            }

            val outcome = searchOnVirtualDisplay(
                context = context,
                packageName = packageName,
                displayId = display.displayId,
                displayWidth = display.width,
                displayHeight = display.height,
                query = query,
                lexicon = lexicon,
                preferredViewId = preferredViewId,
                preferOcr = preferOcr,
            )
            searchState = outcome.state
            if (!outcome.viewId.isNullOrBlank()) {
                knowledge.recordSearch(packageName, outcome.viewId)
            }
            when (outcome.state) {
                SearchState.NOT_FOUND -> return searchFailure(
                    appLabel,
                    outcome.state,
                    "No search field was visible on the virtual display. The app may use a " +
                        "self-drawn search entry that accessibility and OCR could not read.",
                )
                SearchState.NOT_SUBMITTED -> return searchFailure(
                    appLabel,
                    outcome.state,
                    "The query was typed but the page never changed. The app may require " +
                        "tapping its own search button or IME action.",
                )
                else -> Unit
            }
        }

        val seen = LinkedHashSet<String>()
        var screens = 0
        var previous = ""
        var opened: OpenAttempt.Opened? = null
        var openNote: String? = null
        var pageNote: String? = null
        for (index in 0 until maxScreens) {
            val text = readVirtualText(
                context,
                display.displayId,
                includeOcr = preferOcr,
            ) ?: return searchFailure(
                appLabel,
                searchState ?: SearchState.NOT_SUBMITTED,
                "No readable text or frame was available from the virtual display.",
            )
            screens++
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length <= 300 }
                .forEach { seen += it }
            lexicon.blockedReason(text)?.let { reason ->
                pageNote = "This page looks like $reason. Nothing was sent or changed; " +
                    "hand this step to the user instead of trying to bypass it."
                break
            }
            if (open.isNotBlank() && opened == null) {
                when (val attempt = tryOpenVirtual(context, display.displayId, open, text, lexicon)) {
                    is OpenAttempt.Opened -> {
                        opened = attempt
                        break
                    }
                    is OpenAttempt.Blocked -> return ToolResult.Failure(attempt.reason)
                    is OpenAttempt.Failed -> {
                        openNote = attempt.reason
                        break
                    }
                    is OpenAttempt.NotVisible -> Unit
                }
            }
            if (openTop && opened == null) {
                when (
                    val attempt = tryOpenTopVirtual(
                        context = context,
                        displayId = display.displayId,
                        lines = seen,
                        query = query,
                        before = text,
                        lexicon = lexicon,
                    )
                ) {
                    is OpenAttempt.Opened -> {
                        opened = attempt
                        break
                    }
                    is OpenAttempt.Blocked -> return ToolResult.Failure(attempt.reason)
                    is OpenAttempt.Failed -> {
                        openNote = attempt.reason
                        break
                    }
                    is OpenAttempt.NotVisible -> Unit
                }
            }
            if (index == maxScreens - 1) break
            if (text == previous) break
            previous = text
            val beforeScroll = readVirtualText(
                context,
                display.displayId,
                includeOcr = preferOcr,
            ).orEmpty()
            if (context.accessibility.scrollOnDisplay(display.displayId).isFailure) {
                displays.swipe(
                    x1 = display.width / 2,
                    y1 = (display.height * 0.75f).toInt(),
                    x2 = display.width / 2,
                    y2 = (display.height * 0.35f).toInt(),
                    durationMs = 450,
                ).getOrElse { break }
            }
            context.accessibility.awaitContentChange(
                display.displayId,
                900,
                beforeScroll.takeIf { it.isNotEmpty() },
            )
        }
        if (open.isNotBlank() && opened == null && openNote == null) {
            openNote = "Open target '$open' was not visible in the scanned results."
        }
        if (openTop && opened == null && openNote == null) {
            openNote = "No safe result row was visible to open."
        }
        opened?.warning?.let { openNote = it }
        if (opened != null && opened.warning == null && !opened.changed) {
            openNote = "Opened '${opened.label}' but the screen did not change; it may not be " +
                "a result row, or the app is still loading."
        }
        val observed = buildString {
            append(seen.joinToString("\n"))
            opened?.detail?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append('\n')
                append(it)
            }
        }
        val activeSessionId = sessionId ?: sessions.put(
            label = appLabel,
            packageName = packageName,
            query = query,
            displayId = display.displayId,
            lastText = observed,
            opened = opened != null,
        ).id
        if (sessionId != null) {
            sessions.updateQueryAndText(
                id = activeSessionId,
                query = query,
                text = observed,
                opened = opened != null,
            )
        }
        return ToolResult.Success(
            buildOutput(
                appLabel,
                packageName,
                query,
                screens,
                seen,
                mode = "virtual display",
                opened = opened?.label,
                detail = opened?.detail,
                openNote = openNote,
                session = activeSessionId,
                previousText = previousText,
                chrome = lexicon.chrome,
                searchState = searchState,
            ),
        )
    }

    private sealed interface OpenAttempt {
        data class Opened(
            val label: String,
            val detail: String?,
            val changed: Boolean,
            val warning: String? = null,
        ) : OpenAttempt

        data object NotVisible : OpenAttempt

        data class Blocked(val reason: String) : OpenAttempt

        data class Failed(val reason: String) : OpenAttempt
    }

    private suspend fun tryOpenTopForeground(
        context: ToolContext,
        lines: Collection<String>,
        query: String,
        before: String,
        lexicon: ResearchLexicon,
    ): OpenAttempt {
        for (candidate in openTopCandidates(lines, query, lexicon)) {
            when (val attempt = tryOpenForeground(context, candidate, before, lexicon)) {
                is OpenAttempt.NotVisible -> Unit
                else -> return attempt
            }
        }
        return OpenAttempt.NotVisible
    }

    private suspend fun tryOpenTopVirtual(
        context: ToolContext,
        displayId: Int,
        lines: Collection<String>,
        query: String,
        before: String,
        lexicon: ResearchLexicon,
    ): OpenAttempt {
        for (candidate in openTopCandidates(lines, query, lexicon)) {
            when (val attempt = tryOpenVirtual(context, displayId, candidate, before, lexicon)) {
                is OpenAttempt.NotVisible -> Unit
                else -> return attempt
            }
        }
        return OpenAttempt.NotVisible
    }

    private fun openTopCandidates(
        lines: Collection<String>,
        query: String,
        lexicon: ResearchLexicon,
    ): List<String> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val tokens = needle.split(Regex("\\s+")).filter { it.length >= 2 }
        return prioritize(lines, query, lexicon.chrome)
            .asSequence()
            .filter { it.length in 4..160 }
            .filter { it.lowercase() != needle }
            .filter { line ->
                val text = line.lowercase()
                needle in text || tokens.any { it in text }
            }
            .filterNot { " > " in it || "›" in it || "→" in it }
            .filterNot { line ->
                val text = line.lowercase()
                lexicon.searchTerms.any { it in text } || lexicon.isChrome(text)
            }
            .distinctBy { it.lowercase() }
            .take(8)
            .toList()
    }

    private suspend fun tryOpenForeground(
        context: ToolContext,
        open: String,
        before: String,
        lexicon: ResearchLexicon,
    ): OpenAttempt {
        val target = bestOpenTarget(openNodesForeground(context, open), open)
            ?: return OpenAttempt.NotVisible
        if (isControl(target)) {
            return OpenAttempt.Blocked(
                "'${target.text}' is a button/input, not a result row. Use Act or Max " +
                    "with ui_control if you intend to change state.",
            )
        }
        context.accessibility.tap(target.centerX, target.centerY).getOrElse { error ->
            return OpenAttempt.Failed("Could not open '${target.text}': ${error.message}")
        }
        val detail = awaitForegroundDetail(context, before)
        return OpenAttempt.Opened(
            label = target.text,
            detail = detail,
            changed = detail != null && detail != before,
            warning = detail?.let(lexicon::blockedReason)?.let { reason ->
                "Opened '${target.text}' but it needs the user's hand ($reason). " +
                    "Nothing was sent or changed."
            },
        )
    }

    private suspend fun tryOpenVirtual(
        context: ToolContext,
        displayId: Int,
        open: String,
        before: String,
        lexicon: ResearchLexicon,
    ): OpenAttempt {
        val target = bestOpenTarget(openNodesVirtual(context, displayId, open), open)
            ?: return OpenAttempt.NotVisible
        if (isControl(target)) {
            return OpenAttempt.Blocked(
                "'${target.text}' is a button/input, not a result row. Use Act or Max " +
                    "with ui_control if you intend to change state.",
            )
        }
        displays.tap(target.centerX, target.centerY).getOrElse { error ->
            return OpenAttempt.Failed("Could not open '${target.text}': ${error.message}")
        }
        val detail = awaitVirtualDetail(context, displayId, before)
        return OpenAttempt.Opened(
            label = target.text,
            detail = detail,
            changed = detail != null && detail != before,
            warning = detail?.let(lexicon::blockedReason)?.let { reason ->
                "Opened '${target.text}' but it needs the user's hand ($reason). " +
                    "Nothing was sent or changed."
            },
        )
    }

    private suspend fun openNodesForeground(
        context: ToolContext,
        open: String,
    ): List<TextNode> {
        val tree = context.accessibility.findTextAllWindows(open)
            .ifEmpty { context.accessibility.findText(open) }
        if (tree.isNotEmpty()) return tree
        val image = context.screenshot.capture().getOrNull() ?: return emptyList()
        return runCatching { context.ocr.recognize(image) }.getOrNull()?.getOrNull().orEmpty().map { line ->
            TextNode(line.text, line.left, line.top, line.right, line.bottom)
        }
    }

    private suspend fun openNodesVirtual(
        context: ToolContext,
        displayId: Int,
        open: String,
    ): List<TextNode> {
        val tree = context.accessibility.findTextOnDisplay(displayId, open)
        if (tree.isNotEmpty()) return tree
        val image = displays.capture().getOrNull() ?: return emptyList()
        return runCatching { context.ocr.recognize(image) }.getOrNull()?.getOrNull().orEmpty().map { line ->
            TextNode(line.text, line.left, line.top, line.right, line.bottom)
        }
    }

    private fun isControl(node: TextNode): Boolean {
        if (node.editable || node.hasToggle) return true
        val clickClass = node.clickableClassName.orEmpty().lowercase()
        val ownClass = node.className.orEmpty().lowercase()
        return CONTROL_CLASSES.any { it in clickClass || it in ownClass }
    }

    private fun bestOpenTarget(nodes: List<TextNode>, open: String): TextNode? =
        uiBestMatches(
            nodes.filter {
                it.text.isNotBlank() &&
                    it.text.length in 2..160 &&
                    !isControl(it) &&
                    (it.editable || it.clickableClassName != null ||
                        (it.viewId == null && it.className == null))
            },
            open,
            limit = 1,
        ).firstOrNull()

    private suspend fun awaitForegroundDetail(
        context: ToolContext,
        before: String,
    ): String? {
        repeat(4) {
            delay(350)
            val text = readVisibleText(context)
            if (text.isNotBlank() && text != before) return text
        }
        return readVisibleText(context).takeIf { it.isNotBlank() }
    }

    private suspend fun awaitVirtualDetail(
        context: ToolContext,
        displayId: Int,
        before: String,
    ): String? {
        repeat(4) {
            delay(350)
            val text = readVirtualText(context, displayId)
            if (text != null && text != before) return text
        }
        return readVirtualText(context, displayId)
    }

    private data class VirtualTarget(
        val x: Int,
        val y: Int,
        val viewId: String? = null,
    )

    /** What the search page looks like after a query. */
    internal enum class SearchState(val wire: String) {
        RESULTS("results"),
        EMPTY("empty"),
        NOT_FOUND("not-found"),
        NOT_SUBMITTED("not-submitted"),
    }

    private data class SearchOutcome(
        val viewId: String?,
        val state: SearchState,
    )

    private data class KnownSearchRoute(
        val xFraction: Float,
        val yFraction: Float,
        val markers: List<String>,
    )

    private suspend fun searchOnVirtualDisplay(
        context: ToolContext,
        packageName: String,
        displayId: Int,
        displayWidth: Int,
        displayHeight: Int,
        query: String,
        lexicon: ResearchLexicon,
        preferredViewId: String?,
        preferOcr: Boolean,
    ): SearchOutcome {
        val byNode = searchByNode(
            context = context,
            displayId = displayId,
            query = query,
            lexicon = lexicon,
            preferredViewId = preferredViewId,
            preferOcr = preferOcr,
        )
        if (byNode != null && byNode.state != SearchState.NOT_SUBMITTED) return byNode

        // Some apps (WeChat is the canonical example) hide the whole
        // accessibility tree and use an icon-only search entry. OCR cannot see
        // the magnifier. A small, verified app profile is more reliable than
        // guessing a bottom tab. The markers confirm we reached search before
        // any text is typed.
        val known = KNOWN_SEARCH_ROUTES[packageName]
        if (known != null) {
            displays.tap(
                (displayWidth * known.xFraction).toInt(),
                (displayHeight * known.yFraction).toInt(),
            ).getOrElse {
                return SearchOutcome(null, SearchState.NOT_SUBMITTED)
            }
            context.accessibility.awaitContentChange(displayId, 1_200)
            val page = readVirtualText(context, displayId, includeOcr = true).orEmpty()
            val reachedSearch = known.markers.any { it in page.lowercase() } ||
                context.accessibility.editableNodeOnDisplay(displayId) != null
            if (!reachedSearch) {
                return SearchOutcome(null, SearchState.NOT_FOUND)
            }
            return SearchOutcome(
                null,
                applyQuery(
                    context = context,
                    displayId = displayId,
                    query = query,
                    lexicon = lexicon,
                    preferOcr = preferOcr,
                    submit = false,
                ),
            )
        }

        // A self-drawn app may never expose an EditText. Tap the best visible
        // search label (OCR is allowed) and type into whatever owns focus.
        val target = findVirtualSearchTarget(context, displayId, lexicon, preferredViewId)
            ?: return byNode ?: SearchOutcome(null, SearchState.NOT_FOUND)
        val byCoordinates = searchByCoordinates(
            context = context,
            displayId = displayId,
            target = target,
            query = query,
            lexicon = lexicon,
            preferOcr = preferOcr,
        )
        return if (byCoordinates.state != SearchState.NOT_SUBMITTED || byNode == null) {
            byCoordinates
        } else {
            byNode
        }
    }

    /**
     * Node actions are faster and do not need coordinates. They also work
     * when the search field is exposed as an EditText rather than a labelled
     * text node. The coordinate path is tried by the caller when this misses.
     */
    private suspend fun searchByNode(
        context: ToolContext,
        displayId: Int,
        query: String,
        lexicon: ResearchLexicon,
        preferredViewId: String?,
        preferOcr: Boolean,
    ): SearchOutcome? {
        val search = context.accessibility.findSearchNodeOnDisplay(
            displayId,
            lexicon.searchTerms,
            preferredViewId,
        )
        if (search != null && displays.tap(search.centerX, search.centerY).isSuccess) {
            context.accessibility.awaitContentChange(displayId, 700)
            return SearchOutcome(
                search.viewId,
                applyQuery(context, displayId, query, lexicon, preferOcr),
            )
        }

        val screenHeight = context.accessibility.screenSize()?.second ?: Int.MAX_VALUE
        val maxY = (screenHeight * 0.55f).toInt()
        var clicked = false
        for (attempt in 0 until 3) {
            val entry = lexicon.searchTerms
                .flatMap { context.accessibility.findTextOnDisplay(displayId, it) }
                .filter { it.centerY <= maxY && it.text.length <= 40 }
                .minByOrNull { it.top }
            if (entry != null && displays.tap(entry.centerX, entry.centerY).isSuccess) {
                clicked = true
                break
            }
            delay(250)
        }
        if (!clicked) return null
        context.accessibility.awaitContentChange(displayId, 500)
        return SearchOutcome(
            null,
            applyQuery(context, displayId, query, lexicon, preferOcr),
        )
    }

    private suspend fun searchByCoordinates(
        context: ToolContext,
        displayId: Int,
        target: VirtualTarget,
        query: String,
        lexicon: ResearchLexicon,
        preferOcr: Boolean,
    ): SearchOutcome {
        displays.tap(target.x, target.y).getOrElse {
            return SearchOutcome(target.viewId, SearchState.NOT_SUBMITTED)
        }
        context.accessibility.awaitContentChange(displayId, 700)
        return SearchOutcome(
            target.viewId,
            applyQuery(context, displayId, query, lexicon, preferOcr),
        )
    }

    private suspend fun applyQuery(
        context: ToolContext,
        displayId: Int,
        query: String,
        lexicon: ResearchLexicon,
        preferOcr: Boolean,
        submit: Boolean = true,
    ): SearchState {
        repeat(3) { attempt ->
            val editable = context.accessibility.editableNodeOnDisplay(displayId)
            if (editable != null) {
                displays.tap(editable.centerX, editable.centerY)
                delay(150)
                if (context.accessibility.setTextOnDisplay(displayId, query).isSuccess) {
                    val state = submitAndClassify(
                        context, displayId, query, lexicon, preferOcr, submit,
                    )
                    if (state == SearchState.RESULTS || state == SearchState.EMPTY) return state
                    if (attempt < 2) {
                        delay(350)
                        return@repeat
                    }
                    return state
                }
            }

            // No EditText, or it rejected SET_TEXT. The entry may be a custom
            // view that only accepts IME/clipboard input once it has focus.
            if (displays.typeText(query).isSuccess) {
                val state = submitAndClassify(
                    context, displayId, query, lexicon, preferOcr, submit,
                )
                if (state == SearchState.RESULTS || state == SearchState.EMPTY) return state
                if (attempt < 2) {
                    delay(350)
                    return@repeat
                }
                return state
            }
            if (attempt < 2) delay(350)
        }
        return SearchState.NOT_SUBMITTED
    }

    private suspend fun submitAndClassify(
        context: ToolContext,
        displayId: Int,
        query: String,
        lexicon: ResearchLexicon,
        preferOcr: Boolean,
        submit: Boolean,
    ): SearchState {
        val before = readVirtualText(
            context,
            displayId,
            includeOcr = preferOcr,
        ).orEmpty()
        if (submit) {
            submitSearch(context, displayId)
            context.accessibility.awaitContentChange(
                displayId,
                1_500,
                before.takeIf { it.isNotEmpty() },
            )
        }
        var state = awaitVirtualSearchState(
            context, displayId, query, before, lexicon, preferOcr,
        )
        if (state == SearchState.NOT_SUBMITTED) {
            // Last resort for custom search bars: tap the visible action label
            // instead of relying on an IME action the app never exposes.
            if (tapOcrAction(context, displayId, SEARCH_ACTION_LABELS)) {
                context.accessibility.awaitContentChange(
                    displayId,
                    1_500,
                    before.takeIf { it.isNotEmpty() },
                )
                state = awaitVirtualSearchState(
                    context, displayId, query, before, lexicon, preferOcr,
                )
            }
        }
        return state
    }

    private suspend fun awaitVirtualSearchState(
        context: ToolContext,
        displayId: Int,
        query: String,
        before: String,
        lexicon: ResearchLexicon,
        preferOcr: Boolean,
    ): SearchState {
        val attempts = if (preferOcr) 4 else 8
        var state = SearchState.NOT_SUBMITTED
        repeat(attempts) {
            val text = readVirtualText(
                context,
                displayId,
                includeOcr = preferOcr,
            ).orEmpty()
            state = classifySearch(text, query, before, lexicon)
            if (state != SearchState.NOT_SUBMITTED) return state
            delay(300)
        }
        return state
    }

    private suspend fun submitSearch(
        context: ToolContext,
        displayId: Int,
    ): Boolean {
        if (context.accessibility.pressEnterOnDisplay(displayId).isSuccess) return true
        if (displays.keyEvent(66).isSuccess) return true
        // A visible custom search action is handled by tapOcrAction with a
        // top-of-screen filter; never click a bottom navigation "Search" tab.
        return false
    }

    private suspend fun tapOcrAction(
        context: ToolContext,
        displayId: Int,
        labels: List<String>,
    ): Boolean {
        val bytes = displays.capture().getOrNull() ?: return false
        val lines = runCatching { context.ocr.recognize(bytes) }.getOrNull()?.getOrNull()
            .orEmpty()
        val screenHeight = context.accessibility.screenSize()?.second ?: Int.MAX_VALUE
        val maxY = (screenHeight * 0.6f).toInt()
        val match = lines
            .filter { line ->
                val text = line.text.trim().lowercase()
                line.centerY <= maxY && text.isNotEmpty() &&
                    labels.any { it.lowercase() == text || it.lowercase() in text }
            }
            .minByOrNull { it.top }
            ?: return false
        return displays.tap(match.centerX, match.centerY).isSuccess
    }

    internal fun isPlausibleSearchTarget(
        label: String,
        centerY: Int,
        screenHeight: Int,
    ): Boolean {
        if (screenHeight <= 0) return false
        val text = label.trim().lowercase()
        if (text.isEmpty() || text.length > 40) return false
        if ("记录" in text || "history" in text) return false
        return centerY in 0..(screenHeight * 0.55f).toInt()
    }

    private suspend fun findVirtualSearchTarget(
        context: ToolContext,
        displayId: Int,
        lexicon: ResearchLexicon,
        preferredViewId: String?,
    ): VirtualTarget? {
        // The accessibility tree is faster and more precise than OCR. It is
        // empty when the service is off or cannot see the display, so OCR is
        // still the fallback.
        val screenHeight = context.accessibility.screenSize()?.second ?: Int.MAX_VALUE
        val maxY = (screenHeight * 0.55f).toInt()
        repeat(6) {
            context.accessibility.findSearchNodeOnDisplay(
                displayId,
                lexicon.searchTerms,
                preferredViewId,
            )?.takeIf { it.centerY <= maxY }?.let {
                return VirtualTarget(it.centerX, it.centerY, it.viewId)
            }
            val match = lexicon.searchTerms
                .flatMap { context.accessibility.findTextOnDisplay(displayId, it) }
                .filter { isPlausibleSearchTarget(it.text, it.centerY, screenHeight) }
                .minByOrNull { it.top }
            if (match != null) {
                return VirtualTarget(match.centerX, match.centerY, match.viewId)
            }
            delay(300)
        }

        repeat(6) {
            val bytes = displays.capture().getOrNull()
            val lines = bytes?.let {
                runCatching { context.ocr.recognize(it) }.getOrNull()?.getOrNull()
            }.orEmpty()
            val match = lines
                .filter { line ->
                    isPlausibleSearchTarget(line.text, line.centerY, screenHeight) &&
                        lexicon.searchTerms.any { it in line.text.lowercase() }
                }
                .minByOrNull { it.top }
            if (match != null) return VirtualTarget(match.centerX, match.centerY)
            delay(400)
        }
        return null
    }

    private suspend fun readVirtualText(
        context: ToolContext,
        displayId: Int,
        includeOcr: Boolean = false,
    ): String? {
        // A display created this turn may take a moment to appear in the
        // accessibility window cache.
        var tree = ""
        for (attempt in 0 until 6) {
            tree = context.accessibility.screenTextOnDisplay(displayId).trim()
            if (tree.isNotEmpty()) break
            delay(200)
        }
        if (tree.isNotEmpty() && !includeOcr) return tree.take(6_000)

        val bytes = displays.capture().getOrNull()
            ?: return tree.takeIf { it.isNotBlank() }?.take(6_000)
        val lines = runCatching { context.ocr.recognize(bytes) }.getOrNull()?.getOrNull()
            ?: return tree.takeIf { it.isNotBlank() }?.take(6_000)
        val ocr = lines.map { it.text.trim() }.filter { it.isNotBlank() }.joinToString("\n")
        if (ocr.isBlank()) return tree.takeIf { it.isNotBlank() }?.take(6_000)
        return mergeText(tree, ocr).take(12_000)
    }

    private fun mergeText(tree: String, ocr: String): String {
        val seen = HashSet<String>()
        return (tree.lineSequence() + ocr.lineSequence())
            .map { it.trim() }
            .filter { it.isNotBlank() && seen.add(it.lowercase()) }
            .joinToString("\n")
    }

    private fun hasQueryResult(text: String, query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return false
        val tokens = needle.split(Regex("\\s+")).filter { it.length >= 2 }
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        return lines.count { line ->
            val value = line.lowercase()
            needle in value || tokens.any { it in value }
        } >= 2
    }

    internal fun classifySearch(
        text: String,
        query: String,
        before: String?,
        lexicon: ResearchLexicon,
    ): SearchState {
        val page = text.trim()
        if (page.isEmpty()) return SearchState.NOT_SUBMITTED
        if (lexicon.blockedReason(page) != null) return SearchState.NOT_SUBMITTED
        val previous = before?.trim().orEmpty()
        if (previous.isNotEmpty() && normalizePage(page) == normalizePage(previous)) {
            return SearchState.NOT_SUBMITTED
        }
        if (hasQueryResult(page, query)) return SearchState.RESULTS
        if (lexicon.hasNoResults(page)) return SearchState.EMPTY
        if (query.isNotBlank() && page.contains(query, ignoreCase = true)) {
            // The query is visible, but the page may only be the search field
            // itself. Treat a changed page as results and an unchanged one as
            // a submission that never happened.
            return if (previous.isEmpty() || previous != page) {
                SearchState.RESULTS
            } else {
                SearchState.NOT_SUBMITTED
            }
        }
        return SearchState.NOT_SUBMITTED
    }

    private fun normalizePage(text: String): String =
        text.lowercase().replace(Regex("\\s+"), "")

    private fun searchFailure(
        appLabel: String,
        state: SearchState,
        detail: String,
    ): ToolResult.Failure = ToolResult.Failure(
        "app_research could not complete a search in $appLabel (search state: " +
            "${state.wire}). $detail No message, post, like, follow, order or payment was " +
            "made. Retry once, or use screen_ocr/ui_control to inspect the app and tap its " +
            "search entry manually.",
    )

    private fun isSystemApp(context: ToolContext, packageName: String): Boolean = runCatching {
        val info = context.appContext.packageManager.getApplicationInfo(packageName, 0)
        (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }.getOrDefault(false)

    private suspend fun awaitForegroundSearchState(
        context: ToolContext,
        query: String,
        before: String,
        lexicon: ResearchLexicon,
        preferOcr: Boolean,
    ): SearchState {
        var state = SearchState.NOT_SUBMITTED
        repeat(10) {
            val text = readVisibleText(context, includeOcr = preferOcr)
            state = classifySearch(text, query, before, lexicon)
            if (state != SearchState.NOT_SUBMITTED) return state
            delay(300)
        }
        return state
    }

    private suspend fun awaitSearchEntry(
        context: ToolContext,
        lexicon: ResearchLexicon,
        preferredViewId: String?,
    ): com.verlintas.baic2.device.api.TextNode? {
        repeat(12) {
            findSearchEntry(context, lexicon, preferredViewId)?.let { return it }
            delay(300)
        }
        return findSearchEntry(context, lexicon, preferredViewId)
    }

    private fun findSearchEntry(
        context: ToolContext,
        lexicon: ResearchLexicon,
        preferredViewId: String?,
    ): com.verlintas.baic2.device.api.TextNode? {
        context.accessibility.findSearchNode(lexicon.searchTerms, preferredViewId)?.let { return it }
        val screenHeight = context.accessibility.screenSize()?.second ?: Int.MAX_VALUE
        val maxY = (screenHeight * 0.7f).toInt()
        val candidates = lexicon.searchTerms
            .flatMap { context.accessibility.findText(it) }
            .filter { it.text.length <= 40 && it.centerY <= maxY }
            .distinctBy { "${it.text}@${it.centerX},${it.centerY}" }
            .sortedWith(compareBy({ it.top }, { it.left }))
        return candidates.firstOrNull()
    }

    private suspend fun readVisibleText(
        context: ToolContext,
        includeOcr: Boolean = false,
    ): String {
        val tree = context.accessibility.screenTextAllWindows(400).trim()
            .ifBlank { context.accessibility.screenText(200).trim() }
        val lineCount = tree.lineSequence().count { it.isNotBlank() }
        // Four nav labels are not enough to be a readable result page; OCR
        // only becomes worth its cost when the tree is thin. Third-party apps
        // ask for OCR explicitly because their tree can be populated but stale.
        if (!includeOcr && tree.length >= 80 && lineCount >= 4) return tree
        val image = context.screenshot.capture().getOrNull() ?: return tree
        val ocr = runCatching { context.ocr.recognizeText(image) }.getOrNull()?.getOrNull()
            .orEmpty()
        return mergeText(tree, ocr).take(12_000)
    }

    private suspend fun scroll(context: ToolContext): Boolean {
        val (width, height) = context.accessibility.screenSize() ?: return false
        return context.accessibility.swipe(
            x1 = width / 2,
            y1 = (height * 0.75f).toInt(),
            x2 = width / 2,
            y2 = (height * 0.35f).toInt(),
            durationMs = 450,
        ).isSuccess
    }

    internal fun buildOutput(
        label: String,
        packageName: String,
        query: String,
        screens: Int,
        lines: Collection<String>,
        mode: String = "foreground",
        opened: String? = null,
        detail: String? = null,
        openNote: String? = null,
        session: String? = null,
        previousText: String? = null,
        chrome: Set<String> = emptySet(),
        searchState: SearchState? = null,
    ): String = buildString {
        append("Mode: ").append(mode).append('\n')
        append("App: ").append(label).append(" (").append(packageName).append(")\n")
        append("Query: ").append(query).append('\n')
        searchState?.let { append("Search state: ").append(it.wire).append('\n') }
        if (!session.isNullOrBlank()) append("Session: ").append(session).append('\n')
        append("Screens read: ").append(screens).append('\n')
        val delta = previousText?.let { textDelta(it, lines) }
        if (delta != null) {
            append("Delta from previous observation:\n")
            if (delta.added.isEmpty() && delta.removed.isEmpty()) {
                append("No visible change.\n")
            } else {
                delta.added.take(80).forEach { append("+ ").append(it).append('\n') }
                delta.removed.take(40).forEach { append("- ").append(it).append('\n') }
            }
            if (!opened.isNullOrBlank()) {
                append("\nOpened: ").append(opened).append('\n')
            }
        } else {
            append("Visible text:\n")
            var budget = 3_000
            for (line in prioritize(lines, query, chrome)) {
                if (budget <= 0) break
                val clipped = line.take(budget)
                append("- ").append(clipped).append('\n')
                budget -= clipped.length
            }
            if (!opened.isNullOrBlank()) {
                append("\nOpened: ").append(opened).append('\n')
                if (detail.isNullOrBlank()) {
                    append("Detail: no readable text after opening.\n")
                } else {
                    append("Detail:\n")
                    var detailBudget = 2_000
                    for (line in prioritize(detail.lineSequence().toList(), query, chrome)) {
                        if (detailBudget <= 0) break
                        val clipped = line.take(detailBudget)
                        append("- ").append(clipped).append('\n')
                        detailBudget -= clipped.length
                    }
                }
            }
        }
        openNote?.let { append("\nNote: ").append(it).append('\n') }
        append("\nRead-only: no message, post, like, follow, order or payment was made.")
    }.trimEnd()

    internal data class TextDelta(
        val added: List<String>,
        val removed: List<String>,
    )

    /**
     * Compare two visible pages as line sets. The model already has the first
     * page in the transcript, so a follow-up only needs what actually moved.
     */
    internal fun textDelta(previous: String, current: Collection<String>): TextDelta {
        val before = previous.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length <= 300 }
            .distinctBy { it.lowercase() }
            .toList()
        val after = current.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length <= 300 }
            .distinctBy { it.lowercase() }
            .toList()
        val beforeSet = before.map { it.lowercase() }.toSet()
        val afterSet = after.map { it.lowercase() }.toSet()
        return TextDelta(
            added = after.filterNot { it.lowercase() in beforeSet },
            removed = before.filterNot { it.lowercase() in afterSet },
        )
    }

    /**
     * Keep the signal dense: drop obvious UI chrome, collapse duplicates and
     * put query-matching lines first. The model still gets the page's result
     * context, but pays for fewer tokens and sees the relevant rows sooner.
     */
    internal fun prioritize(
        lines: Collection<String>,
        query: String,
        chrome: Set<String> = emptySet(),
    ): List<String> {
        val cleaned = lines.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length <= 300 }
            .filterNot { it.lowercase() in chrome }
            .distinctBy { it.lowercase() }
            .toList()
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return cleaned.take(80)
        val tokens = needle.split(Regex("\\s+")).filter { it.length >= 2 }
        val matched = cleaned.filter { line ->
            val text = line.lowercase()
            needle in text || tokens.any { it in text }
        }
        if (matched.isEmpty()) return cleaned.take(80)
        val matchedSet = matched.toHashSet()
        return (matched + cleaned.filterNot { it in matchedSet }).take(80)
    }


    private companion object {
        /** Structural controls, not result rows. */
        val CONTROL_CLASSES = listOf(
            "button", "imagebutton", "switch", "checkbox", "togglebutton",
            "radiobutton", "edittext",
        )

        /** Two sub-agents must not drive the same virtual display at once. */
        val RESEARCH_MUTEX = Mutex()

        /** Labels that submit a query on custom search bars. */
        val SEARCH_ACTION_LABELS = listOf("搜索", "search", "搜一搜", "查找")

        /**
         * Icon-only search entries that OCR cannot see. Coordinates are
         * fractions of the display, verified by a search-page marker before
         * the tool types anything. Add apps only after a manual check.
         */
        val KNOWN_SEARCH_ROUTES = mapOf(
            "com.tencent.mm" to KnownSearchRoute(
                xFraction = 0.829f,
                yFraction = 0.087f,
                markers = listOf("search local or internet", "recent searches", "搜索", "搜一搜"),
            ),
        )
    }

}
