package com.aiquickassist.data

enum class EngineMode(val label: String) {
    OVERVIEW("Visión general de Google"),
    AI_MODE("Google AI Mode"),
    GEMINI("Gemini"),
    BOTH("Mejor respuesta — ambos")
}

enum class PanelMode(val label: String) {
    LIGHT("Claro"), DARK("Oscuro"), CAMO("Camuflaje (copia los colores de detrás)"), CUSTOM("Color personalizado")
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

/** Fuente real de una respuesta. Se conserva hasta la UI y nunca se reetiqueta. */
enum class Origin(val label: String) {
    GOOGLE_AI_OVERVIEW("Visión general de Google"),
    GOOGLE_AI_MODE("Google AI Mode"),
    GOOGLE_SEARCH("Google"),
    GEMINI("Gemini"),
    OCR_LOCAL("OCR local"),
    UNKNOWN("Desconocido")
}

/** Respuesta de un motor. [choice] contiene las etiquetas elegidas (p. ej. ["B"]). */
data class Block(
    val origin: Origin,
    val choice: List<String> = emptyList(),
    val answer: String = "",
    val explanation: String = "",
    val sources: List<Source> = emptyList(),
    val confidence: Double = 0.0
) {
    val title: String get() = origin.label
}

data class AnalysisResult(
    val parsed: ParsedQuestion,
    val mode: EngineMode,
    val best: Block,
    val google: Block? = null,
    val gemini: Block? = null,
    val note: String? = null,
    val googleUrl: String? = null,
    val withImage: Boolean = false,
    val manual: Boolean = false
) {
    val engineLabel: String
        get() = if (google != null && gemini != null) "Google + Gemini" else best.origin.label
}

/** [googleUrl]/[openLabel]: permite al usuario continuar en la página real de Google. */
class AnalysisException(
    message: String,
    val needsGemini: Boolean = false,
    val googleUrl: String? = null,
    val openLabel: String? = null
) : Exception(message)
