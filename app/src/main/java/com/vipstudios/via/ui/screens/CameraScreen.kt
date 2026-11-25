package com.vipstudios.via.ui.screens

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vipstudios.via.ui.GlowCard
import com.vipstudios.via.ui.ViaBackground
import com.vipstudios.via.ui.ViaTopBar
import com.vipstudios.via.ui.theme.ViaBlack
import com.vipstudios.via.ui.theme.ViaCyan
import com.vipstudios.via.ui.theme.ViaGold
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CameraScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permDenied by remember { mutableStateOf(false) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var cameraActive by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf("") }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPerm = granted
        permDenied = !granted
    }

    fun capturePhoto() {
        val capture = imageCapture ?: return
        val name = "VIA_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val executor = ContextCompat.getMainExecutor(ctx)

        val outputOptions = if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VIA")
            }
            ImageCapture.OutputFileOptions.Builder(
                ctx.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ).build()
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.filesDir, "VIA").apply {
                mkdirs()
            }
            ImageCapture.OutputFileOptions.Builder(File(dir, name)).build()
        }

        statusBanner = "Capturing photo..."
        capture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    statusBanner = "Photo saved: $name"
                }

                override fun onError(exception: ImageCaptureException) {
                    statusBanner = "Capture failed: ${exception.message ?: "Camera error"}"
                }
            }
        )
    }

    ViaBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            ViaTopBar("VIA Camera", back)

            if (!hasCameraPerm) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    GlowCard(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Filled.PhotoCamera,
                                contentDescription = "Camera Permission",
                                tint = ViaGold,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Camera Permission Required",
                                style = MaterialTheme.typography.titleMedium,
                                color = ViaGold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "VIA only activates your camera while this screen is open so you can capture photos. " +
                                    "VIA never records or accesses the camera in the background.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = { cameraPermLauncher.launch(Manifest.permission.CAMERA) },
                                modifier = Modifier.testTag("grant_camera_permission_button")
                            ) {
                                Text("Allow Camera")
                            }
                            if (permDenied) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Camera permission was denied. You can enable it in Android App Settings.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                                TextButton(
                                    onClick = {
                                        ctx.startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts("package", ctx.packageName, null)
                                            }
                                        )
                                    }
                                ) {
                                    Text("Open App Settings")
                                }
                            }
                        }
                    }
                }
            } else {
                val previewView = remember { PreviewView(ctx) }

                DisposableEffect(lifecycleOwner, useFrontCamera) {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    val listener = Runnable {
                        try {
                            val provider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()
                            val selector = if (useFrontCamera) {
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            } else {
                                CameraSelector.DEFAULT_BACK_CAMERA
                            }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                            imageCapture = capture
                            cameraActive = true
                        } catch (e: Exception) {
                            cameraActive = false
                            statusBanner = "Camera unavailable: ${e.message ?: "hardware restricted"}"
                        }
                    }
                    cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(ctx))

                    onDispose {
                        cameraActive = false
                        try {
                            cameraProviderFuture.get().unbindAll()
                        } catch (_: Exception) {
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.5.dp, ViaCyan.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                ) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Clear Camera-Active Status Badge
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = ViaBlack.copy(alpha = 0.8f),
                        border = BorderStroke(
                            1.dp,
                            if (cameraActive) Color(0xFF00FFAA) else ViaGold
                        ),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .testTag("camera_active_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (cameraActive) Color(0xFF00FFAA) else ViaGold)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (cameraActive) "CAMERA ACTIVE" else "INITIALIZING CAMERA",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (cameraActive) Color(0xFF00FFAA) else ViaGold
                            )
                        }
                    }

                    if (statusBanner.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ViaBlack.copy(alpha = 0.84f),
                            border = BorderStroke(1.dp, ViaGold.copy(alpha = 0.65f)),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp, start = 16.dp, end = 16.dp)
                        ) {
                            Text(
                                text = statusBanner,
                                color = ViaGold,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                // Bottom camera controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { useFrontCamera = !useFrontCamera },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .testTag("switch_camera_button")
                    ) {
                        Icon(
                            Icons.Filled.Cameraswitch,
                            contentDescription = "Switch camera lens",
                            tint = ViaCyan
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(ViaGold)
                            .border(3.dp, ViaCyan, CircleShape)
                            .clickable(enabled = cameraActive, onClick = { capturePhoto() })
                            .testTag("capture_photo_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PhotoCamera,
                            contentDescription = "Capture photo",
                            tint = ViaBlack,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(Modifier.size(52.dp))
                }
            }
        }
    }
}
