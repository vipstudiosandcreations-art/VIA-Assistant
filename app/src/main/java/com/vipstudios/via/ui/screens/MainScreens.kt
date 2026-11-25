package com.vipstudios.via.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vipstudios.via.R
import com.vipstudios.via.data.ChatMessage
import com.vipstudios.via.ui.*
import com.vipstudios.via.ui.theme.*
import com.vipstudios.via.voice.VoiceState
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onDone: () -> Unit) {
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fade.animateTo(1f, androidx.compose.animation.core.tween(900))
        delay(1100)
        onDone()
    }
    ViaBackground {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painterResource(R.drawable.via_logo),
                contentDescription = "VIA logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(0.85f).alpha(fade.value)
            )
            Spacer(Modifier.height(16.dp))
            Text("Your Personal AI Assistant", color = ViaCyan, fontSize = 16.sp)
        }
    }
}

@Composable
fun DashboardScreen(vm: ViaViewModel, go: (String) -> Unit) {
    val voiceState by vm.voice.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val online = vm.isOnline()
    val cloud = when {
        vm.backendUrl.isBlank() -> "Cloud AI: not set up"
        online -> "Cloud AI: address set"
        else -> "Offline"
    }
    val quick = listOf(
        "YouTube" to "open youtube", "WhatsApp" to "open whatsapp", "Battery" to "battery",
        "Time" to "what time is it", "Flashlight" to "flashlight", "Maps" to "open maps",
        "Date" to "what is the date"
    )
    val busy = vm.thinking || voiceState != VoiceState.Idle

    ViaBackground {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.via_logo), "VIA", Modifier.size(52.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("VIA", fontSize = 24.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                        Text("Your Personal AI Assistant", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                    Text(
                        (if (online) "Online" else "Offline") + "\n" + cloud,
                        fontSize = 11.sp, textAlign = TextAlign.End,
                        color = if (online) ViaCyan else MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(12.dp))
                Box(contentAlignment = Alignment.Center) {
                    AiCore(Modifier.fillMaxWidth(0.62f).clip(CircleShape).clickable { go("voice") }, active = busy)
                    Icon(Icons.Filled.Mic, "Talk to VIA", tint = ViaBlack, modifier = Modifier.size(44.dp))
                }
                Text("Tap the core to talk", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ask VIA or give a command") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            if (input.isNotBlank()) { vm.send(input); input = ""; go("chat") }
                        }) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary) }
                    }
                )
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quick) { (label, cmd) -> Pill(label) { vm.runQuick(cmd) } }
                }
                if (vm.lastReply.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    GlowCard(Modifier.fillMaxWidth()) {
                        Text(vm.lastReply, Modifier.padding(12.dp), fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                val tiles = listOf(
                    Triple("Chat", Icons.Filled.Chat, "chat"), Triple("Voice", Icons.Filled.Mic, "voice"),
                    Triple("Commands", Icons.Filled.Bolt, "commands"), Triple("History", Icons.Filled.History, "history"),
                    Triple("Memory", Icons.Filled.Memory, "memory"), Triple("Settings", Icons.Filled.Settings, "settings")
                )
                tiles.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (name, icon, route) -> Tile(name, icon, Modifier.weight(1f)) { go(route) } }
                    }
                }
            }
            // Banner ad: bottom of the home screen only, never during voice or while a command runs.
            if (vm.adsEnabled && !busy) AdaptiveBanner()
        }
    }
}

@Composable
private fun Tile(name: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    GlowCard(modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(vertical = 14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, name, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(4.dp))
            Text(name, fontSize = 13.sp)
        }
    }
}

@Composable
fun ChatScreen(vm: ViaViewModel, go: (String) -> Unit, back: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size, vm.thinking) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size)
    }
    ViaBackground {
        Column(Modifier.fillMaxSize()) {
            ViaTopBar("VIA Chat", back)
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(vm.messages, key = { it.id }) { Bubble(it) }
                item { if (vm.thinking) Text("VIA is thinking...", fontSize = 12.sp, color = ViaCyan) else Spacer(Modifier.height(1.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { go("voice") }) { Icon(Icons.Filled.Mic, "Voice", tint = ViaCyan) }
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f), placeholder = { Text("Message VIA") }, maxLines = 4
                )
                IconButton(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank() && !vm.thinking) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val mine = m.fromUser
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (mine) ViaRoyal else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            border = if (mine) null else BorderStroke(1.dp, ViaGold.copy(alpha = 0.4f)),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                m.text, Modifier.padding(12.dp), fontSize = 15.sp,
                color = if (mine) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
