package com.aiquickassist.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.aiquickassist.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

class GoogleAnswer(val block: Block, val url: String)

/**
 * Google real, a través de un WebView normal (la sesión/cookies del propio usuario):
 *  - Visión general: se lee la tarjeta "Visión general creada por IA" de la página de resultados.
 *  - AI Mode: se carga udm=50; con imagen se usa el selector de archivos de la propia página
 *    (onShowFileChooser) entregando el recorte real.
 * No se usan APIs privadas, no se evaden CAPTCHA ni protecciones: si Google los muestra, se ofrece
 * abrir la página para que el usuario continúe. Si no hay respuesta, no se inventa ninguna.
 */
object GoogleEngine {
    private lateinit var app: Context

    @Volatile private var upload: Uri? = null
    @Volatile private var uploadAt = 0L
    @Volatile private var chooserHits = 0

    fun init(c: Context) { app = c.applicationContext }

    /** Diagnóstico de la última operación (lo usa el Modo test). */
    val trace = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var t0 = 0L
    private fun resetTrace() { trace.clear(); t0 = System.currentTimeMillis() }
    private fun t(msg: String) { trace += "+${System.currentTimeMillis() - t0} ms  $msg" }

    /** Recorte listo para el selector de archivos de Google (navegador o sesión interna). Vigente 15 min. */
    fun currentUpload(): Uri? = upload?.takeIf { System.currentTimeMillis() - uploadAt < 15 * 60_000L }
    fun clearUpload() { upload = null }
    fun armUpload(uri: Uri) { upload = uri; uploadAt = System.currentTimeMillis() }

    // ---------------------------------------------------------------- Visión general
    suspend fun overview(query: String): GoogleAnswer = withContext(Dispatchers.Main) {
        val url = GoogleText.searchUrl(query)
        resetTrace(); t("Visión general: consulta «$query»")
        val web = newWebView()
        try {
            load(web, url)
            t("cargada: ${web.url}")
            repeat(16) { n ->
                val r = evalJson(web, OVERVIEW_JS)
                t("lectura #${n + 1}: estado=${r.optString("s").ifEmpty { "(vacío)" }} texto=${r.optString("text").length} car.")
                when (r.optString("s")) {
                    "blocked", "consent" -> throw AnalysisException(
                        "Google pide verificación o consentimiento. Ábrelo para continuar.",
                        googleUrl = url, openLabel = "Ver resultados de Google")
                    "ok" -> GoogleText.cleanOverview(r.optString("text"))?.let { text ->
                        val links = r.optJSONArray("links")
                        val sources = (0 until (links?.length() ?: 0)).mapNotNull {
                            links!!.optJSONObject(it)?.let { o -> Source(o.optString("t").ifBlank { o.optString("u") }, o.optString("u")) }
                        }.distinctBy { it.url }.take(5)
                        return@withContext GoogleAnswer(
                            Block(Origin.GOOGLE_AI_OVERVIEW, answer = TextUtil.firstSentences(text, 450),
                                explanation = text, sources = sources, confidence = 0.8), url)
                    }
                }
                delay(500)
            }
            throw AnalysisException("Visión general de Google no disponible",
                googleUrl = url, openLabel = "Ver resultados de Google")
        } catch (e: TimeoutCancellationException) {
            throw AnalysisException("Google tardó demasiado en responder.", googleUrl = url, openLabel = "Ver resultados de Google")
        } finally { web.destroy() }
    }

    // ---------------------------------------------------------------- AI Mode (texto o imagen real)
    suspend fun aiMode(query: String, image: Uri?): GoogleAnswer = withContext(Dispatchers.Main) {
        val url = GoogleText.aiModeUrl(query)
        resetTrace(); t("AI Mode: consulta «$query» imagen=${image != null}")
        val fail = { msg: String ->
            if (image != null) armUpload(image)       // el navegador ofrecerá este recorte en el selector de Google
            AnalysisException(msg, googleUrl = if (image != null) GoogleText.aiModeUrl("") else url, openLabel = "Abrir Google AI Mode")
        }
        val web = newWebView()
        try {
            if (image != null) {
                armUpload(image)
                chooserHits = 0
                load(web, GoogleText.aiModeUrl(""))
                t("cargada: ${web.url}")
                delay(1500)
                for (attempt in 0 until 3) {
                    val r = evalJson(web, uploadFindJs(attempt))
                    t("botón adjuntar intento ${attempt + 1}: ${r.optString("s")} «${r.optString("l")}» (${r.optDouble("x", -1.0).toInt()},${r.optDouble("y", -1.0).toInt()})")
                    when (r.optString("s")) {
                        "blocked", "consent" -> throw fail("Google pide verificación o inicio de sesión para usar AI Mode.")
                        "found" -> tap(web, r.optDouble("x").toFloat(), r.optDouble("y").toFloat())
                    }
                    var waited = 0
                    while (chooserHits == 0 && waited < 2500) { delay(250); waited += 250 }
                    if (chooserHits > 0) break
                }
                t("selector de archivos solicitado: ${chooserHits} vez/veces")
                if (chooserHits == 0) throw fail("No se pudo enviar la imagen a Google AI Mode automáticamente.")
                delay(3000)                                   // Google procesa la imagen adjunta
                val s = evalString(web, submitJs(query))
                t("enviar: $s")
                if (s == "nobox") throw fail("No se encontró el cuadro de AI Mode.")
            } else {
                load(web, url)
                t("cargada: ${web.url}")
            }
            var last = ""; var stable = 0
            repeat(50) {
                delay(800)
                val r = evalJson(web, AI_TEXT_JS)
                if (r.optString("s") == "blocked" || r.optString("s") == "consent")
                    throw fail("Google pide verificación o inicio de sesión para usar AI Mode.")
                val text = GoogleText.cleanAiMode(r.optString("text"), query)
                t("respuesta: bruto=${r.optString("text").length} limpio=${text?.length ?: 0} estable=$stable")
                if (text != null) {
                    if (text == last) stable++ else { stable = 0; last = text }
                    if (stable >= 3) return@withContext GoogleAnswer(
                        Block(Origin.GOOGLE_AI_MODE, answer = TextUtil.firstSentences(text, 360), explanation = text, confidence = 0.75), url)
                }
            }
            throw fail("No se pudo obtener una respuesta confiable de Google.")
        } catch (e: TimeoutCancellationException) {
            throw fail("Google tardó demasiado en responder.")
        } finally { web.destroy() }
    }

    // ---------------------------------------------------------------- WebView interno
    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView {
        val w = WebView(app)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                chooserHits++
                val u = currentUpload()
                cb.onReceiveValue(if (u != null) arrayOf(u) else null)
                return true
            }
        }
        // Sin ventana: se fuerza un tamaño y visibilidad para que Google renderice como en un móvil
        val d = app.resources.displayMetrics
        val ww = (412 * d.density).toInt(); val hh = (860 * d.density).toInt()
        w.measure(View.MeasureSpec.makeMeasureSpec(ww, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.EXACTLY))
        w.layout(0, 0, ww, hh)
        w.onResume()
        w.dispatchWindowVisibilityChanged(View.VISIBLE)
        return w
    }

    private suspend fun load(w: WebView, url: String) = withTimeout(20_000) {
        suspendCancellableCoroutine { cont ->
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, u: String) { if (cont.isActive) cont.resume(Unit) }
            }
            w.loadUrl(url)
        }
    }

    private suspend fun evalString(w: WebView, js: String): String = suspendCancellableCoroutine { cont ->
        w.evaluateJavascript(js) { raw ->
            val v = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
            if (cont.isActive) cont.resume(v)
        }
    }

    private suspend fun evalJson(w: WebView, js: String): JSONObject =
        runCatching { JSONObject(evalString(w, js)) }.getOrDefault(JSONObject())

    // ---------------------------------------------------------------- JavaScript (lectura de la página)
    private const val GUARD = """
      var u=location.href;
      if(u.indexOf('/sorry/')>=0||document.querySelector('#captcha-form,#recaptcha,iframe[src*=recaptcha]')) return JSON.stringify({s:'blocked'});
      if(u.indexOf('consent.google')>=0||u.indexOf('accounts.google')>=0) return JSON.stringify({s:'consent'});
    """

    /** Busca el encabezado real de la tarjeta de IA y toma su contenedor; no usa ningún otro resultado. */
    private val OVERVIEW_JS = """(function(){ $GUARD
      var re=/^(visi[oó]n general creada por ia|vista general creada por ia|ai overview)$/i, head=null;
      var all=document.querySelectorAll('h1,h2,h3,div,span');
      for(var i=0;i<all.length;i++){var e=all[i]; if(e.children.length<=1 && re.test((e.innerText||'').trim())){head=e;break;}}
      if(!head) return JSON.stringify({s:'none'});
      var box=head, base=(head.innerText||'').length;
      for(var k=0;k<7&&box.parentElement;k++){ box=box.parentElement; if((box.innerText||'').length>base+120) break; }
      [].slice.call(box.querySelectorAll('style,script,noscript,template')).forEach(function(n){n.style.setProperty('display','none','important');});
      var t=(box.innerText||''); if(t.length>5000) return JSON.stringify({s:'none'});
      var links=[].slice.call(box.querySelectorAll('a[href]')).filter(function(a){return /^https?:/.test(a.href)&&a.hostname.indexOf('google.')<0;})
        .slice(0,8).map(function(a){return {t:(a.innerText||a.getAttribute('aria-label')||'').trim().slice(0,80),u:a.href};});
      return JSON.stringify({s:'ok',text:t,links:links});})()"""

    private val AI_TEXT_JS = """(function(){ $GUARD
      var m=document.querySelector('[role=main]')||document.body;
      return JSON.stringify({s:'ok',text:m.innerText||''});})()"""

    /** Devuelve el centro (px de pantalla) del control de adjuntar imagen; el toque real lo da Kotlin. */
    private fun uploadFindJs(attempt: Int): String {
        val re = if (attempt == 0) "(subir|cargar|adjuntar|a[ñn]adir|agregar|imagen|foto|upload|attach|add image|image|photo|lens)"
        else "(subir|upload|archivo|file|galer[ií]a|gallery|dispositivo|device|imagen|image)"
        return """(function(){ $GUARD
          var re=/$re/i, els=[].slice.call(document.querySelectorAll('button,[role=button],[role=menuitem],div[aria-label],span[aria-label],label'));
          for(var i=0;i<els.length;i++){var e=els[i],l=(e.getAttribute('aria-label')||e.innerText||'').trim();
            if(l.length>0&&l.length<60&&re.test(l)){var r=e.getBoundingClientRect(); if(r.width>0&&r.height>0){
              var d=window.devicePixelRatio||1; return JSON.stringify({s:'found',l:l,x:(r.left+r.width/2)*d,y:(r.top+r.height/2)*d});}}}
          return JSON.stringify({s:'nobutton'});})()"""
    }

    /** Toque táctil real (da "activación de usuario" a la página; un click() de JS no abre el selector de archivos). */
    private fun tap(w: WebView, x: Float, y: Float) {
        val t = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(t, t + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
        w.dispatchTouchEvent(down); w.dispatchTouchEvent(up); down.recycle(); up.recycle()
    }

    private fun submitJs(query: String): String = """(function(q){
      var box=document.querySelector('textarea,[contenteditable=true],input[type=text],input[type=search]');
      if(box&&q){box.focus(); if(box.tagName=='TEXTAREA'||box.tagName=='INPUT'){
        Object.getOwnPropertyDescriptor(box.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set.call(box,q);}
        else{box.innerText=q;} box.dispatchEvent(new Event('input',{bubbles:true}));}
      var b=[].slice.call(document.querySelectorAll('button,[role=button]')), re=/(enviar|submit|send|buscar|search)/i;
      for(var i=0;i<b.length;i++){var l=(b[i].getAttribute('aria-label')||'').trim(); if(re.test(l)){b[i].click();return 'clicked';}}
      if(box){box.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true}));return 'enter';}
      return 'nobox';})(${JSONObject.quote(query)})"""
}
