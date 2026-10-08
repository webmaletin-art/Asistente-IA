package com.aiquickassist.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquickassist.data.Tool

enum class MenuAction(val label: String, val glyph: String) {
    TEXT("Texto", "T"), OCR("OCR", "▣"), IMAGE("Imagen", "□"),
    SEARCH("Buscar", "🔍"), BROWSER("Navegador", "◉"), CAMO("Camuflar burbuja", "◐"), TEST("Modo test", "✔"), SETTINGS("Configuración", "⚙")
}

/** Menú vertical de herramientas junto a la burbuja (se abre con presión larga). */
@Composable
fun BubbleMenu(current: Tool, searchEnabled: Boolean, x: Dp, y: Dp, onSelect: (MenuAction) -> Unit, onDismiss: () -> Unit) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)) {
        AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(120)), modifier = Modifier.offset(x, y)) {
            Column(Modifier.width(190.dp).border(1.dp, PAL.line, RoundedCornerShape(6.dp)).background(PAL.bg, RoundedCornerShape(6.dp))) {
                MenuAction.entries.filter { searchEnabled || it != MenuAction.SEARCH }.forEach { a ->
                    val selected = (a == MenuAction.TEXT && current == Tool.TEXT) || (a == MenuAction.OCR && current == Tool.OCR) || (a == MenuAction.IMAGE && current == Tool.IMAGE)
                    Row(Modifier.fillMaxWidth().clickable { onSelect(a) }.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.label, Modifier.weight(1f), fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        Text(a.glyph, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

/** Guía del Modo test: texto del paso actual y sus botones, sobre la app que se esté usando. */
@Composable
fun TestBanner(ui: com.aiquickassist.service.TestUi) {
    Column(Modifier.fillMaxWidth().padding(8.dp).border(1.dp, PAL.line, RoundedCornerShape(6.dp)).background(PAL.bg, RoundedCornerShape(6.dp)).padding(12.dp)) {
        Text(ui.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.blue)
        if (ui.text.isNotBlank()) Text(ui.text, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            ui.buttons.forEach { (label, action) -> WireButton(label, onClick = action) }
        }
    }
}
