package com.aiquickassist.capture

/** Colores de lo que hay detrás de la burbuja o del panel (se muestrea una vez, bajo demanda). */
object Camouflage {
    /** Promedio ARGB opaco del rectángulo [x0,x1)×[y0,y1) (se muestrea cada [stride] píxeles). */
    fun average(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, stride: Int = 6): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        var y = y0.coerceIn(0, h)
        val ye = y1.coerceIn(0, h); val xs = x0.coerceIn(0, w); val xe = x1.coerceIn(0, w)
        while (y < ye) {
            var x = xs
            while (x < xe) { val p = px[y * w + x]; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++; x += stride }
            y += stride
        }
        if (n == 0) return 0xFFFFFFFF.toInt()
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** Promedio del anillo que rodea a un cuadrado [x,y,size] (para camuflar la burbuja con su entorno). */
    fun ring(px: IntArray, w: Int, h: Int, x: Int, y: Int, size: Int, pad: Int): Int {
        val strips = listOf(
            intArrayOf(x - pad, y - pad, x + size + pad, y),                 // arriba
            intArrayOf(x - pad, y + size, x + size + pad, y + size + pad),   // abajo
            intArrayOf(x - pad, y, x, y + size),                             // izquierda
            intArrayOf(x + size, y, x + size + pad, y + size)                // derecha
        ).map { average(px, w, h, it[0], it[1], it[2], it[3], 3) }
        val r = strips.sumOf { (it shr 16) and 0xFF } / 4; val g = strips.sumOf { (it shr 8) and 0xFF } / 4; val b = strips.sumOf { it and 0xFF } / 4
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
