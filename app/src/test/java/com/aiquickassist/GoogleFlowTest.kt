package com.aiquickassist

import com.aiquickassist.data.*
import com.aiquickassist.engine.Analyzer
import com.aiquickassist.engine.GoogleText
import com.aiquickassist.engine.QuestionParser
import com.aiquickassist.service.SelectionView
import org.junit.Assert.*
import org.junit.Test

class GoogleFlowTest {
    @Test fun originLabelsAreFixed() {
        assertEquals("Visión general de Google", Origin.GOOGLE_AI_OVERVIEW.label)
        assertEquals("Google AI Mode", Origin.GOOGLE_AI_MODE.label)
        assertEquals("Gemini", Block(Origin.GEMINI).title)
        // Un resultado de búsqueda normal jamás se etiqueta como Visión general
        assertNotEquals(Origin.GOOGLE_AI_OVERVIEW.label, Block(Origin.GOOGLE_SEARCH).title)
    }

    @Test fun queryIsTheQuestionNotKeywords() {
        val p = QuestionParser.parseText("¿Las plantas también tienen venas?")!!
        assertEquals("¿Las plantas también tienen venas?", GoogleText.query(p))
        val mc = QuestionParser.parseText("¿Capital de Francia?\nA. Madrid\nB. París")!!
        assertTrue(GoogleText.query(mc).contains("B) París"))
    }

    @Test fun urls() {
        assertTrue(GoogleText.searchUrl("a b").startsWith("https://www.google.com/search?"))
        assertTrue(GoogleText.aiModeUrl("").contains("udm=50"))
        assertFalse(GoogleText.aiModeUrl("").contains("&q="))
        assertTrue(GoogleText.aiModeUrl("hola").endsWith("&q=hola"))
    }

    @Test fun overviewCleaningDropsUiAndRejectsEmpty() {
        val raw = "Visión general creada por IA\nLas plantas tienen haces vasculares con xilema y floema.\nMostrar más\nLas respuestas de IA pueden contener errores. Más información"
        assertEquals("Las plantas tienen haces vasculares con xilema y floema.", GoogleText.cleanOverview(raw))
        assertNull(GoogleText.cleanOverview("Visión general creada por IA\nMostrar más"))
    }

    @Test fun cssGarbageIsRejected() {
        val css = ".zF5l1e{color:var(--m3c23,var(--Nsm0ce))}\n.zF5l1e{color:var(--Nsm0ce);-webkit-margin-before:-2px}.ux3"
        assertNull(GoogleText.cleanOverview(css))
        assertTrue(GoogleText.looksLikeCode(".a{color:red}"))
        assertFalse(GoogleText.looksLikeCode("Una gota de lluvia cae a 9 m/s (aprox.)."))
    }

    @Test fun aiModeCleaningDropsQueryEcho() {
        val t = GoogleText.cleanAiMode("¿Qué es esto?\nModo IA\nEs una arandela plana usada para repartir la carga de un tornillo o tuerca.", "¿Qué es esto?")
        assertEquals("Es una arandela plana usada para repartir la carga de un tornillo o tuerca.", t)
        assertNull(GoogleText.cleanAiMode("Modo IA\nTodo", ""))
    }

    @Test fun comparisonKeepsEnginesSeparate() {
        val g = Block(Origin.GOOGLE_AI_MODE, answer = "Es un tornillo de cabeza hexagonal")
        val m = Block(Origin.GEMINI, answer = "Tornillo hexagonal")
        assertTrue(Analyzer.compare(g, m, null, null)!!.contains("coinciden"))
        assertTrue(Analyzer.compare(null, m, "CAPTCHA", null)!!.contains("solo Gemini"))
        assertTrue(Analyzer.compare(g, null, null, "sin configurar")!!.contains("solo Google"))
    }

    @Test fun cropIsExactlyTheSelectedRectangle() {
        // Vista 1000x2000 sobre captura 1080x2160; rectángulo pequeño
        val c = SelectionView.cropRect(100f, 200f, 300f, 400f, 1000, 2000, 1080, 2160)
        assertArrayEquals(intArrayOf(108, 216, 216, 216), c)
        // Nunca sale de la captura ni es la pantalla completa
        val full = SelectionView.cropRect(-50f, -50f, 5000f, 5000f, 1000, 2000, 1080, 2160)
        assertEquals(1080, full[2] + full[0]); assertEquals(2160, full[3] + full[1])
    }
}

class GeminiModelTest {
    @Test fun picksNewestFastStableModel() {
        val names = listOf("models/gemini-1.5-pro", "models/gemini-2.5-flash", "models/gemini-2.5-pro", "models/gemini-3.0-pro-preview",
            "models/gemini-2.5-flash-image", "models/gemini-2.5-flash-preview-tts", "models/embedding-001", "models/gemini-3.0-flash")
        assertEquals("gemini-3.0-flash", com.aiquickassist.engine.GeminiEngine.pickModel(names))
        assertEquals("gemini-2.5-flash", com.aiquickassist.engine.GeminiEngine.pickModel(names.filter { !it.contains("3.0") }))
        assertNull(com.aiquickassist.engine.GeminiEngine.pickModel(listOf("models/embedding-001")))
        assertEquals(listOf("gemini-3.0-flash", "gemini-3.0-pro-preview", "gemini-2.5-flash"), com.aiquickassist.engine.GeminiEngine.rankModels(names).take(3))
    }
}

class ParserWidgetTest {
    @Test fun ratingWidgetIsNotTheQuestion() {
        val p = QuestionParser.parseText(
            "¿Cuál es la capital de Francia?\nA. Madrid\nB. París\nC. Roma\n" +
                "sección de calificación de la respuesta\nA. No me ayuda presiona Intro para enviar esta calificación\n" +
                "B. Está mal presiona Intro para enviar esta calificación\nC. Buena presiona Intro para enviar esta calificación")!!
        assertEquals("¿Cuál es la capital de Francia?", p.question)
        assertEquals("París", p.options[1].text)
    }

    @Test fun aiModeHeaderIsRemoved() {
        val raw = "Conversación en el Modo IA: ¿Qué es la naturaleza?\nEnviaste 1 imagen y dijiste ¿Qué es la naturaleza?\nLa naturaleza es el conjunto de todo lo que existe en el universo físico y material."
        val t = GoogleText.cleanAiMode(raw, "¿Qué es la naturaleza?")!!
        assertFalse(t.contains("Enviaste")); assertFalse(t.contains("Conversación"))
        assertTrue(t.startsWith("La naturaleza es"))
    }
}

class HighlightTest {
    private fun fill(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, c: Int) { for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = c }

    @Test fun findsTheSelectedBlockAndIgnoresHandles() {
        val w = 400; val h = 600
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        val blue = 0xFFB3D7F7.toInt()                      // resaltado
        fill(px, w, 40, 200, 340, 232, blue)               // línea 1
        fill(px, w, 40, 236, 180, 268, blue)               // línea 2 (interlineado de 4 px)
        fill(px, w, 30, 272, 60, 300, 0xFFAEC8FA.toInt())  // «manija» pequeña (no cuenta)
        fill(px, w, 10, 500, 380, 505, blue)               // franja demasiado fina
        val b = com.aiquickassist.capture.HighlightDetector.find(px, w, h)!!
        assertEquals(40, b[0]); assertEquals(200, b[1]); assertEquals(300, b[2]); assertEquals(68, b[3])
        assertNull(com.aiquickassist.capture.HighlightDetector.find(IntArray(w * h) { 0xFFFFFFFF.toInt() }, w, h))
    }

    @Test fun parserPrefersQuestionNearFocus() {
        val lines = listOf(
            com.aiquickassist.engine.ScreenLine("1. ¿Cuál es el animal más pequeño?", false, 200),
            com.aiquickassist.engine.ScreenLine("2. ¿Con qué animal compartimos más ADN?", false, 1200),
            com.aiquickassist.engine.ScreenLine("3. ¿Cuál es el único mamífero que vuela?", false, 2200)
        )
        assertEquals("¿Con qué animal compartimos más ADN?", QuestionParser.parse(lines, 1150)!!.question.removePrefix("2. "))
    }
}

class CleanupTest {
    @Test fun formulaLettersAreMergedAndLeadIsFirstParagraph() {
        val raw = "El perímetro de este rectángulo es 24.\nFórmula:\nP\ne\nr\nm\n=\n2\nPor lo tanto es 24 y esa es la opción correcta de las que hay."
        val t = GoogleText.cleanAiMode(raw, "")!!
        assertFalse(t.lines().any { it.length == 1 })
        assertEquals("El perímetro de este rectángulo es 24.", GoogleText.lead(t))
    }

    @Test fun darkHighlightIsFound() {
        val w = 300; val h = 400
        val px = IntArray(w * h) { 0xFF101010.toInt() }
        for (y in 100 until 130) for (x in 30 until 250) px[y * w + x] = 0xFF2F5A94.toInt()
        val b = com.aiquickassist.capture.HighlightDetector.findAny(px, w, h)!!
        assertEquals(30, b[0]); assertEquals(100, b[1]); assertEquals(220, b[2])
        assertTrue(com.aiquickassist.capture.HighlightDetector.stats(px).contains("azul oscuro="))
    }
}

class CopyFlowTest {
    @Test fun copiedStatementBecomesTrueFalse() {
        val p = QuestionParser.parseText("La capital de Francia es París y está a orillas del Sena.")!!
        assertEquals(QType.TRUE_FALSE, p.type)
        assertEquals("V", p.options[0].label)
        assertEquals("¿Es verdad que La capital de Francia es París y está a orillas del Sena?", GoogleText.queryFor(p))
        // Una pregunta o una definición no se tratan como afirmación
        assertEquals(QType.DEFINITION, QuestionParser.parseText("¿Qué es la fotosíntesis?")!!.type)
        assertEquals(QType.OPEN, QuestionParser.parseText("Vista web")!!.type)
    }

    @Test fun annotateOnlyWhenGoogleSaysSo() {
        val tf = QuestionParser.parseText("La capital de Francia es París y está a orillas del Sena.")!!
        val yes = Analyzer.annotate(tf, Block(Origin.GOOGLE_AI_OVERVIEW, answer = "Sí, París es la capital de Francia."))
        assertEquals(listOf("V"), yes.choice)
        val no = Analyzer.annotate(tf, Block(Origin.GOOGLE_AI_OVERVIEW, answer = "No, la capital es otra."))
        assertEquals(listOf("F"), no.choice)
        assertTrue(Analyzer.annotate(tf, Block(Origin.GOOGLE_AI_OVERVIEW, answer = "Depende del contexto histórico.")).choice.isEmpty())

        val mc = QuestionParser.parseText("¿Capital de Francia?\nA. Madrid\nB. París\nC. Roma")!!
        assertEquals(listOf("B"), Analyzer.annotate(mc, Block(Origin.GOOGLE_AI_OVERVIEW, answer = "La capital de Francia es París.")).choice)
        // Dos opciones citadas → ambiguo → sin elección
        assertTrue(Analyzer.annotate(mc, Block(Origin.GOOGLE_AI_OVERVIEW, answer = "Madrid y París son capitales.")).choice.isEmpty())
    }
}

class CamouflageTest {
    @Test fun averageAndRing() {
        val w = 100; val h = 100
        val px = IntArray(w * h) { 0xFF102030.toInt() }           // fondo oscuro azulado
        for (y in 40 until 60) for (x in 40 until 60) px[y * w + x] = 0xFFFFFFFF.toInt()   // «burbuja» blanca en el centro
        assertEquals(0xFF102030.toInt(), com.aiquickassist.capture.Camouflage.average(px, w, h, 0, 0, 30, 30))
        assertEquals(0xFF102030.toInt(), com.aiquickassist.capture.Camouflage.ring(px, w, h, 40, 40, 20, 10))   // ignora la burbuja
        assertEquals(0xFFFFFFFF.toInt(), com.aiquickassist.capture.Camouflage.average(IntArray(0), 0, 0, 0, 0, 5, 5))
    }
}
