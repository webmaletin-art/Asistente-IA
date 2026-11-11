package com.aiquickassist.dofpa.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquickassist.MainActivity
import com.aiquickassist.data.Settings
import com.aiquickassist.dofpa.data.CorpusRepo
import com.aiquickassist.dofpa.search.CorpusDoc
import com.aiquickassist.dofpa.search.CorpusPassage
import com.aiquickassist.dofpa.search.SearchResult
import com.aiquickassist.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun rememberDocs(): List<CorpusDoc> {
    val rev = CorpusRepo.revision
    var docs by remember { mutableStateOf<List<CorpusDoc>>(emptyList()) }
    LaunchedEffect(rev) { docs = withContext(Dispatchers.IO) { runCatching { CorpusRepo.store.documents() }.getOrDefault(emptyList()) } }
    return docs
}

@Composable
fun ProgressBanner() {
    val p = CorpusRepo.progress
    if (!p.running && p.error == null) return
    Column(Modifier.fillMaxWidth().background(C.surface).padding(12.dp)) {
        if (p.running) {
            Text(p.label, fontSize = 13.sp)
            if (p.total > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, Modifier.fillMaxWidth().padding(top = 6.dp), color = C.text, trackColor = C.line)
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = C.text, trackColor = C.line)
        }
        p.error?.let { Text("Error: $it", fontSize = 13.sp, color = C.err) }
    }
}

@Composable
fun DofpaHome(nav: DofpaNav) {
    val docs = rememberDocs()
    Screen("DOFPA", onBack = null) {
        ProgressBanner()
        Text("Diccionario del Operador FPA — estudio y consulta del material, sin conexión.",
            fontSize = 14.sp, color = C.sub, modifier = Modifier.padding(16.dp))
        Section("Consultar")
        Item("Consulta", "preguntá con tus palabras", onClick = { nav.go("chat") })
        Item("Buscar", "leyes, artículos, conceptos, páginas", onClick = { nav.go("search") })
        Section("Aprender")
        Item("Academia", "materias, exámenes, entrenamiento", onClick = { nav.go("academia") })
        Item("Mi Cuaderno", "tus notas de estudio", onClick = { nav.go("notebook") })
        Item("Modo Guía", "próxima etapa", onClick = { nav.go("guia") })
        Item("Actas inteligentes", "próxima etapa", onClick = { nav.go("actas") })
        Section("Material")
        Item("Biblioteca", "${docs.size} documentos · paquete · importar", onClick = { nav.go("library") })
        Item("Ajustes de DOFPA", onClick = { nav.go("settings") })
        if (docs.any { it.source == "demo" })
            Text("Hay documentos de PRUEBA. Importá el paquete DOFPA desde Biblioteca para cargar el material real.",
                fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(16.dp))
    }
}

@Composable
fun DofpaLibraryScreen(nav: DofpaNav, actions: LibraryActions) {
    val docs = rememberDocs()
    val scope = rememberCoroutineScope()
    val busy = CorpusRepo.progress.running
    var version by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(CorpusRepo.revision) { version = withContext(Dispatchers.IO) { runCatching { CorpusRepo.store.meta("corpus_version") }.getOrNull() } }
    Screen("Biblioteca", nav::back) {
        ProgressBanner()
        Section("Paquete maestro")
        Text("El paquete .dofpa contiene todo el material ya procesado (texto completo, páginas, capítulos, artículos). " +
            "Se instala en el almacenamiento privado del teléfono; nada se sube a Internet.",
            fontSize = 13.sp, color = C.sub, modifier = Modifier.padding(horizontal = 16.dp))
        Text("Corpus instalado: ${version ?: "—"} · ${docs.size} documentos · ${docs.sumOf { it.passages }} fragmentos",
            fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WireButton("Importar paquete DOFPA", enabled = !busy, onClick = actions.importPackage)
            if (CorpusRepo.hasBackup()) WireButton("Restaurar anterior", enabled = !busy) { scope.launch { CorpusRepo.restoreBackup() } }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WireButton("Agregar documentos sueltos", enabled = !busy, onClick = actions.importDocs)
            WireButton("Reconstruir índice", enabled = !busy) { scope.launch { CorpusRepo.rebuildIndex() } }
        }
        if (docs.any { it.source == "demo" })
            WireButton("Borrar documentos de prueba", Modifier.padding(horizontal = 16.dp), enabled = !busy) { scope.launch(Dispatchers.IO) { CorpusRepo.removeDemo() } }
        Section("Documentos (${docs.size})")
        docs.groupBy { it.subject ?: "Sin materia" }.toSortedMap().forEach { (subject, ds) ->
            Text(subject, fontSize = 12.sp, color = C.sub, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
            ds.forEach { d ->
                val st = when (d.status) { "OK" -> ""; "PARCIAL" -> " · parcial"; else -> " · error" }
                Item(d.title, subtitle = "${d.pages} pág. · ${d.passages} fragmentos$st" + (d.law?.let { " · Ley ${com.aiquickassist.dofpa.search.SearchEngine.pretty(it)}" } ?: "") +
                    (d.note?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    onClick = { if (d.passages > 0) nav.go("doc:${d.id}") })
            }
        }
    }
}

/** Lector de documento: abre en la página pedida y la resalta. */
@Composable
fun DocumentScreen(nav: DofpaNav, docId: Long, page: Int) {
    var passages by remember { mutableStateOf<List<CorpusPassage>>(emptyList()) }
    var doc by remember { mutableStateOf<CorpusDoc?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(docId) {
        withContext(Dispatchers.IO) {
            doc = CorpusRepo.store.documents().firstOrNull { it.id == docId }
            passages = CorpusRepo.store.passagesOfDoc(docId)
        }
        val idx = passages.indexOfFirst { it.page >= page }.coerceAtLeast(0)
        if (page > 0 && passages.isNotEmpty()) list.scrollToItem(idx)
    }
    Column(Modifier.fillMaxSize().background(C.bg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
            Column(Modifier.weight(1f)) {
                Text(doc?.title ?: "Documento", fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                if (page > 0) Text("Abierto en la página $page", fontSize = 12.sp, color = C.sub)
            }
        }
        HorizontalDivider(color = C.line)
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            itemsIndexed(passages, key = { _, p -> p.id }) { _, p ->
                val hl = page > 0 && p.page == page
                Column(Modifier.fillMaxWidth().background(if (hl) C.surface else C.bg).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val anchor = listOfNotNull(if (p.page > 0) "p. ${p.page}" else null, p.chapter, p.section?.takeIf { it != p.chapter }).joinToString(" · ")
                    if (anchor.isNotBlank()) Text(anchor, fontSize = 11.sp, color = C.sub)
                    Text(p.text, fontSize = 14.sp)
                }
                HorizontalDivider(color = C.line)
            }
        }
    }
}

@Composable
fun DofpaSearchScreen(nav: DofpaNav) {
    var q by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<SearchResult?>(null) }
    val scope = rememberCoroutineScope()
    fun run() { if (q.isNotBlank()) scope.launch { res = withContext(Dispatchers.IO) { CorpusRepo.engine.search(q, limit = 20) } } }
    Screen("Buscar", nav::back) {
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Ley 23.737, artículo 5, requisa…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() })
        )
        res?.let { r ->
            Text("${r.hits.size} resultados · ${r.millis} ms" + (if (r.corrections.isNotEmpty()) " · interpreté: " + r.corrections.entries.joinToString { "${it.key}→${it.value}" } else ""),
                fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(horizontal = 16.dp))
            r.notFound?.let { Text(it, color = C.err, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
            r.hits.forEach { h ->
                Column(Modifier.fillMaxWidth().clickable { nav.go("doc:${h.doc.id}:${h.passage.page}") }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(h.anchor, fontSize = 12.sp, color = C.blue)
                    Text(h.snippet, fontSize = 14.sp)
                }
                HorizontalDivider(color = C.line)
            }
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
            "auto" to "Automático: Gemini → Google AI Mode → material local",
            "gemini" to "Gemini con los fragmentos del material",
            "local" to "Solo material local (sin conexión, sin créditos)"
        ).forEach { (key, desc) -> RadioItem(desc, engine == key) { engine = key; Settings.dofpaEngine = key } }
        WireButton("Configurar y diagnosticar Gemini", Modifier.padding(16.dp)) {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("route", "gemini").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Section("Respaldo y fuentes externas")
        SwitchItem("Si Gemini falla, preguntar a Google AI Mode", Settings.dofpaAiModeFallback,
            "Envía SOLO tu pregunta a Google (nunca el material). La respuesta se marca como fuente externa.") { Settings.dofpaAiModeFallback = it }
        SwitchItem("Permitir contrastar con Internet", Settings.dofpaInternet,
            "Muestra «¿Sigue vigente?» y la opción de abrir Google AI Mode con la consulta preparada.") { Settings.dofpaInternet = it }
        Section("Chat")
        SwitchItem("Respuesta rápida", Settings.dofpaQuickAnswer, "Respuesta breve primero; ampliás con «Ver más»") { Settings.dofpaQuickAnswer = it }
        Text("DOFPA es apoyo documental de estudio, no autoridad. Verificá con la fuente oficial y revisión humana antes de cualquier uso real.",
            fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(16.dp))
    }
}

@Composable
fun DofpaStub(nav: DofpaNav, route: String) {
    val name = when (route) { "guia" -> "Modo Guía"; "actas" -> "Actas inteligentes"; else -> route }
    Screen(name, nav::back) {
        Text("$name está en desarrollo y llegará en una próxima etapa. No está terminado: por eso no muestra contenido.",
            fontSize = 14.sp, color = C.sub, modifier = Modifier.padding(16.dp))
    }
}
