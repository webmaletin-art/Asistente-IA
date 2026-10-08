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
    onOpenGoogle: (String) -> Unit,
    onAlternate: ((Boolean) -> Unit)? = null,
    altBusy: Boolean = false,
    modifier: Modifier = Modifier,
    maxHeight: androidx.compose.ui.unit.Dp = 520.dp
) {
    if (state is PanelState.None || state is PanelState.Loading) return
    var expanded by remember(state) { mutableStateOf(false) }

    if (state is PanelState.Done && Settings.autoClose && !expanded && !altBusy) {
        LaunchedEffect(state, expanded, altBusy) { delay(Settings.autoCloseSeconds * 1000L); onClose() }
    }

    Column(
        modifier.fillMaxWidth().padding(8.dp)
            .border(1.dp, PAL.line, RoundedCornerShape(6.dp)).background(PAL.bg, RoundedCornerShape(6.dp))
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
                Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    if (state.needsGemini) WireButton("Configurar Gemini", onClick = onConfigureGemini)
                    if (state.url != null) WireButton(state.urlLabel ?: "Ver resultados de Google") { onOpenGoogle(state.url) }
                }
            }
            is PanelState.Done -> DoneContent(state.result, expanded, { expanded = !expanded }, onClose, onSearch, onOpenGoogle, onAlternate, altBusy)
            else -> {}
        }
    }
}


@Composable
private fun DoneContent(r: AnalysisResult, expanded: Boolean, toggle: () -> Unit, onClose: () -> Unit, onSearch: () -> Unit, onOpenGoogle: (String) -> Unit, onAlternate: ((Boolean) -> Unit)?, altBusy: Boolean) {
    val b = r.best
    val head = if (b.choice.isNotEmpty()) "✓ " + b.choice.joinToString(", ") else if (b.confidence > 0) "✓" else "?"
    val headColor = if (b.choice.isNotEmpty() || b.confidence > 0) C.ok else PAL.sub
    val uri = LocalUriHandler.current

    Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(head, color = headColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(b.origin.label, fontSize = 12.sp, color = PAL.sub)
            if (!expanded) Text(b.answer.ifBlank { r.note.orEmpty() }, fontSize = 15.sp, maxLines = 3)
        }
        if (onAlternate != null && !(r.google != null && r.gemini != null)) {
            val o = r.best.origin
            // Segunda opinión, solo si hace falta: «G» = Gemini (usa tu cuota) · «AI» = Google AI Mode (sin créditos)
            if (o == Origin.GOOGLE_AI_OVERVIEW || o == Origin.GOOGLE_AI_MODE) MiniBtn(if (altBusy) "…" else "G", !altBusy) { onAlternate(true) }
            if (o == Origin.GEMINI || o == Origin.GOOGLE_AI_OVERVIEW) MiniBtn(if (altBusy) "…" else "AI", !altBusy) { onAlternate(false) }
        }
        if (Settings.manualSearch) IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Buscar") }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cerrar") }
    }
    if (r.googleUrl != null && Settings.openInGoogle) {
        WireButton(if (r.best.origin == Origin.GOOGLE_AI_MODE || r.google?.origin == Origin.GOOGLE_AI_MODE) "Abrir Google AI Mode" else "Ver en Google",
            Modifier.padding(start = 14.dp, bottom = 10.dp)) { onOpenGoogle(r.googleUrl) }
    }
    if (!expanded) return

    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 14.dp)) {
        HorizontalDivider(color = PAL.line)
        Label("Pregunta"); Text(r.parsed.question.ifBlank { "—" }, fontSize = 15.sp)
        if (r.parsed.options.isNotEmpty()) r.parsed.options.forEach { Text("${it.label}. ${it.text}", fontSize = 14.sp, color = PAL.sub) }
        Text("Tipo: ${r.parsed.type.label} · Motor: ${r.engineLabel}", fontSize = 12.sp, color = PAL.sub)

        Label("Respuesta")
        Text((b.choice.joinToString(", ").let { if (it.isNotEmpty()) "$it. " else "" }) + b.answer, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (r.note != null) Text(r.note, fontSize = 13.sp, color = C.err, modifier = Modifier.padding(top = 4.dp))

        if (Settings.showExplanation && b.explanation.isNotBlank()) { Label("Explicación"); Text(b.explanation, fontSize = 14.sp) }

        if (Settings.showComplementary && r.google != null && r.gemini != null) {
            r.google?.let { EngineBlock(it.title.uppercase(), it) }
            r.gemini?.let { EngineBlock("GEMINI", it) }
        }

        if (Settings.showSources && b.sources.isNotEmpty()) {
            Label("Fuentes")
            b.sources.filter { it.url.isNotBlank() }.forEach {
                Text(it.title, fontSize = 14.sp, color = C.blue, modifier = Modifier.clickable { runCatching { uri.openUri(it.url) } }.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun MiniBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 4.dp).border(1.dp, PAL.line, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 7.dp, vertical = 3.dp)
    ) { Text(label, fontSize = 12.sp, color = PAL.sub, fontWeight = FontWeight.Medium) }
}

@Composable
private fun EngineBlock(title: String, b: Block) {
    Text("$title:", fontSize = 12.sp, color = PAL.sub, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp))
    run {
        Text((b.choice.joinToString(", ").let { if (it.isNotEmpty()) "$it. " else "" }) + b.answer, fontSize = 14.sp)
        if (b.explanation.isNotBlank() && b.explanation != b.answer) Text(b.explanation.take(900), fontSize = 13.sp, color = PAL.sub)
        val uri = LocalUriHandler.current
        b.sources.filter { it.url.isNotBlank() }.take(3).forEach { Text(it.title, fontSize = 12.sp, color = C.blue, modifier = Modifier.clickable { runCatching { uri.openUri(it.url) } }) }
    }
}

@Composable
private fun Label(t: String) = Text(t.uppercase(), fontSize = 12.sp, color = PAL.sub, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))

/** Barra de búsqueda manual: oculta salvo cuando se toca la lupa. */
@Composable
fun SearchBar(text: String, onText: (String) -> Unit, onSubmit: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier.fillMaxWidth().padding(8.dp).border(1.dp, PAL.line, RoundedCornerShape(6.dp)).background(PAL.bg, RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
        Icon(Icons.Default.Search, null, tint = PAL.sub)
        TextField(
            value = text, onValueChange = onText, modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text("¿Qué quieres saber?") }, singleLine = true,
            colors = TextFieldDefaults.colors(focusedContainerColor = PAL.bg, unfocusedContainerColor = PAL.bg,
                focusedIndicatorColor = PAL.bg, unfocusedIndicatorColor = PAL.bg,
                focusedTextColor = PAL.fg, unfocusedTextColor = PAL.fg),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (text.isNotBlank()) onSubmit() })
        )
        if (text.isNotEmpty()) IconButton(onClick = { onText("") }) { Icon(Icons.Default.Close, "Borrar") }
    }
}
