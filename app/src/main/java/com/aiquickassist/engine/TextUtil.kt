package com.aiquickassist.engine

import java.text.Normalizer

object TextUtil {
    private val stopEs = ("el la los las un una unos unas de del al y o u e a en con por para que qué es son se su sus lo le les como cómo " +
        "cual cuál cuales cuáles quien quién donde dónde cuando cuándo cuanto cuánto este esta estos estas ese esa eso aquel muy mas más " +
        "ser fue era sido sobre entre sin sus mi tu si sí no ni pero hay ha han he").split(" ").toSet()
    private val stopEn = ("the a an of and or to in on at by for with is are was were be been what which who whom where when how why this that these " +
        "those it its as from not no do does did has have had can will").split(" ").toSet()
    val stop: Set<String> = (stopEs + stopEn).map { normalize(it) }.toSet()

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Palabras significativas normalizadas (sin acentos ni palabras vacías). */
    fun keywords(s: String): List<String> =
        normalize(s).split(Regex("[^a-z0-9ñ]+")).filter { it.length > 2 && it !in stop }

    fun stem(w: String) = if (w.length > 6) w.take(6) else w

    fun isSpanish(s: String): Boolean {
        val words = normalize(s).split(Regex("[^a-z]+"))
        val es = words.count { it in setOf("el", "la", "los", "las", "de", "que", "es", "un", "una", "en", "y", "por", "con", "cual", "como") }
        val en = words.count { it in setOf("the", "of", "and", "is", "are", "what", "which", "to", "in", "a", "an", "how", "who") }
        return es >= en
    }

    fun sentences(s: String): List<String> =
        s.replace(Regex("\\s+"), " ").split(Regex("(?<=[.!?])\\s+(?=[A-ZÁÉÍÓÚÑ¿¡0-9])")).map { it.trim() }.filter { it.length > 8 }

    fun firstSentences(s: String, maxChars: Int = 320): String {
        val out = StringBuilder()
        for (x in sentences(s)) {
            if (out.isNotEmpty() && out.length + x.length > maxChars) break
            if (out.isNotEmpty()) out.append(' ')
            out.append(x)
            if (out.length >= maxChars * 0.6) break
        }
        return out.toString().ifBlank { s.take(maxChars) }
    }

    fun overlap(a: String, b: String): Double {
        val x = keywords(a).map(::stem).toSet()
        val y = keywords(b).map(::stem).toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }
}
