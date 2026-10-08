package com.aiquickassist.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import com.aiquickassist.data.PanelMode
import com.aiquickassist.data.Settings

/** Colores de los paneles flotantes (respuesta, búsqueda, menú, guía del test). */
data class PanelPalette(val bg: Color, val fg: Color, val sub: Color, val line: Color)

object PanelStyle {
    /** Último color muestreado detrás del panel (modo Camuflaje). */
    var camo by mutableStateOf<Int?>(null)

    fun from(bg: Int, alpha: Float): PanelPalette {
        val fg = if (ColorUtils.calculateLuminance(bg) > 0.5) Color.Black else Color.White
        return PanelPalette(Color(bg).copy(alpha = alpha.coerceIn(0.15f, 1f)), fg, fg.copy(alpha = 0.65f), fg.copy(alpha = 0.2f))
    }

    fun current(): PanelPalette {
        val a = Settings.panelOpacity
        return when (Settings.panelMode) {
            PanelMode.LIGHT -> from(0xFFFFFFFF.toInt(), a)
            PanelMode.DARK -> from(0xFF1E1E1E.toInt(), a)
            PanelMode.CAMO -> from(camo ?: 0xFFFFFFFF.toInt(), a)
            PanelMode.CUSTOM -> from(Settings.panelColor, a)
        }
    }
}

val LocalPanel = compositionLocalOf { PanelStyle.from(0xFFFFFFFF.toInt(), 1f) }
val PAL: PanelPalette @Composable get() = LocalPanel.current

@Composable
fun PanelHost(content: @Composable () -> Unit) {
    val pal = PanelStyle.current()
    CompositionLocalProvider(LocalPanel provides pal, LocalContentColor provides pal.fg) { content() }
}
