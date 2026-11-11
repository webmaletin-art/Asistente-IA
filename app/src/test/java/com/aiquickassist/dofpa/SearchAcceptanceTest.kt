package com.aiquickassist.dofpa

import com.aiquickassist.dofpa.search.*
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

/**
 * Pruebas de ACEPTACIÓN del buscador (criterios del usuario) sobre SQLite real con un corpus FICTICIO
 * que reproduce los errores observados: el Manual menciona la Ley 23.737 junto a la Ley provincial 10.067,
 * tiene portada e índice que coinciden con su nombre, etc. No contiene material institucional.
 */
class SearchAcceptanceTest {
    companion object {
        lateinit var store: CorpusStore
        lateinit var engine: SearchEngine

        @BeforeClass @JvmStatic fun build() {
            store = CorpusStore(JdbcSql()).apply { createSchema() }
            fun doc(title: String, kind: String, pages: List<Pair<Int, List<Triple<String?, String?, String>>>>): Long {
                val id = store.insertDocument(CorpusDoc(0, title, "$title.pdf", kind, null, null, null, pages.size, 0, "", "OK", null, "test"))
                var o = 0
                pages.forEach { (pg, chunks) -> chunks.forEach { (ch, sec, t) -> store.insertPassage(id, o++, pg, ch, sec, t) } }
                return id
            }
            doc("Manual de Estudio FPA", "apunte", listOf(
                1 to listOf(Triple(null, null, "Manual de Estudio para Aspirantes a la Fuerza Policial Antinarcotráfico (FPA). Provincia de Córdoba. Edición 2025.")),
                2 to listOf(Triple(null, "Índice", "Capítulo 1 ........ 3\nCapítulo 2 ........ 10\nCapítulo 3 ........ 20\nCapítulo 4 ........ 30")),
                30 to listOf(Triple("Capítulo 4: El Fuero de Lucha contra el Narcotráfico en la Provincia", "Ley provincial 10.067",
                    "La Ley provincial N.º 10.067 fue un hito en la política criminal de Córdoba: aceptó la competencia para investigar los delitos de " +
                    "estupefacientes de la Ley Nacional N.º 23.737 y creó un fuero especializado. La Ley 10.067 organizó juzgados y fiscalías. La Ley 10.067 es provincial.")),
                41 to listOf(Triple("Capítulo 5: Seguridad vial", "Aquaplaning",
                    "El aquaplaning es la pérdida de contacto entre el neumático y el pavimento por una capa de agua. Ante el aquaplaning no se debe frenar bruscamente.")),
                42 to listOf(Triple("Capítulo 5: Seguridad vial", "Distancia de frenado",
                    "La distancia de frenado aumenta con la velocidad y con el pavimento mojado; el aquaplaning la hace todavía mayor.")),
                50 to listOf(Triple("Capítulo 6: Procedimientos", "Requisa",
                    "La requisa es la inspección de una persona y de sus pertenencias realizada conforme a la ley y bajo control judicial.")),
            ))
            doc("Ley 23.737 - Estupefacientes", "ley", listOf(
                1 to listOf(
                    Triple(null, "ARTÍCULO 1°", "ARTÍCULO 1° - Ley de prueba ficticia sobre estupefacientes. Objeto de la ley: regular la tenencia y el tráfico de estupefacientes."),
                    Triple(null, "ARTÍCULO 5°", "ARTÍCULO 5° - Será reprimido quien siembre, produzca o comercie estupefacientes sin autorización (texto ficticio de prueba)."),
                    Triple(null, "ARTÍCULO 14", "ARTÍCULO 14 - Será reprimido quien tuviere en su poder estupefacientes (texto ficticio)."))
            ))
            doc("Ley Pcial 10067 - Desfederalización", "ley", listOf(
                1 to listOf(Triple(null, "ARTÍCULO 1°", "ARTÍCULO 1° - La Provincia adhiere a la Ley 26.052 y asume competencia en los delitos de estupefacientes previstos (texto ficticio)."))
            ))
            store.reindex()
            engine = SearchEngine(store)
        }
    }

    private fun top(q: String, prev: ParsedQuery? = null) = engine.search(q, prev)

    @Test fun c01_ley23737_responde_sobre_esa_ley() {
        val r = top("Explica la 23.737")
        assertEquals("23737", r.hits.first().doc.law)
        assertNull(r.notFound)
    }

    @Test fun c02_articulo_concreto() {
        val r = top("¿Qué dice el artículo 5 de la ley 23737?")
        assertEquals("5", r.hits.first().passage.article)
        assertEquals("23737", r.hits.first().doc.law)
    }

    @Test fun c03_pagina_de_un_concepto_no_la_portada() {
        val r = top("¿En qué página aparece el aquaplaning?")
        assertEquals(41, r.hits.first().passage.page)
        assertTrue(r.relatedPages.contains(42))
    }

    @Test fun c04_sinonimo() {
        val r = top("¿qué dice sobre la tenencia de drogas?")
        assertTrue(Norm.text(r.hits.first().passage.text).contains("estupefacientes"))
    }

    @Test fun c05_dos_preguntas() {
        val parts = QueryParser.split("¿Qué es el aquaplaning? ¿Y qué es la requisa?")
        assertEquals(2, parts.size)
        assertTrue(top(parts[0]).hits.first().passage.text.contains("aquaplaning"))
        assertTrue(top(parts[1]).hits.first().passage.text.contains("requisa"))
    }

    @Test fun c06_admite_que_no_lo_encuentra() {
        assertNotNull(top("¿Cuál es la capital de Mongolia?").notFound)
        assertNotNull(top("Explicame la ley 99999").notFound)
    }

    @Test fun c07_no_confunde_leyes_relacionadas() {
        assertEquals("10067", top("ley 10.067").hits.first().doc.law)
        assertEquals("23737", top("ley 23737").hits.first().doc.law)
    }

    @Test fun c08_las_fuentes_corresponden() {
        val r = top("¿Qué es la requisa?")
        assertEquals("Requisa", r.hits.first().passage.section)
        assertTrue(r.hits.first().snippet.contains("requisa", ignoreCase = true))
    }

    @Test fun c09_typo() {
        val r = top("estupefasientes")
        assertEquals("estupefacientes", r.corrections["estupefasientes"])
        assertTrue(r.hits.isNotEmpty())
    }

    @Test fun c10_ubicacion_en_documento_nombrado_usa_contexto_y_no_devuelve_portada() {
        val prev = top("¿Qué es el aquaplaning?").query
        val r = top("¿En qué parte del Manual de Estudio FPA aparece?", prev)
        assertEquals("Manual de Estudio FPA", r.docFilter?.title)
        assertEquals(41, r.hits.first().passage.page)
        assertTrue(r.hits.none { it.passage.page <= 2 })
    }

    @Test fun c11_documento_nombrado_sin_tema_pide_aclaracion() {
        val r = top("¿En qué parte del Manual de Estudio FPA?")
        assertTrue(r.hits.isEmpty()); assertNotNull(r.notFound)
    }

    @Test fun c12_velocidad() {
        val qs = listOf("Explica la 23.737", "aquaplaning", "¿qué es la requisa?", "artículo 14", "ley 10067 competencia")
        qs.forEach { engine.search(it) } // calentamiento
        val ms = qs.map { q -> val t = System.nanoTime(); SearchEngine(store).search(q); (System.nanoTime() - t) / 1_000_000 }
        println("Tiempos (ms) corpus de prueba: $ms")
        assertTrue(ms.max() < 500)
    }

    @Test fun c13_intenciones() {
        assertEquals(Intent.WHERE, QueryParser.parse("¿En qué página aparece la requisa?").intent)
        assertEquals(Intent.DEFINITION, QueryParser.parse("¿Qué es la requisa?").intent)
        assertEquals(Intent.ARTICLE, QueryParser.parse("artículo 14 de la ley 23737").intent)
        assertEquals(Intent.SIMPLER, QueryParser.parse("explicámelo más fácil").intent)
        assertTrue(QueryParser.parse("explicámelo más fácil").followUp)
    }

    @Test fun c14_normalizacion() {
        assertEquals("ley 23737", Norm.text("Ley N° 23.737"))
        assertEquals("articulo 5", Norm.text("Art. 5°"))
        assertEquals("23737", Refs.lawOfTitle("ley_23.737_-_estupefacientes.pdf"))
        assertEquals("9169", Refs.lawOfTitle("04-LEY-9169-T.O.-LEY-DE-TRANSITO-CON-9688-Y-9804.pdf"))
        assertEquals("10200", Refs.lawOfTitle("Ley Pcial 10200.pdf"))
        assertEquals("5", Refs.articleOf("ARTÍCULO 5°.-"))
    }
}
