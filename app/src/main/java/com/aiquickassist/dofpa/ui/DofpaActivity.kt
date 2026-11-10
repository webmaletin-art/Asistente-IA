package com.aiquickassist.dofpa.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.aiquickassist.data.Settings
import com.aiquickassist.dofpa.data.DofpaRepo
import com.aiquickassist.ui.AppTheme
import kotlinx.coroutines.launch

/** Punto de entrada del módulo DOFPA. Aislado de AI Quick Assist; comparte tema y motores. */
class DofpaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DofpaRepo.init(this)
        setContent {
            AppTheme {
                val ctx = LocalContext.current
                val scope = rememberCoroutineScope()
                val stack = remember { mutableStateListOf("home") }
                val nav = remember { DofpaNav(stack) }

                // Importador de documentos del usuario (Storage Access Framework)
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
                    if (uris.isNotEmpty()) scope.launch {
                        val res = DofpaRepo.import(ctx, uris)
                        val ok = res.count { it.status != "ERROR" }
                        val err = res.size - ok
                        Toast.makeText(ctx, "Importados $ok · con error $err", Toast.LENGTH_LONG).show()
                    }
                }
                val onImport = { picker.launch(arrayOf("application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "text/*", "image/*")) }

                // Siembra de datos de prueba la primera vez (hasta que el usuario cargue su material)
                LaunchedEffect(Unit) { scope.launch { runCatching { DofpaRepo.seedDemoIfEmpty() } } }

                BackHandler(enabled = stack.size > 1) { nav.back() }
                when (val route = stack.last()) {
                    "home" -> DofpaHome(nav)
                    "chat" -> DofpaChatScreen(nav)
                    "search" -> DofpaSearchScreen(nav)
                    "library" -> DofpaLibraryScreen(nav, onImport)
                    "notebook" -> NotebookScreen(nav)
                    "settings" -> DofpaSettingsScreen(nav)
                    else -> if (route.startsWith("doc:")) DocumentScreen(nav, route.removePrefix("doc:").toLong())
                    else DofpaStub(nav, route)
                }
            }
        }
    }
}

class DofpaNav(private val stack: MutableList<String>) {
    fun go(route: String) { if (stack.last() != route) stack.add(route) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}
