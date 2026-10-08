package com.aiquickassist

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.aiquickassist.ui.*

class MainActivity : ComponentActivity() {
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent?.getStringExtra("url")?.let { com.aiquickassist.ui.BrowserLaunch.url = it }
        pendingRoute = intent?.getStringExtra("route")
        setContent {
            AppTheme {
                val stack = remember { mutableStateListOf("home") }
                val nav = remember { Nav(stack) }
                LaunchedEffect(pendingRoute) {
                    pendingRoute?.let { nav.go(it); pendingRoute = null }
                }
                BackHandler(enabled = stack.size > 1) { nav.back() }
                when (stack.last()) {
                    "home" -> HomeScreen(nav)
                    "settings" -> SettingsScreen(nav)
                    "engine" -> EngineScreen(nav)
                    "web" -> WebEngineScreen(nav)
                    "gemini" -> GeminiScreen(nav)
                    "googleai" -> GoogleAiScreen(nav)
                    "testreport" -> TestReportScreen(nav)
                    "bubble" -> BubbleSettingsScreen(nav)
                    "panel" -> PanelSettingsScreen(nav)
                    "history" -> HistoryScreen(nav)
                    "permissions" -> PermissionsScreen(nav)
                    "privacy" -> PrivacyScreen(nav)
                    "browser" -> BrowserScreen(nav)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("url")?.let { com.aiquickassist.ui.BrowserLaunch.url = it }
        intent.getStringExtra("route")?.let { pendingRoute = it }
    }
}

class Nav(private val stack: MutableList<String>) {
    fun go(route: String) { if (stack.last() != route) stack.add(route) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
}
