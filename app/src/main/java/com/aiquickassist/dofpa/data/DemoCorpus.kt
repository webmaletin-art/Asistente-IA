package com.aiquickassist.dofpa.data

/**
 * Corpus FICTICIO para probar DOFPA sin materiales reales. Contenido inventado, claramente
 * marcado como de prueba; NO es legislación ni procedimiento real. El usuario reemplaza esto
 * importando sus propios documentos y luego puede borrar los de prueba.
 */
object DemoCorpus {
    val docs: List<Triple<String, String, List<String>>> = listOf(
        Triple(
            "Ley de Prueba 0001 - Convivencia (FICTICIA)", "Legislación", listOf(
                """TÍTULO I - DISPOSICIONES GENERALES
                ARTÍCULO 1. La presente ley de prueba regula la convivencia en el territorio imaginario de Demolandia. Sus normas son de aplicación general y tienen fines exclusivamente demostrativos.
                ARTÍCULO 2. Toda persona tiene derecho a un ambiente ordenado. Las autoridades de prueba velarán por el cumplimiento de estas disposiciones.""",
                """TÍTULO II - DE LAS FALTAS
                ARTÍCULO 3. Constituye falta leve arrojar residuos fuera de los lugares habilitados. La sanción prevista es una advertencia o multa simbólica.
                ARTÍCULO 4. Constituye falta grave alterar el orden público de manera reiterada. La sanción podrá incluir tareas comunitarias de prueba."""
            )
        ),
        Triple(
            "Protocolo de Prueba - Actuación general (FICTICIO)", "Procedimientos", listOf(
                """CAPÍTULO 1 - PRINCIPIOS
                El presente protocolo de prueba describe pasos generales de actuación con fines de formación. No sustituye ningún protocolo oficial ni autoriza decisión operativa alguna.
                Principio de legalidad: toda actuación se ajusta a la normativa vigente. Principio de proporcionalidad: las medidas deben ser adecuadas al fin perseguido.""",
                """CAPÍTULO 2 - VERIFICACIONES
                1. Identificar el objetivo de la actuación. 2. Reunir la documentación necesaria. 3. Registrar observaciones con precisión. 4. Solicitar confirmación de la autoridad competente antes de cualquier medida. Estos pasos son ilustrativos."""
            )
        ),
        Triple(
            "Acta Modelo de Prueba - Inventario (FICTICIA)", "Actas", listOf(
                """ACTA DE INVENTARIO (MODELO DE PRUEBA)
                En la localidad de Demolandia, a los ... días del mes de ..., se procede a inventariar los siguientes elementos: (campo) descripción, (campo) cantidad, (campo) estado.
                Observaciones: ____. Firma del responsable: ____. Este es un modelo ilustrativo; completar con datos reales verificados y revisión humana."""
            )
        ),
        Triple(
            "Unidad Académica de Prueba 1 - Introducción (FICTICIA)", "Académico", listOf(
                """UNIDAD 1 - CONCEPTOS BÁSICOS
                Esta unidad de prueba introduce conceptos generales con fines de estudio. Un concepto es una idea que representa una categoría. Una definición describe el significado de un término.
                Ejemplo: "convivencia" se define como la acción de vivir en compañía de otros respetando normas comunes.""",
                """UNIDAD 1 - REPASO
                Preguntas de repaso de prueba: ¿Qué es un concepto? ¿Qué diferencia hay entre concepto y definición? ¿Por qué son importantes las normas de convivencia? Las respuestas se encuentran en el texto anterior."""
            )
        )
    )
}
