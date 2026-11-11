package com.aiquickassist.dofpa.search

/** Qué quiere realmente el usuario. Determina cómo se arma la respuesta. */
enum class Intent(val label: String) {
    DEFINITION("definición"), HOW("cómo funciona"), USE("cómo se utiliza"), WHEN("cuándo"), WHY("por qué"),
    WHERE("dónde aparece"), LAW("qué dice la ley"), ARTICLE("artículo"), SIMPLER("explicación sencilla"),
    EXAMPLE("ejemplo"), RELATION("relación"), COMPARE("comparación"), GENERAL("consulta")
}

data class ParsedQuery(
    val raw: String,
    val norm: String,
    val terms: List<String>,       // términos de contenido (sin vacías ni números de ley/artículo)
    val laws: List<String>,        // números de ley pedidos ("23737")
    val articles: List<String>,    // artículos pedidos ("5", "14 bis")
    val incisos: List<String>,     // incisos pedidos ("c")
    val intent: Intent,
    val followUp: Boolean,         // se refiere a la respuesta anterior ("eso", "está", "y el siguiente")
    val phrase: String?,           // frase exacta (entre comillas o el concepto completo) para coincidencia exacta
    val docTitleWords: List<String> = emptyList() // palabras que nombran un documento (se resuelven en el motor)
)

object QueryParser {
    private val lawNum = Regex("\\b(?:ley(?:es)?|decreto|resolucion)\\s+(?:nacional\\s+|provincial\\s+|pcial\\s+|n\\s+|nro\\s+|numero\\s+)?(\\d{4,6})\\b")
    private val bareLaw = Regex("\\b(\\d{4,6})\\b")
    private val article = Regex("\\barticulos?\\s+(\\d{1,4})(?:\\s+(bis|ter|quater))?\\b")
    private val inciso = Regex("\\binciso\\s+([a-z]|\\d{1,2})\\b")
    private val quoted = Regex("[\"“«]([^\"”»]{3,80})[\"”»]")

    private val intents: List<Pair<Intent, Regex>> = listOf(
        Intent.WHERE to Regex("\\b(en que (parte|pagina|capitulo|seccion|unidad|documento|lugar)|donde (aparece|esta|se encuentra|dice|habla|figura)|ubicacion|que pagina|localiza)\\b"),
        Intent.SIMPLER to Regex("\\b(mas facil|mas simple|mas sencillo|no entendi|no entiendo|en palabras simples|resumido)\\b"),
        Intent.EXAMPLE to Regex("\\b(un ejemplo|dame ejemplos?|por ejemplo|ejemplific)"),
        Intent.RELATION to Regex("\\b(relacion|relaciona|vinculo|tiene que ver)\\b"),
        Intent.COMPARE to Regex("\\b(diferencia|compar|versus|vs|distinto de)\\b"),
        Intent.WHY to Regex("\\b(por que|para que sirve|fundamento|motivo)\\b"),
        Intent.WHEN to Regex("\\b(cuando se|en que casos|en que momento|cuando corresponde|cuando procede)\\b"),
        Intent.USE to Regex("\\b(como se (usa|utiliza|aplica|hace|realiza|completa|labra|redacta)|pasos|procedimiento para)\\b"),
        Intent.HOW to Regex("\\b(como funciona|como opera|en que consiste|como es)\\b"),
        Intent.DEFINITION to Regex("^(que es|que son|que significa|que se entiende|define|definicion|concepto de|significado)\\b|\\b(que es|que son|que significa)\\b"),
    )
    private val followWords = Regex("\\b(eso|esto|este|esta|lo anterior|el anterior|el siguiente|la anterior|lo mismo|ahi|aqui|alli)\\b")
    private val docWords = setOf("manual", "unidad", "modulo", "documento", "apunte", "libro", "presentacion", "capitulo", "pdf", "material", "parte", "pagina", "seccion")

    fun parse(raw: String, knownLaws: Set<String> = emptySet()): ParsedQuery {
        val norm = Norm.text(raw)
        val laws = LinkedHashSet<String>()
        lawNum.findAll(norm).forEach { laws.add(it.groupValues[1]) }
        // Un número de 4-6 cifras que coincide con una ley del corpus también cuenta ("explicá la 23737")
        bareLaw.findAll(norm).forEach { if (it.groupValues[1] in knownLaws) laws.add(it.groupValues[1]) }
        Synonyms.lawsFromAliases(norm).forEach { laws.add(it) }
        val arts = article.findAll(norm).map { it.groupValues[1] + (if (it.groupValues[2].isNotEmpty()) " " + it.groupValues[2] else "") }.toList()
        val incs = inciso.findAll(norm).map { it.groupValues[1] }.toList()
        val intent = when {
            arts.isNotEmpty() && intents.first { it.first == Intent.WHERE }.second.containsMatchIn(norm).not() -> Intent.ARTICLE
            else -> intents.firstOrNull { it.second.containsMatchIn(norm) }?.first
                ?: if (laws.isNotEmpty()) Intent.LAW else Intent.GENERAL
        }
        val numbersInRefs = laws + arts.map { it.substringBefore(' ') } + incs + Synonyms.aliasTokens(norm)
        val tokens = norm.split(' ').filter { it.isNotEmpty() }
        val terms = tokens.filter { t ->
            t !in Norm.stop && t !in numbersInRefs && t.length > 1 &&
                t !in setOf("ley", "leyes", "articulo", "articulos", "inciso", "incisos", "decreto", "resolucion") &&
                t !in intentWords
        }.distinct()
        val quotedPhrase = quoted.find(raw)?.groupValues?.get(1)?.let { Norm.text(it) }
        val phrase = quotedPhrase ?: span(norm, terms)
        // "¿y el artículo 5?", "¿y la pena?": continúa el tema anterior (hereda la ley si no nombra otra)
        val startsWithAnd = norm.startsWith("y ") || norm.startsWith("e ")
        val follow = laws.isEmpty() && (startsWithAnd ||
            terms.size <= 1 && arts.isEmpty() && (followWords.containsMatchIn(norm) || intent in setOf(Intent.SIMPLER, Intent.EXAMPLE, Intent.WHERE)))
        return ParsedQuery(raw, norm, terms, laws.toList(), arts, incs, intent, follow, phrase,
            docTitleWords = terms.filter { it in docWords })
    }

    /**
     * Frase del tema tal como la escribió el usuario, desde el primer término hasta el último, CON las palabras
     * intermedias ("fuero de lucha contra el narcotrafico", "uso racional de la fuerza"). Sirve para coincidencia exacta.
     */
    fun span(norm: String, terms: List<String>): String? {
        if (terms.size !in 2..6) return null
        val toks = norm.split(' ')
        val first = toks.indexOf(terms.first()); val last = toks.lastIndexOf(terms.last())
        if (first < 0 || last <= first || last - first + 1 > terms.size * 2 + 2) return null
        return toks.subList(first, last + 1).joinToString(" ")
    }

    /** Palabras que expresan la intención y no el tema. */
    private val intentWords = setOf(
        "que", "es", "son", "significa", "define", "definicion", "concepto", "significado", "como", "funciona", "opera", "consiste",
        "usa", "utiliza", "aplica", "hace", "realiza", "cuando", "corresponde", "procede", "casos", "momento", "porque", "sirve",
        "fundamento", "motivo", "donde", "aparece", "encuentra", "figura", "habla", "ubicacion", "pagina", "localiza", "parte",
        "facil", "simple", "sencillo", "entendi", "entiendo", "palabras", "resumido", "ejemplo", "ejemplos", "ejemplifica",
        "relacion", "relaciona", "vinculo", "diferencia", "compara", "comparar", "versus", "vs", "distinto", "dice", "establece",
        "explica", "explicame", "explicalo", "explicar", "decir", "dame", "mas", "favor", "hola", "necesito", "entender"
    )

    /** Divide una consulta con varias preguntas ("¿qué es X? ¿y cuándo se usa?") en subconsultas. */
    fun split(raw: String): List<String> {
        val parts = raw.split(Regex("\\?\\s*(?=[¿\\p{L}])|(?<=\\?)\\s*¿|\\s+y\\s+(?:además\\s+)?(?=¿?(?:qué|que|cómo|como|cuándo|cuando|por qué|dónde|donde|cuál|cual)\\b)|;\\s+"))
            .map { it.trim().trim('¿', '?', ' ') }.filter { it.length >= 4 }
        return if (parts.size in 2..4) parts else listOf(raw)
    }
}
