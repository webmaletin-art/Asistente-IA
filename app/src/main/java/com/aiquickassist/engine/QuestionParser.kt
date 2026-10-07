package com.aiquickassist.engine

import com.aiquickassist.data.Option
import com.aiquickassist.data.ParsedQuestion
import com.aiquickassist.data.QType

/** Línea de texto de pantalla; [option] = el origen la marca como opción (radio/checkbox). */
data class ScreenLine(val text: String, val option: Boolean = false)

/** Detecta pregunta, tipo y opciones a partir de texto crudo. */
object QuestionParser {
    private val letterOpt = Regex("""^\s*[\(\[]?([A-Ha-h])[\)\]\.\:\-–]\s*(\S.*)$""")
    private val noise = Regex(
        """^(siguiente|anterior|enviar|borrar (selección|formulario)|atrás|next|back|submit|clear( form| selection)?|\*?obligatori[ao]|\*?required|""" +
            """página \d+.*|page \d+.*|\d{1,2}:\d{2}.*|\d+ ?%|ok|cancelar|cancel)$""",
        RegexOption.IGNORE_CASE
    )
    private val multiCue = Regex(
        """seleccion[ae]\w* (todas|varias|las)|marque (todas|varias)|select all|choose (two|all|more|3)|más de una|varias respuestas|todas las que|all that apply""",
        RegexOption.IGNORE_CASE
    )
    private val tfCue = Regex("""verdadero o falso|true or false|\bv\s*/\s*f\b|\(\s*v\s*\)|\(\s*f\s*\)|verdadero/falso|true/false""", RegexOption.IGNORE_CASE)
    private val defCue = Regex("""^[¿\s]*(qu[eé] (es|son|significa|quiere decir)|define|defin[ae]\w*|what (is|are)|meaning of|concepto de|significado de)\b""", RegexOption.IGNORE_CASE)
    private val trCue = Regex("""traduc|translate|c[oó]mo se dice|how do you say|en (ingl[eé]s|espa[nñ]ol|franc[eé]s|portugu[eé]s)\b|in (english|spanish|french)\b""", RegexOption.IGNORE_CASE)
    private val ocrBullet = Regex("""^[○◯●•◦oO0©Ⓞ]\s+(?=\S)""")

    fun parseText(raw: String, ocr: Boolean = false): ParsedQuestion? {
        var lines = raw.lines().map { ScreenLine(it) }
        if (ocr) lines = fixOcrBullets(lines)
        return parse(lines)
    }

    /** Los radio-buttons suelen leerse como "O"/"0"/"©": se convierten en marcas de opción. */
    private fun fixOcrBullets(lines: List<ScreenLine>): List<ScreenLine> {
        val t = lines.map { it.text.trim() }
        if (t.count { ocrBullet.containsMatchIn(it) && !letterOpt.matches(it) } < 2) return lines
        return lines.mapIndexed { i, l ->
            if (ocrBullet.containsMatchIn(t[i])) ScreenLine(t[i].replace(ocrBullet, ""), true) else l
        }
    }

    fun parse(input: List<ScreenLine>): ParsedQuestion? {
        val lines = input.map { ScreenLine(it.text.replace(Regex("\\s+"), " ").trim(), it.option) }
            .filter { it.text.isNotBlank() && !noise.matches(it.text) && !(it.text.length < 2 && !it.option) }
        if (lines.isEmpty()) return null

        val group = findOptionGroups(lines).maxByOrNull { score(lines, it) }?.takeIf { score(lines, it) > 0 }
        if (group != null) {
            val (start, end, opts) = group
            val (q, ctx) = questionBefore(lines, start)
            val question = q.ifBlank { questionAfter(lines, end) }
            return finish(question, opts, ctx)
        }
        // Sin opciones: pregunta abierta / definición / V-F / traducción
        val all = lines.map { it.text }
        val joined = all.joinToString("\n")
        val question: String
        var ctx = ""
        if (joined.length <= 400) {
            question = all.joinToString(" ")
        } else {
            val qi = all.indexOfFirst { it.contains('?') || it.contains('¿') }
            if (qi >= 0) {
                question = all.drop(qi).take(2).joinToString(" ").take(400)
                ctx = all.take(qi).joinToString(" ").takeLast(1500)
            } else {
                question = all.take(3).joinToString(" ").take(400)
                ctx = all.drop(3).joinToString(" ").take(1500)
            }
        }
        return finish(question, emptyList(), ctx)
    }

    private data class Group(val start: Int, val end: Int, val options: List<Option>)

    private val widgetNoise = Regex("""presiona|intro|calificaci|rating|estrella|votos|[uú]til|gracias|compartir|publicidad""", RegexOption.IGNORE_CASE)

    /** Puntúa un grupo de opciones: prefiere pregunta con «?» y opciones reales; penaliza widgets (calificación, etc.). */
    private fun score(lines: List<ScreenLine>, g: Group): Double {
        val q = questionBefore(lines, g.start).first
        var sc = 1.0
        if (q.contains('?')) sc += 4
        sc += minOf(q.length, 120) / 40.0
        sc += g.options.map { it.text.split(' ').size }.average().coerceAtMost(6.0) / 3
        if (g.options.any { widgetNoise.containsMatchIn(it.text) } || widgetNoise.containsMatchIn(q)) sc -= 8
        return sc
    }

    private fun findOptionGroups(lines: List<ScreenLine>): List<Group> {
        val out = mutableListOf<Group>()
        // 1) Opciones con letra A, B, C... consecutivas
        var i = 0
        while (i < lines.size) {
            val m = letterOpt.matchEntire(lines[i].text)
            if (m != null && m.groupValues[1].uppercase() == "A") {
                val opts = mutableListOf(Option("A", m.groupValues[2].trim()))
                var j = i + 1
                var next = 'B'
                while (j < lines.size) {
                    val mj = letterOpt.matchEntire(lines[j].text)
                    if (mj != null && mj.groupValues[1].uppercase()[0] == next) {
                        opts += Option(next.toString(), mj.groupValues[2].trim()); next++; j++
                    } else if (mj == null && lines[j].text.firstOrNull()?.isLowerCase() == true && lines[j].text.length < 120 && !widgetNoise.containsMatchIn(lines[j].text)) {
                        val last = opts.removeAt(opts.lastIndex)       // línea envuelta
                        opts += last.copy(text = last.text + " " + lines[j].text); j++
                    } else break
                }
                if (opts.size >= 2) out += Group(i, j, opts)
                i = j
            } else i++
        }
        // 2) Opciones marcadas por el origen (radio/checkbox)
        i = 0
        while (i < lines.size) {
            if (lines[i].option) {
                var j = i
                while (j < lines.size && lines[j].option) j++
                if (j - i >= 2) {
                    out += Group(i, j, (i until j).take(8).mapIndexed { k, idx ->
                        Option(('A' + k).toString(), lines[idx].text.replace(letterOpt, "$2"))
                    })
                }
                i = j
            } else i++
        }
        return out
    }

    private fun questionBefore(lines: List<ScreenLine>, start: Int): Pair<String, String> {
        var qIdx = -1
        var k = start - 1
        while (k >= 0 && start - k <= 6 && !lines[k].option && !letterOpt.matches(lines[k].text)) {
            if (lines[k].text.contains('?') || lines[k].text.endsWith(":")) { qIdx = k; break }
            k--
        }
        if (qIdx < 0) qIdx = if (start >= 1) maxOf(0, start - 1) else return "" to ""
        val q = lines.subList(qIdx, start).joinToString(" ") { it.text }.take(500)
        val ctx = lines.subList(maxOf(0, qIdx - 8), qIdx).filter { !it.option }.joinToString(" ") { it.text }
        return q to (if (ctx.length > 200) ctx.takeLast(1200) else "")
    }

    private fun questionAfter(lines: List<ScreenLine>, end: Int) =
        lines.drop(end).firstOrNull { it.text.contains('?') }?.text.orEmpty()

    private fun finish(question: String, options: List<Option>, context: String): ParsedQuestion? {
        val q = question.replace(Regex("^\\s*[-•*–]\\s+"), "").replace(Regex("^\\s*(pregunta\\s*)?\\d{1,3}\\s*[\\.\\)\\:-]\\s*", RegexOption.IGNORE_CASE), "").trim()
        if (q.isBlank() && options.isEmpty()) return null
        val norm = options.map { TextUtil.normalize(it.text).trim() }
        val tfWords = setOf("verdadero", "falso", "true", "false", "v", "f", "cierto")
        return when {
            options.isNotEmpty() && norm.all { it in tfWords } -> ParsedQuestion(QType.TRUE_FALSE, q, options, context)
            options.isNotEmpty() && multiCue.containsMatchIn(q) -> ParsedQuestion(QType.MULTI_SELECT, q, options, context)
            options.isNotEmpty() -> ParsedQuestion(QType.MULTIPLE_CHOICE, q, options, context)
            tfCue.containsMatchIn(q) ->
                ParsedQuestion(QType.TRUE_FALSE, q, listOf(Option("V", "Verdadero"), Option("F", "Falso")), context)
            trCue.containsMatchIn(q) -> ParsedQuestion(QType.TRANSLATION, q, emptyList(), context)
            defCue.containsMatchIn(q) -> ParsedQuestion(QType.DEFINITION, q, emptyList(), context)
            context.length > 250 -> ParsedQuestion(QType.COMPREHENSION, q, emptyList(), context)
            else -> ParsedQuestion(QType.OPEN, q, emptyList(), context)
        }
    }
}
