package com.aiquickassist.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.aiquickassist.capture.ScreenReader
import com.aiquickassist.capture.ScreenSnapshot
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Solo reacciona a eventos de scroll (modo discreto). El texto y la captura se piden
 * únicamente cuando el usuario toca la burbuja.
 */
class AssistAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { Bridge.accessibility = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { Bridge.accessibility = null; return super.onUnbind(intent) }
    override fun onInterrupt() {}

    @Volatile private var selection: String? = null
    @Volatile private var selectionAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> Bridge.onScroll?.invoke()
            // Evento en vivo: llega al marcar el texto, sin el retraso del árbol de accesibilidad
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val text = event.source?.text?.toString() ?: event.text?.joinToString("")
                val from = event.fromIndex; val to = event.toIndex
                selection = if (text != null && from >= 0 && to > from && to <= text.length) text.substring(from, to).takeIf { it.isNotBlank() } else null
                selectionAt = System.currentTimeMillis()
            }
        }
    }

    /** Última selección de texto vista (se descarta a los 10 min o si se deseleccionó). */
    fun liveSelection(): String? = selection?.takeIf { System.currentTimeMillis() - selectionAt < 10 * 60_000L }

    fun readScreen(): ScreenSnapshot? = resources.displayMetrics.let { ScreenReader.read(rootInActiveWindow, packageName, it.widthPixels, it.heightPixels) }

    /** Texto copiado por el usuario y su marca de tiempo; null si no hay o Android no permite leerlo. */
    fun clipboard(): Pair<String, Long>? = runCatching {
        val clip = getSystemService(android.content.ClipboardManager::class.java)?.primaryClip ?: return null
        val t = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.takeIf { it.isNotBlank() } ?: return null
        t to clip.description.timestamp
    }.getOrNull()

    suspend fun capture(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(shot: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                hw?.recycle(); shot.hardwareBuffer.close()
                if (cont.isActive) cont.resume(copy)
            }
            override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
        })
    }
}
