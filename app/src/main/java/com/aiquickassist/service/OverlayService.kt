package com.aiquickassist.service

import android.animation.ValueAnimator
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.aiquickassist.MainActivity
import com.aiquickassist.R
import com.aiquickassist.capture.CropStore
import com.aiquickassist.capture.Ocr
import com.aiquickassist.data.*
import com.aiquickassist.engine.*
import com.aiquickassist.ui.*
import kotlinx.coroutines.*

/** Burbuja flotante y ventanas auxiliares (menú, panel de respuesta, búsqueda, selección OCR). */
class OverlayService : Service() {
    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_TOGGLE_HIDE = "toggle_hide"
        private const val CHANNEL = "bubble"
        @Volatile var running = false

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, OverlayService::class.java)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private lateinit var owner: ServiceLifecycleOwner
    private val state = AssistState()
    private var showPanel by mutableStateOf(false)

    private lateinit var bubble: BubbleView
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private var menuView: View? = null
    private var panelView: View? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var selectionView: View? = null
    private var job: Job? = null
    private var restoreJob: Job? = null
    private var fadeAnim: ValueAnimator? = null
    private var hidden = false
    private val density get() = resources.displayMetrics.density
    private fun px(dp: Int) = (dp * density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        if (!android.provider.Settings.canDrawOverlays(this)) { stopSelf(); return }
        owner = ServiceLifecycleOwner()
        createBubble()
        Bridge.onScroll = { onScroll() }
        scope.launch { snapshotFlow { Settings.bubbleConfig() }.collect { applyConfig(it) } }
        scope.launch { snapshotFlow { Triple(showPanel, state.panel, state.searchOpen) }.collect { syncPanel() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { Settings.bubbleEnabled = false; stopSelf() }
            ACTION_TOGGLE_HIDE -> if (::bubble.isInitialized) setHidden(!hidden)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Bridge.onScroll = null
        if (::owner.isInitialized) {
            job?.cancel(); scope.cancel()
            listOf(menuView, panelView, selectionView).forEach { remove(it) }
            if (::bubble.isInitialized) remove(bubble)
            owner.destroy()
        }
        super.onDestroy()
    }

    // ---------------- Burbuja ----------------
    private fun createBubble() {
        val c = Settings.bubbleConfig()
        val size = px(c.sizeDp)
        bubbleLp = overlayParams(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        val dm = resources.displayMetrics
        bubbleLp.x = if (Settings.bubbleX >= 0) Settings.bubbleX else dm.widthPixels - size
        bubbleLp.y = if (Settings.bubbleY >= 0) Settings.bubbleY else dm.heightPixels / 3
        bubble = BubbleView(this, wm, bubbleLp, ::onTap, ::openMenu) { x, y -> Settings.bubbleX = x; Settings.bubbleY = y }
        bubble.config = c
        wm.addView(bubble, bubbleLp)
    }

    private fun applyConfig(c: BubbleConfig) {
        if (!c.enabled) { stopSelf(); return }
        bubble.config = c
        val size = px(c.sizeDp)
        if (bubbleLp.width != size) { bubbleLp.width = size; bubbleLp.height = size; runCatching { wm.updateViewLayout(bubble, bubbleLp) } }
    }

    private fun setHidden(h: Boolean) {
        hidden = h
        bubble.visibility = if (h) View.GONE else View.VISIBLE
        startAsForeground()
    }

    private fun onScroll() {
        val c = Settings.bubbleConfig()
        if (hidden || (!c.discreet && !c.hideOnScroll)) return
        if (c.discreet) animateFade(0.08f, 180)
        if (c.hideOnScroll) bubble.visibility = View.INVISIBLE
        restoreJob?.cancel()
        restoreJob = scope.launch {
            delay(600)
            if (c.discreet) animateFade(1f, 250)
            if (c.hideOnScroll && c.showOnStop) bubble.visibility = View.VISIBLE
        }
    }

    private fun animateFade(to: Float, ms: Long) {
        fadeAnim?.cancel()
        fadeAnim = ValueAnimator.ofFloat(bubble.fade, to).apply { duration = ms; addUpdateListener { bubble.fade = it.animatedValue as Float }; start() }
    }

    // ---------------- Interacción ----------------
    private fun onTap() {
        when (bubble.status) {
            BubbleStatus.LOADING -> { job?.cancel(); resetIdle() }
            BubbleStatus.DONE, BubbleStatus.ERROR -> if (showPanel) resetIdle() else showPanel = true
            BubbleStatus.IDLE -> runTool(Settings.defaultTool)
        }
    }

    private fun resetIdle() {
        showPanel = false; state.panel = PanelState.None; state.searchOpen = false; bubble.status = BubbleStatus.IDLE
    }

    private fun openMenu() {
        if (menuView != null) return
        val dm = resources.displayMetrics
        val menuW = px(190); val menuH = px(6 * 48 + 8)
        val size = bubbleLp.width
        val left = if (bubbleLp.x + size / 2 < dm.widthPixels / 2) bubbleLp.x + size else bubbleLp.x - menuW
        val top = minOf(bubbleLp.y, dm.heightPixels - menuH - px(24)).coerceAtLeast(px(24))
        val v = owner.compose(this) {
            AppTheme {
                BubbleMenu(Settings.defaultTool, Settings.manualSearch, (left / density).dp, (top / density).dp, ::onMenu, ::closeMenu)
            }
        }
        menuView = v
        wm.addView(v, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS))
    }

    private fun closeMenu() { remove(menuView); menuView = null }

    private fun onMenu(a: MenuAction) {
        closeMenu()
        when (a) {
            MenuAction.TEXT -> { Settings.defaultTool = Tool.TEXT; runTool(Tool.TEXT) }
            MenuAction.OCR -> { Settings.defaultTool = Tool.OCR; runTool(Tool.OCR) }
            MenuAction.IMAGE -> { Settings.defaultTool = Tool.IMAGE; runTool(Tool.IMAGE) }
            MenuAction.SEARCH -> { showPanel = true; state.searchText = ""; state.searchOpen = true }
            MenuAction.BROWSER -> openApp("browser")
            MenuAction.SETTINGS -> openApp("settings")
        }
    }

    private fun openApp(route: String, url: String? = null) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("route", route).putExtra("url", url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ---------------- Herramientas ----------------
    private fun runTool(tool: Tool) {
        resetIdle()
        if (Bridge.accessibility == null) {
            toast("Activa el servicio de accesibilidad para leer la pantalla")
            openApp("permissions"); return
        }
        if (tool == Tool.TEXT) runText() else startSelection(tool)
    }

    private fun runText() {
        val snap = Bridge.readScreen()
        val parsed = snap?.let { s -> s.selected?.let { QuestionParser.parseText(it) } ?: QuestionParser.parse(s.lines) }
        val size = (parsed?.question?.length ?: 0) + (parsed?.options?.sumOf { it.text.length } ?: 0)
        if (parsed == null || size < 8) {
            toast("No hay texto disponible: usando OCR")
            startSelection(Tool.OCR); return
        }
        launchAnalysis { Analyzer.analyze(parsed) }
    }

    private fun launchAnalysis(block: suspend () -> AnalysisResult) {
        job?.cancel()
        showPanel = false
        bubble.status = BubbleStatus.LOADING
        job = scope.launch {
            val ok = state.run(block)
            bubble.status = if (ok) BubbleStatus.DONE else BubbleStatus.ERROR
            showPanel = !ok || Settings.quickAnswer
        }
    }

    private fun startSelection(tool: Tool) {
        job?.cancel()
        job = scope.launch {
            bubble.visibility = View.INVISIBLE
            delay(180)
            val shot = Bridge.capture()
            if (!hidden) bubble.visibility = View.VISIBLE
            if (shot == null) { toast("No se pudo capturar la pantalla"); return@launch }
            showSelection(shot, tool)
        }
    }

    private fun showSelection(shot: Bitmap, tool: Tool) {
        remove(selectionView)
        val v = SelectionView(this, shot, askQuestion = tool == Tool.IMAGE, onCancel = { remove(selectionView); selectionView = null }, onAnalyze = { crop, q ->
            remove(selectionView); selectionView = null
            launchAnalysis { analyzeCrop(crop, tool, q) }
        })
        selectionView = v
        val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(v, lp)
    }

    private val visualRef = Regex("""esta imagen|siguiente imagen|figura|gr[aá]fico|diagrama|tri[aá]ngulo|c[ií]rculo|dibujo|this image|the image|diagram|figure|shown (above|below)""", RegexOption.IGNORE_CASE)

    /**
     * OCR = región → TEXTO → Google Search (el OCR no cambia).
     * IMAGEN = región → RECORTE REAL (archivo + Uri) → Google AI Mode / Gemini. El OCR solo aporta
     * texto auxiliar; la imagen siempre viaja.
     */
    private suspend fun analyzeCrop(crop: Bitmap, tool: Tool, userQuestion: String): AnalysisResult {
        val text = runCatching { Ocr.read(crop) }.getOrDefault("")
        val parsed = QuestionParser.parseText(text, ocr = true)
        if (tool == Tool.OCR) {
            if (parsed == null) throw AnalysisException("No se detectó texto en la selección. Prueba el modo Imagen.")
            val visual = visualRef.containsMatchIn(text) && Settings.aiVisual && Settings.aiModeEnabled && Settings.engineMode != EngineMode.GEMINI
            return if (visual) Analyzer.analyze(parsed, CropImage(crop, CropStore.save(this, crop))) else Analyzer.analyze(parsed)
        }
        val image = CropImage(crop, CropStore.save(this, crop))
        val p = when {
            userQuestion.isNotBlank() -> ParsedQuestion(QType.OPEN, userQuestion, context = text.take(1500), hasImage = true)
            parsed != null && (parsed.options.isNotEmpty() || parsed.question.contains('?')) -> parsed.copy(hasImage = true)
            else -> ParsedQuestion(QType.OPEN, "", context = text.take(1500), hasImage = true)
        }
        return Analyzer.analyze(p, image)
    }

    // ---------------- Panel de respuesta / búsqueda ----------------
    private fun syncPanel() {
        val p = state.panel
        val wanted = state.searchOpen || (showPanel && (p is PanelState.Done || p is PanelState.Failed))
        if (!wanted) { remove(panelView); panelView = null; panelLp = null; return }
        val focusable = state.searchOpen
        panelLp?.let { lp ->
            val f = if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            val newFlags = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or f
            if (newFlags != lp.flags) { lp.flags = newFlags; runCatching { wm.updateViewLayout(panelView, lp) } }
            return
        }
        val v = owner.compose(this) {
            AppTheme {
                Column(Modifier.fillMaxWidth()) {
                    if (state.searchOpen) SearchBar(state.searchText, { state.searchText = it }, ::submitSearch, { state.searchOpen = false; if (state.panel !is PanelState.Done) resetIdle() })
                    ResultPanel(state.panel, onClose = ::resetIdle, onSearch = {
                        val d = state.panel as? PanelState.Done
                        state.searchText = d?.result?.parsed?.question.orEmpty(); state.searchOpen = true
                    }, onConfigureGemini = { resetIdle(); openApp("gemini") }, onOpenGoogle = { u -> resetIdle(); openApp("browser", u) })
                }
            }
        }
        val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        lp.y = px(28)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        panelView = v; panelLp = lp
        wm.addView(v, lp)
    }

    private fun submitSearch() {
        val q = state.searchText.trim()
        if (q.isEmpty()) return
        state.searchOpen = false
        launchAnalysis { Analyzer.search(q) }
    }

    private fun remove(v: View?) { if (v != null) runCatching { wm.removeView(v) } }

    // ---------------- Notificación ----------------
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Burbuja", NotificationManager.IMPORTANCE_MIN))
        fun action(label: String, act: String) = NotificationCompat.Action(0, label,
            PendingIntent.getService(this, act.hashCode(), Intent(this, OverlayService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("AI Quick Assist").setContentText("Burbuja activa")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(action(if (hidden) "Mostrar" else "Ocultar", ACTION_TOGGLE_HIDE))
            .addAction(action("Detener", ACTION_STOP))
            .build()
        if (Build.VERSION.SDK_INT >= 34) ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }
}
