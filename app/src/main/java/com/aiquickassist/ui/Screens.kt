package com.aiquickassist.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aiquickassist.Nav
import com.aiquickassist.data.*
import com.aiquickassist.engine.Analyzer
import com.aiquickassist.engine.GeminiEngine
import com.aiquickassist.service.Bridge
import com.aiquickassist.service.OverlayService
import kotlinx.coroutines.launch

/** Cambia cada vez que la app vuelve al primer plano (para refrescar permisos). */
@Composable
private fun resumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    return tick
}

private fun overlayGranted(ctx: Context) = AndroidSettings.canDrawOverlays(ctx)
private fun notifGranted(ctx: Context) =
    Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

// ---------------------------------------------------------------- Home
@Composable
fun HomeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val tick = resumeTick()
    val canOverlay = remember(tick) { overlayGranted(ctx) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Mantiene el servicio acorde con el ajuste
    LaunchedEffect(tick, canOverlay) {
        if (Settings.bubbleEnabled && canOverlay && !OverlayService.running) OverlayService.start(ctx)
    }

    Screen("AI Quick Assist", null) {
        Text("Asistente rápido para analizar contenido", fontSize = 14.sp, color = C.sub, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        WireButton("Navegador", Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { nav.go("browser") }
        Spacer(Modifier.height(8.dp))
        WireButton(if (Settings.bubbleEnabled) "Desactivar burbuja" else "Activar burbuja", Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (Settings.bubbleEnabled) { Settings.bubbleEnabled = false; OverlayService.stop(ctx) }
            else if (!canOverlay) {
                ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            } else {
                if (!notifGranted(ctx)) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                Settings.bubbleEnabled = true; OverlayService.start(ctx)
            }
        }
        Spacer(Modifier.height(8.dp))
        WireButton("Modo test (informe de diagnóstico)", Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (!canOverlay || Bridge.accessibility == null) nav.go("permissions")
            else { Settings.bubbleEnabled = true; OverlayService.start(ctx, OverlayService.ACTION_TEST) }
        }
        Section("Estado")
        Item("Burbuja", if (Settings.bubbleEnabled && canOverlay) "● Activa" else "○ Inactiva")
        Item("Motor", Settings.engineMode.label)
        Item("Gemini", if (SecureStore.hasGeminiKey()) (if (Settings.geminiEnabled) "Configurado" else "Desactivado") else "No configurado")
        Item("Accesibilidad", if (Bridge.accessibility != null) "Activa" else "No activa", onClick = { nav.go("permissions") })
        Spacer(Modifier.height(12.dp))
        WireButton("Configuración", Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { nav.go("settings") }
    }
}

// ---------------------------------------------------------------- Configuración
@Composable
fun SettingsScreen(nav: Nav) = Screen("Configuración", nav::back) {
    Section("Asistente IA")
    Item("Visión general de Google", onClick = { nav.go("web") })
    Item("Google AI Mode", if (Settings.aiModeEnabled) "Activado" else "Desactivado", onClick = { nav.go("googleai") })
    Item("Gemini", if (SecureStore.hasGeminiKey()) "Configurado" else "No configurado", onClick = { nav.go("gemini") })
    Item("Motor de respuesta", Settings.engineMode.label, onClick = { nav.go("engine") })

    Section("Burbuja")
    val ctx = LocalContext.current
    SwitchItem("Mostrar burbuja", Settings.bubbleEnabled) { on ->
        if (on) {
            if (!overlayGranted(ctx)) nav.go("permissions") else { Settings.bubbleEnabled = true; OverlayService.start(ctx) }
        } else { Settings.bubbleEnabled = false; OverlayService.stop(ctx) }
    }
    Item("Tamaño", "${Settings.bubbleSizeDp} dp", onClick = { nav.go("bubble") })
    Item("Transparencia", "${(Settings.bubbleOpacity * 100).toInt()}%", onClick = { nav.go("bubble") })
    Item("Color", onClick = { nav.go("bubble") })
    Item("Estilo", Settings.bubbleStyle.label, onClick = { nav.go("bubble") })
    Item("Modo discreto", if (Settings.discreet) "Sí" else "No", onClick = { nav.go("bubble") })

    Section("Comportamiento")
    SwitchItem("Usar texto que copies a mano", Settings.useClipboard, "Si copias (Copiar) y tocas la burbuja en menos de 60 s, se usa ese texto. No se copia nada solo") { Settings.useClipboard = it }
    SwitchItem("Detectar selección por resaltado", Settings.detectHighlight, "Si el texto marcado no llega por accesibilidad, se localiza el resaltado en una captura y se lee con OCR") { Settings.detectHighlight = it }
    SwitchItem("Respuesta rápida", Settings.quickAnswer, "Mostrar la respuesta al terminar") { Settings.quickAnswer = it }
    SwitchItem("Mostrar explicación", Settings.showExplanation) { Settings.showExplanation = it }
    SwitchItem("Mostrar fuentes", Settings.showSources) { Settings.showSources = it }
    SwitchItem("Cerrar automáticamente", Settings.autoClose, "Tras ${Settings.autoCloseSeconds} s sin interacción") { Settings.autoClose = it }
    SwitchItem("Usar búsqueda manual", Settings.manualSearch, "Lupa en la burbuja y en el panel") { Settings.manualSearch = it }
    SwitchItem("Mostrar resultados complementarios", Settings.showComplementary) { Settings.showComplementary = it }

    Section("Historial")
    Item("Historial", "${HistoryStore.items.size}", onClick = { nav.go("history") })

    Section("Privacidad")
    Item("Permisos", onClick = { nav.go("permissions") })
    Item("Borrar datos", onClick = { nav.go("privacy") })
}

@Composable
fun EngineScreen(nav: Nav) = Screen("Motores de respuesta", nav::back) {
    Spacer(Modifier.height(8.dp))
    EngineMode.entries.forEach { m ->
        RadioItem(
            m.label, Settings.engineMode == m,
            when (m) {
                EngineMode.OVERVIEW -> "Pregunta → Google → Visión general real. Si Google no la muestra, lo dice."
                EngineMode.AI_MODE -> "Google AI Mode para texto e imágenes (usa tu sesión de Google)."
                EngineMode.GEMINI -> "Solo Gemini. Requiere tu propia clave."
                EngineMode.BOTH -> "Google y Gemini por separado, con comparación."
            }
        ) { Settings.engineMode = m }
    }
    if ((Settings.engineMode == EngineMode.GEMINI || Settings.engineMode == EngineMode.BOTH) && !SecureStore.hasGeminiKey()) {
        Text("Gemini no está configurado.", color = C.err, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        WireButton("Configurar Gemini", Modifier.padding(horizontal = 16.dp)) { nav.go("gemini") }
    }
}

@Composable
fun WebEngineScreen(nav: Nav) = Screen("Visión general de Google", nav::back) {
    Text(
        "Busca tu pregunta en Google y muestra la verdadera «Visión general creada por IA» cuando Google la ofrece. " +
            "Si no existe, se indica «Visión general de Google no disponible» y puedes seguir en los resultados de Google. " +
            "Nunca se sustituye por otros buscadores ni se inventa una respuesta.",
        fontSize = 14.sp, color = C.sub, modifier = Modifier.padding(16.dp)
    )
    Section("Búsqueda")
    SwitchItem("Usar búsqueda manual", Settings.manualSearch) { Settings.manualSearch = it }
    SwitchItem("Mostrar fuentes", Settings.showSources) { Settings.showSources = it }
    SwitchItem("Mostrar resultados complementarios", Settings.showComplementary) { Settings.showComplementary = it }
    SwitchItem("Abrir resultados en Google", Settings.openInGoogle, "Botón «Ver en Google» en el panel") { Settings.openInGoogle = it }
    Text("Si Google pide verificación o consentimiento, se abre su página en el navegador de la app para que la completes tú.", fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(16.dp))
}

@Composable
fun GoogleAiScreen(nav: Nav) = Screen("Google AI Mode", nav::back) {
    SwitchItem("Activado", Settings.aiModeEnabled) { Settings.aiModeEnabled = it }
    SwitchItem("Usar imágenes seleccionadas", Settings.aiUseImages, "El recorte real se envía a Google") { Settings.aiUseImages = it }
    SwitchItem("Usar para preguntas visuales", Settings.aiVisual, "Preguntas OCR que dependen de una figura") { Settings.aiVisual = it }
    SwitchItem("Preferir AI Mode para imágenes", Settings.aiPreferImages) { Settings.aiPreferImages = it }
    SwitchItem("Abrir resultados en Google", Settings.openInGoogle) { Settings.openInGoogle = it }
    Text(
        "No requiere clave API. Se usa la página de Google dentro de la app (tu sesión de Google, si inicias sesión en el navegador de la app). " +
            "Si la subida automática de la imagen falla, «Abrir Google AI Mode» la abre con el recorte listo para el selector de archivos.",
        fontSize = 13.sp, color = C.sub, modifier = Modifier.padding(16.dp)
    )
}

// ---------------------------------------------------------------- Gemini
@Composable
fun GeminiScreen(nav: Nav) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(SecureStore.hasGeminiKey()) }
    var masked by remember { mutableStateOf(SecureStore.maskedGeminiKey()) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(Settings.geminiModel) }
    var usedModel by remember { mutableStateOf<String?>(null) }

    Screen("Gemini", nav::back) {
        SwitchItem("Usar Gemini", Settings.geminiEnabled) { Settings.geminiEnabled = it }
        SwitchItem("Usar Gemini si Google no responde", Settings.geminiFallback,
            "Con «Visión general» o «AI Mode», Gemini responde solo cuando Google falla (se etiqueta como Gemini)") { Settings.geminiFallback = it }
        Section("Configuración")
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (configured) Text("Clave guardada: $masked", fontSize = 14.sp, color = C.sub, modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (configured) "Reemplazar clave API" else "Clave API") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Spacer(Modifier.height(8.dp))
            WireButton("Guardar clave", enabled = key.isNotBlank()) {
                SecureStore.saveGeminiKey(key); key = ""
                GeminiEngine.resetModel()
                configured = true; masked = SecureStore.maskedGeminiKey(); status = "Clave guardada de forma segura."; statusOk = true
            }
            Spacer(Modifier.height(8.dp))
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            WireButton("Obtener clave API (Google AI Studio)", Modifier.fillMaxWidth()) { uri.openUri("https://aistudio.google.com/apikey") }
            Text("Documentación: ai.google.dev/gemini-api/docs", fontSize = 12.sp, color = C.blue,
                modifier = Modifier.clickable { uri.openUri("https://ai.google.dev/gemini-api/docs") }.padding(vertical = 6.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model, onValueChange = { model = it; Settings.geminiModel = it.trim().ifEmpty { "auto" }; GeminiEngine.resetModel() }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text("Modelo (auto = el mejor disponible)") }
            )
            if (usedModel != null) Text("En uso: $usedModel", fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(top = 4.dp))
        }
        Section("Estado")
        Text(
            status ?: if (!configured) "No configurado" else "Configurado (sin probar)",
            color = if (status != null && !statusOk) C.err else if (statusOk) C.ok else C.sub,
            fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WireButton(if (busy) "Probando…" else "Probar conexión", Modifier.fillMaxWidth(), enabled = configured && !busy) {
                busy = true; status = null
                scope.launch {
                    val err = GeminiEngine.test()
                    status = err ?: "Conectado"; statusOk = err == null
                    if (err == null) usedModel = runCatching { GeminiEngine.model() }.getOrNull()
                    busy = false
                }
            }
            WireButton("Eliminar configuración", Modifier.fillMaxWidth(), enabled = configured) {
                SecureStore.clearGeminiKey(); Analyzer.clearCache()
                configured = false; masked = ""; status = "Configuración eliminada."; statusOk = false
            }
        }
        Text(
            "Cada usuario usa su propia clave y su propia cuota. La clave se cifra con Android Keystore y nunca se muestra completa.",
            fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(16.dp)
        )
    }
}

// ---------------------------------------------------------------- Permisos
@Composable
fun PermissionsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val tick = resumeTick()
    val overlay = remember(tick) { overlayGranted(ctx) }
    val access = remember(tick) { Bridge.accessibilityEnabled(ctx) }
    val notif = remember(tick) { notifGranted(ctx) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Screen("Permisos", nav::back) {
        PermRow("Mostrar sobre otras apps", "Necesario para dibujar la burbuja flotante.", overlay) {
            ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }
        PermRow("Notificaciones", "Muestra el aviso de la burbuja activa (con Ocultar / Detener).", notif) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Va ANTES de Accesibilidad: Android solo muestra «Permitir configuración restringida» tras un primer intento
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row { Text("Configuración restringida", fontSize = 16.sp, modifier = Modifier.weight(1f)); Text(if (access) "no hace falta" else "paso previo", fontSize = 13.sp, color = C.sub) }
            Text("Si al activar Accesibilidad Android dice «configuración restringida»:\n" +
                "1. Pulsa «Intentar activar Accesibilidad» (saldrá el aviso y entonces aparece el menú ⋮).\n" +
                "2. Pulsa «Abrir información de la app», toca ⋮ arriba a la derecha → «Permitir configuración restringida».\n" +
                "3. Vuelve aquí y activa Accesibilidad.",
                fontSize = 13.sp, color = C.sub, modifier = Modifier.padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WireButton("1. Intentar activar") { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
                WireButton("2. Info de la app") {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }
            }
        }
        HorizontalDivider(color = C.line)
        PermRow("Accesibilidad", "Permite leer el texto visible y capturar la pantalla solo cuando tocas la burbuja.", access) {
            ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        HorizontalDivider(color = C.line)
        Text(
            "La captura de pantalla se realiza con la función oficial del servicio de accesibilidad, sin grabación continua. " +
                "Android puede pedirte confirmar el servicio en Ajustes.",
            fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun PermRow(title: String, desc: String, granted: Boolean, onGrant: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(if (granted) "✓ Concedido" else "Sin conceder", color = if (granted) C.ok else C.sub, fontSize = 13.sp)
        }
        Text(desc, fontSize = 13.sp, color = C.sub, modifier = Modifier.padding(vertical = 4.dp))
        if (!granted) WireButton("Conceder", onClick = onGrant)
    }
    HorizontalDivider(color = C.line)
}

// ---------------------------------------------------------------- Privacidad
@Composable
fun PrivacyScreen(nav: Nav) {
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val ctx = LocalContext.current
    Screen("Borrar datos", nav::back) {
        Text("No se guardan capturas ni se analiza nada en segundo plano.", fontSize = 14.sp, color = C.sub, modifier = Modifier.padding(16.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WireButton("Borrar historial", Modifier.fillMaxWidth()) { confirm = "¿Borrar todo el historial?" to { HistoryStore.clear() } }
            WireButton("Borrar credenciales", Modifier.fillMaxWidth()) { confirm = "¿Eliminar la clave de Gemini?" to { SecureStore.clearGeminiKey() } }
            WireButton("Restablecer configuración", Modifier.fillMaxWidth()) {
                confirm = "¿Restablecer todos los ajustes?" to { Settings.reset(); OverlayService.stop(ctx) }
            }
            WireButton("Borrar todo", Modifier.fillMaxWidth()) {
                confirm = "¿Borrar historial, credenciales y ajustes?" to {
                    HistoryStore.clear(); SecureStore.clearGeminiKey(); Settings.reset(); Analyzer.clearCache(); OverlayService.stop(ctx)
                }
            }
        }
    }
    confirm?.let { (msg, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null }, containerColor = C.bg,
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Borrar", color = C.err) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar", color = androidx.compose.ui.graphics.Color.Black) } }
        )
    }
}

// ---------------------------------------------------------------- Historial
@Composable
fun HistoryScreen(nav: Nav) {
    var open by remember { mutableStateOf<HistoryItem?>(null) }
    val fmt = remember { java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()) }
    Screen("Historial", nav::back) {
        SwitchItem("Guardar historial", Settings.historyEnabled) { Settings.historyEnabled = it }
        if (HistoryStore.items.isEmpty()) Text("Sin elementos.", color = C.sub, modifier = Modifier.padding(16.dp))
        else {
            WireButton("Borrar todo", Modifier.padding(16.dp)) { HistoryStore.clear() }
            HistoryStore.items.toList().forEach { h ->
                Item(h.question.ifBlank { "(sin pregunta)" }.take(80), subtitle = "${fmt.format(java.util.Date(h.time))} · ${h.engine}${if (h.manual) " · búsqueda" else ""}", onClick = { open = h })
            }
        }
    }
    open?.let { h ->
        AlertDialog(
            onDismissRequest = { open = null }, containerColor = C.bg,
            title = { Text(h.question, fontSize = 16.sp) },
            text = {
                Column {
                    h.options.forEach { Text("${it.label}. ${it.text}", fontSize = 14.sp, color = C.sub) }
                    Text("Respuesta", fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(top = 10.dp))
                    Text(h.answer, fontSize = 15.sp)
                    if (h.explanation.isNotBlank()) { Text("Explicación", fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(top = 10.dp)); Text(h.explanation, fontSize = 14.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { HistoryStore.remove(h.id); open = null }) { Text("Borrar", color = C.err) } },
            dismissButton = { TextButton(onClick = { open = null }) { Text("Cerrar", color = androidx.compose.ui.graphics.Color.Black) } }
        )
    }
}

// ---------------------------------------------------------------- Informe del Modo test
@Composable
fun TestReportScreen(nav: Nav) {
    val ctx = LocalContext.current
    val report = com.aiquickassist.service.TestReport.text
    Screen("Informe de test", nav::back) {
        if (report.isBlank()) Text("Aún no hay informe. Inicia el Modo test desde la pantalla principal o el menú de la burbuja.", color = C.sub, modifier = Modifier.padding(16.dp))
        else {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WireButton("Copiar informe") {
                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Informe", report))
                    android.widget.Toast.makeText(ctx, "Informe copiado", android.widget.Toast.LENGTH_SHORT).show()
                }
                WireButton("Compartir") {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Compartir informe")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            Text(report, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}
