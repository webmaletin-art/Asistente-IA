package com.aiquickassist.dofpa.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquickassist.ui.C
import com.aiquickassist.ui.Item
import com.aiquickassist.ui.Screen
import com.aiquickassist.ui.Section

/** Academia: materias a partir del material cargado. (Exámenes y entrenamiento: próxima etapa.) */
@Composable
fun AcademiaScreen(nav: DofpaNav) {
    val docs = rememberDocs()
    Screen("Academia", nav::back) {
        Section("Materias del material cargado")
        docs.groupBy { it.subject ?: "Sin materia" }.toSortedMap().forEach { (s, ds) ->
            Item(s, "${ds.size} documentos", subtitle = ds.take(3).joinToString(" · ") { it.title })
        }
        Text("Exámenes, entrenamiento diario y repaso espaciado se habilitan en la próxima etapa.",
            fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(16.dp))
    }
}
