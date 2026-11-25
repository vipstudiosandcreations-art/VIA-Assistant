package com.vipstudios.via.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WakeWordService::class.java))
            vm.wakeServiceOn = true
            serviceNote = "Background service started. Remember: the \"Hey VIA\" detector is not installed yet, so VIA is not listening."
        } else {
            vm.wakeServiceOn = false
            serviceNote = "Microphone permission is needed for the background service."
        }
    }

    fun toggleService(on: Boolean) {
        if (!on) {
            ctx.stopService(Intent(ctx, WakeWordService::class.java))
            vm.wakeServiceOn = false
            serviceNote = "Background service stopped."
            return
        }
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    ViaBackground {
        Column(Modifier.fillMaxSize()) {
            ViaTopBar("Settings", back)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {

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

                Section("AI personality")
                OutlinedTextField(value = persona, onValueChange = { persona = it }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { vm.setPersonality(persona) }) { Text("Save personality") }
                Note("Sent to the cloud backend with each question (Stage 2).")

                Section("Cloud AI backend")
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Backend address (https://...)") }, singleLine = true
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
                Note("The backend is built in Stage 2. Never put AI provider keys in the app. They belong on the server only.")

                Section("Background listening")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = vm.wakeServiceOn, onCheckedChange = { toggleService(it) })
                    Spacer(Modifier.width(8.dp))
                    Text("Background service")
                }
                if (serviceNote.isNotBlank()) Text(serviceNote, fontSize = 13.sp, color = ViaCyan)
                Note(
                    "Stage 1 includes only the Android foreground-service shell with a visible notification and Stop button. " +
                        "The \"Hey VIA\" wake-word detector is NOT built yet. Android and some phone makers limit background " +
                        "microphone use, so always-on listening can never be guaranteed."
                )

                Section("Advertisements")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = vm.adsEnabled, onCheckedChange = { vm.setAds(it) })
                    Spacer(Modifier.width(8.dp))
                    Text("Show banner ad on home screen")
                }
                Note(
                    "These are Google TEST ads. No ads show during voice or while a command runs. " +
                        "Before a public release you must add your real AdMob IDs, the consent form (UMP) and a privacy policy."
                )

                Section("Privacy and data")
                Note("Chat, memory and command history are stored only on this phone. VIA never records secretly. The mic is used only when you tap it.")
                OutlinedButton(onClick = { confirmWipe = true }) { Text("Delete all my VIA data") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { go("about") }) { Text("About VIA") }
                Spacer(Modifier.height(24.dp))
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
fun AboutScreen(back: () -> Unit) {
    ViaBackground {
        Column(Modifier.fillMaxSize()) {
            ViaTopBar("About VIA", back)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(painterResource(R.drawable.via_logo), "VIA logo", Modifier.fillMaxWidth(0.8f))
                Spacer(Modifier.height(12.dp))
                Text("VIA - VIP Intelligent Assistant", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                Text("Your Personal AI Assistant", color = ViaCyan)
                Spacer(Modifier.height(8.dp))
                Text("By VIP Studios & Creations", fontWeight = FontWeight.SemiBold)
                Text("Version 0.1.0 (Stage 1 build)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                GlowCard(Modifier.fillMaxWidth()) {
                    Text(
                        "VIA's cloud AI will be built on the open-source OpenJarvis project " +
                            "(github.com/open-jarvis/OpenJarvis), used under its licence with attribution. " +
                            "That integration is part of Stage 2 and is not in this build.",
                        Modifier.padding(12.dp), fontSize = 13.sp
                    )
                }
            }
        }
    }
}
