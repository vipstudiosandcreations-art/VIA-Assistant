package com.vipstudios.via.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.testTag
import com.vipstudios.via.R
import com.vipstudios.via.data.MemoryItem
import com.vipstudios.via.service.WakeWordService
import com.vipstudios.via.ui.*
import com.vipstudios.via.ui.theme.*

@Composable
fun MemoryScreen(vm: ViaViewModel, back: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var newText by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf("memory") }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var editText by remember { mutableStateOf("") }
    val shown = vm.memory.filter { query.isBlank() || it.text.contains(query, ignoreCase = true) }.reversed()

    ViaBackground {
        Column(Modifier.fillMaxSize()) {
            ViaTopBar("Memory", back)
            Column(Modifier.padding(horizontal = 12.dp)) {
                Text(
                    "Only things you approve are stored, here on your phone. Edit or delete any item at any time.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newText, onValueChange = { newText = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Add a memory, note or task") },
                    trailingIcon = {
                        IconButton(onClick = { vm.addMemory(newText, newKind); newText = "" }, enabled = newText.isNotBlank()) {
                            Icon(Icons.Filled.Add, "Add", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf("memory", "note", "task").forEach { k ->
                        RadioButton(selected = newKind == k, onClick = { newKind = k })
                        Text(k.replaceFirstChar { it.uppercase() }, fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search memory") }, singleLine = true
                )
            }
            if (shown.isEmpty()) Text("Nothing saved yet.", Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.id }) { m ->
                    GlowCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                Text(m.kind.replaceFirstChar { it.uppercase() }, fontSize = 11.sp, color = ViaCyan)
                                Text(m.text)
                            }
                            IconButton(onClick = { editing = m; editText = m.text }) { Icon(Icons.Filled.Edit, "Edit") }
                            IconButton(onClick = { vm.deleteMemory(m.id) }) { Icon(Icons.Filled.Delete, "Delete") }
                        }
                    }
                }
            }
        }
    }
    editing?.let { item ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit") },
            text = { OutlinedTextField(value = editText, onValueChange = { editText = it }) },
            confirmButton = { TextButton(onClick = { vm.updateMemory(item.id, editText); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun SettingsScreen(vm: ViaViewModel, go: (String) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(vm.backendUrl) }
    var token by remember { mutableStateOf(vm.apiToken) }
    var persona by remember { mutableStateOf(vm.personality) }
    var confirmWipe by remember { mutableStateOf(false) }
    var serviceNote by remember { mutableStateOf("") }
    val connectSheets = rememberGoogleSheetsConnector(vm)
    val onDeviceSupported = remember(ctx) { WakeWordService.isOnDeviceWakeSupported(ctx) }
    val batteryRestricted = remember(ctx, vm.wakeServiceOn) {
        !WakeWordService.isIgnoringBatteryOptimizations(ctx) || WakeWordService.isBackgroundRestricted(ctx)
    }

    val callState by vm.callState.collectAsState()

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val micGranted = res[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            vm.startBackgroundAssistant()
            serviceNote = if (Build.VERSION.SDK_INT >= 33 && res[Manifest.permission.POST_NOTIFICATIONS] == false) {
                "Background Assistant started, but notifications are disabled. Allow Notifications so Android shows the active assistant indicator."
            } else {
                "Background Assistant started."
            }
        } else {
            vm.stopBackgroundAssistant()
            serviceNote = "Microphone permission is required before enabling Background Assistant."
        }
    }

    val micOnlyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        serviceNote = if (granted) "Microphone permission granted." else "Microphone permission denied."
    }

    val notifOnlyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        serviceNote = if (granted) "Notification permission granted." else "Notification permission denied."
    }

    val callPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val phoneOk = res[Manifest.permission.READ_PHONE_STATE] == true ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (phoneOk) {
            vm.setCallMonitoring(true)
            serviceNote = "Call Assistant enabled."
        } else {
            vm.setCallMonitoring(false)
            serviceNote = "Phone State permission is needed to enable Call Assistant."
        }
    }

    fun toggleService(on: Boolean) {
        if (!on) {
            vm.stopBackgroundAssistant()
            serviceNote = "Background Assistant stopped."
            return
        }
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    fun toggleCallAssistant(on: Boolean) {
        if (!on) {
            vm.setCallMonitoring(false)
            serviceNote = "Call Assistant turned off."
            return
        }
        val hasPhone = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasAnswer = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        if (hasPhone && hasAnswer) {
            vm.setCallMonitoring(true)
        } else {
            callPermLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.ANSWER_PHONE_CALLS
                )
            )
        }
    }

    ViaBackground {
        Column(Modifier.fillMaxSize()) {
            ViaTopBar("Settings", back)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {

                Section("System diagnostics")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { vm.testGemini() },
                        enabled = !vm.geminiTesting,
                        modifier = Modifier.testTag("test_gemini_button")
                    ) {
                        Text(if (vm.geminiTesting) "Testing Gemini..." else "Test Gemini")
                    }
                    OutlinedButton(
                        onClick = { vm.testVoice() },
                        enabled = !vm.voiceTesting,
                        modifier = Modifier.testTag("test_voice_button")
                    ) {
                        Text(if (vm.voiceTesting) "Testing Voice..." else "Test Voice")
                    }
                }
                if (vm.geminiTestStatus.isNotBlank()) {
                    Text(
                        text = vm.geminiTestStatus,
                        fontSize = 12.sp,
                        color = if (vm.geminiTestStatus.startsWith("Gemini OK")) ViaCyan else MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("test_gemini_status")
                    )
                }
                if (vm.voiceTestStatus.isNotBlank()) {
                    Text(
                        text = vm.voiceTestStatus,
                        fontSize = 12.sp,
                        color = ViaGold,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .testTag("test_voice_status")
                    )
                }
                Note("Verify Gemini AI connectivity and Voice (Microphone / STT / TTS) separately.")

                Section("Language (voice and AI replies)")
                listOf("en" to "English", "kn" to "ಕನ್ನಡ (Kannada)", "hi" to "हिन्दी (Hindi)").forEach { (code, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = vm.language == code, onClick = { vm.setLanguage(code) })
                        Text(name)
                    }
                }
                Note("The app screens stay in English for now. Kannada and Hindi voices must be installed in your phone's Text-to-speech settings.")

                Section("Theme")
                listOf("dark" to "Dark (VIA default)", "light" to "Light", "system" to "Follow phone").forEach { (code, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = vm.themeMode == code, onClick = { vm.setTheme(code) })
                        Text(name)
                    }
                }

                Section("Assistant Controls & Modes")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = vm.wakeServiceOn,
                        onCheckedChange = { toggleService(it) },
                        modifier = Modifier.testTag("background_assistant_switch")
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Background Assistant (ON/OFF)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = vm.localWakeWordEnabled,
                        onCheckedChange = { vm.setLocalWakeWord(it) },
                        enabled = onDeviceSupported,
                        modifier = Modifier.testTag("local_wake_word_switch")
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (onDeviceSupported) "Wake Word \"Hey VIA\" (on-device)"
                        else "Wake Word \"Hey VIA\" (unavailable on this device)"
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = vm.voiceResponseEnabled,
                        onCheckedChange = { vm.toggleVoiceResponse() },
                        modifier = Modifier.testTag("voice_response_switch")
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Voice Response (TTS + lip-sync)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = vm.batteryVoiceAlertsEnabled,
                        onCheckedChange = { vm.setBatteryVoiceAlerts(it) },
                        modifier = Modifier.testTag("battery_alerts_switch")
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Battery Alerts (low, charging & full)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = callState.monitoringActive,
                        onCheckedChange = { toggleCallAssistant(it) },
                        modifier = Modifier.testTag("call_assistant_switch")
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Call Assistant (incoming call announcements)")
                }
                if (vm.wakeServiceOn) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { toggleService(false) },
                        modifier = Modifier.testTag("stop_assistant_button")
                    ) {
                        Text("Stop Assistant Immediately")
                    }
                }
                Text(
                    text = "Background Status: ${vm.wakeStatusText}" + if (vm.wakeMicActive) " (MIC ACTIVE)" else "",
                    fontSize = 13.sp,
                    color = if (vm.wakeMicActive) androidx.compose.ui.graphics.Color(0xFF00FFAA) else ViaCyan,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                if (serviceNote.isNotBlank()) Text(serviceNote, fontSize = 12.sp, color = ViaGold)
                if (batteryRestricted) {
                    Note(
                        "Battery optimization or manufacturer background limits may pause Background Assistant when minimized. " +
                            "To allow uninterrupted operation, open App Info → Battery and choose Unrestricted."
                    )
                    TextButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", ctx.packageName, null)
                            }
                            ctx.startActivity(intent)
                        },
                        modifier = Modifier.testTag("battery_settings_button")
                    ) {
                        Text("Allow Background Activity in Settings")
                    }
                }
                Note(
                    "Uses an official Android Foreground Service with a persistent notification. When enabled, \"Hey VIA\" listens locally on-device " +
                        "without continuously uploading audio, answers via Gemini AI and Text-to-Speech even while minimized, and never bypasses force-stop or Android security rules."
                )

                Section("AI personality")
                OutlinedTextField(value = persona, onValueChange = { persona = it }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { vm.setPersonality(persona) }) { Text("Save personality") }
                Note("Applied to VIA's Gemini AI responses in English, Kannada, and Hindi.")

                Section("Cloud AI backend (optional override)")
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Custom backend address (https://...)") }, singleLine = true
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Access token (optional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Row {
                    TextButton(onClick = { vm.setBackend(url, token) }) { Text("Save") }
                    TextButton(onClick = { vm.setBackend(url, token); vm.testConnection() }) { Text("Test connection") }
                }
                if (vm.connectionNote.isNotBlank()) Text(vm.connectionNote, fontSize = 13.sp, color = ViaCyan)
                Note("Leave blank to use VIA's built-in Gemini AI via Firebase AI Logic.")

                Section("Google Sheets knowledge source (read-only)")
                Text(
                    text = if (vm.sheetsTitle.isNotBlank()) {
                        "Spreadsheet: ${vm.sheetsTitle} · Read-Only"
                    } else {
                        "Linked Sheet ID: 1cXGsM...HWuKk · Read-Only"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ViaGold
                )
                Text(
                    text = "Status: ${vm.sheetsStatusText}" +
                        if (vm.sheetsLastSyncText.isNotBlank()) " · Synced ${vm.sheetsLastSyncText}" else "",
                    fontSize = 13.sp,
                    color = if (vm.sheetsConnected && !vm.sheetsNeedsReauth) ViaCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!vm.sheetsConnected || vm.sheetsNeedsReauth) {
                        Button(
                            onClick = connectSheets,
                            enabled = !vm.sheetsLoading,
                            modifier = Modifier.testTag("connect_sheets_button")
                        ) {
                            Text(if (vm.sheetsLoading) "Connecting..." else "Connect Google Sheets")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { vm.refreshSheets() },
                            enabled = !vm.sheetsLoading,
                            modifier = Modifier.testTag("refresh_sheets_button")
                        ) {
                            Text(if (vm.sheetsLoading) "Refreshing..." else "Refresh Sheet Data")
                        }
                    }
                    if (vm.sheetsConnected) {
                        TextButton(
                            onClick = { vm.disconnectSheets() },
                            enabled = !vm.sheetsLoading,
                            modifier = Modifier.testTag("disconnect_sheets_button")
                        ) {
                            Text("Disconnect")
                        }
                    }
                }
                Note(
                    "Uses Google OAuth with read-only permission (spreadsheets.readonly). " +
                        "VIA always checks the latest sheet data when answering questions and never edits, " +
                        "deletes, or overwrites your spreadsheet. OAuth tokens stay strictly in memory and are never saved to disk."
                )

                Section("Device controls & call safety")
                OutlinedButton(
                    onClick = { go("controls") },
                    modifier = Modifier.testTag("settings_open_device_controls_button")
                ) {
                    Text("Open VIA Device Controls")
                }
                Note("Manage camera, flashlight, Wi-Fi, Bluetooth, phone status, alarms, and safe call actions. VIA never secretly records calls or audio.")

                Section("App permissions")
                val micGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                PermissionStatusRow(
                    title = "Microphone Permission",
                    granted = micGranted,
                    reason = "Needed only when you speak or enable Background Assistant / \"Hey VIA\"."
                )
                if (!micGranted) {
                    TextButton(
                        onClick = { micOnlyLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        modifier = Modifier.testTag("allow_mic_permission_button")
                    ) {
                        Text("Allow Microphone Permission")
                    }
                }
                val notifGranted = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                PermissionStatusRow(
                    title = "Notification Permission",
                    granted = notifGranted,
                    reason = if (Build.VERSION.SDK_INT >= 33) {
                        "Shows the persistent Background Assistant indicator and battery alerts."
                    } else {
                        "Enabled automatically by Android below Android 13."
                    }
                )
                if (Build.VERSION.SDK_INT >= 33 && !notifGranted) {
                    TextButton(
                        onClick = { notifOnlyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        modifier = Modifier.testTag("allow_notification_permission_button")
                    ) {
                        Text("Allow Notification Permission")
                    }
                }
                PermissionStatusRow(
                    title = "Camera Permission",
                    granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
                    reason = "Needed only when you open VIA Camera to preview or capture a photo."
                )
                PermissionStatusRow(
                    title = "Phone State (Call Detection)",
                    granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED,
                    reason = "Detects incoming calls when Call Assistant is enabled."
                )
                PermissionStatusRow(
                    title = "Answer Phone Calls",
                    granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED,
                    reason = "Supports \"Hey VIA, answer the call\" where Android officially permits it."
                )
                if (Build.VERSION.SDK_INT >= 31) {
                    PermissionStatusRow(
                        title = "Bluetooth Connect",
                        granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
                        reason = "Reads paired Bluetooth status on Android 12+."
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", ctx.packageName, null)
                            }
                        )
                    },
                    modifier = Modifier.testTag("open_app_permissions_settings_button")
                ) {
                    Text("Manage Permissions in Android Settings")
                }

                Section("Advertisements")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = vm.adsEnabled, onCheckedChange = { vm.setAds(it) })
                    Spacer(Modifier.width(8.dp))
                    Text("Show banner ad on home screen")
                }
                Note(
                    "These are Google TEST ads during development. No ads show over voice controls."
                )

                Section("Privacy, data & developer")
                Note("Chat, memory and command history stay on this phone. Microphone, camera, and call controls activate only when you allow them.")
                OutlinedButton(onClick = { confirmWipe = true }) { Text("Delete all my VIA data") }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { go("about") },
                    modifier = Modifier.testTag("open_about_via_button")
                ) {
                    Text("About VIA & Attributions")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "VIA – VIP Intelligent Assistant\nDeveloped by VIP Studios And Creations · © 2026 VIP Studios And Creations",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Spacer(Modifier.navigationBarsPadding().height(16.dp))
            }
        }
    }
    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        title = { Text("Delete all data?") },
        text = { Text("This removes chat history, memory, notes, tasks and command history from this phone.") },
        confirmButton = { TextButton(onClick = { vm.clearEverything(); confirmWipe = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } }
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title, fontWeight = FontWeight.Bold, fontSize = 16.sp,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)
    )
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun PermissionStatusRow(
    title: String,
    granted: Boolean,
    reason: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (granted) "Granted" else "Not granted",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (granted) androidx.compose.ui.graphics.Color(0xFF00FFAA) else ViaGold
            )
        }
        Text(
            text = reason,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun AboutScreen(back: () -> Unit) {
    ViaBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            ViaTopBar("About VIA", back)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.via_logo),
                    contentDescription = "VIA Logo",
                    modifier = Modifier.fillMaxWidth(0.55f)
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "VIA",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = ViaGold
                    )
                    Text(
                        text = "VIP Intelligent Assistant",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "\"Your Personal AI Assistant\"",
                        color = ViaCyan,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Developed by VIP Studios And Creations",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = ViaGold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "© 2026 VIP Studios And Creations",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                GlowCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("About VIA", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = ViaGold)
                        Text(
                            "VIA is a personal AI assistant developed by VIP Studios And Creations.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Includes Gemini AI chat, \"Hey VIA\" local wake-word listening, Background Assistant service, " +
                                "Kannada/English/Hindi voice input and speech output, interactive animated anime avatar, " +
                                "camera capture, microphone & battery monitoring, safe call handling where Android permits, " +
                                "device controls, real-time India & world clock, read-only Google Sheets integration, AdMob, " +
                                "and local conversation history and memory.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                GlowCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Third-Party Services & Licenses",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = ViaCyan
                        )
                        Text(
                            "VIA integrates official third-party platforms and SDKs owned by their respective providers under their terms and licenses:",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "• Google Gemini AI & Firebase AI Logic (Google LLC) — governed by Google & Firebase Terms of Service.\n" +
                                "• Android OS, Jetpack Compose, CameraX & Telecom APIs (Google LLC) — Apache License 2.0 / Android SDK License.\n" +
                                "• Google AdMob (Mobile Ads SDK) & Google Sheets Read-Only API (Google LLC) — governed by Google Publisher and API Terms.\n" +
                                "• OpenJarvis (github.com/open-jarvis/OpenJarvis) — optional self-hosted backend support, attributed under its open-source license.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
