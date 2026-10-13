package com.theveloper.pixelplay.data.sonos.protocol

internal object XmlText {

    /** Escapes [text] for use as element content or attribute value, dropping characters XML 1.0 forbids. */
    fun escape(text: String): String {
        val out = StringBuilder(text.length)
        text.codePoints().forEach { codePoint ->
            when {
                !isValidXmlCodePoint(codePoint) -> Unit
                codePoint == '&'.code -> out.append("&amp;")
                codePoint == '<'.code -> out.append("&lt;")
                codePoint == '>'.code -> out.append("&gt;")
                codePoint == '"'.code -> out.append("&quot;")
                codePoint == '\''.code -> out.append("&apos;")
                else -> out.appendCodePoint(codePoint)
            }
        }
        return out.toString()
    }

    private fun isValidXmlCodePoint(codePoint: Int): Boolean =
        codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
            codePoint in 0x20..0xD7FF ||
            codePoint in 0xE000..0xFFFD ||
            codePoint in 0x10000..0x10FFFF
}
