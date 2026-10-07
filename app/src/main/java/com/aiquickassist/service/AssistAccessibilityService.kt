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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && event.packageName != packageName) Bridge.onScroll?.invoke()
    }

    fun readScreen(): ScreenSnapshot? = ScreenReader.read(rootInActiveWindow, packageName)

    /**
     * Si el usuario tiene texto seleccionado (barra «Copiar / Seleccionar todo» visible), pulsa «Copiar»
     * mediante accesibilidad, lee el portapapeles y lo restaura. Devuelve null si no hay selección o no se pudo.
     */
    suspend fun copySelection(): String? {
        val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return null
        val before = runCatching { cm.primaryClip }.getOrNull()
        val beforeText = before?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        val beforeTime = before?.description?.timestamp ?: 0L
        var clicked = false
        loop@ for (w in windows) {
            val root = w.root ?: continue
            for (label in listOf("Copiar", "Copy")) {
                for (n in root.findAccessibilityNodeInfosByText(label)) {
                    if (n.text?.toString()?.trim().equals(label, true)) {
                        var t: android.view.accessibility.AccessibilityNodeInfo? = n
                        while (t != null && !t.isClickable) t = t.parent
                        if (t?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) { clicked = true; break@loop }
                    }
                }
            }
        }
        if (!clicked) return null
        kotlinx.coroutines.delay(220)
        val after = runCatching { cm.primaryClip }.getOrNull() ?: return null
        val afterText = after.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        val changed = after.description.timestamp > beforeTime || afterText != beforeText
        if (!changed || afterText.isNullOrBlank()) return null
        if (beforeText != null) runCatching { cm.setPrimaryClip(android.content.ClipData.newPlainText("", beforeText)) }
        return afterText
    }

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
