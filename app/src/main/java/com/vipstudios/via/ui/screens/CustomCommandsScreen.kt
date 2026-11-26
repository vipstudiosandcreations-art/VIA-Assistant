package com.vipstudios.via.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vipstudios.via.data.CustomActionType
import com.vipstudios.via.data.CustomVoiceCommand
import com.vipstudios.via.ui.GlowCard
import com.vipstudios.via.ui.ViaBackground
import com.vipstudios.via.ui.ViaTopBar
import com.vipstudios.via.ui.ViaViewModel
import com.vipstudios.via.ui.theme.ViaCyan
import com.vipstudios.via.ui.theme.ViaGold
import com.vipstudios.via.voice.VoiceState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CustomCommandsScreen(vm: ViaViewModel, back: () -> Unit) {
    val ctx = LocalContext.current
    val voiceState by vm.voice.state.collectAsState()

    var phrase by remember { mutableStateOf("") }
    var selectedAction by remember { mutableStateOf(CustomActionType.OPEN_GALLERY) }
    var actionArg by remember { mutableStateOf("") }
    var actionMenuExpanded by remember { mutableStateOf(false) }
    var micPermissionError by remember { mutableStateOf("") }
    var feedbackBanner by remember { mutableStateOf("") }
    var testedCommand by remember { mutableStateOf(false) }

    var editingItem by remember { mutableStateOf<CustomVoiceCommand?>(null) }
    var editPhrase by remember { mutableStateOf("") }
    var editAction by remember { mutableStateOf(CustomActionType.OPEN_GALLERY) }
    var editArg by remember { mutableStateOf("") }
    var editMenuExpanded by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            micPermissionError = ""
            vm.voice.listen(vm.language) { spoken ->
                phrase = spoken.trim()
            }
        } else {
            micPermissionError = "Microphone permission is required to record a custom voice phrase."
        }
    }

    fun capturePhraseByVoice() {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            micPermissionError = ""
            vm.voice.listen(vm.language) { spoken ->
                phrase = spoken.trim()
            }
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    ViaBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            ViaTopBar("Custom Voice Commands", back)

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = "Map your own spoken phrases to app actions. Whenever you say or type a mapped phrase (including after \"Hey VIA\"), VIA executes that action immediately.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item {
                    GlowCard(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Create Voice Mapping",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = ViaGold
                            )

                            Text(
                                text = "Quick presets:",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                AssistChip(
                                    onClick = {
                                        phrase = "Open Gallery"
                                        selectedAction = CustomActionType.OPEN_GALLERY
                                        actionArg = ""
                                    },
                                    label = { Text("Open Gallery") }
                                )
                                AssistChip(
                                    onClick = {
                                        phrase = "Send Message"
                                        selectedAction = CustomActionType.SEND_MESSAGE
                                        actionArg = ""
                                    },
                                    label = { Text("Send Message") }
                                )
                                AssistChip(
                                    onClick = {
                                        phrase = "Night Light"
                                        selectedAction = CustomActionType.TOGGLE_FLASHLIGHT
                                        actionArg = ""
                                    },
                                    label = { Text("Night Light") }
                                )
                            }

                            OutlinedTextField(
                                value = phrase,
                                onValueChange = { phrase = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("custom_phrase_input"),
                                label = { Text("Voice phrase (e.g. \"Show my photos\")") },
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(
                                        onClick = { capturePhraseByVoice() },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("record_custom_phrase_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Mic,
                                            contentDescription = "Speak phrase",
                                            tint = if (voiceState == VoiceState.Listening) ViaGold else ViaCyan
                                        )
                                    }
                                }
                            )

                            if (micPermissionError.isNotBlank()) {
                                Text(
                                    text = micPermissionError,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            ExposedDropdownMenuBox(
                                expanded = actionMenuExpanded,
                                onExpandedChange = { actionMenuExpanded = it }
                            ) {
                                OutlinedTextField(
                                    value = selectedAction.label,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("App action") },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = actionMenuExpanded)
                                    },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                        .testTag("custom_action_dropdown")
                                )
                                ExposedDropdownMenu(
                                    expanded = actionMenuExpanded,
                                    onDismissRequest = { actionMenuExpanded = false }
                                ) {
                                    CustomActionType.entries.forEach { action ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(action.label, fontWeight = FontWeight.SemiBold)
                                                    Text(
                                                        action.description,
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            },
                                            onClick = {
                                                selectedAction = action
                                                if (!action.requiresArg) actionArg = ""
                                                actionMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            if (selectedAction.requiresArg) {
                                OutlinedTextField(
                                    value = actionArg,
                                    onValueChange = { actionArg = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("custom_action_arg_input"),
                                    label = { Text(selectedAction.argHint) },
                                    singleLine = true
                                )
                            }

                            Button(
                                onClick = {
                                    vm.addCustomCommand(
                                        phrase = phrase,
                                        actionId = selectedAction.id,
                                        actionArg = actionArg
                                    )
                                    feedbackBanner = "Saved: \"$phrase\" -> ${selectedAction.label}"
                                    phrase = ""
                                    actionArg = ""
                                },
                                enabled = phrase.isNotBlank() &&
                                    (selectedAction != CustomActionType.OPEN_APP_BY_NAME || actionArg.isNotBlank()),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("add_custom_command_button")
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Save Voice Command")
                            }

                            if (feedbackBanner.isNotBlank()) {
                                Text(
                                    text = feedbackBanner,
                                    fontSize = 12.sp,
                                    color = ViaCyan
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Saved Voice Mappings (${vm.customCommands.size})",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    if (testedCommand && vm.lastReply.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        GlowCard(Modifier.fillMaxWidth()) {
                            Text(
                                text = vm.lastReply,
                                modifier = Modifier.padding(12.dp),
                                fontSize = 13.sp,
                                color = ViaCyan
                            )
                        }
                    }
                }

                if (vm.customCommands.isEmpty()) {
                    item {
                        GlowCard(Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "No custom voice commands yet",
                                    fontWeight = FontWeight.SemiBold,
                                    color = ViaGold
                                )
                                Text(
                                    text = "Pick a preset above or enter your own voice phrase like \"Open Gallery\" or \"Send Message\" to get started.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(vm.customCommands.reversed(), key = { it.id }) { cmd ->
                        val action = CustomActionType.fromId(cmd.actionId)
                        GlowCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("custom_command_card_${cmd.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = "Say: \"${cmd.phrase}\"",
                                        fontWeight = FontWeight.SemiBold,
                                        color = ViaGold
                                    )
                                    Text(
                                        text = buildString {
                                            append("Action: ${action.label}")
                                            if (cmd.actionArg.isNotBlank()) append(" (${cmd.actionArg})")
                                        },
                                        fontSize = 12.sp,
                                        color = ViaCyan
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        testedCommand = true
                                        vm.runQuick(cmd.phrase)
                                    },
                                    modifier = Modifier.testTag("run_custom_command_${cmd.id}")
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = "Test command", modifier = Modifier.height(16.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("Run")
                                }
                                IconButton(
                                    onClick = {
                                        editingItem = cmd
                                        editPhrase = cmd.phrase
                                        editAction = action
                                        editArg = cmd.actionArg
                                    },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit command")
                                }
                                IconButton(
                                    onClick = { vm.deleteCustomCommand(cmd.id) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete command")
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    editingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text("Edit Voice Command") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editPhrase,
                        onValueChange = { editPhrase = it },
                        label = { Text("Voice phrase") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ExposedDropdownMenuBox(
                        expanded = editMenuExpanded,
                        onExpandedChange = { editMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = editAction.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("App action") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = editMenuExpanded)
                            },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = editMenuExpanded,
                            onDismissRequest = { editMenuExpanded = false }
                        ) {
                            CustomActionType.entries.forEach { act ->
                                DropdownMenuItem(
                                    text = { Text(act.label) },
                                    onClick = {
                                        editAction = act
                                        if (!act.requiresArg) editArg = ""
                                        editMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    if (editAction.requiresArg) {
                        OutlinedTextField(
                            value = editArg,
                            onValueChange = { editArg = it },
                            label = { Text(editAction.argHint) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.updateCustomCommand(item.id, editPhrase, editAction.id, editArg)
                        editingItem = null
                    },
                    enabled = editPhrase.isNotBlank() &&
                        (editAction != CustomActionType.OPEN_APP_BY_NAME || editArg.isNotBlank())
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingItem = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
