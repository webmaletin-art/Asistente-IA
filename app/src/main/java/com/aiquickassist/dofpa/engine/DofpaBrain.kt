package com.aiquickassist.dofpa.engine

import com.aiquickassist.data.Settings
import com.aiquickassist.dofpa.data.DofpaRepo
import com.aiquickassist.dofpa.data.PassageHit
import com.aiquickassist.engine.GeminiEngine
import com.aiquickassist.engine.GoogleText
import com.aiquickassist.engine.TextUtil

enum class DofpaOrigin(val label: String) {
    LOCAL("Material de la Academia (local)"),
    GEMINI("Gemini + material local"),
    NONE("Sin resultados")
}

data class Cite(val docId: Long, val title: String, val page: Int, val section: String?) {
    val label: String get() = buildString {
        append(title)
        if (page > 0) append(" · p. $page")
        if (!section.isNullOrBlank()) append(" · $section")
    }
}

data class DofpaAnswer(
    val text: String,
    val origin: DofpaOrigin,
    val cites: List<Cite>,
    val hits: List<PassageHit>,     // pasajes usados (para conceptos tocables y "ver fuente")
    val note: String? = null,
    val aiModeUrl: String? = null   // para "abrir AI Mode con la consulta preparada"
)

/**
 * Cerebro de DOFPA. SIEMPRE recupera fragmentos del material local y responde citando.
 * Motor: local (sin conexión), gemini (usa solo los fragmentos relevantes), auto (gemini y si
 * falla, local). Google AI Mode no se automatiza: se ofrece abrir con la consulta preparada.
 */
object DofpaBrain {

    suspend fun ask(question: String): DofpaAnswer {
        val q = question.trim()
        if (q.length < 2) return DofpaAnswer("Escribí una consulta.", DofpaOrigin.NONE, emptyList(), emptyList())
        val hits = DofpaRepo.search(q, limit = 24)
        val top = rank(q, hits).take(6)
        val aiUrl = if (Settings.dofpaInternet) GoogleText.aiModeUrl(q) else null

        if (top.isEmpty()) {
            return DofpaAnswer(
                "No encontré esto en el material cargado. Importá el documento correspondiente o reformulá la pregunta." +
                    (if (Settings.dofpaInternet) " También podés contrastar con Internet." else ""),
                DofpaOrigin.NONE, emptyList(), emptyList(), aiModeUrl = aiUrl
            )
        }

        val cites = top.map { Cite(it.docId, it.title, it.page, it.section) }.distinctBy { it.label }
        val engine = Settings.dofpaEngine
        val wantGemini = (engine == "gemini" || engine == "auto") && GeminiEngine.available()

        if (wantGemini) {
            val answer = runCatching { gemini(q, top) }.getOrNull()
            if (answer != null) return DofpaAnswer(answer, DofpaOrigin.GEMINI, cites, top, aiModeUrl = aiUrl)
            // fallback honesto: si Gemini falla (cuota/sin conexión), respondemos con el material local
            val note = if (engine == "gemini") "Gemini no respondió (cuota o sin conexión); se muestra el material local." else null
            return DofpaAnswer(local(q, top), DofpaOrigin.LOCAL, cites, top, note = note, aiModeUrl = aiUrl)
        }
        val note = if (engine != "local" && !GeminiEngine.available()) "Gemini no está configurado; respuesta solo con material local." else null
        return DofpaAnswer(local(q, top), DofpaOrigin.LOCAL, cites, top, note = note, aiModeUrl = aiUrl)
    }

    /** Respuesta local: arma una respuesta extractiva a partir de los mejores fragmentos citados. */
    private fun local(q: String, top: List<PassageHit>): String = buildString {
        val best = top.first()
        append(TextUtil.firstSentences(best.text, 360))
        val extra = top.drop(1).firstOrNull { TextUtil.overlap(it.text, q) > 0.15 }
        if (extra != null) { append("\n\n"); append(TextUtil.firstSentences(extra.text, 240)) }
        append("\n\nFuente: ${Cite(best.docId, best.title, best.page, best.section).label}.")
    }

    /** Gemini con SOLO los fragmentos recuperados (nunca el corpus completo), obligado a citar. */
    private suspend fun gemini(q: String, top: List<PassageHit>): String {
        val ctx = top.mapIndexed { i, h ->
            "[${i + 1}] ${h.title}${if (h.page > 0) ", p. ${h.page}" else ""}${h.section?.let { " ($it)" } ?: ""}:\n${h.text}"
        }.joinToString("\n\n")
        val prompt = buildString {
            append("Sos DOFPA, asistente de estudio. Respondé la consulta USANDO ÚNICAMENTE los fragmentos del material de la Academia que siguen. ")
            append("Si la respuesta no está en ellos, decí claramente que el material no lo cubre; no inventes leyes, artículos, datos ni procedimientos. ")
            append("Respondé directo y claro, en español, y al final citá entre paréntesis el número de fragmento usado, p. ej. (1). No agregues introducciones largas.\n\n")
            append("FRAGMENTOS:\n").append(ctx).append("\n\nCONSULTA: ").append(q)
        }
        return GeminiEngine.complete(prompt).trim()
    }

    /** Reordena por solapamiento real con la consulta (FTS ya filtró por términos). */
    private fun rank(q: String, hits: List<PassageHit>): List<PassageHit> =
        hits.distinctBy { it.passageId }.sortedByDescending { TextUtil.overlap(it.text, q) + (if (it.section != null) 0.03 else 0.0) }
}
