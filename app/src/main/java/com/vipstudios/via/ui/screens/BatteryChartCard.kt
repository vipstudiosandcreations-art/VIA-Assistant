package com.vipstudios.via.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vipstudios.via.data.BatterySample
import com.vipstudios.via.device.PhoneStatusSnapshot
import com.vipstudios.via.ui.GlowCard
import com.vipstudios.via.ui.theme.ViaCyan
import com.vipstudios.via.ui.theme.ViaGold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BatteryTrendChartCard(
    status: PhoneStatusSnapshot,
    history: List<BatterySample>,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val samples = remember(history, status.batteryPercent, status.isCharging, status.batteryTempC) {
        if (history.isNotEmpty()) history
        else listOf(
            BatterySample(
                timestampMs = System.currentTimeMillis(),
                percent = status.batteryPercent,
                isCharging = status.isCharging,
                tempC = status.batteryTempC
            )
        )
    }

    val pulseTransition = rememberInfiniteTransition(label = "battery_live_dot")
    val liveAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_alpha"
    )

    val lineColor = when {
        status.isCharging -> ViaCyan
        status.batteryPercent <= 20 -> MaterialTheme.colorScheme.error
        else -> ViaGold
    }
    val chargingDotColor = ViaCyan
    val dischargingDotColor = ViaGold
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)

    val firstPct = samples.first().percent
    val lastPct = samples.last().percent
    val delta = lastPct - firstPct
    val trendSummary = when {
        samples.size <= 1 -> "Live baseline · Updates automatically"
        delta > 0 -> "+$delta% gained · ${samples.size} samples"
        delta < 0 -> "$delta% consumed · ${samples.size} samples"
        else -> "Steady · ${samples.size} samples"
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val startLabel = remember(samples) { timeFmt.format(Date(samples.first().timestampMs)) }
    val endLabel = remember(samples) { timeFmt.format(Date(samples.last().timestampMs)) }

    GlowCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("battery_trend_chart_card")
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (status.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
                        contentDescription = "Battery icon",
                        tint = lineColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "LIVE BATTERY & CONSUMPTION TREND",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ViaCyan,
                        letterSpacing = 0.6.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00FFAA).copy(alpha = liveAlpha))
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "LIVE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00FFAA)
                    )
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("refresh_battery_chart_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh battery status",
                            tint = ViaGold,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "${status.batteryPercent}%",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = lineColor
                    )
                    val tempStr = status.batteryTempC?.let { String.format(Locale.US, " · %.1f°C", it) } ?: ""
                    Text(
                        text = "${status.batteryStatusLabel} (${status.chargingSource})$tempStr · ${status.batteryHealth}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = trendSummary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.height(150.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    Text("100%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("75%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("50%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("25%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("0%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.width(8.dp))

                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(150.dp)
                        .testTag("battery_trend_canvas")
                ) {
                    val w = size.width
                    val h = size.height
                    val topPad = 8.dp.toPx()
                    val bottomPad = 8.dp.toPx()
                    val chartH = (h - topPad - bottomPad).coerceAtLeast(1f)
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f)

                    // Horizontal percentage grid lines (0, 25, 50, 75, 100)
                    listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { frac ->
                        val y = topPad + chartH * (1f - frac)
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = dashEffect
                        )
                    }

                    val points: List<Pair<Offset, BatterySample>> = if (samples.size == 1) {
                        val pct = samples.first().percent.coerceIn(0, 100) / 100f
                        val y = topPad + chartH * (1f - pct)
                        listOf(
                            Offset(0f, y) to samples.first(),
                            Offset(w, y) to samples.first()
                        )
                    } else {
                        val stepX = w / (samples.size - 1).coerceAtLeast(1)
                        samples.mapIndexed { idx, sample ->
                            val x = idx * stepX
                            val y = topPad + chartH * (1f - (sample.percent.coerceIn(0, 100) / 100f))
                            Offset(x, y) to sample
                        }
                    }

                    val linePath = Path().apply {
                        points.forEachIndexed { idx, (pt, _) ->
                            if (idx == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                        }
                    }

                    val fillPath = Path().apply {
                        points.forEachIndexed { idx, (pt, _) ->
                            if (idx == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                        }
                        lineTo(points.last().first.x, topPad + chartH)
                        lineTo(points.first().first.x, topPad + chartH)
                        close()
                    }

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(lineColor.copy(alpha = 0.34f), Color.Transparent),
                            startY = topPad,
                            endY = topPad + chartH
                        )
                    )

                    drawPath(
                        path = linePath,
                        color = lineColor,
                        style = Stroke(
                            width = 2.6.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )

                    // Sample point markers (Cyan when charging, Gold when discharging)
                    points.forEachIndexed { idx, (pt, sample) ->
                        val dotColor = if (sample.isCharging) chargingDotColor else dischargingDotColor
                        val isLast = idx == points.lastIndex
                        if (isLast) {
                            drawCircle(
                                color = dotColor.copy(alpha = 0.28f),
                                radius = 7.dp.toPx(),
                                center = pt
                            )
                            drawCircle(
                                color = dotColor,
                                radius = 4.dp.toPx(),
                                center = pt
                            )
                        } else if (samples.size <= 24 || idx % 2 == 0) {
                            drawCircle(
                                color = dotColor,
                                radius = 2.6.dp.toPx(),
                                center = pt
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(startLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(color = chargingDotColor, label = "Charging")
                    LegendDot(color = dischargingDotColor, label = "On battery")
                }
                Text(endLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
