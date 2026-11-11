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
import com.aiquickassist.dofpa.data.CorpusRepo
import com.aiquickassist.dofpa.data.DofpaRepo
import com.aiquickassist.dofpa.engine.ChatContext
import com.aiquickassist.ui.AppTheme
import kotlinx.coroutines.launch

/** Punto de entrada del módulo DOFPA. Aislado de AI Quick Assist; comparte tema y motores. */
class DofpaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DofpaRepo.init(this)
        CorpusRepo.init(this)
        setContent {
            AppTheme {
                val ctx = LocalContext.current
                val scope = rememberCoroutineScope()
                val stack = remember { mutableStateListOf("home") }
                val nav = remember { DofpaNav(stack) }
                val chat = remember { ChatContext() }

                // Paquete maestro (.dofpa) generado a partir de todo el material
                val packagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri != null) scope.launch {
                        val msg = CorpusRepo.importPackage(ctx, uri)
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
                }
                // Documentos sueltos (PDF, DOCX, PPTX, TXT, imágenes)
                val docsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
                    if (uris.isNotEmpty()) scope.launch {
                        val res = CorpusRepo.importDocuments(ctx, uris)
                        Toast.makeText(ctx, "Importados ${res.count { it.status != "ERROR" }} · con error ${res.count { it.status == "ERROR" }}", Toast.LENGTH_LONG).show()
                    }
                }
                val actions = LibraryActions(
                    importPackage = { packagePicker.launch(arrayOf("*/*")) },
                    importDocs = { docsPicker.launch(arrayOf("application/pdf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation", "text/*", "image/*")) }
                )

                LaunchedEffect(Unit) { CorpusRepo.bootstrap() }

                BackHandler(enabled = stack.size > 1) { nav.back() }
                val route = stack.last()
                when {
                    route == "home" -> DofpaHome(nav)
                    route == "chat" -> DofpaChatScreen(nav, chat)
                    route == "search" -> DofpaSearchScreen(nav)
                    route == "library" -> DofpaLibraryScreen(nav, actions)
                    route == "notebook" -> NotebookScreen(nav, chat)
                    route == "settings" -> DofpaSettingsScreen(nav)
                    route == "academia" -> AcademiaScreen(nav)
                    route.startsWith("doc:") -> {
                        val parts = route.removePrefix("doc:").split(':')
                        DocumentScreen(nav, parts[0].toLong(), parts.getOrNull(1)?.toIntOrNull() ?: 0)
                    }
                    else -> DofpaStub(nav, route)
                }
            }
        }
    }
}

class LibraryActions(val importPackage: () -> Unit, val importDocs: () -> Unit)

class DofpaNav(private val stack: MutableList<String>) {
    fun go(route: String) { if (stack.last() != route) stack.add(route) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}
