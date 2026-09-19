package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders streaming chat tokens with a smooth fade-in animation for each incoming token chunk,
 * accompanied by an animated blinking cursor to make token generation feel organic and responsive.
 */
@Composable
fun SmoothStreamingTokenText(
    streamingText: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    cursorColor: Color = MaterialTheme.colorScheme.primary
) {
    if (streamingText.isEmpty()) {
        // Initial thinking placeholder with breathing pulse
        val infiniteTransition = rememberInfiniteTransition(label = "thinking_pulse")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "thinking_alpha"
        )

        Row(
            modifier = modifier
                .fillMaxWidth()
                .testTag("streaming_thinking_placeholder"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Processing prompt through on-device GGUF weights...",
                style = textStyle.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = pulseAlpha)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "▌",
                style = textStyle.copy(fontWeight = FontWeight.Bold),
                color = cursorColor.copy(alpha = pulseAlpha)
            )
        }
        return
    }

    // Track previous text to identify incoming token chunk
    var previousText by remember { mutableStateOf("") }
    var stableText by remember { mutableStateOf("") }
    var latestTokenChunk by remember { mutableStateOf("") }

    val tokenAlphaAnimatable = remember { Animatable(1f) }

    LaunchedEffect(streamingText) {
        if (streamingText.length > previousText.length) {
            val newDelta = streamingText.substring(previousText.length)
            stableText = previousText
            latestTokenChunk = newDelta
            previousText = streamingText

            // Smooth fade-in for incoming token delta
            tokenAlphaAnimatable.snapTo(0.15f)
            tokenAlphaAnimatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing)
            )
        } else if (streamingText != previousText) {
            // Text was reset or replaced
            stableText = streamingText
            latestTokenChunk = ""
            previousText = streamingText
            tokenAlphaAnimatable.snapTo(1f)
        }
    }

    // Blinking cursor transition
    val cursorTransition = rememberInfiniteTransition(label = "cursor_blink")
    val cursorAlpha by cursorTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_alpha"
    )

    val currentAlpha = tokenAlphaAnimatable.value

    val annotatedString = remember(stableText, latestTokenChunk, currentAlpha, isStreaming, cursorAlpha, textColor, cursorColor) {
        buildAnnotatedString {
            // Stable text already rendered
            withStyle(SpanStyle(color = textColor)) {
                append(stableText)
            }

            // Latest token chunk with smooth alpha fade-in
            if (latestTokenChunk.isNotEmpty()) {
                withStyle(SpanStyle(color = textColor.copy(alpha = currentAlpha))) {
                    append(latestTokenChunk)
                }
            }

            // Streaming Cursor
            if (isStreaming) {
                withStyle(
                    SpanStyle(
                        color = cursorColor.copy(alpha = cursorAlpha),
                        fontWeight = FontWeight.Bold
                    )
                ) {
                    append(" ▌")
                }
            }
        }
    }

    Text(
        text = annotatedString,
        style = textStyle,
        modifier = modifier.testTag("streaming_token_text")
    )
}
