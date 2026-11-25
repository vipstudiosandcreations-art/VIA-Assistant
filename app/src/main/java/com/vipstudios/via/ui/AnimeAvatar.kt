package com.vipstudios.via.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vipstudios.via.R
import com.vipstudios.via.ui.theme.ViaCyan
import com.vipstudios.via.ui.theme.ViaGold
import com.vipstudios.via.ui.theme.ViaRoyal
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Exact normalized coordinates measured on img_via_avatar.png (896x1200)
private const val AVATAR_ASPECT = 896f / 1200f
private const val LEFT_EYE_X = 0.471f
private const val RIGHT_EYE_X = 0.530f
private const val EYE_Y = 0.1292f
private const val EYE_W = 0.033f
private const val EYE_H = 0.0125f

private const val MOUTH_X = 0.501f
private const val MOUTH_Y = 0.1667f
private const val MOUTH_W = 0.044f
private const val MOUTH_H = 0.013f

private val EyelidSkin = Color(0xFFAC7F6A)
private val MouthMaskSkin = Color(0xFFC79072)
private val UpperLipTint = Color(0xFF8C5642)
private val LashLineColor = Color(0xFF1E1410)
private val MouthInterior = Color(0xFF421920)
private val TongueColor = Color(0xFFB3585D)
private val TeethColor = Color(0xFFF5F6FA)

/**
 * Interactive full-body animated anime AI assistant avatar with JARVIS holographic stage,
 * natural breathing, idle blinking, state-driven posture/expressions, and live lip-sync.
 */
@Composable
fun InteractiveAnimeAvatar(
    state: AssistantState,
    micLevel: Float,
    mouthLevel: Float,
    modifier: Modifier = Modifier,
    onTapAvatar: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "avatar_motion")

    // Smooth breathing cycle
    val breathPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3400, easing = LinearEasing)),
        label = "breath"
    )
    // Subtle idle head/body micro-sway
    val swayPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(6200, easing = LinearEasing)),
        label = "sway"
    )
    // Hologram orbital rotation
    val ringRot by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing)),
        label = "ring_rot"
    )
    val ringRotReverse by infinite.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(11000, easing = LinearEasing)),
        label = "ring_rot_rev"
    )
    // Thinking scan sweep
    val scanSweep by infinite.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scan"
    )

    // Natural randomized single/double eye blink
    var blinkTarget by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2200L, 4600L))
            blinkTarget = 1f
            delay(95L)
            blinkTarget = 0f
            if (Random.nextFloat() < 0.22f) {
                delay(150L)
                blinkTarget = 1f
                delay(85L)
                blinkTarget = 0f
            }
        }
    }
    val blinkAmount by animateFloatAsState(
        targetValue = blinkTarget,
        animationSpec = tween(durationMillis = 75, easing = FastOutSlowInEasing),
        label = "blink_anim"
    )

    // Smooth mouth openness synced to TTS speech cadence
    val smoothedMouth by animateFloatAsState(
        targetValue = if (state == AssistantState.SPEAKING) mouthLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 48, easing = LinearEasing),
        label = "mouth_sync"
    )

    // State-driven posture transitions
    val targetScale = when (state) {
        AssistantState.LISTENING -> 1.038f
        AssistantState.SPEAKING -> 1.022f
        AssistantState.THINKING -> 1.012f
        AssistantState.IDLE -> 1.0f
    }
    val stateScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 240f),
        label = "state_scale"
    )

    val targetTiltZ = when (state) {
        AssistantState.THINKING -> -1.6f
        AssistantState.LISTENING -> 0.7f
        else -> 0f
    }
    val stateTiltZ by animateFloatAsState(
        targetValue = targetTiltZ,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 180f),
        label = "state_tilt"
    )

    val breathSin = sin(breathPhase)
    val swaySin = sin(swayPhase)
    val breathScaleY = 1f + breathSin * 0.0085f
    val breathFloatY = -breathSin * 3.5f + if (state == AssistantState.SPEAKING) -smoothedMouth * 3.5f else 0f
    val headSwayZ = stateTiltZ + swaySin * (if (state == AssistantState.SPEAKING) 1.25f else 0.75f)
    val headSwayY = cos(swayPhase * 0.8f) * 2.2f

    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .testTag("interactive_anime_avatar")
            .semantics { contentDescription = "VIA interactive anime assistant avatar, state ${state.name.lowercase()}" }
            .clickable(interactionSource = interaction, indication = null, onClick = onTapAvatar),
        contentAlignment = Alignment.Center
    ) {
        // 1. Futuristic JARVIS Holographic Stage behind the full-body avatar
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height * 0.44f)
            val baseR = size.minDimension * 0.43f

            val auraColor = when (state) {
                AssistantState.IDLE -> ViaRoyal
                AssistantState.LISTENING -> ViaCyan
                AssistantState.THINKING -> ViaGold
                AssistantState.SPEAKING -> ViaCyan
            }
            val accentColor = when (state) {
                AssistantState.THINKING -> ViaRoyal
                AssistantState.SPEAKING -> ViaGold
                else -> ViaGold
            }
            val dynamicBoost = when (state) {
                AssistantState.LISTENING -> 1f + micLevel * 0.22f
                AssistantState.SPEAKING -> 1f + smoothedMouth * 0.14f
                else -> 1f + breathSin * 0.025f
            }

            // Deep ambient radial core glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        auraColor.copy(alpha = 0.34f),
                        ViaRoyal.copy(alpha = 0.16f),
                        Color.Transparent
                    ),
                    center = c,
                    radius = baseR * 1.25f * dynamicBoost
                ),
                center = c,
                radius = baseR * 1.25f * dynamicBoost
            )

            // Outer dashed telemetry ring
            rotate(ringRot, c) {
                val r = baseR * 1.06f
                drawCircle(
                    color = ViaCyan.copy(alpha = if (state == AssistantState.LISTENING) 0.55f else 0.26f),
                    radius = r,
                    center = c,
                    style = Stroke(width = 1.5.dp.toPx())
                )
                for (i in 0 until 6) {
                    val start = i * 60f + 8f
                    drawArc(
                        color = accentColor.copy(alpha = 0.78f),
                        startAngle = start,
                        sweepAngle = 28f,
                        useCenter = false,
                        topLeft = Offset(c.x - r, c.y - r),
                        size = Size(r * 2f, r * 2f),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // Inner counter-rotating arc reactor ring
            rotate(ringRotReverse, c) {
                val r = baseR * 0.84f * dynamicBoost
                for (i in 0 until 3) {
                    drawArc(
                        color = auraColor.copy(alpha = 0.65f),
                        startAngle = i * 120f,
                        sweepAngle = 72f,
                        useCenter = false,
                        topLeft = Offset(c.x - r, c.y - r),
                        size = Size(r * 2f, r * 2f),
                        style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // Listening audio-reactive pulse rings
            if (state == AssistantState.LISTENING) {
                val pulseR = baseR * (0.95f + micLevel * 0.35f)
                drawCircle(
                    color = ViaCyan.copy(alpha = (0.28f + micLevel * 0.45f).coerceIn(0f, 0.75f)),
                    radius = pulseR,
                    center = c,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }

            // Thinking neural nodes + vertical hologram scan line
            if (state == AssistantState.THINKING) {
                val nodeR = baseR * 0.96f
                for (i in 0 until 8) {
                    val angle = Math.toRadians((ringRot * 1.8f + i * 45f).toDouble())
                    val nx = c.x + (nodeR * cos(angle)).toFloat()
                    val ny = c.y + (nodeR * sin(angle)).toFloat()
                    drawCircle(
                        color = if (i % 2 == 0) ViaGold else ViaCyan,
                        radius = 4.dp.toPx(),
                        center = Offset(nx, ny)
                    )
                }
                val scanY = size.height * scanSweep
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, ViaCyan.copy(alpha = 0.75f), ViaGold.copy(alpha = 0.75f), Color.Transparent)
                    ),
                    start = Offset(size.width * 0.18f, scanY),
                    end = Offset(size.width * 0.82f, scanY),
                    strokeWidth = 2.dp.toPx()
                )
            }

            // Speaking voice-reactive golden/cyan halo arcs
            if (state == AssistantState.SPEAKING) {
                val speakR = baseR * (0.92f + smoothedMouth * 0.20f)
                drawCircle(
                    color = ViaGold.copy(alpha = 0.25f + smoothedMouth * 0.45f),
                    radius = speakR,
                    center = c,
                    style = Stroke(width = 2.2.dp.toPx())
                )
            }

            // Futuristic holographic floor platform under the feet
            val floorCenter = Offset(size.width / 2f, size.height * 0.935f)
            val platformW = size.minDimension * 0.62f
            val platformH = size.minDimension * 0.11f
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(ViaCyan.copy(alpha = 0.35f), ViaRoyal.copy(alpha = 0.12f), Color.Transparent),
                    center = floorCenter,
                    radius = platformW * 0.6f
                ),
                topLeft = Offset(floorCenter.x - platformW / 2f, floorCenter.y - platformH / 2f),
                size = Size(platformW, platformH)
            )
            drawOval(
                color = ViaCyan.copy(alpha = 0.65f),
                topLeft = Offset(floorCenter.x - platformW * 0.46f, floorCenter.y - platformH * 0.36f),
                size = Size(platformW * 0.92f, platformH * 0.72f),
                style = Stroke(width = 1.6.dp.toPx())
            )
        }

        // 2. Full-Body Anime Avatar + Pixel-Locked Facial Rig (Blinking + Lip-Sync + Expressions)
        Box(
            modifier = Modifier
                .fillMaxHeight(0.94f)
                .aspectRatio(AVATAR_ASPECT)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 0.85f)
                    scaleX = stateScale
                    scaleY = stateScale * breathScaleY
                    translationY = breathFloatY
                    rotationZ = headSwayZ
                    rotationY = headSwayY
                    cameraDistance = 14f * density
                }
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_via_avatar),
                contentDescription = "VIA Anime Assistant Avatar",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            // Pixel-locked facial animation overlay (eyelids, expression glow, lip-sync mouth)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // --- A. Natural Blinking Eyelids ---
                if (blinkAmount > 0.02f) {
                    listOf(LEFT_EYE_X, RIGHT_EYE_X).forEach { eyeCenterX ->
                        val ex = w * eyeCenterX
                        val ey = h * EYE_Y
                        val ew = w * EYE_W
                        val eh = h * EYE_H

                        // Upper eyelid skin sweeps downward over the eye
                        val lidDrop = eh * blinkAmount * 1.15f
                        val lidPath = Path().apply {
                            moveTo(ex - ew * 0.56f, ey - eh * 0.62f)
                            quadraticBezierTo(
                                ex, ey - eh * 0.82f,
                                ex + ew * 0.56f, ey - eh * 0.58f
                            )
                            lineTo(ex + ew * 0.56f, ey - eh * 0.55f + lidDrop)
                            quadraticBezierTo(
                                ex, ey - eh * 0.42f + lidDrop * 1.12f,
                                ex - ew * 0.56f, ey - eh * 0.55f + lidDrop
                            )
                            close()
                        }
                        drawPath(lidPath, color = EyelidSkin)

                        // Curved dark lash line along the closing lid edge
                        val lashPath = Path().apply {
                            moveTo(ex - ew * 0.54f, ey - eh * 0.55f + lidDrop)
                            quadraticBezierTo(
                                ex, ey - eh * 0.40f + lidDrop * 1.12f,
                                ex + ew * 0.54f, ey - eh * 0.55f + lidDrop
                            )
                        }
                        drawPath(
                            lashPath,
                            color = LashLineColor,
                            style = Stroke(width = (w * 0.0042f).coerceAtLeast(1.2f), cap = StrokeCap.Round)
                        )
                    }
                } else if (state == AssistantState.LISTENING || state == AssistantState.THINKING) {
                    // Subtle intelligent cyan eye glint when actively listening or processing
                    val glintAlpha = if (state == AssistantState.LISTENING) 0.24f + micLevel * 0.25f else 0.22f
                    listOf(LEFT_EYE_X, RIGHT_EYE_X).forEach { eyeCenterX ->
                        drawCircle(
                            color = ViaCyan.copy(alpha = glintAlpha),
                            radius = w * 0.0045f,
                            center = Offset(w * eyeCenterX - w * 0.003f, h * EYE_Y - h * 0.002f)
                        )
                    }
                }

                // --- B. Real-Time Lip-Sync Mouth Animation ---
                if (state == AssistantState.SPEAKING && smoothedMouth > 0.03f) {
                    val mx = w * MOUTH_X
                    val my = h * MOUTH_Y
                    val mw = w * MOUTH_W
                    val mh = h * MOUTH_H

                    // Mask underlying static smile cleanly with matched facial skin tone
                    drawOval(
                        color = MouthMaskSkin,
                        topLeft = Offset(mx - mw * 0.58f, my - mh * 0.52f),
                        size = Size(mw * 1.16f, mh * 1.18f)
                    )

                    // Shape mouth opening dynamically based on speech syllable intensity
                    val openFactor = smoothedMouth.coerceIn(0.05f, 1f)
                    val dynamicW = mw * (0.64f + (1f - openFactor * 0.28f) * 0.34f)
                    val dynamicH = mh * (0.28f + openFactor * 1.15f)

                    val mouthPath = Path().apply {
                        moveTo(mx - dynamicW * 0.5f, my - dynamicH * 0.16f)
                        quadraticBezierTo(
                            mx, my - dynamicH * 0.36f,
                            mx + dynamicW * 0.5f, my - dynamicH * 0.16f
                        )
                        quadraticBezierTo(
                            mx + dynamicW * 0.42f, my + dynamicH * 0.65f,
                            mx, my + dynamicH * 0.72f
                        )
                        quadraticBezierTo(
                            mx - dynamicW * 0.42f, my + dynamicH * 0.65f,
                            mx - dynamicW * 0.5f, my - dynamicH * 0.16f
                        )
                        close()
                    }

                    // Dark mouth cavity
                    drawPath(mouthPath, color = MouthInterior)

                    // Upper teeth highlight when mouth opens
                    if (openFactor > 0.18f) {
                        val teethH = (dynamicH * 0.28f).coerceAtLeast(1f)
                        drawOval(
                            color = TeethColor,
                            topLeft = Offset(mx - dynamicW * 0.34f, my - dynamicH * 0.22f),
                            size = Size(dynamicW * 0.68f, teethH)
                        )
                    }

                    // Warm tongue hint on wide vowels
                    if (openFactor > 0.42f) {
                        drawOval(
                            color = TongueColor,
                            topLeft = Offset(mx - dynamicW * 0.24f, my + dynamicH * 0.22f),
                            size = Size(dynamicW * 0.48f, dynamicH * 0.36f)
                        )
                    }

                    // Crisp anime lip contour
                    drawPath(
                        mouthPath,
                        color = LashLineColor.copy(alpha = 0.85f),
                        style = Stroke(width = (w * 0.0028f).coerceAtLeast(1.0f), cap = StrokeCap.Round)
                    )
                    // Subtle upper lip shadow
                    drawLine(
                        color = UpperLipTint.copy(alpha = 0.5f),
                        start = Offset(mx - dynamicW * 0.35f, my - dynamicH * 0.26f),
                        end = Offset(mx + dynamicW * 0.35f, my - dynamicH * 0.26f),
                        strokeWidth = (w * 0.0022f).coerceAtLeast(0.8f),
                        cap = StrokeCap.Round
                    )
                }

                // --- C. Subtle Chest Jacket Tech-Core Pulse ---
                val coreAlpha = when (state) {
                    AssistantState.SPEAKING -> 0.18f + smoothedMouth * 0.28f
                    AssistantState.LISTENING -> 0.22f + micLevel * 0.28f
                    AssistantState.THINKING -> 0.24f
                    AssistantState.IDLE -> 0.10f + (breathSin + 1f) * 0.05f
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            (if (state == AssistantState.THINKING) ViaGold else ViaCyan).copy(alpha = coreAlpha),
                            Color.Transparent
                        ),
                        center = Offset(w * 0.50f, h * 0.31f),
                        radius = w * 0.16f
                    ),
                    center = Offset(w * 0.50f, h * 0.31f),
                    radius = w * 0.16f
                )
            }
        }
    }
}
