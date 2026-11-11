package com.aiquickassist.dofpa.search

import kotlin.math.ln

data class Hit(
    val passage: CorpusPassage,
    val doc: CorpusDoc,
    val score: Double,
    val snippet: String,
    val reasons: List<String>          // por qué se eligió (para fuentes ordenadas y para pruebas)
) {
    val anchor: String get() = buildString {
        append(doc.title)
        if (passage.page > 0) append(" · p. ${passage.page}")
        passage.chapter?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        passage.section?.takeIf { it.isNotBlank() && it != passage.chapter }?.let { append(" · $it") }
    }
}

data class SearchResult(
    val query: ParsedQuery,
    val hits: List<Hit>,
    val notFound: String?,             // mensaje honesto si no hay respuesta en el material
    val docFilter: CorpusDoc?,         // documento al que se restringió la búsqueda (si el usuario lo nombró)
    val corrections: Map<String, String>,
    val relatedPages: List<Int>,       // otras páginas del documento principal donde aparece el tema
    val millis: Long
)

/**
 * Búsqueda local de DOFPA. Prioridad: (1) ley/artículo exactos, (2) concepto exacto, (3) título/capítulo,
 * (4) texto con BM25, (5) sinónimos y correcciones, (6) documentos relacionados. Sin modelos pesados.
 */
class SearchEngine(private val store: CorpusStore) {
    private val cache = object : LinkedHashMap<String, SearchResult>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, SearchResult>?) = size > 64
    }
    private var cacheVersion = -1L

    fun search(raw: String, previous: ParsedQuery? = null, limit: Int = 8): SearchResult {
        val t0 = System.nanoTime()
        val key = raw.trim().lowercase() + "|" + (previous?.norm ?: "") + "|" + limit
        synchronized(cache) {
            if (cacheVersion != store.version) { cache.clear(); cacheVersion = store.version }
            cache[key]?.let { return it.copy(millis = (System.nanoTime() - t0) / 1_000_000) }
        }
        val r = doSearch(raw, previous, limit, t0)
        synchronized(cache) { cache[key] = r }
        return r
    }

    private data class Phrase(val match: String, val weight: Double)

    private fun doSearch(raw: String, previous: ParsedQuery?, limit: Int, t0: Long): SearchResult {
        val docs = store.documents()
        val docById = docs.associateBy { it.id }
        var q = QueryParser.parse(raw, store.knownLaws())

        // 1) ¿Nombra un documento? Si lo nombra como CONTENEDOR ("en qué parte del Manual de Estudio…", "el código penal")
        //    se filtra por él y esas palabras dejan de ser tema. Si el título ES el tema ("dosis umbral",
        //    "inventario automotor") no se filtra: solo se prefiere ese documento.
        val (named, titleTokens, asContainer) = resolveDocument(q, docs)
        var docFilter: CorpusDoc? = null
        var preferDoc: CorpusDoc? = null
        if (named != null) {
            val rest = q.terms.filter { it !in titleTokens }
            if (asContainer) {
                docFilter = named
                q = q.copy(terms = rest, phrase = QueryParser.span(q.norm, rest))
            } else preferDoc = named
        }

        // 2) Seguimiento: "¿en qué parte está?", "explicámelo más fácil", "¿y el artículo 5?" → contexto anterior
        val emptyTopic = q.terms.isEmpty() && q.laws.isEmpty() && q.articles.isEmpty()
        if (previous != null && (q.followUp || emptyTopic)) {
            val ownTopic = q.terms.isNotEmpty() || q.articles.isNotEmpty()
            q = q.copy(terms = if (ownTopic) q.terms else (q.terms + previous.terms).distinct(),
                laws = q.laws.ifEmpty { previous.laws },
                articles = q.articles.ifEmpty { if (ownTopic) emptyList() else previous.articles },
                phrase = q.phrase ?: if (ownTopic) null else previous.phrase)
        }
        if (q.laws.isEmpty() && docFilter?.law != null) q = q.copy(laws = listOf(docFilter.law!!))
        if (q.terms.isEmpty() && q.laws.isEmpty() && q.articles.isEmpty()) {
            val msg = if (docFilter != null) "¿Qué querés buscar dentro de «${docFilter.title}»?" else "Escribí qué querés buscar."
            return SearchResult(q, emptyList(), msg, docFilter, emptyMap(), emptyList(), ms(t0))
        }

        // 3) Términos, correcciones de tipeo y sinónimos (los sinónimos pesan menos que el término original)
        val vocab = store.vocab()
        val corrections = LinkedHashMap<String, String>()
        val phrases = ArrayList<Phrase>()
        for (t in q.terms) {
            val known = vocab.isEmpty() || t.length < 3 || t[0].isDigit() || vocab.containsKey(t) || vocab.keys.any { it.startsWith(stem(t)) }
            val term = if (known) t else Fuzzy.closest(t, vocab)?.also { corrections[t] = it } ?: t
            phrases += Phrase(ftsTerm(term), if (term == t) 1.0 else 0.8)
            Synonyms.of(term).forEach { s -> phrases += Phrase(if (' ' in s) "\"$s\"" else ftsTerm(s), 0.45) }
        }
        q.phrase?.takeIf { ' ' in it }?.let { phrases += Phrase("\"$it\"", 1.5) }
        Synonyms.phrases(q.norm).forEach { phrases += Phrase("\"$it\"", 1.2) }
        for (l in q.laws) { phrases += Phrase("refs:${Refs.lawToken(l)}", 2.0); phrases += Phrase(l, 0.6) }
        for (a in q.articles) { phrases += Phrase("refs:${Refs.articleToken(a)}", 2.0); phrases += Phrase("\"articulo ${a}\"", 1.0) }
        val ph = phrases.distinctBy { it.match }.take(18)
        if (ph.isEmpty()) return SearchResult(q, emptyList(), "No entendí qué buscar.", docFilter, corrections, emptyList(), ms(t0))

        // 4) Candidatos con BM25 calculado sobre TODAS las coincidencias (no se corta antes de ordenar)
        val rows = runCatching { store.matchInfo(ph.joinToString(" OR ") { it.match }) }.getOrDefault(emptyList())
        // Con documento nombrado, los candidatos salen SOLO de ese documento (antes de recortar)
        val allowed = docFilter?.let { store.passageIdsOfDoc(it.id) }
        val bm = rows.asSequence().filter { allowed == null || it.first in allowed }
            .map { (id, mi) -> id to bm25(mi, ph.map { it.weight }) }.sortedByDescending { it.second }.take(400).toList()
        val pass = store.passages(bm.map { it.first })
        val stems = q.terms.map { corrections[it] ?: it }.map(::stem)
        val corrected = q.terms.filter { it in corrections }.map { stem(corrections[it]!!) }.toSet()

        // 5) Prioridades estructurales sobre el puntaje textual
        val scored = bm.mapNotNull { (id, base) ->
            val p = pass[id] ?: return@mapNotNull null
            val d = docById[p.docId] ?: return@mapNotNull null
            if (docFilter != null && d.id != docFilter.id) return@mapNotNull null
            val norm = Norm.text(p.text)
            val refLaws = Refs.lawsIn(norm, store.knownLaws())
            val why = ArrayList<String>(); var s = base
            val cov = if (stems.isEmpty()) 1.0 else stems.sumOf { st -> if (!norm.contains(st)) 0.0 else if (st in corrected) 0.5 else 1.0 } / stems.size
            // La ley pedida: su propio texto va primero; los textos que solo la citan, después
            if (q.laws.isNotEmpty()) {
                when {
                    d.law in q.laws -> { s += 28 + 12 * cov; why += "es la Ley ${d.law}" }
                    q.laws.any { it in refLaws } -> { s += 5 + 4 * cov; why += "cita la Ley ${q.laws.first { it in refLaws }}" }
                    refLaws.isNotEmpty() || d.law != null -> { s -= 6; why += "habla de otra ley" }
                }
            }
            for (a in q.articles) {
                if (p.article == a) {
                    // el primero del documento (el texto vigente, no un anexo o ley modificatoria del final)
                    s += 12 - minOf(p.ordinal * 0.01, 4.0); why += "es el artículo $a"
                    if (q.laws.isEmpty() || d.law in q.laws) s += 10
                } else if (norm.contains("articulo $a ") || norm.endsWith("articulo $a")) { s += 2; why += "menciona el artículo $a" }
            }
            q.phrase?.let { if (' ' in it && norm.contains(it)) { s += 4; why += "frase exacta" } }
            if (q.intent == Intent.DEFINITION && q.terms.isNotEmpty() && isDefinition(norm, q.terms)) { s += 5; why += "definición" }
            val head = Norm.text(listOfNotNull(p.chapter, p.section).joinToString(" "))
            if (stems.isNotEmpty() && stems.any { head.contains(it) }) { s += 2; why += "título/capítulo" }
            s += 4 * cov
            if (preferDoc != null && d.id == preferDoc.id) { s += 6; why += "documento sobre el tema" }
            if (isTocOrCover(p, d, norm)) { s -= 6; why += "portada/índice" }
            if (p.text.length < 80) s -= 2
            // Exposición de una ley: preferir su comienzo (objeto, definiciones)
            if (q.intent == Intent.LAW && q.terms.isEmpty() && d.law in q.laws) s -= p.ordinal * 0.02
            Triple(p, d, s to (why to cov))
        }.sortedByDescending { it.third.first }

        // 6) "¿Qué dice la ley X…?": el texto de la propia ley va primero (la explicación del apunte, después);
        //    en cualquier otra consulta sobre una ley, su texto aparece al menos segundo.
        val ordered = scored.toMutableList()
        if (q.laws.isNotEmpty() && docFilter == null) {
            val i = ordered.indexOfFirst { it.second.law in q.laws }
            val want = if (q.intent == Intent.LAW || q.intent == Intent.ARTICLE) 0 else 1
            if (i > want) ordered.add(want, ordered.removeAt(i))
        }

        // 7) Diversidad: como máximo 4 fragmentos por documento en el resultado
        val perDoc = HashMap<Long, Int>()
        val top = ordered.filter { (p, _, _) -> (perDoc.merge(p.docId, 1, Int::plus) ?: 0) <= 4 }.take(limit)
        val hits = top.map { (p, d, sc) -> Hit(p, d, sc.first, snippet(p.text, stems, q), sc.second.first) }

        // 8) Admitir cuando no está
        val notFound = when {
            hits.isEmpty() -> if (docFilter != null) "No encontré eso en «${docFilter.title}»." else "No encontré esto en el material cargado."
            q.laws.isNotEmpty() && q.laws.none { it in store.knownLaws() } &&
                hits.none { h -> q.laws.any { l -> l in Refs.lawsIn(Norm.text(h.passage.text), store.knownLaws()) } } ->
                "No encontré la Ley ${q.laws.joinToString { pretty(it) }} en el material cargado."
            q.articles.isNotEmpty() && hits.none { it.passage.article in q.articles || Norm.text(it.passage.text).contains("articulo ${q.articles.first()}") } ->
                "No encontré el artículo ${q.articles.first()} en el material cargado."
            // Sin ley/artículo: si ningún fragmento cubre lo esencial de la consulta, se admite que no está
            stems.isNotEmpty() && q.laws.isEmpty() && q.articles.isEmpty() &&
                (top.maxOfOrNull { it.third.second.second } ?: 0.0) < (if (stems.size >= 3) 0.6 else 0.5) ->
                if (docFilter != null) "No encontré eso en «${docFilter.title}»." else "No encontré esto en el material cargado."
            else -> null
        }
        val related = if (hits.isNotEmpty()) scored.filter { it.first.docId == hits[0].doc.id && it.third.second.second > 0 }
            .map { it.first.page }.distinct().filter { it != hits[0].passage.page }.sorted().take(8) else emptyList()
        return SearchResult(q, if (notFound != null && hits.none { it.score > 6 }) emptyList() else hits, notFound, docFilter, corrections, related, ms(t0))
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    /** BM25 por frase y columna a partir de matchinfo('pcnalx'); columnas: texto, títulos, referencias. */
    private fun bm25(mi: IntArray, weights: List<Double>): Double {
        if (mi.size < 3) return 0.0
        val p = mi[0]; val c = mi[1]; val n = mi[2].coerceAtLeast(1)
        val colW = doubleArrayOf(1.0, 2.0, 3.0)
        val k1 = 1.2; val b = 0.75
        var s = 0.0
        for (i in 0 until p) for (j in 0 until c) {
            val base = 3 + 2 * c + 3 * (i * c + j)
            if (base + 2 >= mi.size) continue
            val tf = mi[base].toDouble(); if (tf <= 0) continue
            val df = mi[base + 2].toDouble()
            val avg = mi[3 + j].toDouble().coerceAtLeast(1.0); val len = mi[3 + c + j].toDouble()
            val idf = ln(1 + (n - df + 0.5) / (df + 0.5))
            s += (weights.getOrElse(i) { 1.0 }) * colW.getOrElse(j) { 1.0 } * idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * len / avg))
        }
        return s
    }

    /** Raíz simple para plurales: "estupefacientes" → "estupefaciente". */
    private fun stem(t: String): String = when {
        t.length >= 7 && t.endsWith("es") -> t.dropLast(2)
        t.length >= 5 && t.endsWith("s") -> t.dropLast(1)
        else -> t
    }

    private fun ftsTerm(t: String): String {
        val s = stem(t)
        return if (s.length >= 5 && !s[0].isDigit()) "$s*" else s
    }

    private fun isDefinition(norm: String, terms: List<String>): Boolean {
        val t = terms.joinToString(" ")
        val st = stem(terms.first())
        return norm.contains("$t es ") || norm.contains("$t son ") || norm.contains("se entiende por $t") || norm.contains("se define") && norm.contains(st) ||
            norm.contains("$t se define") || norm.contains("$t consiste") || norm.contains("$t significa") || norm.startsWith(st)
    }

    private fun isTocOrCover(p: CorpusPassage, d: CorpusDoc, norm: String): Boolean {
        val dots = Regex("\\.{5,}|…{2,}").findAll(p.text).count()
        if (dots >= 3 || norm.startsWith("indice") || norm.contains("tabla de contenido")) return true
        // En leyes y códigos la primera página ya es texto normativo (objeto, art. 1): no es portada
        if (d.kind in setOf("ley", "codigo", "constitucion", "norma")) return false
        return p.page <= 1 && d.pages > 5 && p.text.length < 700
    }

    private data class Named(val doc: CorpusDoc?, val tokens: Set<String>, val asContainer: Boolean)

    /**
     * Documento nombrado en la consulta: 2+ palabras significativas del título. Se prefieren las coincidencias
     * contiguas ("código penal" ≠ "código procesal penal") y las que nombran un contenedor (manual, código, ley…).
     */
    private fun resolveDocument(q: ParsedQuery, docs: List<CorpusDoc>): Named {
        val qTokens = q.norm.split(' ')
        val qt = qTokens.toSet()
        var best: CorpusDoc? = null; var bestScore = 0.0; var bestTok = emptySet<String>(); var bestContainer = false
        for (d in docs) {
            val titleSeq = Norm.tokens(d.title).filter { it.length >= 3 && it !in Norm.stop && !it[0].isDigit() }
            val tt = titleSeq.toSet()
            if (tt.isEmpty()) continue
            val hit = tt.intersect(qt)
            if (hit.size < 2) continue
            val frac = hit.size.toDouble() / tt.size
            val container = hit.any { it in containerWords }
            val contiguous = titleSeq.zipWithNext().any { (a, b) -> qTokens.zipWithNext().any { it.first == a && it.second == b } }
            if (!(frac >= 0.6 || container && contiguous && frac >= 0.4)) continue
            val score = frac + hit.size * 0.02 + (if (contiguous) 0.3 else 0.0) + (if (container) 0.5 else 0.0)
            if (score > bestScore) { best = d; bestScore = score; bestTok = hit; bestContainer = container }
        }
        return Named(best, bestTok, bestContainer)
    }

    private val containerWords = setOf("manual", "unidad", "modulo", "codigo", "constitucion", "protocolo", "ley", "estudio",
        "curso", "trayecto", "capacitacion", "presentacion", "programa", "anexo", "decreto", "resolucion", "informe")

    /** Oración(es) que mejor responden, del texto original. */
    private fun snippet(text: String, stems: List<String>, q: ParsedQuery): String {
        val sentences = text.replace(Regex("\\s+"), " ").split(Regex("(?<=[.;:!?])\\s+(?=[A-ZÁÉÍÓÚÑ0-9¿(«\"-])")).filter { it.length > 15 }
        if (sentences.isEmpty()) return text.take(320)
        val keys = stems + q.laws + q.articles.map { "articulo $it" }
        val best = sentences.withIndex().maxByOrNull { (i, s) -> val n = Norm.text(s); keys.count { n.contains(it) } * 10 - i * 0.01 }!!
        val sb = StringBuilder(best.value)
        if (sb.length < 160 && best.index + 1 < sentences.size) sb.append(' ').append(sentences[best.index + 1])
        return sb.toString().take(420)
    }

    companion object {
        fun pretty(law: String) = if (law.length >= 4) law.dropLast(3) + "." + law.takeLast(3) else law
    }
}

/** Corrección de tipeo contra el vocabulario del corpus (distancia de edición ≤1, o ≤2 en palabras largas). */
object Fuzzy {
    fun closest(t: String, vocab: Map<String, Int>): String? {
        val maxD = if (t.length >= 7) 2 else 1
        var best: String? = null; var bd = Int.MAX_VALUE; var bdf = -1
        for ((w, df) in vocab) {
            if (kotlin.math.abs(w.length - t.length) > maxD || w[0] != t[0] && maxD == 1) continue
            val d = dist(t, w, maxD); if (d > maxD) continue
            if (d < bd || (d == bd && df > bdf)) { best = w; bd = d; bdf = df }
        }
        return best
    }

    /** Damerau-Levenshtein (transposiciones adyacentes) con corte temprano. */
    fun dist(a: String, b: String, max: Int): Int {
        val n = a.length; val m = b.length
        var prev2 = IntArray(m + 1); var prev = IntArray(m + 1) { it }; var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i; var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v; if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }
}
