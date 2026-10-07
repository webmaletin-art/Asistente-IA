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
