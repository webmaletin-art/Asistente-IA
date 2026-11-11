package com.aiquickassist.dofpa.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.aiquickassist.dofpa.data.CorpusRepo
import com.aiquickassist.dofpa.data.DofpaRepo
import com.aiquickassist.dofpa.engine.ChatContext
import com.aiquickassist.ui.AppTheme
import kotlinx.coroutines.launch

/** Punto de entrada del módulo DOFPA. Aislado de AI Quick Assist; comparte tema y motores. */
class DofpaActivity : ComponentActivity() {
    /** Archivo recibido por "Compartir" / "Abrir con": espera la confirmación del usuario. */
    private var incoming by mutableStateOf<Uri?>(null)

    private fun readIncoming(i: Intent?) {
        i ?: return
        incoming = when (i.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                                  else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readIncoming(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readIncoming(intent)
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

                incoming?.let { uri ->
                    val name = remember(uri) { displayName(ctx, uri) }
                    AlertDialog(
                        onDismissRequest = { incoming = null },
                        title = { Text("¿Importar este archivo?") },
                        text = { Text("«$name» se instalará como corpus DOFPA. El corpus actual se guarda como copia de seguridad (podés restaurarlo desde Biblioteca). Tus documentos sueltos se conservan.") },
                        confirmButton = { TextButton(onClick = {
                            incoming = null; nav.go("library")
                            scope.launch { Toast.makeText(ctx, CorpusRepo.importPackage(ctx, uri), Toast.LENGTH_LONG).show() }
                        }) { Text("Importar") } },
                        dismissButton = { TextButton(onClick = { incoming = null }) { Text("Cancelar") } }
                    )
                }

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

private fun displayName(ctx: android.content.Context, uri: Uri): String = runCatching {
    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment ?: "archivo"

class LibraryActions(val importPackage: () -> Unit, val importDocs: () -> Unit)

class DofpaNav(private val stack: MutableList<String>) {
    fun go(route: String) { if (stack.last() != route) stack.add(route) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}
