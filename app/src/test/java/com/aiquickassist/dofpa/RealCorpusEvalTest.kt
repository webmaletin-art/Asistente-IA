package com.aiquickassist.dofpa

import com.aiquickassist.dofpa.search.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Evaluación del buscador sobre el CORPUS REAL (paquete maestro descomprimido).
 * El corpus es privado: NO está en el repositorio ni en CI. Esta prueba solo corre si existe el archivo
 * indicado en la variable DOFPA_CORPUS (o en /root/dofpa_work/out/dofpa_corpus.db); si no, se omite.
 * Imprime un informe (consulta → documento/página/sección/tiempo) y verifica los casos críticos.
 */
class RealCorpusEvalTest {
    companion object {
        var engine: SearchEngine? = null
        val report = StringBuilder()
        var hashOk = false

        @BeforeClass @JvmStatic fun open() {
            val src = (System.getenv("DOFPA_CORPUS") ?: "/root/dofpa_work/out/dofpa_corpus.db").let(::File)
            if (!src.exists()) return
            val tmp = File.createTempFile("dofpa_eval", ".db").also { it.deleteOnExit() }
            src.copyTo(tmp, overwrite = true)
            val store = CorpusStore(JdbcSql("jdbc:sqlite:${tmp.path}"))
            // Misma verificación de integridad que hace la app al importar el paquete
            hashOk = store.meta("content_sha256") == com.aiquickassist.dofpa.data.CorpusRepo.contentHash(store)
            store.createSchema()
            val t0 = System.currentTimeMillis()
            store.reindex()
            report.appendLine("Índice construido en ${System.currentTimeMillis() - t0} ms · ${store.counts()}")
            engine = SearchEngine(store)
        }
    }

    private fun q(text: String, prev: ParsedQuery? = null): SearchResult {
        val e = engine; assumeTrue("Corpus real no disponible", e != null)
        val r = e!!.search(text, prev)
        report.appendLine("\nQ: $text  (${r.millis} ms)${r.corrections.takeIf { it.isNotEmpty() }?.let { " corr=$it" } ?: ""}")
        r.notFound?.let { report.appendLine("   NO ENCONTRADO: $it") }
        r.hits.take(3).forEachIndexed { i, h ->
            report.appendLine("   ${i + 1}. ${h.doc.title} · p.${h.passage.page} · ${h.passage.section ?: h.passage.chapter ?: "-"} · ${"%.1f".format(h.score)} ${h.reasons}")
        }
        File(System.getProperty("java.io.tmpdir"), "dofpa_eval_report.txt").writeText(report.toString())
        return r
    }

    private fun SearchResult.top() = hits.first()

    @Test fun e00_integridad_del_paquete() { assumeTrue(engine != null); assertTrue("content_sha256 no coincide", hashOk) }
    @Test fun e01_explica_23737() { val r = q("Explica la 23.737"); assertEquals("23737", r.top().doc.law) }
    @Test fun e02_que_es_la_ley_23737() { val r = q("¿qué es la ley 23737?"); assertEquals("23737", r.top().doc.law) }
    @Test fun e03_articulo_14_23737() {
        val r = q("¿Qué dice el artículo 14 de la ley 23.737?")
        assertEquals("23737", r.top().doc.law); assertEquals("14", r.top().passage.article)
    }
    @Test fun e04_art_5_inciso_c() { val r = q("art 5 inc c ley 23737"); assertEquals("23737", r.top().doc.law); assertEquals("5", r.top().passage.article) }
    @Test fun e05_ley_de_drogas_alias() {
        val r = q("qué dice la ley de drogas sobre la tenencia para consumo personal")
        assertEquals("23737", r.top().doc.law); assertEquals("14", r.top().passage.article)
    }
    @Test fun e06_ley_9169_no_se_confunde() { val r = q("Explica la ley 9169"); assertEquals("9169", r.top().doc.law) }
    @Test fun e07_ley_10067() { val r = q("ley 10067"); assertEquals("10067", r.top().doc.law) }
    @Test fun e08_manual_ubicacion_no_portada() {
        val r = q("¿En qué parte del Manual de Estudio habla del fuero de lucha contra el narcotráfico?")
        assertTrue(r.top().doc.title.contains("Manual", true))
        assertTrue("no debe ser la portada", r.top().passage.page > 2)
        assertTrue("debe hablar del fuero", Norm.text(r.top().passage.text).contains("fuero de lucha"))
    }
    @Test fun e09_aquaplaning() { val r = q("¿qué es el aquaplaning?"); assertTrue(r.top().doc.subject!!.contains("Conducción")) }
    @Test fun e10_typo() { val r = q("que es el aquaplanin"); assertTrue(r.top().doc.subject!!.contains("Conducción")) }
    @Test fun e11_requisa() { val r = q("¿qué es la requisa?"); assertNull(r.notFound) }
    @Test fun e12_uso_fuerza() { val r = q("principios del uso racional de la fuerza"); assertTrue(r.top().doc.subject!!.contains("Procedimientos")) }
    @Test fun e13_dosis_umbral() { val r = q("qué es la dosis umbral"); assertNull(r.notFound) }
    @Test fun e14_constitucion_art18() {
        val r = q("artículo 18 de la constitución nacional")
        assertTrue(r.top().doc.title.contains("constitucion nacional", true)); assertEquals("18", r.top().passage.article)
    }
    @Test fun e15_codigo_penal_homicidio() { val r = q("artículo 79 código penal"); assertTrue(r.top().doc.title.contains("penal", true)); assertEquals("79", r.top().passage.article) }
    @Test fun e16_no_encontrado() { val r = q("receta de pizza napolitana con mozzarella"); assertNotNull(r.notFound) }
    @Test fun e17_seguimiento() {
        val a = q("Explica la 23.737"); val b = q("¿y el artículo 5?", a.query)
        assertEquals("23737", b.top().doc.law); assertEquals("5", b.top().passage.article)
    }
    @Test fun e18_etica_funcion_publica() { val r = q("ley de ética en el ejercicio de la función pública"); assertEquals("25188", r.top().doc.law) }
    @Test fun e19_velocidad_transito() { val r = q("velocidad máxima en zona urbana ley de tránsito"); assertTrue(r.top().doc.law in setOf("9169", "24449")) }
    @Test fun e20_acta_inventario() { val r = q("cómo se hace un acta de inventario de automotor"); assertEquals("acta", r.top().doc.kind) }
    @Test fun e22_codigo_procesal_cordoba() { val r = q("artículo 1 del código procesal penal de córdoba"); assertTrue(r.top().doc.title.contains("Córdoba")); assertEquals("1", r.top().passage.article) }
    @Test fun e23_sinonimo_droga_estupefaciente() { val r = q("qué es un estupefaciente"); assertNull(r.notFound) }
    @Test fun e24_typo_estupefasiente() { val r = q("que es un estupefasiente"); assertTrue(r.corrections.isNotEmpty()); assertNull(r.notFound) }
    @Test fun e25_frase_uso_racional() { val r = q("uso racional de la fuerza"); assertTrue(Norm.text(r.top().passage.text).contains("uso racional")) }
    @Test fun e26_ley_inexistente() { val r = q("qué dice la ley 99999"); assertNotNull(r.notFound) }
    @Test fun e21_velocidad() {
        val qs = listOf("Explica la 23.737", "qué es el aquaplaning", "artículo 18 constitución", "fuero narcotráfico córdoba", "requisa sin orden judicial")
        val e = engine; assumeTrue(e != null)
        val times = qs.map { s -> val t = System.nanoTime(); e!!.search(s + " x"); (System.nanoTime() - t) / 1_000_000 }
        report.appendLine("\nTiempos (sin caché): $times ms")
        File(System.getProperty("java.io.tmpdir"), "dofpa_eval_report.txt").writeText(report.toString())
        assertTrue("búsqueda > 500 ms: $times", times.max() < 500)
    }
}
