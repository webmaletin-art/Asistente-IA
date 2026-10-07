package com.aiquickassist.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquickassist.data.*
import com.aiquickassist.engine.PanelState
import kotlinx.coroutines.delay

/** Panel de respuesta: mínimo ("✓ B") y ampliable. Se usa en la burbuja y en el navegador. */
@Composable
fun ResultPanel(
    state: PanelState,
    onClose: () -> Unit,
    onSearch: () -> Unit,
    onConfigureGemini: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: androidx.compose.ui.unit.Dp = 520.dp
) {
    if (state is PanelState.None || state is PanelState.Loading) return
    var expanded by remember(state) { mutableStateOf(false) }

    if (state is PanelState.Done && Settings.autoClose && !expanded) {
        LaunchedEffect(state, expanded) { delay(Settings.autoCloseSeconds * 1000L); onClose() }
    }

    Column(
        modifier.fillMaxWidth().padding(8.dp)
            .border(1.dp, Color0, RoundedCornerShape(6.dp)).background(C.bg, RoundedCornerShape(6.dp))
            .animateContentSize(androidx.compose.animation.core.tween(120))
            .pointerInput(state) {
                detectVerticalDragGestures { _, dy -> if (dy < -12) expanded = true else if (dy > 12) expanded = false }
            }
    ) {
        when (state) {
            is PanelState.Failed -> {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("✕", color = C.err, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text(state.message, Modifier.weight(1f), fontSize = 15.sp)
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cerrar") }
                }
                if (state.needsGemini) WireButton("Configurar Gemini", Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), onClick = onConfigureGemini)
            }
            is PanelState.Done -> DoneContent(state.result, expanded, { expanded = !expanded }, onClose, onSearch)
            else -> {}
        }
    }
}

private val Color0 = C.line

@Composable
private fun DoneContent(r: AnalysisResult, expanded: Boolean, toggle: () -> Unit, onClose: () -> Unit, onSearch: () -> Unit) {
    val b = r.best
    val head = if (b.choice.isNotEmpty()) "✓ " + b.choice.joinToString(", ") else if (b.confidence > 0) "✓" else "?"
    val headColor = if (b.choice.isNotEmpty() || b.confidence > 0) C.ok else C.sub
    val uri = LocalUriHandler.current

    Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(head, color = headColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (!expanded) Text(b.answer.ifBlank { r.note.orEmpty() }, fontSize = 15.sp, maxLines = 2)
        }
        if (Settings.manualSearch) IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Buscar") }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cerrar") }
    }
    if (!expanded) return

    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 14.dp)) {
        HorizontalDivider(color = C.line)
        Label("Pregunta"); Text(r.parsed.question.ifBlank { "—" }, fontSize = 15.sp)
        if (r.parsed.options.isNotEmpty()) r.parsed.options.forEach { Text("${it.label}. ${it.text}", fontSize = 14.sp, color = C.sub) }
        Text("Tipo: ${r.parsed.type.label} · Motor: ${r.engineLabel}", fontSize = 12.sp, color = C.sub)

        Label("Respuesta")
        Text((b.choice.joinToString(", ").let { if (it.isNotEmpty()) "$it. " else "" }) + b.answer, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (r.note != null) Text(r.note, fontSize = 13.sp, color = C.err, modifier = Modifier.padding(top = 4.dp))

        if (Settings.showExplanation && b.explanation.isNotBlank()) { Label("Explicación"); Text(b.explanation, fontSize = 14.sp) }

        if (Settings.showComplementary) {
            val collapsible = r.mode == EngineMode.BOTH
            r.web?.let { EngineBlock("VISIÓN GENERAL CREADA POR IA", it, collapsible) }
            r.gemini?.let { EngineBlock("GEMINI", it, collapsible) }
        }
        if (r.mode == EngineMode.BOTH && r.web != null && r.gemini != null) { Label("MEJOR RESPUESTA"); Text(b.choice.joinToString(", ") + " " + b.answer, fontSize = 14.sp) }

        if (Settings.showSources && b.sources.isNotEmpty()) {
            Label("Fuentes")
            b.sources.filter { it.url.isNotBlank() }.forEach {
                Text(it.title, fontSize = 14.sp, color = C.blue, modifier = Modifier.clickable { runCatching { uri.openUri(it.url) } }.padding(vertical = 2.dp))
            }
        }
        if (Settings.showComplementary && r.related.isNotEmpty()) {
            Label(if (r.manual) "Resultado de búsqueda" else "Información relacionada")
            r.related.take(5).forEach {
                Text(it.title, fontSize = 14.sp, color = C.blue, modifier = Modifier.clickable { if (it.url.isNotBlank()) runCatching { uri.openUri(it.url) } }.padding(top = 4.dp))
                if (it.snippet.isNotBlank()) Text(it.snippet.take(160), fontSize = 12.sp, color = C.sub)
            }
        }
    }
}

@Composable
private fun EngineBlock(title: String, b: Block, collapsible: Boolean) {
    var open by remember { mutableStateOf(!collapsible) }
    Row(Modifier.fillMaxWidth().clickable(enabled = collapsible) { open = !open }.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 12.sp, color = C.sub, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (collapsible) Text(if (open) "−" else "+", fontSize = 18.sp, color = C.sub)
    }
    if (open) {
        Text((b.choice.joinToString(", ").let { if (it.isNotEmpty()) "$it. " else "" }) + b.answer, fontSize = 14.sp)
        if (b.explanation.isNotBlank() && b.explanation != b.answer) Text(b.explanation, fontSize = 13.sp, color = C.sub)
        b.sources.filter { it.url.isNotBlank() }.take(2).forEach { Text("Fuente: ${it.title}", fontSize = 12.sp, color = C.sub) }
    }
}

@Composable
private fun Label(t: String) = Text(t.uppercase(), fontSize = 12.sp, color = C.sub, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))

/** Barra de búsqueda manual: oculta salvo cuando se toca la lupa. */
@Composable
fun SearchBar(text: String, onText: (String) -> Unit, onSubmit: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier.fillMaxWidth().padding(8.dp).border(1.dp, C.line, RoundedCornerShape(6.dp)).background(C.bg, RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
        Icon(Icons.Default.Search, null, tint = C.sub)
        TextField(
            value = text, onValueChange = onText, modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text("¿Qué quieres saber?") }, singleLine = true,
            colors = TextFieldDefaults.colors(focusedContainerColor = C.bg, unfocusedContainerColor = C.bg,
                focusedIndicatorColor = C.bg, unfocusedIndicatorColor = C.bg),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (text.isNotBlank()) onSubmit() })
        )
        if (text.isNotEmpty()) IconButton(onClick = { onText("") }) { Icon(Icons.Default.Close, "Borrar") }
    }
}
