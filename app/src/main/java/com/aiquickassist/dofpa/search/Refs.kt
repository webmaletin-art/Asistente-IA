package com.aiquickassist.dofpa.search

/** Referencias estructurales: números de ley y de artículo, en formato de token indexable. */
object Refs {
    private val lawMention = Regex("\\b(?:ley(?:es)?|decreto|resolucion)\\s+(?:nacional\\s+|provincial\\s+|pcial\\s+|n\\s+|nro\\s+|numero\\s+)?(\\d{3,6})\\b")
    private val articleHead = Regex("^\\s*articulo\\s+(\\d{1,4})(?:\\s+(bis|ter|quater))?\\b")
    private val titleLaw = listOf(
        Regex("\\bley\\s*(?:pcial|provincial|nacional|n|nro|numero)?\\s*(\\d{4,6})\\b"),
        Regex("\\b(\\d{2})\\s(\\d{3})\\b(?=.*\\bley\\b)|\\bley\\s(\\d{2})\\s(\\d{3})\\b")
    )

    /** Ley a la que corresponde un documento, a partir de su título/nombre de archivo (o null). */
    fun lawOfTitle(title: String): String? {
        val t = Norm.text(title.replace('_', ' ').replace('-', ' '))
        titleLaw[0].find(t)?.let { return it.groupValues[1] }
        titleLaw[1].find(t)?.let { m -> return m.groupValues.drop(1).filter { it.isNotEmpty() }.joinToString("") }
        return null
    }

    /** Leyes mencionadas en un texto normalizado; [known] agrega números de leyes del corpus citados sin la palabra "ley". */
    fun lawsIn(norm: String, known: Set<String> = emptySet()): Set<String> {
        val out = LinkedHashSet<String>()
        lawMention.findAll(norm).forEach { out.add(it.groupValues[1]) }
        if (known.isNotEmpty()) for (tok in norm.split(' ')) if (tok.length >= 4 && tok in known) out.add(tok)
        return out
    }

    /** Número de artículo si el fragmento ES ese artículo (su sección empieza con "Artículo N"). */
    fun articleOf(sectionOrStart: String?): String? {
        if (sectionOrStart.isNullOrBlank()) return null
        val m = articleHead.find(Norm.text(sectionOrStart)) ?: return null
        return m.groupValues[1] + (if (m.groupValues[2].isNotEmpty()) " " + m.groupValues[2] else "")
    }

    fun lawToken(n: String) = "ley$n"
    fun articleToken(a: String) = "art" + a.replace(" ", "")
}
