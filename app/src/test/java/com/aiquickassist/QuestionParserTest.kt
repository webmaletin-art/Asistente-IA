package com.aiquickassist

import com.aiquickassist.data.QType
import com.aiquickassist.engine.QuestionParser
import com.aiquickassist.engine.ScreenLine
import org.junit.Assert.*
import org.junit.Test

class QuestionParserTest {
    @Test fun multipleChoiceWithLetters() {
        val p = QuestionParser.parseText("¿Qué es un automóvil?\nA. Animal\nB. Vehículo motorizado\nC. Planta\nD. Mineral")!!
        assertEquals(QType.MULTIPLE_CHOICE, p.type)
        assertEquals("¿Qué es un automóvil?", p.question)
        assertEquals(4, p.options.size)
        assertEquals("Vehículo motorizado", p.options[1].text)
    }

    @Test fun formRadioOptionsWithoutLetters() {
        val p = QuestionParser.parse(listOf(
            ScreenLine("Pregunta"), ScreenLine("1. ¿Capital de Francia?"),
            ScreenLine("Madrid", true), ScreenLine("París", true), ScreenLine("Roma", true), ScreenLine("Siguiente")
        ))!!
        assertEquals(QType.MULTIPLE_CHOICE, p.type)
        assertEquals("¿Capital de Francia?", p.question)
        assertEquals("París", p.options[1].text)
    }

    @Test fun trueFalseAndDefinition() {
        assertEquals(QType.TRUE_FALSE, QuestionParser.parseText("El sol es una estrella. Verdadero o falso")!!.type)
        assertEquals(QType.DEFINITION, QuestionParser.parseText("¿Qué es la fotosíntesis?")!!.type)
    }

    @Test fun ocrRadioBullets() {
        val p = QuestionParser.parseText("¿Color del cielo?\nO Azul\nO Verde\nO Rojo", ocr = true)!!
        assertEquals(3, p.options.size)
        assertEquals("Azul", p.options[0].text)
    }
}
