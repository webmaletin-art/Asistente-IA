package com.aiquickassist.dofpa.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquickassist.MainActivity
import com.aiquickassist.data.Settings
import com.aiquickassist.dofpa.data.DofpaRepo
import com.aiquickassist.dofpa.data.PassageHit
import com.aiquickassist.dofpa.engine.DofpaBrain
import com.aiquickassist.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DofpaHome(nav: DofpaNav) {
    val docs by DofpaRepo.documents().collectAsState(initial = emptyList())
    Screen("DOFPA", onBack = null) {
        Text("Diccionario del Operador FPA — estudio y consulta del material cargado, sin conexión.",
            fontSize = 14.sp, color = C.sub, modifier = Modifier.padding(16.dp))
        Section("Consultar y aprender")
        Item("Consulta", "preguntá con tus palabras", onClick = { nav.go("chat") })
        Item("Buscar", "definiciones, leyes, artículos…", onClick = { nav.go("search") })
        Item("Academia", "próxima etapa", onClick = { nav.go("academia") })
        Item("Modo Guía", "próxima etapa", onClick = { nav.go("guia") })
        Item("Actas inteligentes", "próxima etapa", onClick = { nav.go("actas") })
        Item("Mi Cuaderno", "tus notas de estudio", onClick = { nav.go("notebook") })
        Section("Material")
        Item("Biblioteca", "${docs.size} documentos · importar", onClick = { nav.go("library") })
        Item("Ajustes de DOFPA", onClick = { nav.go("settings") })
        if (docs.any { it.kind == "ficticio" })
            Text("Hay documentos de PRUEBA cargados. Importá tu material y borralos desde Biblioteca.",
                fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(16.dp))
    }
}

@Composable
fun DofpaLibraryScreen(nav: DofpaNav, onImport: () -> Unit) {
    val docs by DofpaRepo.documents().collectAsState(initial = emptyList())
    Screen("Biblioteca", nav::back) {
        WireButton("Importar documentos (PDF, DOCX, imágenes…)", Modifier.fillMaxWidth().padding(16.dp), onClick = onImport)
        if (DofpaRepo.importing) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = C.text)
            Spacer(Modifier.width(8.dp)); Text(DofpaRepo.importStatus, fontSize = 13.sp, color = C.sub)
        }
        Text("Tus documentos se procesan en el teléfono y no salen de él. Nada se sube a Internet al importar.",
            fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(16.dp))
        if (docs.any { it.kind == "ficticio" })
            WireButton("Borrar documentos de prueba", Modifier.padding(horizontal = 16.dp)) { DofpaRepo.removeDemo() }
        Section("Documentos (${docs.size})")
        docs.forEach { d ->
            val st = when (d.status) { "OK" -> ""; "PARCIAL" -> " · parcial"; else -> " · error" }
            Item(d.title, subtitle = "${d.subject ?: d.kind} · ${d.passages} fragmentos$st" + (d.note?.let { " · $it" } ?: ""),
                onClick = { if (d.passages > 0) nav.go("doc:${d.id}") })
        }
    }
}

@Composable
fun DocumentScreen(nav: DofpaNav, docId: Long) {
    var passages by remember { mutableStateOf<List<com.aiquickassist.dofpa.data.DofpaPassage>>(emptyList()) }
    var title by remember { mutableStateOf("Documento") }
    LaunchedEffect(docId) {
        withContext(Dispatchers.IO) {
            title = DofpaRepo.document(docId)?.title ?: "Documento"
            passages = DofpaRepo.passagesOf(docId)
        }
    }
    Screen(title, nav::back) {
        val ctx = LocalContext.current
        passages.forEach { p ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                val anchor = buildString { if (p.page > 0) append("p. ${p.page}"); if (!p.section.isNullOrBlank()) { if (isNotEmpty()) append(" · "); append(p.section) } }
                if (anchor.isNotBlank()) Text(anchor, fontSize = 11.sp, color = C.sub)
                Text(p.text, fontSize = 14.sp)
            }
            HorizontalDivider(color = C.line)
        }
        if (passages.isEmpty()) Text("Sin texto indexado.", color = C.sub, modifier = Modifier.padding(16.dp))
    }
}

@Composable
fun DofpaSearchScreen(nav: DofpaNav) {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PassageHit>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run() { scope.launch { hits = withContext(Dispatchers.IO) { DofpaRepo.search(q) }; searched = true } }
    Screen("Buscar", nav::back) {
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Buscar en el material…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() })
        )
        if (searched && hits.isEmpty()) Text("Sin resultados en el material cargado.", color = C.sub, modifier = Modifier.padding(16.dp))
        hits.forEach { h ->
            Column(Modifier.fillMaxWidth().clickable { nav.go("doc:${h.docId}") }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(h.title + (if (h.page > 0) " · p. ${h.page}" else "") + (h.section?.let { " · $it" } ?: ""), fontSize = 12.sp, color = C.sub)
                Text(com.aiquickassist.engine.TextUtil.firstSentences(h.text, 180), fontSize = 14.sp)
            }
            HorizontalDivider(color = C.line)
        }
    }
}

@Composable
fun DofpaSettingsScreen(nav: DofpaNav) {
    val ctx = LocalContext.current
    var engine by remember { mutableStateOf(Settings.dofpaEngine) }
    Screen("Ajustes de DOFPA", nav::back) {
        Section("Motor de respuesta")
        listOf(
            "local" to "Solo material local (sin conexión, sin créditos)",
            "gemini" to "Gemini con los fragmentos del material",
            "auto" to "Automático: Gemini y, si falla, material local"
        ).forEach { (key, desc) ->
            RadioItem(desc, engine == key) { engine = key; Settings.dofpaEngine = key }
        }
        WireButton("Configurar Gemini", Modifier.padding(16.dp)) {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("route", "gemini")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Section("Internet")
        SwitchItem("Permitir contrastar con Internet", Settings.dofpaInternet,
            "Por defecto DOFPA responde solo con el material de la Academia. Activa el botón para abrir Google AI Mode con la consulta preparada.") { Settings.dofpaInternet = it }
        Section("Chat")
        SwitchItem("Respuesta rápida", Settings.dofpaQuickAnswer, "Respuesta breve primero; ampliás si querés") { Settings.dofpaQuickAnswer = it }
        Text("DOFPA es apoyo documental de estudio, no autoridad. Verificá con la fuente oficial y revisión humana antes de cualquier uso real.",
            fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(16.dp))
    }
}

@Composable
fun DofpaStub(nav: DofpaNav, route: String) {
    val name = when (route) { "academia" -> "Academia"; "guia" -> "Modo Guía"; "actas" -> "Actas inteligentes"; else -> route }
    Screen(name, nav::back) {
        Text("$name estará disponible en una próxima etapa. El material que importes ya queda indexado y disponible para Consulta y Buscar.",
            fontSize = 14.sp, color = C.sub, modifier = Modifier.padding(16.dp))
    }
}
