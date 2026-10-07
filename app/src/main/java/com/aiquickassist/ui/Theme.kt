package com.aiquickassist.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val bg = Color.White
    val surface = Color(0xFFF5F5F5)
    val line = Color(0xFFDDDDDD)
    val text = Color.Black
    val sub = Color(0xFF757575)
    val ok = Color(0xFF2E7D32)
    val err = Color(0xFFC62828)
    val blue = Color(0xFF1565C0)
}

private val scheme = lightColorScheme(
    primary = Color.Black, onPrimary = Color.White, background = C.bg, onBackground = C.text,
    surface = C.bg, onSurface = C.text, surfaceVariant = C.surface, onSurfaceVariant = C.sub,
    outline = C.line, error = C.err
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
        ),
        content = content
    )
}

@Composable
fun Screen(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(C.bg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
            else Spacer(Modifier.width(12.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
        HorizontalDivider(color = C.line)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp), content = content)
    }
}

@Composable
fun Section(title: String) {
    Text(title.uppercase(), fontSize = 12.sp, color = C.sub, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp))
}

@Composable
fun Item(title: String, value: String? = null, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = C.sub)
        }
        if (value != null) Text(value, fontSize = 14.sp, color = C.sub)
        if (onClick != null) Text("  >", fontSize = 16.sp, color = C.sub)
    }
    HorizontalDivider(color = C.line, modifier = Modifier.padding(start = 16.dp))
}

@Composable
fun SwitchItem(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = C.sub)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Color.Black, checkedThumbColor = Color.White,
                uncheckedTrackColor = C.line, uncheckedThumbColor = Color.White, uncheckedBorderColor = C.line)
        )
    }
    HorizontalDivider(color = C.line, modifier = Modifier.padding(start = 16.dp))
}

@Composable
fun RadioItem(title: String, selected: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = Color.Black))
        Column { Text(title, fontSize = 16.sp); if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = C.sub) }
    }
}

@Composable
fun WireButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (enabled) Color(0xFF999999) else C.line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color.Black)
    ) { Text(text, fontSize = 15.sp) }
}

@Composable
fun Box1(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.border(1.dp, C.line, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(C.bg), content = content)
}
