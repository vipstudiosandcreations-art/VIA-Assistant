package com.vipstudios.via.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.vipstudios.via.BuildConfig
import com.vipstudios.via.ui.theme.ViaCyan
import com.vipstudios.via.ui.theme.ViaGold
import com.vipstudios.via.ui.theme.ViaRoyal
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun ViaBackground(content: @Composable BoxScope.() -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    val sf = MaterialTheme.colorScheme.surface
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(bg, sf, bg)))
            .drawBehind {
                // Subtle futuristic HUD radial core glow & precision horizon grid lines
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(ViaRoyal.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.36f),
                        radius = size.minDimension * 0.75f
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.36f),
                    radius = size.minDimension * 0.75f
                )
                val step = 48.dp.toPx()
                var y = step
                while (y < size.height) {
                    drawLine(
                        color = ViaCyan.copy(alpha = 0.035f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                    y += step
                }
            },
        content = content
    )
}

@Composable
fun ViaTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("top_bar_back_button")
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun GlowCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, ViaGold.copy(alpha = 0.35f)),
        content = content
    )
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, ViaCyan.copy(alpha = 0.6f), RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Animated "AI core": rotating gold and cyan rings with a pulsing centre. */
@Composable
fun AiCore(modifier: Modifier = Modifier, level: Float = 0f, active: Boolean = false) {
    val t = rememberInfiniteTransition(label = "core")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(12000, easing = LinearEasing)), label = "rot")
    val rot2 by t.animateFloat(360f, 0f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "rot2")
    val pulse by t.animateFloat(
        0.92f, 1.08f,
        infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    Canvas(modifier = modifier.aspectRatio(1f)) {
        val c = center
        val r = size.minDimension / 2f
        val boost = if (active) 1f + level * 0.3f else 1f
        drawCircle(
            brush = Brush.radialGradient(listOf(ViaCyan.copy(alpha = 0.35f), Color.Transparent), center = c, radius = r),
            radius = r
        )
        drawCircle(color = ViaCyan.copy(alpha = 0.5f), radius = r * 0.95f, style = Stroke(width = 2.dp.toPx()))
        rotate(rot, c) {
            val d = r * 1.7f
            drawArc(ViaGold, 0f, 110f, false, Offset(c.x - d / 2, c.y - d / 2), Size(d, d), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            drawArc(ViaGold, 180f, 110f, false, Offset(c.x - d / 2, c.y - d / 2), Size(d, d), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }
        rotate(rot2, c) {
            val d = r * 1.4f
            drawArc(ViaCyan, 40f, 70f, false, Offset(c.x - d / 2, c.y - d / 2), Size(d, d), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            drawArc(ViaCyan, 220f, 70f, false, Offset(c.x - d / 2, c.y - d / 2), Size(d, d), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        val cr = r * 0.5f * pulse * boost
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White, ViaCyan, ViaRoyal, Color.Transparent), center = c, radius = cr),
            radius = cr
        )
    }
}

@Composable
fun Waveform(level: Float, active: Boolean, modifier: Modifier = Modifier, bars: Int = 24) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "phase"
    )
    Canvas(modifier.fillMaxWidth().height(42.dp)) {
        val w = size.width / (bars * 2f)
        for (i in 0 until bars) {
            val base = if (active) 0.15f + level * 0.85f else 0.08f
            val h = size.height * (base * (0.4f + 0.6f * abs(sin(phase + i * 0.5f)))).coerceIn(0.05f, 1f)
            drawRoundRect(
                color = if (i % 2 == 0) ViaGold else ViaCyan,
                topLeft = Offset((i * 2 + 0.5f) * w, (size.height - h) / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f)
            )
        }
    }
}

/** Responsive Google AdMob adaptive banner that collapses safely if loading fails. */
@Composable
fun AdaptiveBanner(modifier: Modifier = Modifier) {
    var adFailed by remember { mutableStateOf(false) }
    if (adFailed) return

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag("admob_banner_container"),
        contentAlignment = Alignment.Center
    ) {
        val adWidthDp = maxWidth.value.toInt().coerceAtLeast(320)
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                AdView(ctx).apply {
                    try {
                        setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, adWidthDp))
                        adUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID
                        adListener = object : AdListener() {
                            override fun onAdFailedToLoad(error: LoadAdError) {
                                if (BuildConfig.DEBUG) {
                                    Log.w("AdaptiveBanner", "Banner ad failed to load (code=${error.code})")
                                }
                                adFailed = true
                            }
                        }
                        loadAd(AdRequest.Builder().build())
                    } catch (t: Throwable) {
                        if (BuildConfig.DEBUG) {
                            Log.w("AdaptiveBanner", "Banner ad initialization error: ${t.javaClass.simpleName}")
                        }
                        adFailed = true
                    }
                }
            },
            onRelease = { view ->
                try {
                    view.destroy()
                } catch (_: Throwable) {
                }
            }
        )
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun Context.safeStartActivity(intent: Intent): Boolean {
    if (findActivity() == null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }
}

/**
 * Reusable launcher for Google Identity Services AuthorizationClient consent flow (read-only Sheets).
 */
@Composable
fun rememberGoogleSheetsConnector(vm: ViaViewModel): () -> Unit {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (activity != null) {
            vm.handleSheetsConsentResult(activity, result.resultCode, result.data)
        }
    }
    return {
        if (activity != null) {
            vm.connectSheets(activity) { intentSender ->
                consentLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
        }
    }
}
