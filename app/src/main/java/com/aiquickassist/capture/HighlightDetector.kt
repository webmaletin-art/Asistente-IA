package com.aiquickassist.capture

/**
 * Localiza el resaltado azul claro de una selección de texto en una captura (sin depender de que la
 * app exponga la selección por accesibilidad). Trabaja sobre píxeles ARGB; no usa APIs de Android.
 */
object HighlightDetector {
    /** Azul claro del resaltado de selección (modo claro). */
    fun isHighlight(p: Int): Boolean {
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        return b >= 225 && g in 190..245 && r in 130..215 && b - r >= 35 && b - g >= 5
    }

    /** Azul medio-oscuro del resaltado en páginas oscuras. */
    fun isDarkHighlight(p: Int): Boolean {
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        return b in 110..215 && r <= 110 && g in 80..165 && b - r >= 60 && b - g >= 25
    }

    /** Prueba el resaltado claro y, si no hay, el oscuro. */
    fun findAny(px: IntArray, w: Int, h: Int): IntArray? = find(px, w, h, 70, ::isHighlight) ?: find(px, w, h, 70, ::isDarkHighlight)

    /** Diagnóstico para el Modo test: cuántos píxeles de cada clase y los colores azulados más frecuentes. */
    fun stats(px: IntArray): String {
        var light = 0; var dark = 0
        val hist = HashMap<Int, Int>()
        var i = 0
        while (i < px.size) {
            val p = px[i]
            if (isHighlight(p)) light++
            if (isDarkHighlight(p)) dark++
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            if (b - r >= 30 && b - g >= 10) { val q = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4); hist[q] = (hist[q] ?: 0) + 1 }
            i += 1
        }
        val top = hist.entries.sortedByDescending { it.value }.take(3).joinToString(", ") {
            "#%02X%02X%02X×%d".format(((it.key shr 8) and 0xF) * 17, ((it.key shr 4) and 0xF) * 17, (it.key and 0xF) * 17, it.value)
        }
        return "px azul claro=$light · azul oscuro=$dark · azulados frecuentes: ${top.ifEmpty { "ninguno" }}"
    }

    /** Devuelve [x, y, ancho, alto] del bloque resaltado más grande, o null si no hay. */
    fun find(px: IntArray, w: Int, h: Int, minRun: Int = 70, test: (Int) -> Boolean = ::isHighlight): IntArray? {
        // 1) por fila: tramo continuo más largo (admite huecos de 4 px entre letras)
        val runStart = IntArray(h) { -1 }; val runEnd = IntArray(h) { -1 }
        for (y in 0 until h) {
            var best = 0; var bs = -1; var be = -1
            var cs = -1; var last = -100
            for (x in 0 until w) {
                if (test(px[y * w + x])) {
                    if (cs < 0 || x - last > 4) cs = x
                    last = x
                    if (last - cs + 1 > best) { best = last - cs + 1; bs = cs; be = last }
                }
            }
            if (best >= minRun) { runStart[y] = bs; runEnd[y] = be }
        }
        // 2) unir filas contiguas (huecos ≤ 14 px = interlineado) en bloques
        data class Band(var top: Int, var bottom: Int, var l: Int, var r: Int, var area: Long)
        val bands = mutableListOf<Band>()
        var cur: Band? = null; var gap = 0
        for (y in 0 until h) {
            if (runStart[y] >= 0) {
                val c = cur
                if (c != null && gap <= 14) {
                    c.bottom = y; c.l = minOf(c.l, runStart[y]); c.r = maxOf(c.r, runEnd[y]); c.area += runEnd[y] - runStart[y] + 1
                } else { cur = Band(y, y, runStart[y], runEnd[y], (runEnd[y] - runStart[y] + 1).toLong()); bands += cur }
                gap = 0
            } else if (cur != null) gap++
        }
        val best = bands.filter { it.bottom - it.top + 1 >= 14 && it.bottom - it.top + 1 <= h * 0.55 }.maxByOrNull { it.area } ?: return null
        return intArrayOf(best.l, best.top, best.r - best.l + 1, best.bottom - best.top + 1)
    }
}
