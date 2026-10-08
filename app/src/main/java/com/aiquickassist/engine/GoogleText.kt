package com.aiquickassist.engine

import com.aiquickassist.data.ParsedQuestion
import com.aiquickassist.data.QType
import java.net.URLEncoder

/** Lógica pura (testeable) para construir consultas a Google y limpiar el texto leído de la página. */
object GoogleText {
    private val ui = Regex(
        """^(mostrar (más|menos)|show (more|less)|más información|learn more|ver más|""" +
            """visi[oó]n general creada por ia|vista general creada por ia|ai overview|""" +
            """las respuestas de ia pueden contener errores.*|ai responses may include mistakes.*|""" +
            """gracias|comentarios|feedback|\d+ sitios?|modo ia|ai mode|todo|im[aá]genes|pregunta lo que quieras.*|ask anything.*|""" +
            """iniciar sesi[oó]n|sign in|m[aá]s|enviar|send)$""",
        RegexOption.IGNORE_CASE
    )

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Consulta a Google: la pregunta tal cual; en opción múltiple se añaden las opciones. */
    fun query(p: ParsedQuestion): String {
        val base = p.question.replace(Regex("\\s+"), " ").trim()
        val q = if ((p.type == QType.MULTIPLE_CHOICE || p.type == QType.MULTI_SELECT) && p.options.isNotEmpty())
            base + " " + p.options.joinToString(" ") { "${it.label}) ${it.text}" } else base
        return q.take(300)
    }

    /** Afirmación sin «verdadero/falso» ni «?»: se busca como «¿Es verdad que …?». */
    fun queryFor(p: ParsedQuestion): String {
        val q = query(p)
        return if (p.type == QType.TRUE_FALSE && !p.question.contains('?') && !Regex("verdader|falso|true or false", RegexOption.IGNORE_CASE).containsMatchIn(p.question))
            "¿Es verdad que ${p.question.trim().trimEnd('.', ' ')}?" else q
    }

    fun searchUrl(q: String) = "https://www.google.com/search?hl=es&q=${enc(q)}"
    fun aiModeUrl(q: String) = "https://www.google.com/search?udm=50&hl=es" + if (q.isBlank()) "" else "&q=${enc(q)}"

    /** Líneas que son código (CSS/JS) y no texto legible. */
    fun looksLikeCode(l: String): Boolean {
        if (l.contains('{') || l.contains('}')) return true
        if (Regex("""^[.#@][\w-]+.*[:;]""").containsMatchIn(l) && l.count { it == ';' || it == ':' } >= 2) return true
        if (l.contains("var(--") || l.contains("-webkit-") || l.contains("function(")) return true
        val letters = l.count { it.isLetter() }
        return l.length > 25 && letters < l.length * 0.55
    }

    private fun lines(raw: String, drop: (String) -> Boolean): List<String> =
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !ui.matches(it) && !looksLikeCode(it) && !drop(it) }

    /** Las fórmulas se leen letra por letra («P / e / r…»): se unen las tiradas de líneas de ≤2 caracteres. */
    fun mergeShortRuns(ls: List<String>): List<String> {
        val out = mutableListOf<String>(); var run = StringBuilder(); var n = 0
        fun flush() { if (n >= 3) out += run.toString() else if (n > 0) out += run.toString().let { r -> r }; run = StringBuilder(); n = 0 }
        for (l in ls) { if (l.length <= 2) { run.append(l); n++ } else { flush(); out += l } }
        flush()
        return out.filter { it.isNotBlank() }
    }

    /** Primer párrafo (línea) de la respuesta: es lo que se muestra en grande. */
    fun lead(text: String, max: Int = 400): String {
        val ls = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val first = ls.firstOrNull().orEmpty()
        val s = if (first.length < 40 && ls.size > 1 && !first.matches(Regex(".*[.!?]$"))) first + " " + ls[1] else first
        return s.take(max)
    }

    /** Texto de la tarjeta "Visión general"; null si no hay contenido suficiente. */
    fun cleanOverview(raw: String): String? {
        val t = lines(raw) { false }.joinToString("\n")
        return t.takeIf { it.length >= 30 }
    }

    /** Respuesta de AI Mode; se descarta el eco de la consulta y la interfaz. */
    fun cleanAiMode(raw: String, query: String): String? {
        val q = query.trim()
        val prefix = Regex("""^(conversaci[oó]n en el modo ia|conversation in ai mode)\s*:?\s*""", RegexOption.IGNORE_CASE)
        val sent = Regex("""enviaste \d+ im[aá]gen(?:es)?( y dijiste:?)?|you sent \d+ images?( and said:?)?""", RegexOption.IGNORE_CASE)
        val math = Regex("[\\x{1D400}-\\x{1D7FF}]")                 // duplicados MathML («𝐶 𝑂»)
        val t = mergeShortRuns(lines(raw) { false }.map { it.replace(math, "") })
            .map { it.replace(prefix, "").replace(sent, "").trim() }
            .filter { it.isNotEmpty() && !it.matches(Regex("""^\+?\d{1,3}$|^(https?://)?(www\.)?[\w-]+(\.[\w-]+)+(/\S*)?$""")) && !it.equals(q, true) && !(q.length > 8 && it.startsWith(q, true) && it.length < q.length + 12) }
            .joinToString("\n")
        return t.takeIf { it.length >= 60 }
    }
}
