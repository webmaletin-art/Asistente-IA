package com.aiquickassist.dofpa.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
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
import com.aiquickassist.dofpa.data.DofpaNote
import com.aiquickassist.dofpa.data.DofpaDb
import com.aiquickassist.dofpa.engine.DofpaAnswer
import com.aiquickassist.dofpa.engine.DofpaBrain
import com.aiquickassist.dofpa.engine.DofpaOrigin
import com.aiquickassist.ui.C
import com.aiquickassist.ui.Screen
import com.aiquickassist.ui.WireButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Msg(val user: Boolean, val text: String, val answer: DofpaAnswer? = null)

@Composable
fun DofpaChatScreen(nav: DofpaNav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgs = remember { mutableStateListOf<Msg>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun send(text: String) {
        val q = text.trim(); if (q.isEmpty() || busy) return
        msgs.add(Msg(true, q)); input = ""; busy = true
        scope.launch {
            val a = withContext(Dispatchers.IO) { runCatching { DofpaBrain.ask(q) }.getOrElse { DofpaAnswer("Error: ${it.message}", DofpaOrigin.NONE, emptyList(), emptyList()) } }
            msgs.add(Msg(false, a.text, a)); busy = false
            listState.animateScrollToItem(msgs.size)
        }
    }

    Screen("Consulta", nav::back) {
        if (msgs.isEmpty()) Text("Preguntá con tus palabras sobre el material cargado. DOFPA responde citando la fuente.",
            fontSize = 14.sp, color = C.sub, modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(12.dp)) {
            items(msgs) { m ->
                if (m.user) UserBubble(m.text) else AssistantBubble(m.answer!!, onSuggest = { input = it }, ctx = ctx)
                Spacer(Modifier.height(10.dp))
            }
            if (busy) item { Text("Consultando…", fontSize = 13.sp, color = C.sub, modifier = Modifier.padding(8.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Escribí tu consulta…") }, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) })
            )
            IconButton(onClick = { send(input) }, enabled = !busy) { Icon(Icons.Default.Send, "Enviar") }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(text, fontSize = 15.sp, modifier = Modifier.background(C.surface, RoundedCornerShape(8.dp)).padding(10.dp))
    }
}

@Composable
private fun AssistantBubble(a: DofpaAnswer, onSuggest: (String) -> Unit, ctx: Context) {
    var expanded by remember { mutableStateOf(!Settings.dofpaQuickAnswer) }
    var showSug by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(a.origin.label.uppercase(), fontSize = 10.sp, color = C.sub)
        val body = if (Settings.dofpaQuickAnswer && !expanded) com.aiquickassist.engine.TextUtil.firstSentences(a.text, 220) else a.text
        Text(body, fontSize = 15.sp)
        if (a.text.length > body.length) Text(if (expanded) "Ver menos" else "Ver más", fontSize = 13.sp, color = C.blue,
            modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 2.dp))
        if (a.note != null) Text(a.note, fontSize = 12.sp, color = C.err, modifier = Modifier.padding(top = 2.dp))
        if (a.cites.isNotEmpty()) {
            Text("Fuentes", fontSize = 11.sp, color = C.sub, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            a.cites.forEach { Text("• ${it.label}", fontSize = 12.sp, color = C.sub) }
        }
        // Acciones
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniAction("Copiar") {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("DOFPA", a.text))
                Toast.makeText(ctx, "Copiado", Toast.LENGTH_SHORT).show()
            }
            MiniAction("Guardar") {
                saveNote(ctx, a)
                Toast.makeText(ctx, "Guardado en Mi Cuaderno", Toast.LENGTH_SHORT).show()
            }
            if (a.aiModeUrl != null) MiniAction("Abrir AI Mode") {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("route", "browser").putExtra("url", a.aiModeUrl)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        // Preguntas sugeridas plegables
        if (a.origin != DofpaOrigin.NONE) {
            Text(if (showSug) "¿Querés profundizar? ▲" else "¿Querés profundizar sobre este tema? ▼",
                fontSize = 13.sp, color = C.blue, modifier = Modifier.clickable { showSug = !showSug }.padding(top = 8.dp))
            if (showSug) suggestions(a).forEach { s ->
                Text("· $s", fontSize = 14.sp, color = C.text,
                    modifier = Modifier.clickable { onSuggest(s) }.padding(vertical = 3.dp))
            }
        }
    }
}

@Composable
private fun MiniAction(label: String, onClick: () -> Unit) {
    Box(Modifier.clickable(onClick = onClick)) {
        Text(label, fontSize = 12.sp, color = C.blue, fontWeight = FontWeight.Medium)
    }
}

/** Preguntas de seguimiento derivadas localmente (sin gastar IA). Se colocan en el campo, no se envían. */
private fun suggestions(a: DofpaAnswer): List<String> {
    val out = mutableListOf("Explicámelo más fácil", "Dame un ejemplo")
    a.cites.firstOrNull()?.section?.let { out.add("¿Qué dice $it?") }
    a.cites.firstOrNull()?.let { out.add("¿En qué parte de \"${it.title}\" está?") }
    if (Settings.dofpaInternet) out.add("¿Esto sigue vigente?")
    return out.distinct().take(5)
}

private fun saveNote(ctx: Context, a: DofpaAnswer) {
    Thread {
        runCatching {
            DofpaDb.get(ctx).notes().insert(
                DofpaNote(
                    title = a.text.take(50).ifBlank { "Nota" },
                    subject = a.cites.firstOrNull()?.title,
                    body = a.text,
                    sources = a.cites.joinToString("\n") { it.label }
                )
            )
        }
    }.start()
}

@Composable
fun NotebookScreen(nav: DofpaNav) {
    val ctx = LocalContext.current
    val notes by DofpaDb.get(ctx).notes().all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Screen("Mi Cuaderno", nav::back) {
        if (notes.isEmpty()) Text("Guardá respuestas o fragmentos desde la Consulta para repasarlos luego.",
            fontSize = 14.sp, color = C.sub, modifier = Modifier.padding(16.dp))
        notes.forEach { n ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("Borrar", fontSize = 12.sp, color = C.err, modifier = Modifier.clickable {
                        scope.launch { withContext(Dispatchers.IO) { DofpaDb.get(ctx).notes().delete(n.id) } }
                    })
                }
                if (!n.subject.isNullOrBlank()) Text(n.subject!!, fontSize = 12.sp, color = C.sub)
                Text(n.body, fontSize = 14.sp, maxLines = 4)
            }
            HorizontalDivider(color = C.line)
        }
    }
}
