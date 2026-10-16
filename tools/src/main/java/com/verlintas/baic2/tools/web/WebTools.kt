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

package com.verlintas.baic2.tools.web

import com.verlintas.baic2.core.model.DangerLevel
import com.verlintas.baic2.core.model.ToolResult
import com.verlintas.baic2.core.model.ToolSpec
import com.verlintas.baic2.tools.DeviceTool
import com.verlintas.baic2.tools.ToolContext
import java.io.IOException
import java.net.URLDecoder
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

internal data class SearchHit(val title: String, val url: String, val snippet: String)

/** Shared, SSRF-guarded HTTP fetching for web tools. */
class WebFetcher @Inject constructor(
    client: OkHttpClient,
) {

    private val client = client.newBuilder()
        // Redirects are followed manually so every hop is SSRF-checked before
        // the connection is opened (auto-follow would hit the private host first).
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Executes [request], following at most [maxRedirects] hops with every
     * target validated against the public-URL policy before it is dialled.
     */
    private fun executeGuarded(request: Request, maxRedirects: Int = 5): okhttp3.Response {
        var current = request
        repeat(maxRedirects + 1) { hop ->
            // Lexical checks stop literals; resolving stops DNS tricks
            // (localtest.me, attacker names pointing at 127.0.0.1).
            if (!isPublicHttpUrl(current.url.toString()) || !hostResolvesPublic(current.url)) {
                throw IOException("blocked_url: refusing a private or unresolvable host")
            }
            val response = client.newCall(current).execute()
            val redirect = response.code in REDIRECT_CODES
            val location = if (redirect) response.header("Location") else null
            if (location == null) return response
            response.close()
            if (hop == maxRedirects) {
                throw IOException("too many redirects for ${request.url}")
            }
            val next = current.url.resolve(location)
                ?: throw IOException("blocked_redirect: bad location '$location'")
            if (!isPublicHttpUrl(next.toString())) {
                throw IOException("blocked_redirect: refusing to follow to a private address")
            }
            current = current.newBuilder().url(next).build()
        }
        throw IOException("too many redirects for ${request.url}")
    }

    fun fetch(
        url: String,
        userAgent: String = DEFAULT_UA,
        maxBytes: Long = 2_000_000,
    ): Result<String> {
        if (!isPublicHttpUrl(url)) {
            return Result.failure(IllegalArgumentException("blocked_url: only public http(s) URLs are allowed"))
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()
        return try {
            executeGuarded(request).use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("HTTP ${response.code} for $url"))
                }
                val finalUrl = response.request.url.toString()
                if (!isPublicHttpUrl(finalUrl)) {
                    return Result.failure(
                        IllegalArgumentException("blocked_redirect: '$url' redirected to a private address"),
                    )
                }
                val source = response.body?.source() ?: return Result.failure(IOException("empty body"))
                source.request(maxBytes)
                val bytes = source.buffer.clone().readByteArray()
                // Honour the server charset, then sniff <meta charset>; many
                // mainland sites still serve GBK and UTF-8 decoding garbles them.
                val charset = response.body?.contentType()?.charset()
                    ?: charsetFromHtml(bytes)
                    ?: Charsets.UTF_8
                Result.success(String(bytes, charset))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    private fun charsetFromHtml(bytes: ByteArray): java.nio.charset.Charset? {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val match = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .find(head) ?: return null
        return runCatching { java.nio.charset.Charset.forName(match.groupValues[1]) }.getOrNull()
    }

    fun fetchBytes(url: String, maxBytes: Long = 20_000_000L): Result<ByteArray> {
        if (!isPublicHttpUrl(url)) {
            return Result.failure(IllegalArgumentException("blocked_url: only public http(s) URLs are allowed"))
        }
        val request = Request.Builder().url(url).header("User-Agent", DEFAULT_UA).get().build()
        return try {
            executeGuarded(request).use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("HTTP ${response.code} for $url"))
                }
                val finalUrl = response.request.url.toString()
                if (!isPublicHttpUrl(finalUrl)) {
                    return Result.failure(
                        IllegalArgumentException("blocked_redirect: '$url' redirected to a private address"),
                    )
                }
                val source = response.body?.source() ?: return Result.failure(IOException("empty body"))
                source.request(maxBytes)
                val bytes = source.buffer.clone().readByteArray()
                if (bytes.size > maxBytes) {
                    Result.failure(IOException("file exceeds the ${maxBytes / 1_000_000} MB limit"))
                } else {
                    Result.success(bytes)
                }
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    private fun hostResolvesPublic(url: okhttp3.HttpUrl): Boolean {
        val addresses = try {
            java.net.InetAddress.getAllByName(url.host)
        } catch (e: java.net.UnknownHostException) {
            return false
        }
        if (addresses.isEmpty()) return false
        return addresses.all { address ->
            val bytes = address.address
            !address.isLoopbackAddress && !address.isSiteLocalAddress &&
                !address.isLinkLocalAddress && !address.isAnyLocalAddress &&
                !address.isMulticastAddress && !isCarrierGradeNat(bytes)
        }
    }

    private fun isCarrierGradeNat(bytes: ByteArray): Boolean =
        bytes.size == 4 && (bytes[0].toInt() and 0xFF) == 100 &&
            (bytes[1].toInt() and 0xFF) in 64..127

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}

/** Rejects loopback/private hosts to keep the model from probing the LAN. */
internal fun isPublicHttpUrl(raw: String): Boolean {
    val url = raw.toHttpUrlOrNull() ?: return false
    if (url.scheme != "http" && url.scheme != "https") return false
    val host = url.host.lowercase()
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return false
    if (host.contains(':')) {
        // IPv6 literals only: a *domain* starting with "fc"/"fd" is legitimate.
        if (host == "::1" || host.startsWith("fc") || host.startsWith("fd") ||
            host.startsWith("fe80") || host.contains("::ffff:")
        ) {
            return false
        }
    }
    val parts = host.split('.')
    if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
        val a = parts[0].toInt()
        val b = parts[1].toInt()
        if (a == 10 || a == 127 || a == 0) return false
        if (a == 172 && b in 16..31) return false
        if (a == 192 && b == 168) return false
        if (a == 169 && b == 254) return false
    }
    return true
}

class WebSearchTool @Inject constructor(
    private val fetcher: WebFetcher,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "web_search",
        untrustedOutput = true,
        description = "Search the web across multiple engines; results are merged, deduplicated " +
            "and ranked by relevance. Optionally reads the full text of the top results " +
            "(read_top). `query` may be an array of up to 3 queries for comparisons. " +
            "Use `freshness` for time-sensitive news and `engines` to restrict or widen " +
            "the engine set (e.g. \"bing,baidu\" for local results). Site: and quotes are " +
            "passed through to the engines.",
        parametersJson = """{"type":"object","properties":{"query":{"description":"One query string or an array of up to 3 queries"},"max_results":{"type":"integer","description":"1-15, default 10"},"read_top":{"type":"integer","description":"read the full text of the top N results, 0-3, default 1"},"freshness":{"type":"string","enum":["any","day","week","month","year"],"description":"prefer recent results where the engine supports it"},"engines":{"type":"string","description":"comma-separated subset of ddg-lite,ddg,bing,baidu,mojeek,360,sogou"},"refresh":{"type":"boolean","description":"bypass the 5-minute cache"}},"required":["query"]}""",
        readOnly = true,
        danger = DangerLevel.LOW,
        parallelSafe = true,
        cacheable = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val queries = parseQueries(arguments)
        if (queries.isEmpty()) return ToolResult.Failure("Missing 'query' argument")
        val maxResults = ((arguments["max_results"] as? JsonPrimitive)?.intOrNull ?: 10)
            .coerceIn(1, 15)
        val readTop = ((arguments["read_top"] as? JsonPrimitive)?.intOrNull ?: 1).coerceIn(0, 3)
        val refresh = (arguments["refresh"] as? JsonPrimitive)?.booleanOrNull ?: false
        val freshness = (arguments["freshness"] as? JsonPrimitive)?.contentOrNull
            ?.lowercase()?.takeIf { it in FRESHNESS_VALUES } ?: "any"
        val engines = parseEngines(arguments["engines"])
        val enginesExplicit = (arguments["engines"] as? JsonPrimitive)?.contentOrNull
            ?.split(',')?.map { it.trim().lowercase() }?.any { it in ALL_ENGINES } == true
        // Engines that just failed twice stay out of the rotation for a while,
        // unless the caller asked for them explicitly.
        val activeEngines = if (enginesExplicit) {
            engines
        } else {
            engines.filterNot { EngineHealth.isCoolingDown(it) }.toSet().ifEmpty { engines }
        }

        val cacheKey = queries.joinToString("|") +
            "|$maxResults|$readTop|$freshness|${engines.sorted().joinToString(",")}"
        if (!refresh) SearchCache.get(cacheKey)?.let { return ToolResult.Success(it) }

        val (hits, diagnostics) = runEngines(queries, (maxResults * 2).coerceAtMost(30), activeEngines, freshness)
        if (hits.isEmpty()) {
            return ToolResult.Failure(
                "No results for ${queries.joinToString()} ($diagnostics). " +
                    "Try a simpler query or engines=\"bing,baidu\" on a local network.",
            )
        }
        val ranked = SearchPipeline.rank(hits, queries)
        val deduped = SearchPipeline.dedupe(ranked).take(maxResults)

        val text = buildString {
            append("Results for ").append(queries.joinToString(" | ") { "'$it'" })
            if (freshness != "any") append(" (freshness: ").append(freshness).append(')')
            append(":\n")
            deduped.forEachIndexed { index, hit ->
                append('\n').append(index + 1).append(". ").append(hit.title).append('\n')
                append("   ").append(hit.url).append('\n')
                if (hit.snippet.isNotBlank()) {
                    append("   ").append(hit.snippet.take(240)).append('\n')
                }
            }
            if (readTop > 0) append(readBodies(deduped, readTop))
            append('\n').append('[').append(diagnostics).append(']')
        }
        val clipped = if (text.length > MAX_OUTPUT_CHARS) {
            text.take(MAX_OUTPUT_CHARS) + "…(truncated)"
        } else {
            text
        }
        SearchCache.put(cacheKey, clipped)
        return ToolResult.Success(clipped)
    }

    private fun parseQueries(arguments: JsonObject): List<String> {
        val element = arguments["query"] ?: return emptyList()
        val raw = when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(element.contentOrNull)
            else -> emptyList()
        }
        return raw.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(3)
    }

    /** Splits the optional engine filter; unknown names fall back to all engines. */
    private fun parseEngines(element: JsonElement?): Set<String> {
        val requested = ((element as? JsonPrimitive)?.contentOrNull ?: return ALL_ENGINES)
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it in ALL_ENGINES }
            .toSet()
        return requested.ifEmpty { ALL_ENGINES }
    }

    private suspend fun runEngines(
        queries: List<String>,
        perQuery: Int,
        engines: Set<String>,
        freshness: String,
    ): Pair<List<SearchHit>, String> =
        supervisorScope {
            val jobs = buildList {
                queries.forEach { query ->
                    if ("ddg-lite" in engines) {
                        add("ddg-lite" to async { searchDuckDuckGoLite(query, perQuery, freshness) })
                    }
                    if ("ddg" in engines) {
                        add("ddg" to async { searchDuckDuckGo(query, perQuery, freshness) })
                    }
                    if ("bing" in engines) {
                        add("bing" to async { searchBing(query, perQuery, freshness) })
                    }
                    if ("baidu" in engines) {
                        add("baidu" to async { searchBaidu(query, perQuery) })
                    }
                    if ("mojeek" in engines) {
                        add("mojeek" to async { searchMojeek(query, perQuery) })
                    }
                    if ("360" in engines) {
                        add("360" to async { search360(query, perQuery) })
                    }
                    if ("sogou" in engines) {
                        add("sogou" to async { searchSogou(query, perQuery) })
                    }
                }
            }
            data class EngineTally(var count: Int = 0, var error: String? = null)
            val tally = LinkedHashMap<String, EngineTally>()
            val hits = mutableListOf<SearchHit>()
            jobs.forEach { (name, job) ->
                val slot = tally.getOrPut(name) { EngineTally() }
                val outcome = runCatching {
                    withTimeoutOrNull(ENGINE_TIMEOUT_MS) { job.await() }
                }
                val result = outcome.getOrNull()
                when {
                    result != null -> {
                        slot.count += result.size
                        hits += result
                        if (result.isEmpty()) {
                            slot.error = slot.error ?: "0 results (blocked?)"
                        }
                        EngineHealth.report(name, ok = true)
                    }
                    outcome.isSuccess -> {
                        // withTimeoutOrNull cancels only the waiter; stop the engine too.
                        job.cancel()
                        slot.error = slot.error ?: "timeout"
                        EngineHealth.report(name, ok = false)
                    }
                    else -> {
                        val message = outcome.exceptionOrNull()?.message.orEmpty()
                        slot.error = slot.error ?: message.ifBlank { "failed" }.take(60)
                        EngineHealth.report(name, ok = false)
                    }
                }
            }
            val diagnostics = tally.entries.joinToString(" ") { (name, slot) ->
                when {
                    slot.count > 0 -> "$name\u2713${slot.count}"
                    slot.error != null -> "$name\u2717(${slot.error})"
                    else -> "$name\u2717"
                }
            }
            hits to diagnostics
        }

    private suspend fun readBodies(hits: List<SearchHit>, wanted: Int): String =
        withContext(Dispatchers.IO) {
            val chunks = mutableListOf<String>()
            var attempts = 0
            for (hit in hits) {
                if (chunks.size >= wanted || attempts >= READ_ATTEMPTS) break
                attempts++
                val html = fetcher.fetch(hit.url).getOrNull() ?: continue
                val article = SearchPipeline.extractArticle(html)
                if (article.isBlank()) continue
                val body = if (article.length > BODY_BUDGET) {
                    article.take(BODY_BUDGET) + "…"
                } else {
                    article
                }
                chunks += "\n--- ${hit.title}\n${hit.url}\n$body"
            }
            if (chunks.isEmpty()) "" else "\n\nTop result bodies:" + chunks.joinToString("\n")
        }

    private fun searchDuckDuckGoLite(query: String, limit: Int, freshness: String = "any"): List<SearchHit> {
        val url = "https://lite.duckduckgo.com/lite/?q=" +
            java.net.URLEncoder.encode(query, "UTF-8") + freshnessParam("ddg", freshness)
        val html = fetcher.fetch(url).getOrElse { failure -> throw IllegalStateException(failure.message.orEmpty()) }
        val doc = Jsoup.parse(html, url)
        return doc.select("a.result-link").mapNotNull { anchor ->
            val href = anchor.absUrl("href")
            if (!href.startsWith("http")) return@mapNotNull null
            val snippet = anchor.parent()?.parent()?.nextElementSibling()?.text().orEmpty()
            SearchHit(anchor.text(), href, snippet)
        }.take(limit)
    }

    private fun searchDuckDuckGo(query: String, limit: Int, freshness: String = "any"): List<SearchHit> {
        val url = "https://html.duckduckgo.com/html/?q=" +
            java.net.URLEncoder.encode(query, "UTF-8") + freshnessParam("ddg", freshness)
        val html = fetcher.fetch(url).getOrElse { failure -> throw IllegalStateException(failure.message.orEmpty()) }
        val doc = Jsoup.parse(html, url)
        return doc.select("div.result, div.web-result").mapNotNull { result ->
            val hit = titleAnchor(result, url, "a.result__a") ?: return@mapNotNull null
            val snippet = result.selectFirst(".result__snippet")?.text().orEmpty()
            hit.copy(snippet = snippet)
        }.take(limit)
    }

    private fun searchBing(query: String, limit: Int, freshness: String = "any"): List<SearchHit> {
        val url = "https://www.bing.com/search?q=" +
            java.net.URLEncoder.encode(query, "UTF-8") + freshnessParam("bing", freshness)
        val html = fetcher.fetch(url).getOrElse { failure -> throw IllegalStateException(failure.message.orEmpty()) }
        val doc = Jsoup.parse(html, url)
        return doc.select("li.b_algo").mapNotNull { item ->
            val hit = titleAnchor(item, url) ?: return@mapNotNull null
            val snippet = item.selectFirst(".b_caption p, p")?.text().orEmpty()
            hit.copy(snippet = snippet)
        }.take(limit)
    }

    private fun searchBaidu(query: String, limit: Int): List<SearchHit> {
        val url = "https://www.baidu.com/s?wd=" + java.net.URLEncoder.encode(query, "UTF-8")
        val html = fetcher.fetch(url).getOrElse { failure -> throw IllegalStateException(failure.message.orEmpty()) }
        val doc = Jsoup.parse(html, url)
        return doc.select("div.result, div.c-container, div.c-result").mapNotNull { item ->
            val hit = titleAnchor(item, url) ?: return@mapNotNull null
            val snippet = item.selectFirst(".c-abstract, .content-right_8Zs40, .c-span-last")?.text().orEmpty()
            hit.copy(snippet = snippet)
        }.take(limit)
    }

    private fun searchMojeek(query: String, limit: Int): List<SearchHit> {
        val url = "https://www.mojeek.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val html = fetcher.fetch(url).getOrElse { failure -> throw IllegalStateException(failure.message.orEmpty()) }
        val doc = Jsoup.parse(html, url)
        return doc.select("ul.results-standard li, li.result").mapNotNull { item ->
            val hit = titleAnchor(item, url, "a.title") ?: return@mapNotNull null
            val snippet = item.selectFirst("p.s, .s")?.text().orEmpty()
            hit.copy(snippet = snippet)
        }.take(limit)
    }

    private fun search360(query: String, limit: Int): List<SearchHit> {
        val url = "https://www.so.com/s?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val html = fetcher.fetch(url).getOrElse { failure -> throw IllegalStateException(failure.message.orEmpty()) }
        val doc = Jsoup.parse(html, url)
        return doc.select("li.res-list, div.res-list, .result").mapNotNull { item ->
            val hit = titleAnchor(item, url) ?: return@mapNotNull null
            val snippet = item.selectFirst(".res-desc, p.res-desc")?.text().orEmpty()
            hit.copy(snippet = snippet)
        }.take(limit)
    }

    private fun searchSogou(query: String, limit: Int): List<SearchHit> {
        val url = "https://www.sogou.com/web?query=" + java.net.URLEncoder.encode(query, "UTF-8")
        val html = fetcher.fetch(url).getOrElse { failure -> throw IllegalStateException(failure.message.orEmpty()) }
        val doc = Jsoup.parse(html, url)
        return doc.select("div.vrwrap, div.rb").mapNotNull { item ->
            val hit = titleAnchor(item, url, "h3 a") ?: return@mapNotNull null
            val snippet = item.selectFirst(".str-text-info, .str_info, .space-txt")?.text().orEmpty()
            hit.copy(snippet = snippet)
        }.take(limit)
    }

    /**
     * Resolves a result's title anchor: explicit selectors first, then the
     * first public link inside the container (engine markup changes often).
     */
    private fun titleAnchor(
        item: org.jsoup.nodes.Element,
        baseUrl: String,
        vararg preferred: String,
    ): SearchHit? {
        val selectors = preferred.toList() + listOf("h3 a", "h2 a", "a.title", "a.result__a")
        for (selector in selectors) {
            val anchor = item.selectFirst(selector) ?: continue
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (href.startsWith("http")) {
                return SearchHit(
                    anchor.text().ifBlank { item.selectFirst("h2, h3")?.text().orEmpty() },
                    href,
                    "",
                )
            }
        }
        item.selectFirst("a[href]")?.let { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (href.startsWith("http") && !href.contains(baseUrl.substringAfter("//").substringBefore("/"))) {
                return SearchHit(anchor.text(), href, "")
            }
        }
        return null
    }

    /** Time filter for the engines that support one; others keep the plain query. */
    private fun freshnessParam(engine: String, freshness: String): String {
        if (freshness == "any") return ""
        return when (engine) {
            "ddg" -> "&df=" + when (freshness) {
                "day" -> "d"
                "week" -> "w"
                "month" -> "m"
                else -> "y"
            }

            "bing" -> {
                val code = when (freshness) {
                    "day" -> "ez1"
                    "week" -> "ez2"
                    else -> "ez3"
                }
                "&filters=" + java.net.URLEncoder.encode("ex1:\"$code\"", "UTF-8")
            }

            else -> ""
        }
    }

    private companion object {
        val ALL_ENGINES = setOf("ddg-lite", "ddg", "bing", "baidu", "mojeek", "360", "sogou")
        val FRESHNESS_VALUES = setOf("any", "day", "week", "month", "year")
        const val ENGINE_TIMEOUT_MS = 8_000L
        const val READ_ATTEMPTS = 6
        const val BODY_BUDGET = 5_000
        const val MAX_OUTPUT_CHARS = 9_000
    }
}

class WebReadTool @Inject constructor(
    private val fetcher: WebFetcher,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "web_read",
        untrustedOutput = true,
        description = "Fetch a web page and return its readable article text (boilerplate stripped). " +
            "Long pages are paged: pass 'offset' from the '(more: …)' hint to continue instead of " +
            "re-fetching.",
        parametersJson = """{"type":"object","properties":{"url":{"type":"string"},"offset":{"type":"integer","description":"character offset to continue from, default 0"},"max_chars":{"type":"integer","description":"page size, 500-24000, default 8000"}},"required":["url"]}""",
        readOnly = true,
        danger = DangerLevel.LOW,
        parallelSafe = true,
        cacheable = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val url = (arguments["url"] as? JsonPrimitive)?.content?.trim()
            ?: return ToolResult.Failure("Missing 'url' argument")
        val offset = ((arguments["offset"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
        val maxChars = ((arguments["max_chars"] as? JsonPrimitive)?.intOrNull ?: 8_000)
            .coerceIn(500, 24_000)

        val html = fetcher.fetch(url).getOrElse { failure ->
            return ToolResult.Failure("Fetch failed: ${failure.message}")
        }
        val doc = Jsoup.parse(html, url)
        val title = doc.title().trim()
        var text = SearchPipeline.extractArticle(html)
        if (text.isBlank()) {
            doc.select("script, style, nav, header, footer, aside, noscript, iframe, svg").remove()
            text = (doc.body()?.text() ?: doc.text()).replace(Regex("\\s+"), " ").trim()
        }
        if (text.isBlank()) return ToolResult.Failure("Page has no readable text.")

        val header = if (title.isBlank()) url else "$title\n$url"
        if (offset >= text.length) {
            return ToolResult.Success("$header\n\n(page has ${text.length} characters; offset $offset is past the end)")
        }
        val chunk = text.substring(offset, minOf(text.length, offset + maxChars))
        val more = offset + chunk.length < text.length
        return ToolResult.Success(
            buildString {
                append(header).append("\n\n")
                append(chunk)
                if (more) {
                    append("\n…(more: call web_read offset=").append(offset + chunk.length)
                        .append(" to continue)")
                }
            },
        )
    }
}

class GetWeatherTool @Inject constructor(
    private val fetcher: WebFetcher,
    private val json: Json,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "get_weather",
        untrustedOutput = true,
        description = "Current weather and a 3-day forecast for a city (no API key).",
        parametersJson = """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""",
        readOnly = true,
        danger = DangerLevel.LOW,
        parallelSafe = true,
        cacheable = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val city = (arguments["city"] as? JsonPrimitive)?.content?.trim()
            ?: return ToolResult.Failure("Missing 'city' argument")
        val url = "https://wttr.in/" + java.net.URLEncoder.encode(city, "UTF-8") + "?format=j1"
        val body = fetcher.fetch(url).getOrElse { failure ->
            return ToolResult.Failure("Weather lookup failed: ${failure.message}")
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ToolResult.Failure("Weather response was not parseable.")
        val current = root["current_condition"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return ToolResult.Failure("No weather data for '$city'.")
        val desc = current["weatherDesc"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("value")?.jsonPrimitive?.content.orEmpty()
        val text = buildString {
            append("$city: $desc, ${current.str("temp_C")}°C (feels ${current.str("FeelsLikeC")}°C), ")
            append("humidity ${current.str("humidity")}%, wind ${current.str("windspeedKmph")} km/h\n")
            root["weather"]?.jsonArray?.take(3)?.forEach { day ->
                val d = day.jsonObject
                append("\n${d.str("date")}: ${d.str("mintempC")}~${d.str("maxtempC")}°C")
            }
        }
        return ToolResult.Success(text)
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content.orEmpty()
}

class FetchRssTool @Inject constructor(
    private val fetcher: WebFetcher,
) : DeviceTool {

    override val spec = ToolSpec(
        name = "fetch_rss",
        untrustedOutput = true,
        description = "Parse an RSS/Atom feed and return recent items (title, link, date).",
        parametersJson = """{"type":"object","properties":{"url":{"type":"string"},"limit":{"type":"integer"}},"required":["url"]}""",
        readOnly = true,
        danger = DangerLevel.LOW,
        parallelSafe = true,
        cacheable = true,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val url = (arguments["url"] as? JsonPrimitive)?.content?.trim()
            ?: return ToolResult.Failure("Missing 'url' argument")
        val limit = ((arguments["limit"] as? JsonPrimitive)?.intOrNull ?: 10).coerceIn(1, 30)
        val body = fetcher.fetch(url).getOrElse { failure ->
            return ToolResult.Failure("Feed fetch failed: ${failure.message}")
        }
        val doc = runCatching { Jsoup.parse(body, url, Parser.xmlParser()) }.getOrNull()
            ?: return ToolResult.Failure("Feed parse failed.")
        val items = doc.select("item, entry").take(limit)
        if (items.isEmpty()) return ToolResult.Failure("No RSS/Atom items found at this URL.")
        return ToolResult.Success(
            buildString {
                append("Feed items:\n")
                items.forEach { item ->
                    val title = item.selectFirst("title")?.text().orEmpty()
                    val link = item.selectFirst("link")?.let { node ->
                        node.text().takeIf { it.isNotBlank() }
                            ?: node.attr("href").takeIf { it.isNotBlank() }
                    }.orEmpty()
                    val date = item.selectFirst("pubDate, updated, published")?.text().orEmpty()
                    append("\n- $title\n  $link${if (date.isBlank()) "" else "\n  $date"}")
                }
            },
        )
    }
}
