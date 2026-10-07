package com.aiquickassist.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.aiquickassist.Nav
import com.aiquickassist.data.*
import com.aiquickassist.engine.*
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val HOME = "https://www.google.com"

private class BrowserTab(val id: Int) {
    var title by mutableStateOf("Nueva pestaña")
    var url by mutableStateOf(HOME)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var progress by mutableIntStateOf(100)
    var web: WebView? = null
}

/** JS: devuelve la selección o, si no hay, las líneas visibles marcando radios/casillas como opciones. */
private const val EXTRACT_JS = """
(function(){
  var s = (window.getSelection() || '').toString();
  if (s && s.trim().length > 0) return JSON.stringify({sel: s, lines: []});
  var opts = {};
  document.querySelectorAll('[role=radio],[role=checkbox],input[type=radio],input[type=checkbox]').forEach(function(e){
    var t = e.getAttribute('aria-label') || (e.labels && e.labels[0] && e.labels[0].innerText) || (e.closest('label') && e.closest('label').innerText) || e.getAttribute('data-value') || '';
    t = (t||'').trim(); if (t) opts[t] = true;
  });
  var lines = (document.body.innerText || '').split('\n').map(function(x){return x.trim();}).filter(function(x){return x.length>0;}).slice(0,400);
  return JSON.stringify({sel: '', lines: lines.map(function(l){return {t:l, o: !!opts[l]};})});
})()
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tabs = remember { mutableStateListOf(BrowserTab(0)) }
    var current by remember { mutableIntStateOf(0) }
    var nextId by remember { mutableIntStateOf(1) }
    val tab = tabs[current.coerceIn(0, tabs.lastIndex)]
    val assist = remember { AssistState() }
    var urlText by remember(tab.id, tab.url) { mutableStateOf(tab.url) }
    var editing by remember { mutableStateOf(false) }

    fun normalize(input: String): String {
        val t = input.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(' ') || !t.contains('.') -> "https://www.google.com/search?q=" + Http.enc(t)
            else -> "https://$t"
        }
    }
    fun load(s: String) { val u = normalize(s); tab.url = u; tab.web?.loadUrl(u) }

    BackHandler(enabled = tab.canBack || tabs.size > 1) {
        if (tab.canBack) tab.web?.goBack() else { tab.web?.destroy(); tabs.remove(tab); current = (tabs.size - 1).coerceAtLeast(0) }
    }
    DisposableEffect(Unit) { onDispose { tabs.forEach { it.web?.destroy() } } }

    fun analyzeSelection() {
        val web = tab.web ?: return
        assist.panel = PanelState.Loading
        web.evaluateJavascript(EXTRACT_JS) { raw ->
            scope.launch {
                val parsed = runCatching {
                    val json = JSONObject(org.json.JSONTokener(raw).nextValue() as String)
                    val sel = json.optString("sel")
                    if (sel.isNotBlank()) QuestionParser.parseText(sel)
                    else {
                        val arr = json.getJSONArray("lines")
                        QuestionParser.parse((0 until arr.length()).map { ScreenLine(arr.getJSONObject(it).getString("t"), arr.getJSONObject(it).optBoolean("o")) })
                    }
                }.getOrNull()
                if (parsed == null) assist.panel = PanelState.Failed("No se encontró texto. Selecciona la pregunta y vuelve a intentar.", false)
                else assist.run { Analyzer.analyze(parsed) }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(C.bg).systemBarsPadding()) {
        // Barra superior
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Salir") }
            OutlinedTextField(
                value = urlText, onValueChange = { urlText = it; editing = true }, singleLine = true,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { editing = false; load(urlText) }),
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Black, unfocusedBorderColor = C.line)
            )
            IconButton(onClick = { tab.web?.reload() }) { Icon(Icons.Default.Refresh, "Recargar") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { tab.web?.goBack() }, enabled = tab.canBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
            IconButton(onClick = { tab.web?.goForward() }, enabled = tab.canForward) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Adelante", modifier = Modifier.rotate(180f)) }
            Spacer(Modifier.weight(1f))
            if (Settings.manualSearch) IconButton(onClick = { assist.searchText = ""; assist.searchOpen = true }) { Icon(Icons.Default.Search, "Buscar") }
            WireButton("Analizar", onClick = ::analyzeSelection)
            Spacer(Modifier.width(4.dp))
        }
        // Pestañas
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { i, t ->
                val sel = i == current
                Row(Modifier.padding(2.dp).border(1.dp, if (sel) Color.Black else C.line, RoundedCornerShape(4.dp)).clickable { current = i }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(t.title.take(16), fontSize = 12.sp)
                    if (tabs.size > 1) Text("  ✕", fontSize = 12.sp, color = C.sub, modifier = Modifier.clickable {
                        t.web?.destroy(); tabs.remove(t); current = current.coerceAtMost(tabs.lastIndex)
                    })
                }
            }
            TextButton(onClick = { tabs.add(BrowserTab(nextId++)); current = tabs.lastIndex }) { Text("+", fontSize = 18.sp, color = Color.Black) }
        }
        if (tab.progress < 100) LinearProgressIndicator(progress = { tab.progress / 100f }, Modifier.fillMaxWidth(), color = Color.Black, trackColor = C.line)
        else HorizontalDivider(color = C.line)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(tab.id) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        WebView(c).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.setSupportZoom(true); settings.builtInZoomControls = true; settings.displayZoomControls = false
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = !r.url.scheme.orEmpty().startsWith("http")
                                override fun onPageStarted(v: WebView, url: String, f: Bitmap?) { tab.url = url }
                                override fun onPageFinished(v: WebView, url: String) {
                                    tab.url = url; tab.canBack = v.canGoBack(); tab.canForward = v.canGoForward()
                                    tab.title = v.title ?: url
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(v: WebView, p: Int) { tab.progress = p }
                                override fun onReceivedTitle(v: WebView, t: String?) { if (!t.isNullOrBlank()) tab.title = t }
                            }
                            tab.web = this
                            loadUrl(tab.url)
                        }
                    },
                    update = { tab.canBack = it.canGoBack(); tab.canForward = it.canGoForward() }
                )
            }
            Column(Modifier.align(Alignment.TopCenter)) {
                if (assist.searchOpen) SearchBar(assist.searchText, { assist.searchText = it }, {
                    val q = assist.searchText.trim(); assist.searchOpen = false
                    scope.launch { assist.run { Analyzer.search(q) } }
                }, { assist.searchOpen = false })
                if (assist.panel is PanelState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color.Black, trackColor = C.line)
                ResultPanel(assist.panel, onClose = { assist.panel = PanelState.None }, onSearch = {
                    assist.searchText = (assist.panel as? PanelState.Done)?.result?.parsed?.question.orEmpty(); assist.searchOpen = true
                }, onConfigureGemini = { assist.panel = PanelState.None; nav.go("gemini") })
            }
        }
    }
}

