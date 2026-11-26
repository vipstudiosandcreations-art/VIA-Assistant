package com.vipstudios.via.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
        fade.animateTo(1f, tween(300))
        delay(250)
        onDone()
    }
    ViaBackground {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painterResource(R.drawable.via_logo),
                contentDescription = "VIA AI Crowned Lion Logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(0.76f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.dp, ViaGold.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
                    .alpha(fade.value)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "VIP INTELLIGENT ASSISTANT",
                style = MaterialTheme.typography.titleMedium,
                color = ViaGold,
                letterSpacing = 1.8.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text("Your Personal AI Assistant", color = ViaCyan, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Developed by VIP Studios And Creations",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun DashboardScreen(vm: ViaViewModel, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val voiceState by vm.voice.state.collectAsState()
    val micLevel by vm.voice.level.collectAsState()
    val mouthLevel by vm.voice.mouthLevel.collectAsState()
    val partialSpeech by vm.voice.partial.collectAsState()
    val voiceError by vm.voice.error.collectAsState()

    val assistantState = vm.assistantState(voiceState)
    val callState by vm.callState.collectAsState()
    val phoneStatus by vm.phoneStatus.collectAsState()
    val connectSheets = rememberGoogleSheetsConnector(vm)
    var input by remember { mutableStateOf("") }
    var micDenied by remember { mutableStateOf(false) }
    var callFeedback by remember { mutableStateOf("") }

    val micPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            micDenied = false
            vm.startListening()
        } else {
            micDenied = true
        }
    }

    fun triggerVoiceInput() {
        when (voiceState) {
            VoiceState.Listening -> vm.voice.stopListening()
            VoiceState.Speaking -> vm.voice.stopSpeaking()
            VoiceState.Idle -> {
                val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) vm.startListening() else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    fun submitInput() {
        val text = input.trim()
        if (text.isNotEmpty() && !vm.thinking) {
            vm.send(text)
            input = ""
        }
    }

    val quickActions = listOf(
        "Hey VIA" to "Hey VIA",
        "Battery Status" to "battery status",
        "Time" to "what time is it",
        "Open Camera" to "open camera",
        "Tasks" to "show my tasks"
    )

    ViaBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // 1. Futuristic JARVIS HUD Header
            HudHeader(
                state = assistantState,
                voiceResponseOn = vm.voiceResponseEnabled,
                wakeMicActive = vm.wakeMicActive,
                wakeServiceOn = vm.wakeServiceOn,
                isOffline = !phoneStatus.isOnline,
                onToggleVoiceResponse = { vm.toggleVoiceResponse() },
                onStopAssistant = { vm.stopBackgroundAssistant() },
                onNavigate = go
            )

            // Safe incoming call banner when call monitoring is active and ringing
            AnimatedVisibility(visible = callState.isRinging) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF102A3A),
                    border = BorderStroke(1.5.dp, ViaGold),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag("dashboard_incoming_call_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = "Incoming call", tint = ViaGold)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Incoming Call", fontSize = 11.sp, color = ViaGold, fontWeight = FontWeight.Bold)
                            Text(
                                text = callState.callerDisplay.ifBlank { "Incoming call" },
                                fontSize = 13.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(
                            onClick = { callFeedback = vm.answerIncomingCall() },
                            modifier = Modifier.testTag("dashboard_accept_call_button")
                        ) {
                            Text("Accept", color = Color(0xFF00FFAA), fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = { callFeedback = vm.declineIncomingCall() },
                            modifier = Modifier.testTag("dashboard_decline_call_button")
                        ) {
                            Text("Decline", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (callFeedback.isNotBlank() && !callState.isRinging) {
                Text(
                    text = callFeedback,
                    fontSize = 11.sp,
                    color = ViaCyan,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                )
            }

            // 2. Adaptive Main Stage (Portrait stack vs Wide landscape/tablet split)
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                val isWide = maxWidth >= 640.dp

                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AvatarStage(
                            state = assistantState,
                            micLevel = micLevel,
                            mouthLevel = mouthLevel,
                            partialSpeech = partialSpeech,
                            onTapAvatar = { triggerVoiceInput() },
                            onStopSpeaking = { vm.voice.stopSpeaking() },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                        ChatFeedPanel(
                            messages = vm.messages,
                            streamingReply = vm.streamingReply,
                            thinking = vm.thinking,
                            errorText = vm.aiError ?: voiceError ?: if (micDenied) "Microphone permission is required for voice input." else null,
                            sheetsConnected = vm.sheetsConnected,
                            sheetsLoading = vm.sheetsLoading,
                            sheetsNeedsReauth = vm.sheetsNeedsReauth,
                            onSheetsClick = {
                                if (vm.sheetsConnected && !vm.sheetsNeedsReauth) vm.refreshSheets()
                                else connectSheets()
                            },
                            onExpandChat = { go("chat") },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AvatarStage(
                            state = assistantState,
                            micLevel = micLevel,
                            mouthLevel = mouthLevel,
                            partialSpeech = partialSpeech,
                            onTapAvatar = { triggerVoiceInput() },
                            onStopSpeaking = { vm.voice.stopSpeaking() },
                            modifier = Modifier
                                .weight(0.56f)
                                .fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        ChatFeedPanel(
                            messages = vm.messages,
                            streamingReply = vm.streamingReply,
                            thinking = vm.thinking,
                            errorText = vm.aiError ?: voiceError ?: if (micDenied) "Microphone permission is required for voice input." else null,
                            sheetsConnected = vm.sheetsConnected,
                            sheetsLoading = vm.sheetsLoading,
                            sheetsNeedsReauth = vm.sheetsNeedsReauth,
                            onSheetsClick = {
                                if (vm.sheetsConnected && !vm.sheetsNeedsReauth) vm.refreshSheets()
                                else connectSheets()
                            },
                            onExpandChat = { go("chat") },
                            modifier = Modifier
                                .weight(0.44f)
                                .fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // 3. Quick Command Chips + Command Input Dock
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(quickActions) { (label, cmd) ->
                    Pill(label, modifier = Modifier.testTag("quick_pill_${label.lowercase().replace(" ", "_")}")) {
                        vm.runQuick(cmd)
                    }
                }
                item {
                    Pill("Device Controls", modifier = Modifier.testTag("quick_pill_device_controls")) {
                        go("controls")
                    }
                }
                item {
                    Pill("Camera", modifier = Modifier.testTag("quick_pill_camera")) {
                        go("camera")
                    }
                }
                item {
                    Pill("World Clock", modifier = Modifier.testTag("quick_pill_world_clock")) {
                        go("clock")
                    }
                }
                item {
                    Pill("Command Center", modifier = Modifier.testTag("quick_pill_command_center")) {
                        go("commands")
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            CommandInputDock(
                input = input,
                onInputChange = { input = it },
                state = assistantState,
                voiceResponseOn = vm.voiceResponseEnabled,
                onToggleVoiceResponse = { vm.toggleVoiceResponse() },
                onMicClick = { triggerVoiceInput() },
                onSendClick = { submitInput() }
            )

            // Responsive AdMob banner placed strictly below the input dock, never covering voice/avatar controls.
            if (vm.adsEnabled && assistantState == AssistantState.IDLE) {
                AdaptiveBanner(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun HudHeader(
    state: AssistantState,
    voiceResponseOn: Boolean,
    wakeMicActive: Boolean,
    wakeServiceOn: Boolean,
    isOffline: Boolean,
    onToggleVoiceResponse: () -> Unit,
    onStopAssistant: () -> Unit,
    onNavigate: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.via_logo),
            contentDescription = "VIA Emblem",
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .border(1.dp, ViaGold.copy(alpha = 0.65f), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "VIA",
                    style = MaterialTheme.typography.titleLarge,
                    color = ViaGold,
                    fontSize = 18.sp
                )
                Spacer(Modifier.width(8.dp))
                StateBadge(state)
                if (isOffline) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = ViaGold.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, ViaGold.copy(alpha = 0.7f)),
                        modifier = Modifier.testTag("offline_mode_badge")
                    ) {
                        Text(
                            text = "OFFLINE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = ViaGold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (wakeMicActive || state == AssistantState.LISTENING) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFF00FFAA).copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, Color(0xFF00FFAA).copy(alpha = 0.7f)),
                        modifier = Modifier.testTag("mic_active_indicator")
                    ) {
                        Text(
                            text = "MIC ON",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00FFAA),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "VIP Intelligent Assistant",
                    fontSize = 11.sp,
                    color = ViaCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (wakeServiceOn) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "· Stop BG",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ViaGold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onStopAssistant)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                            .testTag("header_stop_assistant_button")
                    )
                }
            }
        }

        IconButton(
            onClick = onToggleVoiceResponse,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("header_voice_toggle_button")
        ) {
            Icon(
                imageVector = if (voiceResponseOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = if (voiceResponseOn) "Voice output enabled" else "Voice output muted",
                tint = if (voiceResponseOn) ViaCyan else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = { onNavigate("commands") },
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("nav_commands_button")
        ) {
            Icon(Icons.Filled.Bolt, contentDescription = "Command Center", tint = ViaGold)
        }
        IconButton(
            onClick = { onNavigate("memory") },
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("nav_memory_button")
        ) {
            Icon(Icons.Filled.Memory, contentDescription = "Local Memory", tint = ViaCyan)
        }
        IconButton(
            onClick = { onNavigate("history") },
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("nav_history_button")
        ) {
            Icon(Icons.Filled.History, contentDescription = "History", tint = MaterialTheme.colorScheme.onSurface)
        }
        IconButton(
            onClick = { onNavigate("settings") },
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("nav_settings_button")
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun StateBadge(state: AssistantState) {
    val (label, color) = when (state) {
        AssistantState.IDLE -> "IDLE" to ViaCyan
        AssistantState.LISTENING -> "LISTENING" to Color(0xFF00FFAA)
        AssistantState.THINKING -> "THINKING" to ViaGold
        AssistantState.SPEAKING -> "SPEAKING" to ViaRoyal
    }
    val pulse = rememberInfiniteTransition(label = "badge_pulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dot_alpha"
    )
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.65f)),
        modifier = Modifier.testTag("assistant_state_badge")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (state == AssistantState.IDLE) 0.9f else dotAlpha))
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (state == AssistantState.SPEAKING) Color(0xFF8FB0FF) else color,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun AvatarStage(
    state: AssistantState,
    micLevel: Float,
    mouthLevel: Float,
    partialSpeech: String,
    onTapAvatar: () -> Unit,
    onStopSpeaking: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        InteractiveAnimeAvatar(
            state = state,
            micLevel = micLevel,
            mouthLevel = mouthLevel,
            modifier = Modifier.fillMaxSize(),
            onTapAvatar = onTapAvatar
        )

        // Floating HUD prompt / live speech overlay at the bottom of the avatar stage
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(visible = state == AssistantState.LISTENING && partialSpeech.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ViaBlack.copy(alpha = 0.82f),
                    border = BorderStroke(1.dp, ViaCyan.copy(alpha = 0.7f)),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "\"$partialSpeech\"",
                        color = ViaCyan,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (state == AssistantState.SPEAKING) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = ViaRoyal.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, ViaGold.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onStopSpeaking)
                        .testTag("stop_speaking_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop speaking", tint = ViaGold, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tap to stop", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                val hint = when (state) {
                    AssistantState.IDLE -> "Tap avatar or mic · \"Hey VIA\""
                    AssistantState.LISTENING -> "Listening..."
                    AssistantState.THINKING -> "Analyzing & generating reply..."
                    AssistantState.SPEAKING -> ""
                }
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ViaBlack.copy(alpha = 0.65f))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun ChatFeedPanel(
    messages: List<ChatMessage>,
    streamingReply: String,
    thinking: Boolean,
    errorText: String?,
    sheetsConnected: Boolean,
    sheetsLoading: Boolean,
    sheetsNeedsReauth: Boolean,
    onSheetsClick: () -> Unit,
    onExpandChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val shownMessages = remember(messages) { messages.takeLast(24) }

    LaunchedEffect(shownMessages.size, streamingReply, thinking) {
        val target = (shownMessages.size + if (streamingReply.isNotBlank() || thinking) 1 else 0 - 1).coerceAtLeast(0)
        if (shownMessages.isNotEmpty()) {
            listState.animateScrollToItem(target)
        }
    }

    GlowCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "LIVE DIALOG",
                    style = MaterialTheme.typography.labelMedium,
                    color = ViaGold
                )
                Spacer(Modifier.width(8.dp))
                val sheetLabel = when {
                    sheetsLoading -> "Syncing..."
                    sheetsConnected && !sheetsNeedsReauth -> "Sheets: Live"
                    sheetsNeedsReauth -> "Reconnect Sheet"
                    else -> "Connect Sheet"
                }
                val sheetColor = if (sheetsConnected && !sheetsNeedsReauth) Color(0xFF00FFAA) else ViaGold
                Surface(
                    shape = RoundedCornerShape(50),
                    color = sheetColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, sheetColor.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = !sheetsLoading, onClick = onSheetsClick)
                        .testTag("dashboard_sheets_chip")
                ) {
                    Text(
                        text = sheetLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = sheetColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onExpandChat)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("expand_chat_button"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Full Chat", fontSize = 11.sp, color = ViaCyan, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.OpenInFull,
                        contentDescription = "Open full chat",
                        tint = ViaCyan,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
            HorizontalDivider(color = ViaCyan.copy(alpha = 0.16f))

            if (!errorText.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("ai_error_banner")
                ) {
                    Text(
                        text = errorText,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(shownMessages, key = { it.id }) { msg ->
                    ChatBubble(msg)
                }
                if (streamingReply.isNotBlank()) {
                    item(key = "streaming") {
                        ChatBubble(ChatMessage(id = -1L, fromUser = false, text = streamingReply, time = 0L))
                    }
                } else if (thinking) {
                    item(key = "thinking") {
                        Text(
                            "VIA is thinking...",
                            fontSize = 12.sp,
                            color = ViaCyan,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandInputDock(
    input: String,
    onInputChange: (String) -> Unit,
    state: AssistantState,
    voiceResponseOn: Boolean,
    onToggleVoiceResponse: () -> Unit,
    onMicClick: () -> Unit,
    onSendClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, ViaCyan.copy(alpha = 0.28f)),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Voice response toggle button
            IconButton(
                onClick = onToggleVoiceResponse,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("dock_voice_toggle_button")
            ) {
                Icon(
                    imageVector = if (voiceResponseOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = if (voiceResponseOn) "Disable voice response" else "Enable voice response",
                    tint = if (voiceResponseOn) ViaGold else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Text question / command input
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .testTag("message_input"),
                placeholder = { Text("Ask VIA or type a command...", fontSize = 14.sp) },
                maxLines = 3,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ViaCyan,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            Spacer(Modifier.width(8.dp))

            // Microphone button
            val micBg = when (state) {
                AssistantState.LISTENING -> Brush.linearGradient(listOf(ViaCyan, Color(0xFF00FFAA)))
                AssistantState.SPEAKING -> Brush.linearGradient(listOf(ViaRoyal, ViaCyan))
                else -> Brush.linearGradient(listOf(ViaRoyal, ViaCyan.copy(alpha = 0.85f)))
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(micBg)
                    .border(1.5.dp, ViaGold.copy(alpha = 0.75f), CircleShape)
                    .clickable(onClick = onMicClick)
                    .testTag("mic_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (state == AssistantState.LISTENING || state == AssistantState.SPEAKING) {
                        Icons.Filled.Stop
                    } else {
                        Icons.Filled.Mic
                    },
                    contentDescription = if (state == AssistantState.LISTENING) "Stop listening" else "Speak to VIA",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(6.dp))

            // Send button
            val canSend = input.isNotBlank() && state != AssistantState.THINKING
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (canSend) ViaGold else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = canSend, onClick = onSendClick)
                    .testTag("send_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send question",
                    tint = if (canSend) ViaBlack else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
fun ChatScreen(vm: ViaViewModel, go: (String) -> Unit, back: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val voiceState by vm.voice.state.collectAsState()
    val phoneStatus by vm.phoneStatus.collectAsState()
    val assistantState = vm.assistantState(voiceState)

    LaunchedEffect(vm.messages.size, vm.streamingReply, vm.thinking) {
        if (vm.messages.isNotEmpty()) {
            listState.animateScrollToItem(vm.messages.size)
        }
    }

    ViaBackground {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    ViaTopBar("VIA Chat", back)
                }
                IconButton(
                    onClick = { vm.toggleVoiceResponse() },
                    modifier = Modifier
                        .statusBarsPadding()
                        .minimumInteractiveComponentSize()
                        .testTag("chat_voice_toggle_button")
                ) {
                    Icon(
                        if (vm.voiceResponseEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = "Toggle voice response",
                        tint = if (vm.voiceResponseEnabled) ViaGold else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!phoneStatus.isOnline) {
                Surface(
                    color = ViaGold.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, ViaGold.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag("chat_offline_banner")
                ) {
                    Text(
                        text = "Offline mode — local commands (Open Camera, Battery Status, Time, Flashlight, Tasks) work without Gemini AI.",
                        color = ViaGold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            if (!vm.aiError.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = vm.aiError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            LazyColumn(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(vm.messages, key = { it.id }) { ChatBubble(it) }
                if (vm.streamingReply.isNotBlank()) {
                    item(key = "chat_streaming") {
                        ChatBubble(ChatMessage(id = -2L, fromUser = false, text = vm.streamingReply, time = 0L))
                    }
                } else {
                    item {
                        if (vm.thinking) Text("VIA is thinking...", fontSize = 12.sp, color = ViaCyan)
                        else Spacer(Modifier.height(1.dp))
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { go("voice") },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("chat_voice_button")
                ) {
                    Icon(Icons.Filled.Mic, "Voice mode", tint = ViaCyan)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_message_input"),
                    placeholder = { Text("Message VIA") },
                    maxLines = 4
                )
                IconButton(
                    onClick = { vm.send(input); input = "" },
                    enabled = input.isNotBlank() && assistantState != AssistantState.THINKING,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("chat_send_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(m: ChatMessage) {
    val mine = m.fromUser
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (mine) ViaRoyal else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            border = if (mine) BorderStroke(1.dp, ViaCyan.copy(alpha = 0.45f)) else BorderStroke(1.dp, ViaGold.copy(alpha = 0.4f)),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                m.text,
                Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                fontSize = 14.sp,
                color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
