package com.aiquickassist.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquickassist.Nav
import com.aiquickassist.data.BubbleStyle
import com.aiquickassist.data.Settings
import com.aiquickassist.service.BubbleRenderer
import com.aiquickassist.service.BubbleStatus

private val palette = listOf(
    0xFF000000, 0xFFFFFFFF, 0xFF757575, 0xFFC62828, 0xFF2E7D32, 0xFF1565C0, 0xFFF9A825, 0xFF6A1B9A
).map { it.toInt() }

@Composable
fun BubbleSettingsScreen(nav: Nav) {
    var custom by remember { mutableStateOf(false) }
    Screen("Burbuja flotante", nav::back) {
        // Vista previa en tiempo real
        Box(Modifier.fillMaxWidth().padding(16.dp).height(110.dp).background(C.surface, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).border(1.dp, C.line, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center) {
            BubblePreview(Settings.bubbleStyle, Settings.bubbleColor, Settings.bubbleOpacity, Settings.bubbleSizeDp, BubbleStatus.IDLE)
        }

        Section("Tamaño")
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("${Settings.bubbleSizeDp} dp", fontSize = 13.sp, color = C.sub)
            Slider(Settings.bubbleSizeDp.toFloat(), { Settings.bubbleSizeDp = it.toInt() }, valueRange = 28f..96f, colors = sliderColors())
        }
        Section("Transparencia")
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("${(Settings.bubbleOpacity * 100).toInt()}%  (de 100% a 5%; sigue siendo táctil)", fontSize = 13.sp, color = C.sub)
            Slider(Settings.bubbleOpacity, { Settings.bubbleOpacity = it }, valueRange = 0.05f..1f, colors = sliderColors())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(100, 80, 60, 40, 20, 10, 5).forEach { p ->
                    Text("$p%", fontSize = 12.sp, color = C.blue, modifier = Modifier.clickable { Settings.bubbleOpacity = p / 100f }.padding(4.dp))
                }
            }
        }
        Section("Color")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            palette.forEach { c ->
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(c)).border(if (Settings.bubbleColor == c) 3.dp else 1.dp, if (Settings.bubbleColor == c) C.blue else C.line, CircleShape)
                    .clickable { Settings.bubbleColor = c })
            }
        }
        WireButton("Color personalizado", Modifier.padding(16.dp)) { custom = true }

        Section("Estilo")
        BubbleStyle.entries.forEach { s -> RadioItem(s.label, Settings.bubbleStyle == s, if (s == BubbleStyle.SHIELD) "Presentación mínima y discreta (accesibilidad visual)" else null) { Settings.bubbleStyle = s } }

        Section("Comportamiento")
        SwitchItem("Modo discreto", Settings.discreet, "Reduce la opacidad mientras te desplazas") { Settings.discreet = it }
        SwitchItem("Ocultar durante scroll", Settings.hideOnScroll) { Settings.hideOnScroll = it }
        SwitchItem("Mostrar al detener", Settings.showOnStop, "Vuelve a aparecer cuando termina el scroll") { Settings.showOnStop = it }
        Text("El modo discreto usa el servicio de accesibilidad para detectar el scroll.", fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(16.dp))
    }
    if (custom) ColorDialog(Settings.bubbleColor, { custom = false }) { Settings.bubbleColor = it; custom = false }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Color.Black, activeTrackColor = Color.Black, inactiveTrackColor = C.line)

@Composable
fun BubblePreview(style: BubbleStyle, color: Int, opacity: Float, sizeDp: Int, status: BubbleStatus) {
    val d = LocalDensity.current.density
    Canvas(Modifier.size(sizeDp.dp)) {
        drawIntoCanvas { BubbleRenderer.draw(it.nativeCanvas, size.width, size.height, style, color, opacity, status, d) }
    }
}

@Composable
private fun ColorDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var r by remember { mutableFloatStateOf(android.graphics.Color.red(initial).toFloat()) }
    var g by remember { mutableFloatStateOf(android.graphics.Color.green(initial).toFloat()) }
    var b by remember { mutableFloatStateOf(android.graphics.Color.blue(initial).toFloat()) }
    val argb = android.graphics.Color.rgb(r.toInt(), g.toInt(), b.toInt())
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.bg,
        title = { Text("Color personalizado", fontSize = 18.sp) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().height(36.dp).background(Color(argb)).border(1.dp, C.line))
                Text("R", fontSize = 12.sp); Slider(r, { r = it }, valueRange = 0f..255f, colors = sliderColors())
                Text("G", fontSize = 12.sp); Slider(g, { g = it }, valueRange = 0f..255f, colors = sliderColors())
                Text("B", fontSize = 12.sp); Slider(b, { b = it }, valueRange = 0f..255f, colors = sliderColors())
                Text("#%06X".format(argb and 0xFFFFFF), fontSize = 13.sp, color = C.sub)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(argb) }) { Text("Aplicar", color = Color.Black) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = Color.Black) } }
    )
}
