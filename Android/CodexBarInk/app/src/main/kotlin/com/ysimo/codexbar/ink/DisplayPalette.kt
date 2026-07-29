package com.ysimo.codexbar.ink

internal enum class DisplayAccent {
    INK,
    BLUE,
    VIOLET,
}

internal object DisplayPalette {
    fun accent(providerID: String, supportsColor: Boolean): DisplayAccent {
        if (!supportsColor) return DisplayAccent.INK
        return when (providerID.lowercase()) {
            "codex" -> DisplayAccent.BLUE
            "claude" -> DisplayAccent.VIOLET
            else -> DisplayAccent.INK
        }
    }
}

internal object BooxDisplayCapability {
    fun supportsColor(colorType: Int?, wideColorGamut: Boolean, model: String): Boolean {
        return colorType?.let { it > 0 } == true ||
            wideColorGamut ||
            model.trim().lowercase().let { normalized ->
                normalized.contains("color") || normalized.endsWith("c")
            }
    }
}
