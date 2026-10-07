package com.aiquickassist.engine

import android.graphics.Bitmap
import com.aiquickassist.data.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Orquesta los motores según el modo elegido, con respaldo y caché en memoria. */
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

    suspend fun analyze(p: ParsedQuestion, image: Bitmap? = null, manual: Boolean = false): AnalysisResult {
        val mode = Settings.engineMode
        val key = "$mode|${p.type}|${p.question}|${p.options}|${p.context.take(80)}|${image != null}"
        if (image == null) synchronized(cache) { cache[key] }?.let { return it.copy(manual = manual) }

        val r = when (mode) {
            EngineMode.WEB -> webOnly(p, image != null, manual)
            EngineMode.GEMINI -> {
                val g = GeminiEngine.ask(p.copy(hasImage = image != null), image)
                AnalysisResult(p, mode, g, gemini = g, manual = manual)
            }
            EngineMode.BOTH -> both(p, image, manual)
        }
        if (image == null && r.best.choice.isNotEmpty() || r.best.answer.isNotBlank() && image == null)
            synchronized(cache) { cache[key] = r }
        return r.copy(manual = manual)
    }

    private suspend fun webOnly(p: ParsedQuestion, hadImage: Boolean, manual: Boolean): AnalysisResult {
        val w = WebEngine.answer(p)
        val ev = if (manual || p.type == QType.OPEN || p.type == QType.DEFINITION) runCatching { WebEngine.search(WebEngine.queryFor(p), TextUtil.isSpanish(p.question)) }.getOrNull() else null
        val note = if (hadImage) "Este motor solo analiza texto: la imagen no se envió." else null
        return AnalysisResult(p, EngineMode.WEB, w, web = w, note = note, related = ev?.related.orEmpty() + ev?.pages.orEmpty().map { Source(it.title, it.url, it.snippet) })
    }

    private suspend fun both(p: ParsedQuestion, image: Bitmap?, manual: Boolean): AnalysisResult = coroutineScope {
        val webD = async { runCatching { WebEngine.answer(p) } }
        val gemD = async {
            if (GeminiEngine.available()) runCatching { GeminiEngine.ask(p.copy(hasImage = image != null), image) }
            else Result.failure(AnalysisException("Gemini no está disponible.", needsGemini = true))
        }
        val w = webD.await().getOrNull()
        val gRes = gemD.await()
        val g = gRes.getOrNull()
        val gErr = gRes.exceptionOrNull()?.message
        val wErr = webD.await().exceptionOrNull()?.message

        if (w == null && g == null) throw AnalysisException("Ambos motores fallaron. Web: $wErr. Gemini: $gErr")
        val ev = if (manual) runCatching { WebEngine.search(WebEngine.queryFor(p), TextUtil.isSpanish(p.question)) }.getOrNull() else null
        val related = ev?.related.orEmpty() + ev?.pages.orEmpty().map { Source(it.title, it.url, it.snippet) }

        val (best, note) = when {
            g == null -> w!!.copy(title = "MEJOR RESPUESTA") to "Gemini no está disponible (${gErr ?: "sin configurar"}); se muestra solo el motor web."
            w == null || (w.choice.isEmpty() && w.confidence == 0.0 && p.options.isNotEmpty()) ->
                g.copy(title = "MEJOR RESPUESTA") to (if (w == null) "El motor web no respondió; se muestra Gemini." else null)
            else -> merge(p, w, g)
        }
        AnalysisResult(p, EngineMode.BOTH, best, web = w, gemini = g, note = note, related = related)
    }

    private fun merge(p: ParsedQuestion, w: Block, g: Block): Pair<Block, String?> {
        val title = "MEJOR RESPUESTA"
        val sources = (g.sources + w.sources).distinctBy { it.url }
        if (p.options.isNotEmpty()) {
            if (w.choice.toSet() == g.choice.toSet() && g.choice.isNotEmpty())
                return g.copy(title = title, sources = sources, confidence = maxOf(g.confidence, 0.9)) to null
            // Discrepancia: se favorece a Gemini (razona sobre la pregunta) salvo que la web sea mucho más segura
            val useWeb = w.choice.isNotEmpty() && w.confidence > g.confidence + 0.25 && g.choice.isEmpty()
            val pick = if (useWeb) w else g
            val note = "Los motores no coinciden (web: ${w.choice.joinToString().ifBlank { "—" }}, Gemini: ${g.choice.joinToString().ifBlank { "—" }}). " +
                "Se muestra la opción más razonable; hay incertidumbre."
            return pick.copy(title = title, sources = sources, confidence = minOf(pick.confidence, 0.6)) to note
        }
        val sim = TextUtil.overlap(w.answer, g.answer + " " + g.explanation)
        val note = if (sim < 0.08) "Las respuestas de ambos motores difieren bastante; contrasta con las fuentes." else null
        return g.copy(title = title, sources = sources, confidence = if (note == null) 0.85 else 0.5) to note
    }
}
