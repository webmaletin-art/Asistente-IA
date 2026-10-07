package com.aiquickassist.service

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings as AndroidSettings
import com.aiquickassist.capture.ScreenSnapshot

/** Punto de contacto entre el servicio de accesibilidad y la burbuja. */
object Bridge {
    @Volatile var accessibility: AssistAccessibilityService? = null
    @Volatile var onScroll: (() -> Unit)? = null

    fun accessibilityEnabled(ctx: Context): Boolean {
        val me = ComponentName(ctx, AssistAccessibilityService::class.java).flattenToString()
        val list = AndroidSettings.Secure.getString(ctx.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return list?.split(':')?.any { it.equals(me, true) } == true
    }

    fun readScreen(): ScreenSnapshot? = accessibility?.readScreen()
    suspend fun capture(): Bitmap? = accessibility?.capture()
}
