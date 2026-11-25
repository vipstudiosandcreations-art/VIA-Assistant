package com.vipstudios.via

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vipstudios.via.ui.ViaViewModel
import com.vipstudios.via.ui.screens.AboutScreen
import com.vipstudios.via.ui.screens.ChatScreen
import com.vipstudios.via.ui.screens.CommandsScreen
import com.vipstudios.via.ui.screens.DashboardScreen
import com.vipstudios.via.ui.screens.HistoryScreen
import com.vipstudios.via.ui.screens.MemoryScreen
import com.vipstudios.via.ui.screens.SettingsScreen
import com.vipstudios.via.ui.screens.SplashScreen
import com.vipstudios.via.ui.screens.VoiceScreen
import com.vipstudios.via.ui.theme.ViaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: ViaViewModel = viewModel()
            ViaTheme(vm.themeMode) { ViaNav(vm) }
        }
    }
}

@Composable
fun ViaNav(vm: ViaViewModel) {
    val nav = rememberNavController()
    val go: (String) -> Unit = { route -> nav.navigate(route) { launchSingleTop = true } }
    val back: () -> Unit = { nav.popBackStack() }

    NavHost(navController = nav, startDestination = "splash") {
        composable("splash") {
            SplashScreen {
                nav.navigate("dashboard") { popUpTo("splash") { inclusive = true } }
            }
        }
        composable("dashboard") { DashboardScreen(vm, go) }
        composable("chat") { ChatScreen(vm, go, back) }
        composable("voice") { VoiceScreen(vm, back) }
        composable("commands") { CommandsScreen(vm, back) }
        composable("history") { HistoryScreen(vm, back) }
        composable("memory") { MemoryScreen(vm, back) }
        composable("settings") { SettingsScreen(vm, go, back) }
        composable("about") { AboutScreen(back) }
    }
}
