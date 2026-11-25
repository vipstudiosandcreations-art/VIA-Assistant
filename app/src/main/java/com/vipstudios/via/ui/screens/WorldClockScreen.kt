package com.vipstudios.via.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vipstudios.via.time.CityTimeSnapshot
import com.vipstudios.via.time.NetworkTimeSyncResult
import com.vipstudios.via.time.WorldTimeEngine
import com.vipstudios.via.ui.GlowCard
import com.vipstudios.via.ui.ViaBackground
import com.vipstudios.via.ui.ViaTopBar
import com.vipstudios.via.ui.ViaViewModel
import com.vipstudios.via.ui.theme.ViaBlack
import com.vipstudios.via.ui.theme.ViaCyan
import com.vipstudios.via.ui.theme.ViaGold
import com.vipstudios.via.ui.theme.ViaRoyal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

@Composable
fun WorldClockScreen(vm: ViaViewModel, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var nowInstant by remember { mutableStateOf(Instant.now()) }
    var showSeconds by remember { mutableStateOf(true) }
    var networkSyncText by remember { mutableStateOf("Offline-ready · Using system clock + IANA time zones") }
    var syncingNetwork by remember { mutableStateOf(false) }

    // Real-time 1-second tick aligned smoothly to wall clock
    LaunchedEffect(Unit) {
        while (true) {
            nowInstant = Instant.now()
            val millisIntoSecond = nowInstant.toEpochMilli() % 1000L
            delay((1000L - millisIntoSecond).coerceIn(200L, 1000L))
        }
    }

    val indiaSnap = remember(nowInstant, vm.use24HourClock, showSeconds) {
        WorldTimeEngine.snapshotForZone(
            zoneIdStr = WorldTimeEngine.INDIA_ZONE_ID,
            label = "India",
            flag = "\uD83C\uDDEE\uD83C\uDDF3",
            use24Hour = vm.use24HourClock,
            includeSeconds = showSeconds,
            instant = nowInstant
        )
    }
    val localZoneId = remember { ZoneId.systemDefault().id }
    val localSnap = remember(nowInstant, vm.use24HourClock, showSeconds, localZoneId) {
        WorldTimeEngine.snapshotForZone(
            zoneIdStr = localZoneId,
            label = "Device Local",
            flag = "",
            use24Hour = vm.use24HourClock,
            includeSeconds = showSeconds,
            instant = nowInstant
        )
    }
    val worldSnaps = remember(nowInstant, vm.use24HourClock, showSeconds) {
        WorldTimeEngine.featuredSnapshots(
            use24Hour = vm.use24HourClock,
            includeSeconds = showSeconds,
            instant = nowInstant
        )
    }

    ViaBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            ViaTopBar("Real-Time & World Clock", back)

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                // Hero India (Asia/Kolkata) Real-Time Clock Card
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                        border = BorderStroke(
                            1.5.dp,
                            Brush.linearGradient(listOf(ViaGold, ViaCyan))
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("india_clock_card")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            ViaRoyal.copy(alpha = 0.25f),
                                            ViaBlack.copy(alpha = 0.45f)
                                        )
                                    )
                                )
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Schedule,
                                    contentDescription = "India Clock",
                                    tint = ViaGold,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "${indiaSnap.flag} INDIA STANDARD TIME",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ViaGold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        vm.speakCustom(
                                            "In India, it is ${indiaSnap.formattedTime} on ${indiaSnap.formattedDate}.",
                                            "en"
                                        )
                                    },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("speak_india_time_button")
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "Speak India time",
                                        tint = ViaCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = indiaSnap.formattedTime,
                                style = MaterialTheme.typography.headlineLarge,
                                color = Color.White,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("india_time_text")
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = indiaSnap.formattedDate,
                                fontSize = 15.sp,
                                color = ViaCyan,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.testTag("india_date_text")
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${indiaSnap.zoneId} · ${indiaSnap.shortZoneName} · ${indiaSnap.utcOffset}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (localZoneId != WorldTimeEngine.INDIA_ZONE_ID) {
                                Spacer(Modifier.height(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = ViaBlack.copy(alpha = 0.55f),
                                    border = BorderStroke(1.dp, ViaCyan.copy(alpha = 0.35f))
                                ) {
                                    Text(
                                        text = "Device Local ($localZoneId): ${localSnap.formattedTime} · ${localSnap.formattedDate}",
                                        fontSize = 11.sp,
                                        color = ViaCyan,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ClockTogglePill(
                                    label = if (vm.use24HourClock) "24-Hour Format" else "12-Hour Format",
                                    selected = vm.use24HourClock,
                                    modifier = Modifier.testTag("clock_format_toggle")
                                ) {
                                    vm.setUse24HourClock(!vm.use24HourClock)
                                }
                                ClockTogglePill(
                                    label = if (showSeconds) "Seconds: ON" else "Seconds: OFF",
                                    selected = showSeconds,
                                    modifier = Modifier.testTag("clock_seconds_toggle")
                                ) {
                                    showSeconds = !showSeconds
                                }
                            }
                        }
                    }
                }

                // Network Time Verification Row
                item {
                    GlowCard(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Public,
                                contentDescription = "Clock Source",
                                tint = ViaCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = networkSyncText,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    if (!syncingNetwork) {
                                        syncingNetwork = true
                                        networkSyncText = "Checking network UTC time..."
                                        scope.launch {
                                            networkSyncText = when (val res = WorldTimeEngine.verifyNetworkTimeUtc()) {
                                                is NetworkTimeSyncResult.Synced -> {
                                                    val drift = abs(res.offsetSeconds)
                                                    if (drift <= 2L) "Verified via worldtimeapi.org (device clock accurate within ${drift}s)"
                                                    else "Network UTC checked (device clock offset ${res.offsetSeconds}s)"
                                                }
                                                is NetworkTimeSyncResult.Error -> res.message
                                            }
                                            syncingNetwork = false
                                        }
                                    }
                                },
                                enabled = !syncingNetwork,
                                modifier = Modifier.testTag("verify_network_time_button")
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Verify", fontSize = 12.sp)
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "WORLD TIME ZONES (AUTO DST)",
                        style = MaterialTheme.typography.labelMedium,
                        color = ViaGold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                items(worldSnaps, key = { it.zoneId }) { city ->
                    WorldCityRowCard(
                        city = city,
                        onSpeak = {
                            val dstPhrase = if (city.isDst) " Daylight Saving Time is active." else ""
                            vm.speakCustom(
                                "In ${city.label}, it is ${city.formattedTime} on ${city.formattedDate}.$dstPhrase",
                                "en"
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ClockTogglePill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) ViaCyan.copy(alpha = 0.2f) else ViaBlack.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, if (selected) ViaCyan else ViaGold.copy(alpha = 0.45f)),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) ViaCyan else Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun WorldCityRowCard(city: CityTimeSnapshot, onSpeak: () -> Unit) {
    GlowCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("world_city_${city.zoneId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSpeak)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${city.flag} ${city.label}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (city.isDst) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = ViaGold.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, ViaGold.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = "DST",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = ViaGold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = city.formattedDate,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${city.zoneId} · ${city.shortZoneName} (${city.utcOffset})",
                    fontSize = 11.sp,
                    color = ViaCyan.copy(alpha = 0.85f)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = city.formattedTime,
                    style = MaterialTheme.typography.titleMedium,
                    color = ViaGold,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(2.dp))
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Speak ${city.label} time",
                    tint = ViaCyan,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
