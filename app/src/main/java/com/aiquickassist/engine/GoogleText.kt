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

    fun searchUrl(q: String) = "https://www.google.com/search?hl=es&q=${enc(q)}"
    fun aiModeUrl(q: String) = "https://www.google.com/search?udm=50&hl=es" + if (q.isBlank()) "" else "&q=${enc(q)}"

    private fun lines(raw: String, drop: (String) -> Boolean): List<String> =
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !ui.matches(it) && !drop(it) }

    /** Texto de la tarjeta "Visión general"; null si no hay contenido suficiente. */
    fun cleanOverview(raw: String): String? {
        val t = lines(raw) { false }.joinToString("\n")
        return t.takeIf { it.length >= 30 }
    }

    /** Respuesta de AI Mode; se descarta el eco de la consulta y la interfaz. */
    fun cleanAiMode(raw: String, query: String): String? {
        val q = query.trim()
        val t = lines(raw) { it.equals(q, true) }.joinToString("\n")
        return t.takeIf { it.length >= 60 }
    }
}
