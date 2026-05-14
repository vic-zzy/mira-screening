package com.mira.screening.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * "Mira is thinking" indicator rendered as three dots that pulse in a wave.
 *
 * Each dot runs the same fade-and-scale loop but with a staggered start
 * offset, so they animate in sequence rather than in unison. The result reads
 * the way iMessage, WhatsApp, and ChatGPT show a typing indicator, and gives
 * the eye continuous motion to look at while Gemma 4 warms up the first
 * tokens of a response rather than a static, ambiguous spinner.
 */
@Composable
fun TypingDotsIndicator(
    dotColor: Color = MaterialTheme.colorScheme.primary,
    dotSize: Dp = 7.dp,
    spacing: Dp = 5.dp,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    val cycleMs = 900
    val staggerMs = cycleMs / 3

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        repeat(3) { index ->
            val pulse by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = cycleMs, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * staggerMs)
                ),
                label = "dot-pulse-$index"
            )
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        alpha = pulse
                        // Scale tracks alpha so the dot reads as both fading
                        // and bouncing slightly, which makes the wave more
                        // legible than a pure fade.
                        scaleX = 0.6f + 0.4f * pulse
                        scaleY = 0.6f + 0.4f * pulse
                    }
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}
