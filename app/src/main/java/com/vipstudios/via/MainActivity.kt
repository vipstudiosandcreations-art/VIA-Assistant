package com.vipstudios.via

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vipstudios.via.ai.configureAppCheck
import com.vipstudios.via.ui.ViaViewModel
import com.vipstudios.via.ui.screens.AboutScreen
import com.vipstudios.via.ui.screens.CameraScreen
import com.vipstudios.via.ui.screens.ChatScreen
import com.vipstudios.via.ui.screens.CommandsScreen
import com.vipstudios.via.ui.screens.CustomCommandsScreen
import com.vipstudios.via.ui.screens.DashboardScreen
import com.vipstudios.via.ui.screens.DeviceControlsScreen
import com.vipstudios.via.ui.screens.HistoryScreen
import com.vipstudios.via.ui.screens.MemoryScreen
import com.vipstudios.via.ui.screens.SettingsScreen
import com.vipstudios.via.ui.screens.SplashScreen
import com.vipstudios.via.ui.screens.VoiceScreen
import com.vipstudios.via.ui.screens.WorldClockScreen
import com.vipstudios.via.ui.theme.ViaTheme

class MainActivity : ComponentActivity() {

    private var pendingIntentState by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure App Check with launch intent before any UI, ViewModel, or Firebase operations.
        configureAppCheck(this, intent)

        pendingIntentState = intent

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        setContent {
            val vm: ViaViewModel = viewModel()
            val lifecycleOwner = LocalLifecycleOwner.current
            val incomingIntent = pendingIntentState

            DisposableEffect(lifecycleOwner, vm) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> vm.setUiForeground(true)
                        Lifecycle.Event.ON_PAUSE -> vm.setUiForeground(false)
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    vm.setUiForeground(false)
                }
            }

            LaunchedEffect(Unit) {
                vm.restoreBackgroundAssistantIfNeeded()
            }

            LaunchedEffect(incomingIntent) {
                if (incomingIntent != null) {
                    vm.handleLaunchIntent(incomingIntent)
                    pendingIntentState = null
                }
            }

            val skipSplash = intent?.getBooleanExtra(EXTRA_START_VOICE, false) == true ||
                !intent?.getStringExtra(EXTRA_WAKE_COMMAND).isNullOrBlank()

            ViaTheme(vm.themeMode) { ViaNav(vm, skipSplash) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra("FIREBASE_APPCHECK_DEBUG_TOKEN")) {
            configureAppCheck(this, intent)
        }
        setIntent(intent)
        pendingIntentState = intent
    }

    companion object {
        const val EXTRA_START_VOICE = "com.vipstudios.via.EXTRA_START_VOICE"
        const val EXTRA_WAKE_COMMAND = "com.vipstudios.via.EXTRA_WAKE_COMMAND"
        const val EXTRA_WAKE_EVENT_ID = "com.vipstudios.via.EXTRA_WAKE_EVENT_ID"
    }
}

@Composable
fun ViaNav(vm: ViaViewModel, skipSplash: Boolean = false) {
    val nav = rememberNavController()
    val go: (String) -> Unit = { route -> nav.navigate(route) { launchSingleTop = true } }
    val back: () -> Unit = { nav.popBackStack() }

    val pendingRoute = vm.pendingNavigationRoute
    LaunchedEffect(pendingRoute) {
        if (!pendingRoute.isNullOrBlank()) {
            go(pendingRoute)
            vm.clearPendingNavigation()
        }
    }

    NavHost(navController = nav, startDestination = if (skipSplash) "dashboard" else "splash") {
        composable("splash") {
            SplashScreen {
                nav.navigate("dashboard") { popUpTo("splash") { inclusive = true } }
            }
        }
        composable("dashboard") { DashboardScreen(vm, go) }
        composable("chat") { ChatScreen(vm, go, back) }
        composable("voice") { VoiceScreen(vm, back) }
        composable("commands") { CommandsScreen(vm, go, back) }
        composable("custom_commands") { CustomCommandsScreen(vm, back) }
        composable("controls") { DeviceControlsScreen(vm, go, back) }
        composable("camera") { CameraScreen(back) }
        composable("history") { HistoryScreen(vm, back) }
        composable("memory") { MemoryScreen(vm, back) }
        composable("clock") { WorldClockScreen(vm, back) }
        composable("settings") { SettingsScreen(vm, go, back) }
        composable("about") { AboutScreen(back) }
    }
}
