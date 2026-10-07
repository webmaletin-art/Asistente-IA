package com.aiquickassist.engine

import android.graphics.Bitmap
import android.net.Uri
import com.aiquickassist.data.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Recorte real: bitmap (para Gemini) + Uri del archivo temporal (para Google). */
class CropImage(val bitmap: Bitmap, val uri: Uri)

/**
 * Orquesta los motores. Cada respuesta conserva su [Origin]; nunca se reetiqueta.
 * No hay sustitutos de Google: si Google no responde, se dice y se ofrece abrir Google.
 */
object Analyzer {
    private val cache = object : LinkedHashMap<String, AnalysisResult>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, AnalysisResult>?) = size > 40
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    /** Consulta manual (lupa): se interpreta como cualquier otra pregunta. */
    suspend fun search(query: String): AnalysisResult {
        val parsed = QuestionParser.parseText(query) ?: throw AnalysisException("Escribe una consulta.")
        return analyze(parsed, null, manual = true)
    }

    suspend fun analyze(p: ParsedQuestion, image: CropImage? = null, manual: Boolean = false): AnalysisResult {
        val mode = Settings.engineMode
        val key = "$mode|${p.type}|${p.question}|${p.options}|${p.context.take(80)}"
        if (image == null) synchronized(cache) { cache[key] }?.let { return it.copy(manual = manual) }

        val r = when (mode) {
            EngineMode.GEMINI -> {
                val g = GeminiEngine.ask(p.copy(hasImage = image != null), image?.bitmap)
                AnalysisResult(p, mode, g, gemini = g, withImage = image != null)
            }
            EngineMode.BOTH -> both(p, image)
            else -> {
                val (g, url, note) = google(p, image, mode)
                AnalysisResult(p, mode, g, google = g, googleUrl = url, note = note, withImage = image != null)
            }
        }
        if (image == null) synchronized(cache) { cache[key] = r }
        return r.copy(manual = manual)
    }

    private data class G(val block: Block, val url: String, val note: String?)

    /** Elige el producto de Google: AI Mode para imágenes y modo AI Mode; Visión general para texto. */
    private suspend fun google(p: ParsedQuestion, image: CropImage?, mode: EngineMode): G {
        val q = GoogleText.query(p)
        if (image != null) {
            if (!Settings.aiModeEnabled || !Settings.aiUseImages)
                throw AnalysisException("Google AI Mode está desactivado para imágenes (Configuración → Google AI Mode).")
            if (mode == EngineMode.OVERVIEW && !Settings.aiPreferImages) {
                if (p.question.isBlank()) throw AnalysisException("La Visión general de Google no analiza imágenes. Activa «Preferir AI Mode para imágenes».")
                return G(GoogleEngine.overview(q).block, GoogleText.searchUrl(q), "Imagen no enviada: se usó solo el texto.")
            }
            val a = GoogleEngine.aiMode(p.question, image.uri)
            return G(a.block, a.url, null)
        }
        if (mode == EngineMode.AI_MODE) {
            if (!Settings.aiModeEnabled) throw AnalysisException("Google AI Mode está desactivado (Configuración → Google AI Mode).")
            val a = GoogleEngine.aiMode(q, null)
            return G(a.block, a.url, null)
        }
        val a = GoogleEngine.overview(q)
        return G(a.block, a.url, null)
    }

    /** Google y Gemini por separado; se muestran ambos sin mezclar etiquetas. */
    private suspend fun both(p: ParsedQuestion, image: CropImage?): AnalysisResult = coroutineScope {
        val gD = async { runCatching { google(p, image, if (image != null) EngineMode.AI_MODE else EngineMode.OVERVIEW) } }
        val mD = async {
            if (GeminiEngine.available()) runCatching { GeminiEngine.ask(p.copy(hasImage = image != null), image?.bitmap) }
            else Result.failure(AnalysisException("Gemini no está configurado.", needsGemini = true))
        }
        val g = gD.await(); val m = mD.await()
        val gg = g.getOrNull(); val mm = m.getOrNull()
        if (gg == null && mm == null) {
            val ge = g.exceptionOrNull() as? AnalysisException
            throw AnalysisException("Google: ${g.exceptionOrNull()?.message}. Gemini: ${m.exceptionOrNull()?.message}",
                needsGemini = (m.exceptionOrNull() as? AnalysisException)?.needsGemini == true,
                googleUrl = ge?.googleUrl, openLabel = ge?.openLabel)
        }
        val note = compare(gg?.block, mm, g.exceptionOrNull()?.message, m.exceptionOrNull()?.message)
        val best = mm ?: gg!!.block
        AnalysisResult(p, EngineMode.BOTH, best, google = gg?.block, gemini = mm, note = note,
            googleUrl = gg?.url ?: (g.exceptionOrNull() as? AnalysisException)?.googleUrl, withImage = image != null)
    }

    fun compare(google: Block?, gemini: Block?, gErr: String?, mErr: String?): String? = when {
        google == null -> "Google no respondió (${gErr ?: "sin datos"}); se muestra solo Gemini."
        gemini == null -> "Gemini no está disponible (${mErr ?: "sin configurar"}); se muestra solo Google."
        TextUtil.overlap(google.answer + " " + google.explanation, gemini.answer + " " + gemini.explanation) >= 0.12 ->
            "Google y Gemini coinciden en lo esencial."
        else -> "Google y Gemini difieren; contrasta ambas respuestas."
    }
}
