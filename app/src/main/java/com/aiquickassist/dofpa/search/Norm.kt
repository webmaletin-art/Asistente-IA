package com.aiquickassist.dofpa.search

import java.text.Normalizer

/**
 * Normalización ÚNICA para indexar y consultar (la misma en el teléfono y en los tests):
 * minúsculas, sin acentos, números de ley unidos ("23.737" → "23737"), abreviaturas expandidas.
 */
object Norm {
    private val marks = Regex("\\p{Mn}+")
    private val thousands = Regex("(\\d)\\.(\\d{3})(?!\\d)")
    private val nonWord = Regex("[^a-z0-9]+")

    fun text(s: String): String {
        var t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(marks, "")
        t = t.replace('º', ' ').replace('°', ' ').replace('ª', ' ')
        while (thousands.containsMatchIn(t)) t = t.replace(thousands, "$1$2")
        t = t.replace(Regex("\\barts?\\.\\s*"), " articulo ")
            .replace(Regex("\\barts?\\s+(?=\\d)"), " articulo ")
            .replace(Regex("\\binc\\.\\s*"), " inciso ")
            .replace(Regex("\\binc\\s+(?=[a-z0-9]\\b)"), " inciso ")
            .replace(Regex("\\b(nro|num|n)\\.\\s*(?=\\d)"), " ")
        t = t.replace(nonWord, " ").trim().replace(Regex(" +"), " ")
        // "ley n 23737" / "ley nro 23737" → "ley 23737"
        return t.replace(Regex("\\b(n|nro|num|numero)\\s+(?=\\d)"), "")
    }

    fun tokens(s: String): List<String> = text(s).split(' ').filter { it.isNotEmpty() }

    /** Palabras vacías: no se usan como términos de búsqueda (sí quedan en el índice). */
    val stop: Set<String> = ("a al algo algun alguna algunas alguno algunos ante antes aqui asi aun como con contra cual cuales cuando " +
        "cuanto de del desde donde dos e el ella ellas ello ellos en entre era es esa esas ese eso esos esta estas este esto estos " +
        "ha han hay la las le les lo los mas me mi mis mucho muy ni no nos o otra otro para pero poco por porque que quien se sea " +
        "segun ser si sin sobre son su sus tambien tan te tiene tienen todo todos tu un una uno unos y ya yo " +
        "explica explicame explicar explicalo dime decime contame cuentame dame quiero saber puedes podes podrias sobre acerca " +
        "the of and to in is").split(' ').toSet()
}
