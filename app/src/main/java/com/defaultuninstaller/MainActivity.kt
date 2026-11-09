package com.defaultuninstaller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.defaultuninstaller.ui.applist.AppListScreen
import com.defaultuninstaller.ui.applist.AppListViewModel
import com.defaultuninstaller.ui.history.HistoryScreen
import com.defaultuninstaller.ui.removed.RemovedAppsScreen
import com.defaultuninstaller.ui.settings.SettingsScreen
import com.defaultuninstaller.ui.setup.SetupScreen
import com.defaultuninstaller.ui.theme.DefaultUninstallerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as App).container
        setContent {
            val loaded by container.settings.loaded.collectAsStateWithLifecycle()
            val settings = loaded ?: return@setContent
            DefaultUninstallerTheme(settings.theme, settings.dynamicColor) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val nav = rememberNavController()
                    val start = remember { if (settings.onboardingDone) "list" else "welcome" }
                    NavHost(nav, startDestination = start) {
                        composable("welcome") {
                            SetupScreen(firstRun = true, onDone = {
                                nav.navigate("list") { popUpTo("welcome") { inclusive = true } }
                            })
                        }
                        composable("list") {
                            val vm = viewModel { AppListViewModel(container) }
                            AppListScreen(
                                vm = vm,
                                onOpenRemoved = { nav.navigate("removed") },
                                onOpenHistory = { nav.navigate("history") },
                                onOpenSettings = { nav.navigate("settings") },
                                onOpenSetup = { nav.navigate("setup") },
                            )
                        }
                        composable("setup") { SetupScreen(firstRun = false, onDone = { nav.popBackStack() }) }
                        composable("removed") { RemovedAppsScreen(onBack = { nav.popBackStack() }) }
                        composable("history") { HistoryScreen(onBack = { nav.popBackStack() }) }
                        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }
}
