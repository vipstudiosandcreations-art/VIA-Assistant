package com.vipstudios.via.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vipstudios.via.ui.GlowCard
import com.vipstudios.via.ui.ViaBackground
import com.vipstudios.via.ui.ViaTopBar
import com.vipstudios.via.ui.ViaViewModel
import com.vipstudios.via.ui.safeStartActivity
import com.vipstudios.via.ui.theme.ViaBlack
import com.vipstudios.via.ui.theme.ViaCyan
import com.vipstudios.via.ui.theme.ViaGold
import java.util.Locale

@Composable
fun DeviceControlsScreen(vm: ViaViewModel, go: (String) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val phoneStatus by vm.phoneStatus.collectAsState()
    val callState by vm.callState.collectAsState()
    var permissionNote by remember { mutableStateOf("") }
    var alarmTimeText by remember { mutableStateOf("07:00") }
    var alarmLabel by remember { mutableStateOf("VIA Reminder") }

    LaunchedEffect(Unit) {
        vm.refreshDeviceStatus()
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permissionNote = if (ok) {
            "Microphone permission granted. Tap Speak Now to use voice."
        } else {
            "Microphone permission denied. Voice input stays off until you allow it."
        }
    }

    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        vm.refreshDeviceStatus()
        permissionNote = if (ok) "Bluetooth permission granted." else "Bluetooth permission denied; basic on/off status remains available."
    }

    val callPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val phoneOk = res[Manifest.permission.READ_PHONE_STATE] == true ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (phoneOk) {
            vm.setCallMonitoring(true)
            permissionNote = "Call monitoring enabled."
        } else {
            vm.setCallMonitoring(false)
            permissionNote = "Phone state permission denied. Call detection stays disabled."
        }
    }

    ViaBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            ViaTopBar("Device Controls", back)

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (permissionNote.isNotBlank()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ViaCyan.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, ViaCyan.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = permissionNote,
                                color = ViaCyan,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }

                // Active Incoming Call Card (shown when ringing)
                if (callState.isRinging) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF102A3A),
                            border = BorderStroke(1.5.dp, ViaGold),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("controls_incoming_call_card")
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    "INCOMING CALL",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ViaGold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = callState.callerDisplay.ifBlank { "Incoming call" },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = Color.White
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = { permissionNote = vm.answerIncomingCall() },
                                        modifier = Modifier.weight(1f).testTag("controls_accept_call_button")
                                    ) {
                                        Text("Accept")
                                    }
                                    OutlinedButton(
                                        onClick = { permissionNote = vm.declineIncomingCall() },
                                        modifier = Modifier.weight(1f).testTag("controls_decline_call_button")
                                    ) {
                                        Text("Decline", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }

                // 1. Read-Only Phone Status Card
                item {
                    GlowCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = ViaGold)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "READ-ONLY PHONE STATUS",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ViaGold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { vm.refreshDeviceStatus() },
                                    modifier = Modifier.testTag("refresh_device_status_button")
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh device status", tint = ViaCyan)
                                }
                            }
                            HorizontalDivider(color = ViaCyan.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 6.dp))
                            val tempLabel = phoneStatus.batteryTempC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "Unavailable"
                            StatusRow("Battery", "${phoneStatus.batteryPercent}% · ${phoneStatus.batteryStatusLabel} (${phoneStatus.chargingSource})")
                            StatusRow("Battery Temp / Health", "$tempLabel · Health: ${phoneStatus.batteryHealth}")
                            StatusRow("Network Status", phoneStatus.networkLabel)
                            StatusRow("Wi-Fi Status", phoneStatus.wifiStatus)
                            StatusRow("Bluetooth Status", phoneStatus.bluetoothStatus)
                            StatusRow("Date & Time", phoneStatus.deviceDateTime)
                            StatusRow("Device Model", "${phoneStatus.deviceModel} · ${phoneStatus.androidVersion}")
                        }
                    }
                }

                // 2. Camera Control
                item {
                    ControlCard(
                        icon = Icons.Filled.PhotoCamera,
                        title = "Camera",
                        subtitle = "Opens VIA camera viewfinder with live CAMERA ACTIVE badge. Permission requested only on use.",
                        tag = "control_camera_card"
                    ) {
                        Button(
                            onClick = { go("camera") },
                            modifier = Modifier.testTag("open_camera_control_button")
                        ) {
                            Text("Open Camera")
                        }
                    }
                }

                // 3. Microphone Control
                item {
                    val micGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ControlCard(
                        icon = Icons.Filled.Mic,
                        title = "Microphone",
                        subtitle = if (micGranted) {
                            "Microphone permission granted. Listen only when you tap Speak or enable background \"Hey VIA\"."
                        } else {
                            "Microphone permission needed for speech-to-text and \"Hey VIA\" voice input."
                        },
                        tag = "control_mic_card"
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (micGranted) {
                                Button(
                                    onClick = { go("voice") },
                                    modifier = Modifier.testTag("open_voice_control_button")
                                ) {
                                    Text("Open Voice Mode")
                                }
                            } else {
                                Button(
                                    onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                    modifier = Modifier.testTag("grant_mic_control_button")
                                ) {
                                    Text("Allow Microphone")
                                }
                            }
                        }
                    }
                }

                // 4. Battery & Alerts Control
                item {
                    ControlCard(
                        icon = Icons.Filled.BatteryChargingFull,
                        title = "Battery & Voice Alerts",
                        subtitle = "Current: ${phoneStatus.batteryPercent}% (${phoneStatus.batteryStatusLabel}). " +
                            (vm.latestBatteryAlertText.ifBlank { "Alerts warn once on low/very-low battery, charging start, milestones, and full charge." }),
                        tag = "control_battery_card"
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = vm.batteryVoiceAlertsEnabled,
                                onCheckedChange = { vm.setBatteryVoiceAlerts(it) },
                                modifier = Modifier.testTag("controls_battery_alerts_switch")
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Voice battery alerts", fontSize = 13.sp)
                        }
                    }
                }

                // 5. Wi-Fi & Network Controls
                item {
                    ControlCard(
                        icon = Icons.Filled.Wifi,
                        title = "Wi-Fi & Network",
                        subtitle = "Wi-Fi: ${phoneStatus.wifiStatus} · Network: ${phoneStatus.networkLabel}",
                        tag = "control_wifi_card"
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
                                    val opened = ctx.safeStartActivity(Intent(action)) ||
                                        ctx.safeStartActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                                    if (!opened) {
                                        permissionNote = "Wi-Fi settings screen is unavailable on this device."
                                    }
                                },
                                modifier = Modifier.testTag("open_wifi_settings_button")
                            ) {
                                Text("Wi-Fi Settings")
                            }
                            OutlinedButton(
                                onClick = { vm.refreshDeviceStatus() },
                                modifier = Modifier.testTag("check_network_button")
                            ) {
                                Text("Refresh")
                            }
                        }
                    }
                }

                // 6. Bluetooth Control
                item {
                    val hasBtPerm = Build.VERSION.SDK_INT < 31 ||
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    ControlCard(
                        icon = Icons.Filled.Bluetooth,
                        title = "Bluetooth",
                        subtitle = "Status: ${phoneStatus.bluetoothStatus}",
                        tag = "control_bluetooth_card"
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    if (!ctx.safeStartActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) {
                                        permissionNote = "Bluetooth settings screen is unavailable on this device."
                                    }
                                },
                                modifier = Modifier.testTag("open_bluetooth_settings_button")
                            ) {
                                Text("Bluetooth Settings")
                            }
                            if (!hasBtPerm && Build.VERSION.SDK_INT >= 31) {
                                TextButton(
                                    onClick = { btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                                    modifier = Modifier.testTag("grant_bluetooth_button")
                                ) {
                                    Text("Allow Details")
                                }
                            }
                        }
                    }
                }

                // 7. Flashlight Control
                item {
                    ControlCard(
                        icon = Icons.Filled.FlashlightOn,
                        title = "Flashlight",
                        subtitle = if (phoneStatus.torchOn) "Flashlight is currently ON." else "Flashlight is currently OFF.",
                        tag = "control_flashlight_card"
                    ) {
                        Button(
                            onClick = { permissionNote = vm.toggleFlashlight() },
                            modifier = Modifier.testTag("toggle_flashlight_button")
                        ) {
                            Text(if (phoneStatus.torchOn) "Turn Flashlight Off" else "Turn Flashlight On")
                        }
                    }
                }

                // 8. Phone Calls Control
                item {
                    ControlCard(
                        icon = Icons.Filled.Call,
                        title = "Phone Calls (Safe Mode)",
                        subtitle = "${callState.statusNote}\n" +
                            "Phone State: ${if (callState.hasPhoneStatePermission) "Granted" else "Off"} · " +
                            "Answer/Decline: ${if (callState.hasAnswerPermission) "Granted" else "Off"} · " +
                            "Default Dialer Role: ${if (callState.isDefaultDialer) "Yes" else "No"}",
                        tag = "control_calls_card"
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!callState.hasPhoneStatePermission || !callState.hasAnswerPermission) {
                                    Button(
                                        onClick = {
                                            callPermLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.READ_PHONE_STATE,
                                                    Manifest.permission.ANSWER_PHONE_CALLS
                                                )
                                            )
                                        },
                                        modifier = Modifier.testTag("enable_call_permissions_button")
                                    ) {
                                        Text("Enable Call Detection")
                                    }
                                } else {
                                    Switch(
                                        checked = callState.monitoringActive,
                                        onCheckedChange = { vm.setCallMonitoring(it) },
                                        modifier = Modifier.testTag("call_monitoring_switch")
                                    )
                                    Text(if (callState.monitoringActive) "Monitoring ON" else "Monitoring OFF", fontSize = 13.sp)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        if (!ctx.safeStartActivity(Intent(Intent.ACTION_DIAL))) {
                                            permissionNote = "Phone dialer app is unavailable on this device."
                                        }
                                    },
                                    modifier = Modifier.testTag("open_dialer_button")
                                ) {
                                    Text("Open Phone")
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (!ctx.safeStartActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))) {
                                            permissionNote = "Default Apps settings screen is unavailable on this device."
                                        }
                                    },
                                    modifier = Modifier.testTag("default_apps_button")
                                ) {
                                    Text("Default Apps")
                                }
                            }
                        }
                    }
                }

                // 9. Alarms & Reminders Control
                item {
                    ControlCard(
                        icon = Icons.Filled.Alarm,
                        title = "Alarms & Reminders",
                        subtitle = "Sets an alarm via your phone's Clock app (format HH:MM).",
                        tag = "control_alarm_card"
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = alarmTimeText,
                                    onValueChange = { alarmTimeText = it },
                                    label = { Text("Time (HH:MM)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(0.42f)
                                )
                                OutlinedTextField(
                                    value = alarmLabel,
                                    onValueChange = { alarmLabel = it },
                                    label = { Text("Label") },
                                    singleLine = true,
                                    modifier = Modifier.weight(0.58f)
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    val parts = alarmTimeText.trim().split(":")
                                    val hr = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
                                    val min = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
                                    val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                                        putExtra(AlarmClock.EXTRA_HOUR, hr)
                                        putExtra(AlarmClock.EXTRA_MINUTES, min)
                                        putExtra(AlarmClock.EXTRA_MESSAGE, alarmLabel.ifBlank { "VIA Alarm" })
                                    }
                                    permissionNote = if (ctx.safeStartActivity(i)) {
                                        "Opened Clock app for $hr:${String.format(Locale.US, "%02d", min)}."
                                    } else {
                                        "No Clock app found to set alarms."
                                    }
                                },
                                modifier = Modifier.testTag("set_alarm_control_button")
                            ) {
                                Text("Set Alarm")
                            }
                        }
                    }
                }

                // 10. Date & Time Control
                item {
                    ControlCard(
                        icon = Icons.Filled.Schedule,
                        title = "Date & Time",
                        subtitle = phoneStatus.deviceDateTime,
                        tag = "control_datetime_card"
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { go("clock") },
                                modifier = Modifier.testTag("open_world_clock_control_button")
                            ) {
                                Text("World Clock")
                            }
                            OutlinedButton(
                                onClick = {
                                    if (!ctx.safeStartActivity(Intent(Settings.ACTION_DATE_SETTINGS))) {
                                        permissionNote = "System date/time settings screen is unavailable on this device."
                                    }
                                },
                                modifier = Modifier.testTag("open_date_settings_button")
                            ) {
                                Text("System Date/Time")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable
private fun ControlCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tag: String,
    actions: @Composable () -> Unit
) {
    GlowCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = title, tint = ViaCyan)
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = ViaGold)
            }
            Spacer(Modifier.height(4.dp))
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            actions()
        }
    }
}
