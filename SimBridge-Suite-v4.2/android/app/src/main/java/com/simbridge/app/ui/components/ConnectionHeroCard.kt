package com.simbridge.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simbridge.app.i18n.UiStrings
import com.simbridge.app.ui.components.GlassSurface
import com.simbridge.app.ui.theme.BridgeColors

@Composable
fun ConnectionHeroCard(
    strings: UiStrings,
    isConnected: Boolean,
    bluetoothEnabled: Boolean,
    iphoneName: String,
    callAudioReady: Boolean,
    onCallAudioChipClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringPulse"
    )

    GlassSurface(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(126.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val centerY = size.height / 2f
                    val leftPhoneCenterX = size.width * 0.24f
                    val rightPhoneCenterX = size.width * 0.76f
                    val phoneW = 54.dp.toPx()
                    val phoneH = 92.dp.toPx()
                    val strokePx = 2.4.dp.toPx()

                    val ringTint = when {
                        !bluetoothEnabled -> BridgeColors.RedDanger
                        isConnected -> BridgeColors.MintReady
                        else -> BridgeColors.PurplePrimary
                    }

                    if (isConnected || bluetoothEnabled) {
                        drawCircle(
                            color = ringTint.copy(alpha = 0.10f),
                            radius = 64.dp.toPx() * pulse,
                            center = Offset(rightPhoneCenterX, centerY),
                            style = Stroke(width = 1.dp.toPx())
                        )
                        drawCircle(
                            color = ringTint.copy(alpha = 0.18f),
                            radius = 46.dp.toPx() * pulse,
                            center = Offset(rightPhoneCenterX, centerY),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }

                    val lineStartX = leftPhoneCenterX + phoneW / 2f + 12.dp.toPx()
                    val lineEndX = rightPhoneCenterX - phoneW / 2f - 12.dp.toPx()
                    drawLine(
                        color = ringTint.copy(alpha = if (isConnected) 0.55f else 0.28f),
                        start = Offset(lineStartX, centerY),
                        end = Offset(lineEndX, centerY),
                        strokeWidth = 2.dp.toPx()
                    )

                    if (isConnected) {
                        drawCircle(
                            color = BridgeColors.MintReady.copy(alpha = 0.25f),
                            radius = 10.dp.toPx(),
                            center = Offset(lineEndX - 4.dp.toPx(), centerY)
                        )
                        drawCircle(
                            color = BridgeColors.MintReady,
                            radius = 4.dp.toPx(),
                            center = Offset(lineEndX - 4.dp.toPx(), centerY)
                        )
                    }

                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(leftPhoneCenterX - phoneW / 2f, centerY - phoneH / 2f),
                        size = Size(phoneW, phoneH),
                        cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                        style = Stroke(width = strokePx)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 2.dp.toPx(),
                        center = Offset(leftPhoneCenterX, centerY - phoneH / 2f + 8.dp.toPx())
                    )

                    val simW = 16.dp.toPx()
                    val simH = 22.dp.toPx()
                    val simLeft = leftPhoneCenterX - simW / 2f
                    val simTop = centerY - simH / 2f + 3.dp.toPx()
                    val simPath = Path().apply {
                        moveTo(simLeft + 5.dp.toPx(), simTop)
                        lineTo(simLeft + simW, simTop)
                        lineTo(simLeft + simW, simTop + simH)
                        lineTo(simLeft, simTop + simH)
                        lineTo(simLeft, simTop + 5.dp.toPx())
                        close()
                    }
                    drawPath(simPath, color = Color.White.copy(alpha = 0.85f), style = Stroke(width = 1.6.dp.toPx()))

                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(rightPhoneCenterX - phoneW / 2f, centerY - phoneH / 2f),
                        size = Size(phoneW, phoneH),
                        cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                        style = Stroke(width = strokePx)
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(rightPhoneCenterX - 10.dp.toPx(), centerY - phoneH / 2f + 7.dp.toPx()),
                        end = Offset(rightPhoneCenterX + 10.dp.toPx(), centerY - phoneH / 2f + 7.dp.toPx()),
                        strokeWidth = 2.6.dp.toPx()
                    )
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) BridgeColors.MintIconBg else BridgeColors.CardElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = if (isConnected) BridgeColors.MintReady else BridgeColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Text(strings.thisPhone, color = BridgeColors.TextSecondary, fontSize = 13.sp)
                Text(if (isConnected) iphoneName else "iPhone", color = BridgeColors.TextSecondary, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(18.dp))

            val headline = when {
                !bluetoothEnabled -> strings.btOffTitle
                isConnected -> strings.connectedTo(iphoneName)
                else -> strings.notPairedTitle
            }
            Text(
                text = headline,
                color = BridgeColors.TextPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isConnected) strings.homeConnectedSub else strings.homeUnpairedSub,
                color = BridgeColors.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPillBadge(icon = Icons.Rounded.Lock, text = strings.badgeEncrypted)
                StatusPillBadge(
                    icon = Icons.Rounded.Call,
                    text = if (callAudioReady) strings.badgeCallAudioReady else strings.badgeCallAudioSetup,
                    onClick = onCallAudioChipClick
                )
            }
        }
    }
}

@Composable
private fun StatusPillBadge(
    icon: ImageVector,
    text: String,
    onClick: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, BridgeColors.NeonMint.copy(alpha = 0.35f)),
        modifier = if (onClick != null) Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick) else Modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            Text(text, color = BridgeColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
