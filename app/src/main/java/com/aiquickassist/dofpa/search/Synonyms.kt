package com.aiquickassist.dofpa.search

/**
 * Sinónimos, abreviaturas y nombres comunes de leyes del dominio. Amplían la búsqueda con PESO MENOR
 * que el término original: nunca reemplazan una coincidencia exacta.
 */
object Synonyms {
    /** término normalizado → equivalentes (normalizados; pueden ser frases). */
    private val map: Map<String, List<String>> = mapOf(
        "droga" to listOf("estupefaciente", "sustancia"), "drogas" to listOf("estupefacientes", "sustancias"),
        "estupefaciente" to listOf("droga"), "estupefacientes" to listOf("drogas"),
        "narcotrafico" to listOf("trafico estupefacientes", "comercio estupefacientes"),
        "narco" to listOf("narcotrafico"), "narcomenudeo" to listOf("microtrafico", "comercializacion estupefacientes"),
        "microtrafico" to listOf("narcomenudeo"),
        "allanamiento" to listOf("registro domiciliario"), "requisa" to listOf("registro personal"),
        "secuestro" to listOf("incautacion"), "incautacion" to listOf("secuestro"), "decomiso" to listOf("secuestro"),
        "aprehension" to listOf("detencion"), "detencion" to listOf("aprehension"), "arresto" to listOf("detencion"),
        "fiscal" to listOf("ministerio publico"), "juez" to listOf("magistrado"),
        "transito" to listOf("vial"), "vial" to listOf("transito"), "choque" to listOf("colision", "siniestro"),
        "accidente" to listOf("siniestro", "colision"), "auto" to listOf("automotor", "vehiculo"), "moto" to listOf("motovehiculo"),
        "policia" to listOf("policial"), "agente" to listOf("efectivo", "funcionario"),
        "arma" to listOf("armamento"), "tiro" to listOf("disparo"), "fuerza" to listOf("coercion"),
        "ddhh" to listOf("derechos humanos"), "dd hh" to listOf("derechos humanos"),
        "cp" to listOf("codigo penal"), "cpp" to listOf("codigo procesal penal"), "cppn" to listOf("codigo procesal penal"),
        "cn" to listOf("constitucion nacional"), "fpa" to listOf("fuerza policial antinarcotrafico"),
        "mpf" to listOf("ministerio publico fiscal"), "upc" to listOf("universidad provincial cordoba"),
        "alcoholemia" to listOf("alcohol sangre"), "toxicologia" to listOf("toxico"), "rcp" to listOf("reanimacion cardiopulmonar"),
        "primeros auxilios" to listOf("auxilio"), "genero" to listOf("violencia genero"), "contravencion" to listOf("falta"),
    )

    /** Nombres comunes → número de ley. */
    val lawAliases: Map<String, List<String>> = mapOf(
        "ley de drogas" to listOf("23737"), "ley de estupefacientes" to listOf("23737"), "ley de narcotrafico" to listOf("23737"),
        // En Córdoba rige la ley provincial 9169, que adhiere a la nacional 24.449: se buscan ambas
        "ley de transito" to listOf("9169", "24449"), "ley nacional de transito" to listOf("24449"), "ley provincial de transito" to listOf("9169"),
        "ley de etica publica" to listOf("25188"), "etica en el ejercicio de la funcion publica" to listOf("25188"),
        "ley de desfederalizacion" to listOf("10067"), "desfederalizacion" to listOf("10067"),
        "ley de seguridad publica" to listOf("10954"), "seguridad ciudadana" to listOf("10954"),
    )

    fun of(term: String): List<String> = map[term].orEmpty()

    /** Expresiones de la consulta → cómo las dice el texto legal (frases exactas). */
    private val phraseMap: Map<String, List<String>> = mapOf(
        "consumo personal" to listOf("uso personal"), "uso personal" to listOf("consumo personal"),
        "tenencia simple" to listOf("tuviere en su poder"), "tenencia de drogas" to listOf("tuviere en su poder estupefacientes"),
        "venta de drogas" to listOf("comercie con estupefacientes", "comercializacion de estupefacientes"),
        "cultivo" to listOf("siembre o cultive"), "orden de allanamiento" to listOf("orden de registro"),
        "uso de la fuerza" to listOf("empleo de la fuerza"), "legitima defensa" to listOf("defensa propia"),
    )

    fun phrases(norm: String): List<String> = phraseMap.filterKeys { norm.contains(it) }.values.flatten().distinct()

    /** Palabras consumidas por un alias de ley ("ley de drogas" → "drogas"): dejan de ser tema. */
    fun aliasTokens(norm: String): Set<String> =
        lawAliases.keys.filter { norm.contains(it) }.flatMap { it.split(' ') }.toSet()

    fun lawsFromAliases(norm: String): List<String> {
        val hits = lawAliases.filterKeys { norm.contains(it) }
        // Si coinciden alias anidados ("ley de etica publica" ⊂ frase más larga) gana el más largo
        val longest = hits.keys.filter { k -> hits.keys.none { o -> o != k && o.contains(k) } }
        return longest.flatMap { hits[it]!! }.distinct()
    }
}
