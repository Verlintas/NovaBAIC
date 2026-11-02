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

package com.verlintas.baic2.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.verlintas.baic2.core.model.AppMode
import com.verlintas.baic2.core.model.ModeProfile
import com.verlintas.baic2.designsystem.Baic2Mono
import com.verlintas.baic2.designsystem.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/** Compact monospace badge for the current autonomy mode. */
@Composable
fun Baic2ModeChip(
    mode: ModeProfile,
    modifier: Modifier = Modifier,
) {
    val cls = mode.engineMode
    val container: Color
    val content: Color
    when (cls) {
        AppMode.CHAT -> {
            container = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
        AppMode.CHAT_PLUS -> {
            container = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
            content = MaterialTheme.colorScheme.tertiary
        }
        AppMode.ACT -> {
            container = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            content = MaterialTheme.colorScheme.primary
        }
        AppMode.MAX -> {
            container = MaterialTheme.colorScheme.primary
            content = MaterialTheme.colorScheme.onPrimary
        }
    }
    val label = modeProfileLabel(mode)
    val animatedContainer by animateColorAsState(container, tween(220), label = "mode-container")
    val animatedContent by animateColorAsState(content, tween(220), label = "mode-content")
    val glintColor = MaterialTheme.colorScheme.primary
    val animationsEnabled = rememberBaic2AnimationsEnabled()
    // Entering MAX earns the loud version of the effect: a longer sweep,
    // a second coloured band, a bigger halo and a burst of sparkles.
    val strong = mode.autonomous

    // MAX keeps an ambient shimmer while it is active: a breathing halo, a
    // slow aurora sheen across the pill and a gentle hue drift.
    val ambient = rememberInfiniteTransition(label = "max-ambient")
    val sheen by ambient.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        // Ping-pong: the sweep turns around instead of snapping back, so the
        // ambient shimmer loops without a visible jump.
        animationSpec = infiniteRepeatable(
            tween(2_800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "max-sheen",
    )
    val breathe by ambient.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.70f,
        animationSpec = infiniteRepeatable(
            tween(1_900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "max-breathe",
    )

    // Codex-style pop + light sweep whenever the mode actually changes.
    val pop = remember { Animatable(1f) }
    val sweep = remember { Animatable(1f) }
    val sparks = remember {
        // Six sparkles evenly spaced on an ellipse just outside the pill, with
        // a little jitter so it reads organic instead of mechanical.
        val count = 6
        List(count) { index ->
            val random = kotlin.random.Random(971 + index * 37)
            val angle = (index * (360f / count) + 18f) * (PI.toFloat() / 180f) +
                (random.nextFloat() - 0.5f) * 0.26f
            ModeSpark(
                x = cos(angle) * (0.98f + random.nextFloat() * 0.34f),
                y = sin(angle) * (1.20f + random.nextFloat() * 0.42f),
                delay = 0.12f + index * 0.075f + random.nextFloat() * 0.06f,
                size = 1.8f + random.nextFloat() * 1.6f,
            )
        }
    }
    var previousMode by remember { mutableStateOf(mode) }
    LaunchedEffect(mode) {
        if (previousMode != mode) {
            previousMode = mode
            launch {
                pop.snapTo(if (strong) 1.30f else 1.22f)
                pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 700f))
            }
            if (animationsEnabled) {
                launch {
                    sweep.snapTo(0f)
                    sweep.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(if (strong) 1_250 else 900, easing = FastOutSlowInEasing),
                    )
                }
            }
        }
    }

    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .scale(pop.value)
            .drawBehind {
                val progress = sweep.value
                if (progress < 1f) {
                    val envelope = sin(progress * PI).toFloat()
                    val aurora = Baic2Aurora.colors
                    if (strong) {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    glintColor.copy(alpha = 0.75f * envelope),
                                    aurora[0].copy(alpha = 0.45f * envelope),
                                    Color.Transparent,
                                ),
                                center = center,
                                radius = size.maxDimension * 2.1f,
                            ),
                        )
                        val star = auroraStarPath()
                        val unit = size.minDimension / 26f
                        sparks.forEach { spark ->
                            val local = ((progress - spark.delay) / 0.55f).coerceIn(0f, 1f)
                            if (local > 0f && local < 1f) {
                                val twinkle = sin(local * PI).toFloat()
                                val scale = spark.size * unit * (0.7f + 1.5f * twinkle)
                                withTransform({
                                    translate(
                                        center.x + spark.x * size.width * 0.5f,
                                        center.y + spark.y * size.height * 0.5f,
                                    )
                                    scale(scale, scale, pivot = Offset.Zero)
                                }) {
                                    drawPath(star, color = Color.White, alpha = twinkle * 0.95f)
                                }
                            }
                        }
                    } else {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(glintColor.copy(alpha = 0.45f * envelope), Color.Transparent),
                                center = center,
                                radius = size.maxDimension * 1.5f,
                            ),
                        )
                    }
                }
                // Ambient halo that never stops while MAX is active.
                if (strong) {
                    val intensity = if (animationsEnabled) breathe else 0.5f
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                glintColor.copy(alpha = intensity * 0.55f),
                                Baic2Aurora.colors[0].copy(alpha = intensity * 0.28f),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = size.maxDimension * 1.9f,
                        ),
                    )
                }
            }
            .clip(shape)
            .background(animatedContainer)
            .drawWithContent {
                drawContent()
                val progress = sweep.value
                if (progress < 1f) {
                    val envelope = sin(progress * PI).toFloat()
                    val band = size.width * (if (strong) 1.05f else 0.8f)
                    val travel = size.width + band * 2f
                    if (strong) {
                        // Trailing coloured band behind the white core.
                        val trail = (progress - 0.14f).coerceIn(0f, 1f)
                        if (trail > 0f) {
                            val trailEnvelope = sin(trail * PI).toFloat()
                            val cx = -band + trail * travel
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Baic2Aurora.colors[2].copy(alpha = 0.55f * trailEnvelope),
                                        Color.Transparent,
                                    ),
                                    start = Offset(cx - band / 2f, 0f),
                                    end = Offset(cx + band / 2f, size.height),
                                ),
                                blendMode = BlendMode.Plus,
                            )
                        }
                    }
                    val cx = -band + progress * travel
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = envelope * (if (strong) 1f else 0.85f)),
                                Color.Transparent,
                            ),
                            start = Offset(cx - band / 2f, 0f),
                            end = Offset(cx + band / 2f, size.height),
                        ),
                        blendMode = BlendMode.Plus,
                    )
                }
                // Ambient shimmer while MAX stays selected: a slow hue drift
                // plus a glass glint that keeps crossing the pill.
                if (strong) {
                    val t = if (animationsEnabled) sheen else 0.5f
                    val aurora = Baic2Aurora.colors
                    val hueShift = -size.width + t * size.width * 2f
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                aurora[0].copy(alpha = 0.16f),
                                aurora[1].copy(alpha = 0.16f),
                                aurora[2].copy(alpha = 0.16f),
                                aurora[0].copy(alpha = 0.16f),
                            ),
                            start = Offset(hueShift, 0f),
                            end = Offset(hueShift + size.width * 2f, size.height),
                        ),
                        blendMode = BlendMode.Plus,
                    )
                    val sheenBand = size.width * 0.9f
                    val sheenTravel = size.width + sheenBand * 2f
                    val sheenX = -sheenBand + t * sheenTravel
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.20f),
                                Color.Transparent,
                            ),
                            start = Offset(sheenX - sheenBand / 2f, 0f),
                            end = Offset(sheenX + sheenBand / 2f, size.height),
                        ),
                        blendMode = BlendMode.Plus,
                    )
                }
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = Baic2Mono.label,
            color = animatedContent,
        )
    }
}

/**
 * Badge label for a mode profile: the user-supplied name when set, the
 * localized stock label otherwise (unknown ids degrade to their class label).
 */
@Composable
fun modeProfileLabel(mode: ModeProfile): String = mode.name ?: stringResource(
    when (mode.id) {
        "CHAT" -> R.string.mode_chat
        "CHAT_PLUS" -> R.string.mode_chat_plus
        "ACT" -> R.string.mode_act
        "MAX" -> R.string.mode_max
        else -> when (mode.engineMode) {
            AppMode.CHAT -> R.string.mode_chat
            AppMode.CHAT_PLUS -> R.string.mode_chat_plus
            AppMode.ACT -> R.string.mode_act
            AppMode.MAX -> R.string.mode_max
        }
    },
)

private data class ModeSpark(
    val x: Float,
    val y: Float,
    val delay: Float,
    val size: Float,
)
