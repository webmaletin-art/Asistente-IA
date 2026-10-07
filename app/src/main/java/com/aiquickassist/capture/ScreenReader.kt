package com.aiquickassist.capture

import android.view.accessibility.AccessibilityNodeInfo
import com.aiquickassist.engine.ScreenLine

class ScreenSnapshot(val selected: String?, val lines: List<ScreenLine>)

/** Extrae el texto visible mediante el árbol de accesibilidad (sin capturas ni OCR). */
object ScreenReader {
    private const val MAX_NODES = 4000
    private const val MAX_CHARS = 14000

    fun read(root: AccessibilityNodeInfo?, ownPackage: String): ScreenSnapshot? {
        if (root == null || root.packageName == ownPackage) return null
        val lines = mutableListOf<ScreenLine>()
        var selected: String? = null
        var nodes = 0
        var chars = 0

        fun visit(n: AccessibilityNodeInfo, optionParent: Boolean) {
            if (nodes++ > MAX_NODES || chars > MAX_CHARS || !n.isVisibleToUser) return
            val cls = n.className?.toString().orEmpty()
            val isOpt = n.isCheckable || cls.endsWith("RadioButton") || cls.endsWith("CheckBox")
            val text = n.text?.toString()
            if (selected == null && text != null && n.textSelectionStart >= 0 && n.textSelectionEnd > n.textSelectionStart &&
                n.textSelectionEnd <= text.length
            ) selected = text.substring(n.textSelectionStart, n.textSelectionEnd)

            val label = text?.takeIf { it.isNotBlank() }
                ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() && n.childCount == 0 }
            if (label != null && !(n.isEditable && cls.endsWith("EditText"))) {
                lines += ScreenLine(label, isOpt || optionParent)
                chars += label.length
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, isOpt && label == null) }
        }
        visit(root, false)
        return ScreenSnapshot(selected?.takeIf { it.isNotBlank() }, lines)
    }
}
