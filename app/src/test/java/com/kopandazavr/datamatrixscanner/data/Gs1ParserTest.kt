package com.kopandazavr.datamatrixscanner.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

class Gs1ParserTest {
    @Test
    fun parsesGtinAndSerial() {
        val parsed = Gs1Parser.parse("010460123456789021ABC1234\u001D91KEY".toByteArray(StandardCharsets.ISO_8859_1), null)
        assertEquals("04601234567890", parsed.gtin)
        assertEquals("ABC1234", parsed.serial)
    }

    @Test
    fun preservesGroupSeparatorInFallback() {
        val parsed = Gs1Parser.parse("ABC\u001DDEF".toByteArray(StandardCharsets.ISO_8859_1), null)
        assertEquals("ABC<GS>DEF", parsed.displayText)
    }
}
