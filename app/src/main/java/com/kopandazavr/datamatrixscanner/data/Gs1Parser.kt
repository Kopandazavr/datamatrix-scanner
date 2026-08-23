package com.kopandazavr.datamatrixscanner.data

import java.nio.charset.StandardCharsets

object Gs1Parser {
    private const val GS = '\u001D'

    fun parse(rawBytes: ByteArray, fallbackText: String?): ParsedGs1 {
        val raw = rawBytes.toString(StandardCharsets.ISO_8859_1).trimStart(GS)
        val gtin = if (raw.startsWith("01") && raw.length >= 16 && raw.substring(2, 16).all(Char::isDigit)) {
            raw.substring(2, 16)
        } else null

        val serialStart = when {
            gtin != null && raw.length >= 18 && raw.substring(16).startsWith("21") -> 18
            else -> raw.indexOf("21").takeIf { it >= 0 }?.plus(2)
        }
        val serial = serialStart?.let { start ->
            raw.substring(start)
                .substringBefore(GS)
                .substringBefore("\u001E")
                .takeIf { it.isNotBlank() }
                ?.take(32)
        }

        val escaped = rawBytes.joinToString("") { byte ->
            when (val value = byte.toInt() and 0xFF) {
                29 -> "<GS>"
                in 32..126 -> value.toChar().toString()
                else -> "\\x${value.toString(16).uppercase().padStart(2, '0')}"
            }
        }
        val lines = buildList {
            gtin?.let { add("GTIN $it") }
            serial?.let { add("Serial $it") }
            if (isEmpty()) add(fallbackText?.takeIf(String::isNotBlank) ?: escaped)
        }
        return ParsedGs1(gtin, serial, lines.joinToString("\n"))
    }
}
