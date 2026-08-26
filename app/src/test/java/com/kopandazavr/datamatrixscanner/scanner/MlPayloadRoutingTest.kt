package com.kopandazavr.datamatrixscanner.scanner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MlPayloadRoutingTest {
    @Test fun rawBytesWinWhenAvailable() {
        val raw = byteArrayOf(0, 1, 2, 29)
        assertArrayEquals(raw, decodedBarcodeBytes(raw, "ignored"))
    }

    @Test fun utf8RawValueIsNotDiscardedWhenMlKitOmitsRawBytes() {
        val value = "010460123456789021abc\u001d91xyz"
        assertArrayEquals(value.toByteArray(Charsets.UTF_8), decodedBarcodeBytes(null, value))
    }

    @Test fun noPayloadStaysUndecoded() {
        assertNull(decodedBarcodeBytes(null, null))
    }
}
