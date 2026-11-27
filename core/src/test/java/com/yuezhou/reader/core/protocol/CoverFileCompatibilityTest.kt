package com.yuezhou.reader.core.protocol

import java.util.Base64
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CoverFileCompatibilityTest {
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aV1sAAAAASUVORK5CYII=",
    )

    @Test fun readsLegacyPngWithoutRewritingIt() {
        assertArrayEquals(png, CoverFile.decode(document(png)).pngBytes)
    }

    @Test fun continuesToRequireCurrentFormatsWhenWriting() {
        assertThrows(ProtocolError.InvalidInput::class.java) { CoverFile.encode(png) }
        val jpegHeader = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        assertArrayEquals(jpegHeader, CoverFile.decode(CoverFile.encode(jpegHeader)).pngBytes)
    }

    @Test fun rejectsOversizeLegacyImage() {
        val oversized = png.copyOf(CoverFile.MAX_IMAGE_BYTES + 1)
        assertThrows(ProtocolError.InvalidInput::class.java) { CoverFile.decode(document(oversized)) }
    }

    @Test fun rejectsUnknownOrTruncatedSignatureAndInvalidBase64() {
        for (bytes in listOf("not an image".toByteArray(), png.copyOf(7))) {
            assertThrows(ProtocolError.InvalidInput::class.java) { CoverFile.decode(document(bytes)) }
        }
        assertThrows(ProtocolError.InvalidInput::class.java) { CoverFile.decode("{\"coverPng\":\"!\"}") }
    }

    private fun document(bytes: ByteArray) = "{\"coverPng\":\"${Base64.getEncoder().encodeToString(bytes)}\"}"
}
