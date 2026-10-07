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

    /** Devuelve [x, y, ancho, alto] del bloque resaltado más grande, o null si no hay. */
    fun find(px: IntArray, w: Int, h: Int, minRun: Int = 70): IntArray? {
        // 1) por fila: tramo continuo más largo (admite huecos de 4 px entre letras)
        val runStart = IntArray(h) { -1 }; val runEnd = IntArray(h) { -1 }
        for (y in 0 until h) {
            var best = 0; var bs = -1; var be = -1
            var cs = -1; var last = -100
            for (x in 0 until w) {
                if (isHighlight(px[y * w + x])) {
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
