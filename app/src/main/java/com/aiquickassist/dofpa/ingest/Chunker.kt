package com.aiquickassist.dofpa.ingest

/** Un fragmento listo para indexar: texto + anclaje (página y sección detectada). */
data class Chunk(val page: Int, val section: String?, val text: String)

/**
 * Parte las páginas en fragmentos citables. Detecta encabezados (ARTÍCULO, TÍTULO, CAPÍTULO,
 * UNIDAD) para anclar cada fragmento a su sección, y corta los bloques largos por párrafos
 * sin pasar de ~1200 caracteres, preservando el contexto.
 */
object Chunker {
    private const val MAX = 1200
    private val header = Regex(
        """^\s*(ART[IÍ]CULO|ART\.|T[IÍ]TULO|CAP[IÍ]TULO|SECCI[OÓ]N|UNIDAD|ANEXO)\s*(N[°º]?\s*)?[0-9IVXLC]+[\.\-:)]?.*""",
        RegexOption.IGNORE_CASE
    )

    fun chunk(pages: List<Page>): List<Chunk> {
        val out = ArrayList<Chunk>()
        var section: String? = null
        for (p in pages) {
            val paras = p.text.split(Regex("\n\\s*\n|\r\n\\s*\r\n")).map { it.replace(Regex("[ \t]+"), " ").trim() }.filter { it.isNotEmpty() }
            val buf = StringBuilder()
            fun flush() { if (buf.isNotBlank()) { out.add(Chunk(p.number, section, buf.toString().trim())); buf.setLength(0) } }
            for (para in paras) {
                val firstLine = para.lineSequence().firstOrNull()?.trim().orEmpty()
                if (header.matches(firstLine) && firstLine.length < 160) { flush(); section = firstLine }
                if (buf.length + para.length + 1 > MAX && buf.isNotEmpty()) flush()
                if (para.length > MAX) {
                    flush()
                    for (piece in splitLong(para)) out.add(Chunk(p.number, section, piece))
                } else {
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(para)
                }
            }
            flush()
        }
        return out.filter { it.text.length >= 2 }
    }

    private fun splitLong(s: String): List<String> {
        val sentences = s.split(Regex("(?<=[.!?])\\s+"))
        val res = ArrayList<String>(); val b = StringBuilder()
        for (sen in sentences) {
            if (b.length + sen.length > MAX && b.isNotEmpty()) { res.add(b.toString().trim()); b.setLength(0) }
            if (sen.length > MAX) { for (i in sen.indices step MAX) res.add(sen.substring(i, minOf(i + MAX, sen.length))) }
            else { if (b.isNotEmpty()) b.append(' '); b.append(sen) }
        }
        if (b.isNotBlank()) res.add(b.toString().trim())
        return res
    }
}
