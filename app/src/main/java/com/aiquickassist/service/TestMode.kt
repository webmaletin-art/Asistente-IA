package com.aiquickassist.service

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aiquickassist.data.*
import com.aiquickassist.engine.GoogleEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Texto del paso actual del Modo test (se dibuja como banner sobre la app que estés usando). */
class TestUi(val title: String, val text: String, val buttons: List<Pair<String, () -> Unit>>)

/** Último informe del Modo test (en memoria y en un archivo de caché). */
object TestReport {
    var text by mutableStateOf("")

    fun save(ctx: Context, report: String): File {
        text = report
        val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        return File(dir, "test_${System.currentTimeMillis()}.txt").also { it.writeText(report) }
    }
}

/** Utilidades para construir el informe. */
object TestProbe {
    fun env(ctx: Context): String = buildString {
        val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        appendLine("Fecha: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("App: $v")
        appendLine("Accesibilidad conectada: ${Bridge.accessibility != null}")
        appendLine("Motor elegido: ${Settings.engineMode.label}")
        appendLine("Google AI Mode: activado=${Settings.aiModeEnabled} imágenes=${Settings.aiUseImages} visual=${Settings.aiVisual} preferirImagen=${Settings.aiPreferImages}")
        appendLine("Gemini: usar=${Settings.geminiEnabled} clave=${SecureStore.hasGeminiKey()} modelo=${Settings.geminiModel} respaldo=${Settings.geminiFallback}")
    }

    fun parsed(p: ParsedQuestion?): String = if (p == null) "(no se detectó pregunta)" else buildString {
        append("tipo=${p.type.label} · pregunta=«${p.question}»")
        if (p.options.isNotEmpty()) append(" · opciones=" + p.options.joinToString(" | ") { "${it.label}) ${it.text}" })
        if (p.context.isNotBlank()) append(" · contexto=${p.context.length} car.")
    }

    fun clip(s: String, n: Int = 400) = s.replace("\n", " ⏎ ").let { if (it.length > n) it.take(n) + "…" else it }

    /** Ejecuta un motor, mide el tiempo y devuelve un bloque de informe (con el diagnóstico de Google si aplica). */
    suspend fun probe(name: String, google: Boolean, call: suspend () -> Block): String {
        val t0 = SystemClock.elapsedRealtime()
        val head = try {
            val b = call()
            "[$name] OK en ${SystemClock.elapsedRealtime() - t0} ms · origen=${b.origin} · elección=${b.choice.joinToString().ifEmpty { "—" }}\n" +
                "    respuesta: ${clip(b.answer, 500)}" +
                (if (b.explanation.isNotBlank() && b.explanation != b.answer) "\n    detalle: ${clip(b.explanation, 500)}" else "")
        } catch (e: Exception) {
            val extra = (e as? AnalysisException)?.let { x -> (if (x.googleUrl != null) " · url=${x.googleUrl}" else "") + (if (x.needsGemini) " · necesita Gemini" else "") }.orEmpty()
            "[$name] FALLÓ en ${SystemClock.elapsedRealtime() - t0} ms · ${e.message}$extra"
        }
        return if (google) head + "\n" + GoogleEngine.trace.joinToString("\n") { "      · $it" } else head
    }
}
