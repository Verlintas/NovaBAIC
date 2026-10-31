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

package com.verlintas.baic2.tools.skills

import android.content.Context
import android.net.Uri
import com.verlintas.baic2.core.model.Skill
import com.verlintas.baic2.core.model.SkillStep
import com.verlintas.baic2.core.model.SkillToolDef
import com.verlintas.baic2.core.model.ToolCall
import com.verlintas.baic2.core.model.ToolCallStatus
import com.verlintas.baic2.tools.UiRefRegistry
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.verlintas.baic2.tools.materializeUiRefs
import com.verlintas.baic2.tools.snapshotRefLabels
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Skill storage: one directory per skill under filesDir/skills with a
 * skill.yaml manifest. Imports are copied so the picker URI can disappear.
 */
@Singleton
class SkillRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val root: File get() = File(context.filesDir, "skills")

    suspend fun list(): List<Skill> = withContext(Dispatchers.IO) {
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        dirs.mapNotNull { dir ->
            val manifest = File(dir, "skill.yaml")
            if (!manifest.exists()) return@mapNotNull null
            SkillCodec.parse(manifest.readText()).getOrNull()
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun find(idOrName: String): Skill? = withContext(Dispatchers.IO) {
        list().firstOrNull { it.id == idOrName || it.name.equals(idOrName, ignoreCase = true) }
    }

    suspend fun import(uri: Uri, displayName: String?): Result<Skill> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                var total = 0
                while (total <= MAX_MANIFEST_BYTES) {
                    val read = stream.read(chunk)
                    if (read == -1) break
                    total += read
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray().toString(Charsets.UTF_8)
            } ?: error("unreadable skill file")
            require(text.length <= MAX_MANIFEST_BYTES) { "skill_manifest_too_large" }
            val skill = SkillCodec.parse(text).getOrThrow()
            save(skill)
            skill
        }
    }

    suspend fun save(skill: Skill) = withContext(Dispatchers.IO) {
        // The id comes from an imported manifest: slug it so crafted ids like
        // "../../foo" can never escape the skills directory.
        val id = storageId(skill.id).ifBlank { "skill-${System.currentTimeMillis()}" }
        val directory = File(root, id).apply { mkdirs() }
        File(directory, "skill.yaml").writeText(SkillCodec.encode(skill))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val safe = storageId(id)
        if (safe.isNotBlank()) runCatching { File(root, safe).deleteRecursively() }
    }

    /** Turns a completed run's tool calls into a replayable skill. */
    suspend fun saveFromToolCalls(name: String, calls: List<ToolCall>): Skill {
        val id = storageId(name).ifBlank { "skill-${System.currentTimeMillis()}" }
        val steps = buildSteps(calls)
        val skill = Skill(
            id = id,
            name = name,
            description = "录制自一次完成的操作序列",
            tools = listOf(skillTool(id, calls, steps)),
        )
        save(skill)
        return skill
    }

    /**
     * Auto-saves a successful UI flow when the user opted in. Returns null
     * when the same flow is already stored, so repeated runs do not duplicate
     * the library. Old auto-saved entries are pruned to a bounded window.
     */
    suspend fun saveAutoFromToolCalls(
        suggestedName: String,
        calls: List<ToolCall>,
    ): Skill? = withContext(Dispatchers.IO) {
        // Metadata and orchestration steps are not part of the replayable
        // device flow; including them would make the skill noisy or invalid.
        val replayCalls = filterAutoSaveCalls(calls)
        if (replayCalls.none(::isReplayableSequence)) return@withContext null
        // A skill file is app-private but still exportable. Never record a
        // flow that appears to type a password/PIN/OTP into a labelled field.
        if (containsSensitiveInput(replayCalls)) return@withContext null
        // Snapshot calls are filtered from the recipe, but their labels are
        // still needed to resolve refs in the remaining sequence steps.
        val parameterized = parameterizeAutoSteps(buildAutoSteps(calls))
        val steps = parameterized.steps
        val id = "auto-${skillSignature(steps)}"
        val manifest = File(root, id)
        if (manifest.exists()) {
            File(manifest, "skill.yaml").setLastModified(System.currentTimeMillis())
            return@withContext null
        }
        val name = autoSkillName(steps)
            ?: suggestedName.trim().take(40).takeIf { it.isNotBlank() }
            ?: "Auto flow"
        val skill = Skill(
            id = id,
            name = name,
            description = "Auto-saved from a successful multi-step run",
            tools = listOf(skillTool(id, replayCalls, steps, parameterized.parametersJson)),
        )
        save(skill)
        pruneAutoSkills()
        skill
    }

    private fun buildSteps(calls: List<ToolCall>): List<SkillStep> {
        val refs = LinkedHashMap<String, String>()
        return calls.map { call ->
            // Element refs are snapshot-scoped; resolve them to text before
            // persisting so a replay days later does not tap a stale handle.
            // Prefer labels from this run's own snapshot result: the process
            // registry may already belong to a newer snapshot.
            if (call.name == "ui_control" && call.argumentsJson.contains("snapshot")) {
                val labels = snapshotRefLabels(call.result)
                if (labels.isNotEmpty()) {
                    refs.clear()
                    refs.putAll(labels)
                }
            }
            val args = if (call.name == "ui_control") {
                materializeUiRefs(call.argumentsJson) { ref ->
                    refs[ref.trim().lowercase()] ?: UiRefRegistry.label(ref)
                }
            } else {
                call.argumentsJson
            }
            SkillStep(tool = call.name, argsJson = args)
        }
    }

    private fun skillTool(
        id: String,
        calls: List<ToolCall>,
        steps: List<SkillStep>,
        parametersJson: String = """{"type":"object","properties":{}}""",
    ): SkillToolDef = SkillToolDef(
        id = id.replace('-', '_'),
        description = "Replays the recorded sequence: ${calls.joinToString(", ") { it.name }}",
        steps = steps,
        parametersJson = parametersJson,
    )

    private fun pruneAutoSkills() {
        val auto = root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("auto-") }
            ?.sortedByDescending { File(it, "skill.yaml").lastModified() }
            .orEmpty()
        auto.drop(MAX_AUTO_SKILLS).forEach { runCatching { it.deleteRecursively() } }
    }

    private fun storageId(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private companion object {
        const val MAX_MANIFEST_BYTES = 64 * 1024
        const val MAX_AUTO_SKILLS = 20

    }
}

private val AUTO_SAVE_SKIP = setOf(
    // Internal mind and orchestration: replaying these would mutate memory or
    // re-enter the agent, not reproduce a device flow.
    "core_memory_update",
    "memory_search",
    "memory_read",
    "memory_write",
    "memory_forget",
    "memory_hold",
    "memory_alias",
    "memory_overview",
    "plan_update",
    "ask_user",
    "load_skill",
    "spawn_agent",
    "automation",
    // Observation-only calls. The replay should act, not re-read.
    "get_screen_state",
    "get_foreground_app",
    "screen_ocr",
    "take_screenshot",
    "screen_record",
    "device_info",
)

private val OBSERVATION_ACTIONS = setOf(
    "snapshot",
    "find",
    "screen_text",
    "read_text",
    "wait_for",
    "assert_text",
)

/** Keeps only the calls that belong in a replayable device flow. */
internal fun filterAutoSaveCalls(calls: List<ToolCall>): List<ToolCall> =
    calls.filter(::isAutoSaveCall)

private fun isAutoSaveCall(call: ToolCall): Boolean =
    call.status == ToolCallStatus.DONE &&
        call.name !in AUTO_SAVE_SKIP &&
        !isPureObservation(call)

private fun isPureObservation(call: ToolCall): Boolean {
    if (call.name != "ui_control") return false
    val root = runCatching {
        Json.parseToJsonElement(call.argumentsJson) as? JsonObject
    }.getOrNull() ?: return false
    return (root["action"] as? JsonPrimitive)?.content
        ?.trim()
        ?.lowercase() in OBSERVATION_ACTIONS
}

private val SENSITIVE_FIELD_TERMS = setOf(
    "password",
    "passcode",
    "pass phrase",
    "pin",
    "otp",
    "verification code",
    "security code",
    "密码",
    "口令",
    "验证码",
    "动态码",
)

/**
 * Best-effort credential guard. It looks at the tap that precedes a type
 * step; if that control is labelled as a password/PIN/OTP field, auto-save is
 * skipped. A value with a provider-token prefix is also treated as a secret.
 */
internal fun containsSensitiveInput(calls: List<ToolCall>): Boolean {
    for (call in calls) {
        if (call.name != "ui_control") continue
        val root = runCatching {
            Json.parseToJsonElement(call.argumentsJson) as? JsonObject
        }.getOrNull() ?: continue
        val action = (root["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
        if (action == "type") {
            if (isSensitiveTypeStep(root, lastTapLabel = null)) return true
            continue
        }
        if (action != "sequence") continue
        val steps = (root["steps"] as? JsonArray) ?: continue
        var lastTapLabel: String? = null
        for (element in steps) {
            val step = element as? JsonObject ?: continue
            val stepAction = (step["action"] as? JsonPrimitive)?.content
                ?.trim()
                ?.lowercase()
            when (stepAction) {
                "tap", "long_press" -> {
                    lastTapLabel = (step["text"] as? JsonPrimitive)?.content
                }
                "type" -> if (isSensitiveTypeStep(step, lastTapLabel)) return true
            }
        }
    }
    return false
}

private fun isSensitiveTypeStep(step: JsonObject, lastTapLabel: String?): Boolean {
    val value = (step["value"] as? JsonPrimitive)?.content.orEmpty()
    if (SENSITIVE_VALUE_PREFIXES.any { value.startsWith(it, ignoreCase = true) }) return true
    val label = ((step["text"] as? JsonPrimitive)?.content ?: lastTapLabel).orEmpty().lowercase()
    return SENSITIVE_FIELD_TERMS.any { it in label }
}

private val SENSITIVE_VALUE_PREFIXES = listOf("sk-", "ghp_", "xoxb-", "AKIA", "Bearer ")

/** Builds the auto recipe while still using snapshot calls as ref labels. */
internal fun buildAutoSteps(calls: List<ToolCall>): List<SkillStep> {
    val refs = LinkedHashMap<String, String>()
    val steps = mutableListOf<SkillStep>()
    for (call in calls) {
        if (call.name == "ui_control" && call.argumentsJson.contains("snapshot")) {
            val labels = snapshotRefLabels(call.result)
            if (labels.isNotEmpty()) {
                refs.clear()
                refs.putAll(labels)
            }
        }
        if (!isAutoSaveCall(call)) continue
        val args = if (call.name == "ui_control") {
            materializeUiRefs(call.argumentsJson) { ref ->
                refs[ref.trim().lowercase()] ?: UiRefRegistry.label(ref)
            }
        } else {
            call.argumentsJson
        }
        steps += SkillStep(tool = call.name, argsJson = args)
    }
    return steps
}

internal data class ParameterizedSteps(
    val steps: List<SkillStep>,
    val parametersJson: String,
)

/**
 * Replace literals typed by the original run with {{inputN}} parameters. This
 * keeps the recipe reusable and prevents personal text (search terms, chat
 * messages) from being copied into a skill file.
 */
internal fun parameterizeAutoSteps(steps: List<SkillStep>): ParameterizedSteps {
    val names = LinkedHashMap<String, String>()
    fun nameFor(value: String): String =
        names.getOrPut(value) { "input${names.size + 1}" }

    fun replaceTypedValue(step: JsonObject): JsonObject {
        val action = (step["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
        if (action != "type") return step
        val value = (step["value"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() && !it.contains("{{") }
            ?: return step
        val parameter = nameFor(value)
        return JsonObject(step + ("value" to JsonPrimitive("{{${parameter}}}")))
    }

    val transformed = steps.map { step ->
        if (step.tool != "ui_control") return@map step
        val root = runCatching {
            Json.parseToJsonElement(step.argsJson) as? JsonObject
        }.getOrNull() ?: return@map step
        val action = (root["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
        val rewritten = when (action) {
            "type" -> replaceTypedValue(root)
            "sequence" -> {
                val sequence = root["steps"] as? JsonArray ?: return@map step
                val newSteps = sequence.map { element ->
                    (element as? JsonObject)?.let(::replaceTypedValue) ?: element
                }
                JsonObject(root + ("steps" to JsonArray(newSteps)))
            }
            else -> return@map step
        }
        step.copy(argsJson = rewritten.toString())
    }

    if (names.isEmpty()) {
        return ParameterizedSteps(
            steps = transformed,
            parametersJson = """{"type":"object","properties":{}}""",
        )
    }
    val properties = JsonObject(
        names.values.associateWith { name ->
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("string"),
                    "description" to JsonPrimitive("Text to type for $name"),
                ),
            )
        },
    )
    val required = JsonArray(names.values.map { JsonPrimitive(it) })
    val schema = JsonObject(
        mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to properties,
            "required" to required,
        ),
    )
    return ParameterizedSteps(transformed, schema.toString())
}

/** A single successful ui_control sequence with at least two steps. */
fun isReplayableSequence(call: ToolCall): Boolean {
    if (call.name != "ui_control" || call.status != ToolCallStatus.DONE) return false
    val root = runCatching {
        Json.parseToJsonElement(call.argumentsJson) as? JsonObject
    }.getOrNull() ?: return false
    if ((root["action"] as? JsonPrimitive)?.content != "sequence") return false
    return ((root["steps"] as? JsonArray)?.size ?: 0) >= 2
}

/**
 * Auto-capture needs a successful multi-step UI sequence. Earlier failed
 * attempts are tolerated but never recorded; only DONE steps become recipe
 * steps. A single tap or a run with no successful sequence is ignored.
 */
fun shouldAutoSaveSkill(calls: List<ToolCall>): Boolean =
    calls.any { it.status == ToolCallStatus.DONE && isReplayableSequence(it) }

/** Prefer the first tap label so auto-saved skills are recognisable. */
internal fun autoSkillName(steps: List<SkillStep>): String? {
    for (step in steps) {
        if (step.tool != "ui_control") continue
        val root = runCatching {
            Json.parseToJsonElement(step.argsJson) as? JsonObject
        }.getOrNull() ?: continue
        if ((root["action"] as? JsonPrimitive)?.content != "sequence") continue
        val sequenceSteps = (root["steps"] as? JsonArray) ?: continue
        val label = sequenceSteps.asSequence()
            .mapNotNull { it as? JsonObject }
            .filter {
                (it["action"] as? JsonPrimitive)?.content in setOf("tap", "long_press")
            }
            .mapNotNull { (it["text"] as? JsonPrimitive)?.content?.trim() }
            .firstOrNull { it.isNotBlank() }
        if (label != null) return "Auto · ${label.take(40)}"
    }
    return null
}

internal fun skillSignature(steps: List<SkillStep>): String {
    val payload = steps.joinToString("\u0000") {
        it.tool + "\u0001" + it.argsJson
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)
}
