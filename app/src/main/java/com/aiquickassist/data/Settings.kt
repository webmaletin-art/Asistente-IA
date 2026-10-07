package com.aiquickassist.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

data class BubbleConfig(
    val enabled: Boolean,
    val sizeDp: Int,
    val opacity: Float,
    val color: Int,
    val style: BubbleStyle,
    val discreet: Boolean,
    val hideOnScroll: Boolean,
    val showOnStop: Boolean
)

/** Configuración local persistente, observable desde Compose. */
object Settings {
    private lateinit var sp: SharedPreferences
    private val props = mutableListOf<P<*>>()

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        props.forEach { it.reload() }
    }

    fun reset() {
        sp.edit().clear().apply()
        props.forEach { it.reload() }
    }

    private class P<T>(
        val key: String,
        val def: T,
        val read: (SharedPreferences, String, T) -> T,
        val write: (SharedPreferences.Editor, String, T) -> Unit
    ) : ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(def)
        fun reload() { state.value = read(Settings.sp, key, def) }
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            Settings.sp.edit().also { write(it, key, value) }.apply()
        }
    }

    private fun bool(key: String, def: Boolean) = P(key, def, { s, k, d -> s.getBoolean(k, d) }, { e, k, v -> e.putBoolean(k, v) }).also { props += it }
    private fun int(key: String, def: Int) = P(key, def, { s, k, d -> s.getInt(k, d) }, { e, k, v -> e.putInt(k, v) }).also { props += it }
    private fun float(key: String, def: Float) = P(key, def, { s, k, d -> s.getFloat(k, d) }, { e, k, v -> e.putFloat(k, v) }).also { props += it }
    private fun str(key: String, def: String) = P(key, def, { s, k, d -> s.getString(k, d) ?: d }, { e, k, v -> e.putString(k, v) }).also { props += it }
    private inline fun <reified E : Enum<E>> enum(key: String, def: E) = P(
        key, def,
        { s, k, d -> runCatching { enumValueOf<E>(s.getString(k, d.name) ?: d.name) }.getOrDefault(d) },
        { e, k, v -> e.putString(k, v.name) }
    ).also { props += it }

    // Motor
    var engineMode by enum("engineMode", EngineMode.WEB)
    var geminiEnabled by bool("geminiEnabled", true)
    var geminiModel by str("geminiModel", "gemini-2.5-flash")
    var defaultTool by enum("defaultTool", Tool.TEXT)

    // Búsqueda
    var manualSearch by bool("manualSearch", true)
    var showComplementary by bool("showComplementary", true)

    // Comportamiento
    var quickAnswer by bool("quickAnswer", true)
    var showExplanation by bool("showExplanation", true)
    var showSources by bool("showSources", true)
    var autoClose by bool("autoClose", true)
    var autoCloseSeconds by int("autoCloseSeconds", 8)
    var historyEnabled by bool("historyEnabled", true)

    // Burbuja
    var bubbleEnabled by bool("bubbleEnabled", false)
    var bubbleSizeDp by int("bubbleSizeDp", 52)
    var bubbleOpacity by float("bubbleOpacity", 0.9f)
    var bubbleColor by int("bubbleColor", 0xFF000000.toInt())
    var bubbleStyle by enum("bubbleStyle", BubbleStyle.NORMAL)
    var discreet by bool("discreet", false)
    var hideOnScroll by bool("hideOnScroll", false)
    var showOnStop by bool("showOnStop", true)
    var bubbleX by int("bubbleX", -1)
    var bubbleY by int("bubbleY", -1)

    fun bubbleConfig() = BubbleConfig(
        bubbleEnabled, bubbleSizeDp, bubbleOpacity, bubbleColor, bubbleStyle,
        discreet, hideOnScroll, showOnStop
    )
}
