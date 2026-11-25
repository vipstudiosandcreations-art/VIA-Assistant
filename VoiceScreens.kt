package com.vipstudios.via.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vipstudios.via.ui.*
import com.vipstudios.via.ui.theme.*
import com.vipstudios.via.voice.VoiceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VoiceScreen(vm: ViaViewModel, back: () -> Unit) {
    val ctx = LocalContext.current
    val state by vm.voice.state.collectAsState()
    val level by vm.voice.level.collectAsState()
    val partial by vm.voice.partial.collectAsState()
    val error by vm.voice.error.collectAsState()
    var micDenied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startListening() else micDenied = true
    }

    DisposableEffect(Unit) {
        vm.voiceScreenActive = true
        onDispose {
            vm.voiceScreenActive = false
            vm.voice.cancel()
        }
    }

    fun tapMic() {
        if (state == VoiceState.Listening) {
            vm.voice.stopListening()
            return
        }
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.startListening() else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val status = when (state) {
        VoiceState.Listening -> "Listening..."
        VoiceState.Speaking -> "Speaking..."
        VoiceState.Idle -> if (vm.thinking) "Thinking..." else "Tap the mic to talk"
    }

    // No ads on this screen on purpose.
    ViaBackground {
        Column(Modifier.fillMaxSize()) {
            ViaTopBar("Voice Assistant", back)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AiCore(Modifier.fillMaxWidth(0.7f), level = level, active = state != VoiceState.Idle)
                Spacer(Modifier.height(8.dp))
                Text(status, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ViaCyan)
                Waveform(level = level, active = state == VoiceState.Listening, modifier = Modifier.padding(vertical = 8.dp))
                if (partial.isNotBlank() && state == VoiceState.Listening)
                    Text("\"$partial\"", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vm.lastReply.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    GlowCard(Modifier.fillMaxWidth()) { Text(vm.lastReply, Modifier.padding(12.dp)) }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, fontSize = 14.sp)
                }
                if (micDenied) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Microphone permission was denied. VIA only listens when you tap the mic. " +
                            "To use voice, allow Microphone for VIA in phone Settings, Apps, VIA, Permissions.",
                        textAlign = TextAlign.Center, fontSize = 13.sp, color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundBtn(Icons.Filled.Stop, "Stop speaking", 52.dp, state == VoiceState.Speaking) { vm.voice.stopSpeaking() }
                    RoundBtn(
                        if (state == VoiceState.Listening) Icons.Filled.Stop else Icons.Filled.Mic,
                        "Microphone", 76.dp, true
                    ) { tapMic() }
                    RoundBtn(Icons.Filled.Replay, "Repeat answer", 52.dp, vm.lastReply.isNotBlank()) { vm.repeatLast() }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = vm.continuous, onCheckedChange = { vm.setContinuous(it) })
                    Spacer(Modifier.width(8.dp))
                    Text("Continuous conversation", fontSize = 14.sp)
                }
                Text(
                    "Language: " + vm.voice.label(vm.language) + ". Change it in Settings.\n" +
                        "VIA listens only while this screen is open and the mic is active.",
                    fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RoundBtn(icon: ImageVector, desc: String, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size / 2))
    }
}

private data class Cmd(val title: String, val example: String, val run: String?)

@Composable
fun CommandsScreen(vm: ViaViewModel, back: () -> Unit) {
    val groups = listOf(
        "Apps" to listOf(
            Cmd("Open YouTube", "open youtube", "open youtube"),
            Cmd("Open Instagram", "open instagram", "open instagram"),
            Cmd("Open WhatsApp", "open whatsapp", "open whatsapp"),
            Cmd("Open Chrome", "open chrome", "open chrome"),
            Cmd("Any installed app", "open calculator", null),
            Cmd("Phone settings", "open settings, open wifi settings", "open settings")
        ),
        "Phone info" to listOf(
            Cmd("Battery", "battery", "battery"),
            Cmd("Time", "what time is it", "what time is it"),
            Cmd("Date", "what is the date", "what is the date"),
            Cmd("Connection", "check internet", "check internet")
        ),
        "Productivity" to listOf(
            Cmd("Save a note", "note buy milk", null),
            Cmd("Add a task", "add task call the bank", null),
            Cmd("Show tasks", "show my tasks", "show my tasks"),
            Cmd("Alarm", "set an alarm for 6:30 am", null),
            Cmd("Reminder (as alarm)", "remind me to call mom at 5 pm", null),
            Cmd("Remember something", "remember that I like green tea", null)
        ),
        "Device" to listOf(
            Cmd("Flashlight", "flashlight on / off", "flashlight"),
            Cmd("Volume", "volume up / volume down / mute", "volume")
        ),
        "Communication (you press Send or Call)" to listOf(
            Cmd("Prepare WhatsApp message", "whatsapp 919812345678 saying I am on my way", null),
            Cmd("Prepare a call", "call 9812345678", null),
            Cmd("Share text", "share see you at 6", null)
        ),
        "Navigation and search" to listOf(
            Cmd("Navigate", "navigate to Mysuru palace", null),
            Cmd("Search maps", "find cafes near me", null),
            Cmd("Web search (opens browser)", "search for weather today", null)
        )
    )
    var shown by remember { mutableStateOf("") }
    ViaBackground {
        Column(Modifier.fillMaxSize()) {
            ViaTopBar("Command Centre", back)
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text(
                        "Tap a command to run it, or say or type the example. Basic Hindi and Kannada keywords work for a few commands. Replies are in English for now.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (vm.lastReply.isNotBlank() && shown.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        GlowCard(Modifier.fillMaxWidth()) { Text(vm.lastReply, Modifier.padding(12.dp), fontSize = 14.sp) }
                    }
                }
                groups.forEach { (group, cmds) ->
                    item { Text(group, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp)) }
                    items(cmds) { c ->
                        GlowCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.title, fontWeight = FontWeight.SemiBold)
                                    Text("\"${c.example}\"", fontSize = 12.sp, color = ViaCyan)
                                }
                                if (c.run != null) {
                                    TextButton(onClick = { shown = c.run; vm.runQuick(c.run) }) { Text("Run") }
                                }
                            }
                        }
                    }
                }
                item {
                    Text("Not supported yet", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 14.dp))
                    Text(
                        "Contact names (needs contacts permission), reading PDFs and documents, " +
                            "internet-sourced answers, translation and maths (need the Stage 2 backend), " +
                            "and the \"Hey VIA\" wake word. Messages and calls are never sent automatically.",
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(vm: ViaViewModel, back: () -> Unit) {
    val fmt = remember { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }
    var confirmChat by remember { mutableStateOf(false) }
    var confirmCmds by remember { mutableStateOf(false) }
    ViaBackground {
        Column(Modifier.fillMaxSize()) {
            ViaTopBar("History", back)
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmChat = true }) { Text("Clear chat") }
                OutlinedButton(onClick = { confirmCmds = true }) { Text("Clear commands") }
            }
            Text(
                "${vm.messages.size} chat messages saved on this phone. Command history:",
                Modifier.padding(12.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (vm.commandLog.isEmpty()) {
                Text("No commands yet. Try \"open youtube\" or \"battery\".", Modifier.padding(horizontal = 12.dp))
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(vm.commandLog.reversed(), key = { it.id }) { c ->
                    GlowCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(c.text, fontWeight = FontWeight.SemiBold)
                            Text(c.result, fontSize = 13.sp)
                            Text(fmt.format(Date(c.time)), fontSize = 11.sp, color = ViaCyan)
                        }
                    }
                }
            }
        }
    }
    if (confirmChat) AlertDialog(
        onDismissRequest = { confirmChat = false },
        title = { Text("Clear chat history?") },
        text = { Text("This deletes all chat messages from this phone.") },
        confirmButton = { TextButton(onClick = { vm.clearChat(); confirmChat = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmChat = false }) { Text("Cancel") } }
    )
    if (confirmCmds) AlertDialog(
        onDismissRequest = { confirmCmds = false },
        title = { Text("Clear command history?") },
        text = { Text("This deletes the command list from this phone.") },
        confirmButton = { TextButton(onClick = { vm.clearCommands(); confirmCmds = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmCmds = false }) { Text("Cancel") } }
    )
}
