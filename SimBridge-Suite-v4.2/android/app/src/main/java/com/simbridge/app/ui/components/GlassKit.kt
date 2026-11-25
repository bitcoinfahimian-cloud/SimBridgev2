package com.simbridge.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.simbridge.app.ui.theme.BridgeColors

/** Glass surface: translucent gradient fill + light-to-dark gradient hairline + top highlight. No blur needed (works on API 29+). */
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp)): Modifier =
    this
        .clip(shape)
        .background(
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.11f), Color.White.copy(alpha = 0.04f))
            ),
            shape
        )
        .border(
            BorderStroke(
                1.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.14f))
                )
            ),
            shape
        )

@Composable
fun GlassSurface(
    shape: Shape = RoundedCornerShape(24.dp),
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.glass(shape), content = content)
}

/** Neon gradient outline (cyan -> violet -> magenta). */
fun neonBrush(): Brush = Brush.linearGradient(listOf(BridgeColors.NeonCyan, BridgeColors.NeonViolet, BridgeColors.NeonMagenta))

fun Modifier.neonBorder(shape: Shape, width: Dp = 1.5.dp): Modifier =
    this.border(BorderStroke(width, neonBrush()), shape)

/** Animated aurora background: three slowly drifting blurred-looking radial blobs over a near-black base. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val t = rememberInfiniteTransition(label = "aurora")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(24000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0f, 1f, infiniteRepeatable(tween(30000, easing = LinearEasing), RepeatMode.Reverse), label = "c")

    Box(
        modifier = modifier
            .background(BridgeColors.Background)
            .drawBehind {
                val w = size.width
                val h = size.height
                fun blob(color: Color, cx: Float, cy: Float, r: Float, alpha: Float) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(color.copy(alpha = alpha), Color.Transparent),
                            center = Offset(cx, cy), radius = r
                        ),
                        radius = r, center = Offset(cx, cy)
                    )
                }
                blob(Color(0xFF6D4AFF), w * (0.85f - 0.25f * a), h * (0.08f + 0.10f * a), w * 0.85f, 0.50f)
                blob(Color(0xFF0891B2), w * (0.10f + 0.20f * b), h * (0.45f + 0.10f * b), w * 0.75f, 0.34f)
                blob(Color(0xFFC0197A), w * (0.80f - 0.20f * c), h * (0.95f - 0.12f * c), w * 0.80f, 0.30f)
            },
        content = content
    )
}

/** Neon pill button: dark glass fill, gradient outline, soft glow. */
@Composable
fun NeonButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "btnScale")
    Box(
        modifier = modifier
            .graphicsScale(scale)
            .drawBehind {
                if (enabled) drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(BridgeColors.NeonCyan.copy(alpha = 0.22f), BridgeColors.NeonViolet.copy(alpha = 0.30f), BridgeColors.NeonMagenta.copy(alpha = 0.22f))
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                    size = size.copy(width = size.width, height = size.height),
                    topLeft = Offset(0f, 0f),
                    alpha = 0.9f
                )
            }
            .clip(shape)
            .background(Color(0xFF17143A).copy(alpha = if (enabled) 0.85f else 0.45f), shape)
            .then(if (enabled) Modifier.neonBorder(shape) else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), shape))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = androidx.compose.ui.Alignment.Center,
        content = content
    )
}

private fun Modifier.graphicsScale(s: Float): Modifier =
    this.then(Modifier.graphicsLayer(scaleX = s, scaleY = s))

/** Neon switch with spring knob. */
@Composable
fun NeonSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val knobX by animateDpAsState(if (checked) 22.dp else 0.dp, spring(dampingRatio = 0.5f, stiffness = 420f), label = "knob")
    val trackA by animateColorAsState(if (checked) BridgeColors.NeonCyan else Color.White.copy(alpha = 0.10f), label = "trackA")
    val trackB by animateColorAsState(if (checked) BridgeColors.NeonViolet else Color.White.copy(alpha = 0.10f), label = "trackB")
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .size(width = 52.dp, height = 30.dp)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(trackA, trackB)), shape)
            .border(1.dp, Color.White.copy(alpha = if (checked) 0.0f else 0.18f), shape)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .offset(x = 4.dp + knobX, y = 4.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}
