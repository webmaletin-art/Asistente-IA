package com.aiquickassist.data

enum class EngineMode(val label: String) {
    WEB("Visión general creada por IA"),
    GEMINI("Gemini"),
    BOTH("Mejor respuesta — ambos")
}

enum class Tool(val label: String, val glyph: String) {
    TEXT("Texto", "T"),
    OCR("OCR", "▣"),
    IMAGE("Imagen", "□")
}

enum class BubbleStyle(val label: String) {
    NORMAL("Normal"),
    TRANSPARENT("Transparente"),
    NO_BG("Sin fondo"),
    LETTERS("Solo letras"),
    OUTLINE("Solo contorno"),
    SQUARE("Solo cuadro"),
    SQUARE_LETTERS("Cuadro + letras"),
    SHIELD("Modo escudo")
}

enum class QType(val label: String) {
    MULTIPLE_CHOICE("Opción múltiple"),
    MULTI_SELECT("Varias respuestas"),
    TRUE_FALSE("Verdadero/falso"),
    DEFINITION("Definición"),
    TRANSLATION("Traducción"),
    COMPREHENSION("Comprensión"),
    OPEN("Pregunta abierta")
}

data class Option(val label: String, val text: String)

data class ParsedQuestion(
    val type: QType,
    val question: String,
    val options: List<Option> = emptyList(),
    val context: String = "",
    val hasImage: Boolean = false
)

data class Source(val title: String, val url: String, val snippet: String = "")

/** Respuesta de un motor. [choice] contiene las etiquetas elegidas (p. ej. ["B"]). */
data class Block(
    val title: String,
    val choice: List<String> = emptyList(),
    val answer: String = "",
    val explanation: String = "",
    val sources: List<Source> = emptyList(),
    val confidence: Double = 0.0
)

data class AnalysisResult(
    val parsed: ParsedQuestion,
    val mode: EngineMode,
    val best: Block,
    val web: Block? = null,
    val gemini: Block? = null,
    val note: String? = null,
    val related: List<Source> = emptyList(),
    val manual: Boolean = false
) {
    val engineLabel: String
        get() = when {
            mode == EngineMode.BOTH && web != null && gemini != null -> "Ambos"
            mode == EngineMode.BOTH && gemini != null -> "Gemini"
            mode == EngineMode.GEMINI -> "Gemini"
            else -> "Visión general creada por IA"
        }
}

class AnalysisException(message: String, val needsGemini: Boolean = false) : Exception(message)
