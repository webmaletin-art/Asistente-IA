package com.aiquickassist.engine

import com.aiquickassist.data.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * Motor "Visión general creada por IA".
 * Usa únicamente fuentes públicas con API abierta (DuckDuckGo Instant Answer y Wikipedia):
 * sin scraping, sin APIs privadas, sin saltarse protecciones. Si existe un resumen de IA en
 * esas fuentes se prioriza; si no, se usan los resultados web disponibles.
 */
object WebEngine {
    private const val TITLE = "VISIÓN GENERAL CREADA POR IA"

    data class Evidence(
        val abstractText: String,
        val abstractSource: Source?,
        val pages: List<Source>,          // snippet = extracto
        val related: List<Source>
    ) {
        val fullText get() = (listOf(abstractText) + pages.map { it.snippet }).joinToString("\n")
        val isEmpty get() = abstractText.isBlank() && pages.isEmpty()
    }

    suspend fun answer(p: ParsedQuestion): Block {
        val ev = search(queryFor(p), TextUtil.isSpanish(p.question + " " + p.options.joinToString(" ") { it.text }))
        if (ev.isEmpty) throw AnalysisException("No se encontraron resultados web para esta consulta.")
        val sources = buildList {
            ev.abstractSource?.let { add(it) }
            addAll(ev.pages.take(3).map { Source(it.title, it.url) })
        }.distinctBy { it.url }

        return when (p.type) {
            QType.MULTIPLE_CHOICE, QType.MULTI_SELECT -> pickOptions(p, ev, sources)
            QType.TRUE_FALSE -> trueFalse(p, ev, sources)
            QType.COMPREHENSION -> comprehension(p, ev, sources)
            QType.TRANSLATION -> Block(
                TITLE, answer = TextUtil.firstSentences(bestText(ev), 240),
                explanation = "La traducción exacta requiere Gemini; se muestra información web relacionada.",
                sources = sources, confidence = 0.2
            )
            else -> Block(TITLE, answer = TextUtil.firstSentences(bestText(ev), 340), sources = sources, confidence = 0.6)
        }
    }

    /** Búsqueda manual / complementaria: devuelve evidencia sin interpretar. */
    suspend fun search(query: String, spanish: Boolean = true): Evidence = coroutineScope {
        val ddg = async { runCatching { duck(query) }.getOrNull() }
        val wiki = async { runCatching { wikipedia(query, if (spanish) "es" else "en") }.getOrDefault(emptyList()) }
        val wikiAlt = async {
            // segunda oportunidad en el otro idioma si el principal no devuelve nada
            val first = wiki.await()
            if (first.isEmpty()) runCatching { wikipedia(query, if (spanish) "en" else "es") }.getOrDefault(emptyList()) else emptyList()
        }
        val d = ddg.await()
        val pages = wiki.await().ifEmpty { wikiAlt.await() }
        Evidence(d?.first.orEmpty(), d?.second, pages, d?.third.orEmpty())
    }

    fun queryFor(p: ParsedQuestion): String {
        var q = p.question.replace(Regex("[¿?¡!]"), " ").replace(Regex("\\s+"), " ").trim()
        if (q.length < 12 && p.options.isNotEmpty()) q += " " + p.options.joinToString(" ") { it.text }
        if (q.length > 120) q = TextUtil.keywords(q).distinct().sortedByDescending { it.length }.take(8).joinToString(" ")
        return q.take(200)
    }

    private fun bestText(ev: Evidence) = ev.abstractText.ifBlank { ev.pages.firstOrNull()?.snippet.orEmpty() }

    // ---------- Opción múltiple ----------
    private fun pickOptions(p: ParsedQuestion, ev: Evidence, sources: List<Source>): Block {
        val evTokens = TextUtil.keywords(ev.fullText).map(TextUtil::stem).toSet()
        val optTokens = p.options.map { o -> TextUtil.keywords(o.text).map(TextUtil::stem).distinct() }
        // Palabras comunes a todas las opciones no discriminan
        val common = optTokens.reduceOrNull { a, b -> a.intersect(b.toSet()).toList() }.orEmpty().toSet()
        val scores = optTokens.map { toks ->
            val use = toks.filter { it !in common }
            if (use.isEmpty()) 0.0
            else {
                val w = use.sumOf { minOf(it.length, 8).toDouble() }
                use.filter { it in evTokens }.sumOf { minOf(it.length, 8).toDouble() } / w
            }
        }
        val order = scores.indices.sortedByDescending { scores[it] }
        val top = order.first()
        if (scores[top] <= 0.0) {
            return Block(TITLE, answer = "Sin evidencia suficiente para elegir una opción.",
                explanation = TextUtil.firstSentences(bestText(ev), 300), sources = sources, confidence = 0.0)
        }
        val chosen: List<Int> = if (p.type == QType.MULTI_SELECT)
            order.filter { scores[it] >= maxOf(0.4, scores[top] * 0.7) } else listOf(top)
        val margin = if (order.size > 1) scores[top] - scores[order[1]] else scores[top]
        val conf = (scores[top] * 0.6 + margin * 0.8).coerceIn(0.05, 0.95)
        return Block(
            TITLE,
            choice = chosen.map { p.options[it].label },
            answer = chosen.joinToString("; ") { p.options[it].text },
            explanation = TextUtil.firstSentences(bestText(ev), 320),
            sources = sources, confidence = conf
        )
    }

    // ---------- Verdadero / falso ----------
    private fun trueFalse(p: ParsedQuestion, ev: Evidence, sources: List<Source>): Block {
        val kws = TextUtil.keywords(p.question).map(TextUtil::stem).distinct()
        val evTokens = TextUtil.keywords(ev.fullText).map(TextUtil::stem).toSet()
        val cov = if (kws.isEmpty()) 0.0 else kws.count { it in evTokens }.toDouble() / kws.size
        val t = p.options.firstOrNull { TextUtil.normalize(it.text) in setOf("verdadero", "true", "v", "cierto") }
        val f = p.options.firstOrNull { TextUtil.normalize(it.text) in setOf("falso", "false", "f") }
        val expl = TextUtil.firstSentences(bestText(ev), 300)
        return when {
            cov >= 0.7 && t != null -> Block(TITLE, listOf(t.label), t.text, "Estimado por coincidencia con fuentes. $expl", sources, 0.45)
            cov < 0.4 && f != null -> Block(TITLE, listOf(f.label), f.text, "Estimado: las fuentes no respaldan la afirmación. $expl", sources, 0.3)
            else -> Block(TITLE, answer = "Resultado no concluyente.", explanation = expl, sources = sources, confidence = 0.0)
        }
    }

    // ---------- Comprensión (extractivo sobre el contexto) ----------
    private fun comprehension(p: ParsedQuestion, ev: Evidence, sources: List<Source>): Block {
        val best = TextUtil.sentences(p.context).maxByOrNull { TextUtil.overlap(it, p.question) }
        return if (best != null && TextUtil.overlap(best, p.question) > 0.1)
            Block(TITLE, answer = best, explanation = "Fragmento del texto más relacionado con la pregunta.", sources = sources, confidence = 0.5)
        else Block(TITLE, answer = TextUtil.firstSentences(bestText(ev), 300), sources = sources, confidence = 0.3)
    }

    // ---------- Fuentes ----------
    private suspend fun duck(q: String): Triple<String, Source?, List<Source>> {
        val j = JSONObject(Http.get("https://api.duckduckgo.com/?q=${Http.enc(q)}&format=json&no_html=1&skip_disambig=1&no_redirect=1"))
        val abs = j.optString("AbstractText")
        val src = if (abs.isNotBlank()) Source(j.optString("AbstractSource", "DuckDuckGo"), j.optString("AbstractURL")) else null
        val rel = mutableListOf<Source>()
        val topics = j.optJSONArray("RelatedTopics")
        if (topics != null) for (i in 0 until minOf(topics.length(), 6)) {
            val t = topics.optJSONObject(i) ?: continue
            if (t.has("Text") && t.optString("FirstURL").isNotBlank())
                rel += Source(t.getString("Text").take(70), t.getString("FirstURL"), t.getString("Text"))
        }
        return Triple(abs, src, rel)
    }

    private suspend fun wikipedia(q: String, lang: String): List<Source> {
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrlimit=4" +
            "&gsrsearch=${Http.enc(q)}&prop=extracts%7Cinfo&exintro=1&explaintext=1&exlimit=4&exchars=900&inprop=url&redirects=1"
        val pages = JSONObject(Http.get(url)).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        return pages.keys().asSequence().map { pages.getJSONObject(it) }
            .sortedBy { it.optInt("index", 99) }
            .filter { it.optString("extract").isNotBlank() }
            .map { Source("${it.getString("title")} — Wikipedia", it.optString("fullurl"), it.getString("extract").trim()) }
            .toList()
    }
}
