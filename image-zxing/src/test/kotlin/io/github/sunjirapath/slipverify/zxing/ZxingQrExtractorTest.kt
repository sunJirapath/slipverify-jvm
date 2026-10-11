package io.github.sunjirapath.slipverify.zxing

import io.github.sunjirapath.promptparse.SlipVerify
import io.github.sunjirapath.slipverify.ExtractionResult
import io.github.sunjirapath.slipverify.ReasonCode
import io.github.sunjirapath.slipverify.zxing.SlipImages.b64
import java.awt.image.BufferedImage
import java.time.Duration
import java.util.Random
import java.util.function.Predicate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZxingQrExtractorTest {
    private val payload = SlipVerify.build("004", "SYNTH202610050001")
    private val tmPayload = SlipVerify.buildTrueMoney("P2P", "TM51005913", "05102026")
    private val extractor = ZxingQrExtractor()
    private val isSlip = Predicate<String> { SlipVerify.parse(it) != null || SlipVerify.parseTrueMoney(it) != null }

    private fun extract(
        base64: String,
        e: ZxingQrExtractor = extractor,
    ): ExtractionResult = e.extract(base64, isSlip)

    private fun slipPng(p: String = payload) = SlipImages.png(SlipImages.slip(p))

    // ---- base64 handling ----

    @Test
    fun `raw base64 data url whitespace and url-safe all decode`() {
        val bytes = SlipImages.jpeg(SlipImages.slip(payload), 0.8f)
        val raw = b64(bytes)
        val urlSafe = raw.replace('+', '-').replace('/', '_').trimEnd('=')
        assertTrue(raw.contains('+') || raw.contains('/'), "fixture should exercise the url-safe alphabet")
        val variants =
            mapOf(
                "raw" to raw,
                "data url" to "data:image/jpeg;base64,$raw",
                "data url upper" to "DATA:image/jpeg;BASE64,$raw",
                "whitespace" to "  " + raw.chunked(76).joinToString("\r\n") + "\n",
                "spaces" to raw.chunked(100).joinToString(" "),
                "url-safe no padding" to urlSafe,
            )
        for ((name, text) in variants) {
            assertEquals(payload, extract(text).slipPayload, name)
        }
    }

    @Test
    fun `invalid empty and malformed base64`() {
        val cases =
            mapOf(
                "" to ReasonCode.EMPTY_INPUT,
                "   \n " to ReasonCode.EMPTY_INPUT,
                "data:image/png;base64," to ReasonCode.EMPTY_INPUT,
                "!!!not base64!!!" to ReasonCode.INVALID_BASE64,
                "abcde" to ReasonCode.INVALID_BASE64,
                "data:image/png,rawdata" to ReasonCode.INVALID_BASE64,
                "data:image/png;base64" to ReasonCode.INVALID_BASE64,
                "iVBO\u0000Rw0K" to ReasonCode.INVALID_BASE64,
            )
        for ((text, reason) in cases) {
            val r = extract(text)
            assertEquals(reason, r.failure, text)
            assertNull(r.slipPayload)
            assertNull(r.image)
        }
    }

    @Test
    fun `oversized input is refused before decoding`() {
        val small = ZxingQrExtractor(ImageLimits(maxEncodedBytes = 1000))
        assertEquals(ReasonCode.IMAGE_TOO_LARGE, extract("A".repeat(1001), small).failure)
        assertEquals(ReasonCode.IMAGE_TOO_LARGE, extract(b64(slipPng()), small).failure)
        assertEquals(5 * 1024 * 1024, ImageLimits().maxEncodedBytes)
    }

    @Test
    fun `non images and unsupported formats`() {
        val heic = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63, 0, 0, 0, 0)
        val webp = "RIFF....WEBPVP8 ".toByteArray()
        val gif = "GIF89a".toByteArray() + ByteArray(20)
        val pdf = "%PDF-1.7 hello".toByteArray()
        val cases =
            mapOf(
                "text" to "hello world".toByteArray(),
                "heic" to heic,
                "webp" to webp,
                "gif" to gif,
                "pdf" to pdf,
            )
        for ((name, bytes) in cases) {
            assertEquals(ReasonCode.UNSUPPORTED_IMAGE_FORMAT, extract(b64(bytes)).failure, name)
        }
    }

    @Test
    fun `corrupt images with valid magic bytes are unsupported`() {
        val png = slipPng()
        val truncated = png.copyOf(200)
        val garbage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64) { 7 }
        val jpegGarbage = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(40) { 1 }
        for (bytes in listOf(truncated, garbage, jpegGarbage)) {
            assertEquals(ReasonCode.UNSUPPORTED_IMAGE_FORMAT, extract(b64(bytes)).failure)
        }
    }

    @Test
    fun `decompression bomb header is refused without decoding`() {
        val bomb = SlipImages.bombPng(60_000, 60_000)
        assertEquals(ReasonCode.IMAGE_DIMENSIONS_TOO_LARGE, extract(b64(bomb)).failure)
        val wide = SlipImages.bombPng(20_000, 10)
        assertEquals(ReasonCode.IMAGE_DIMENSIONS_TOO_LARGE, extract(b64(wide)).failure)
        val tight = ZxingQrExtractor(ImageLimits(maxPixels = 1_000_000))
        assertEquals(ReasonCode.IMAGE_DIMENSIONS_TOO_LARGE, extract(b64(slipPng()), tight).failure)
    }

    @Test
    fun `decode timeout`() {
        val rnd = Random(1)
        val noise = BufferedImage(3000, 3000, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until noise.height) for (x in 0 until noise.width) noise.setRGB(x, y, rnd.nextInt(0xFFFFFF))
        val quick = ZxingQrExtractor(ImageLimits(maxEncodedBytes = 60_000_000, decodeTimeout = Duration.ofMillis(20)))
        val r = extract(b64(SlipImages.png(noise)), quick)
        assertEquals(ReasonCode.IMAGE_DECODE_TIMEOUT, r.failure)
    }

    @Test
    fun `no qr in a blank image`() {
        val blank =
            BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB).also { g ->
                g.createGraphics().apply {
                    color = java.awt.Color.WHITE
                    fillRect(0, 0, 600, 600)
                    dispose()
                }
            }
        val r = extract(b64(SlipImages.png(blank)))
        assertEquals(ReasonCode.NO_QR_FOUND, r.failure)
        assertTrue(r.candidates.isEmpty())
    }

    // ---- QR selection ----

    @Test
    fun `picks the slip qr and ignores the decoy`() {
        val r = extract(b64(slipPng()))
        assertEquals(payload, r.slipPayload)
        assertNull(r.failure)
        assertNotNull(r.image)
    }

    @Test
    fun `a decoy alone is returned as a candidate but not as the slip`() {
        val r = extract(b64(SlipImages.png(SlipImages.slip(null, decoy = true))))
        assertNull(r.slipPayload)
        assertNull(r.failure)
        assertEquals(listOf(SlipImages.DECOY), r.candidates)
    }

    @Test
    fun `trueMoney slips are found too`() {
        assertEquals(tmPayload, extract(b64(slipPng(tmPayload))).slipPayload)
    }

    // ---- robustness variants ----

    private fun variants(): Map<String, ByteArray> {
        val base = SlipImages.slip(payload)
        return linkedMapOf(
            "png original" to SlipImages.png(base),
            "jpeg q50" to SlipImages.jpeg(base, 0.5f),
            "jpeg q20" to SlipImages.jpeg(base, 0.2f),
            "rotated 90" to SlipImages.png(SlipImages.rotate(base, 90.0)),
            "rotated 180" to SlipImages.png(SlipImages.rotate(base, 180.0)),
            "rotated 270" to SlipImages.png(SlipImages.rotate(base, 270.0)),
            "scaled 0.5" to SlipImages.png(SlipImages.scale(base, 0.5)),
            "tiny screenshot 0.3" to SlipImages.png(SlipImages.scale(base, 0.3)),
            "photo tilt 8" to SlipImages.jpeg(SlipImages.photo(base, 8.0), 0.7f),
            "photo tilt -15" to SlipImages.jpeg(SlipImages.photo(base, -15.0), 0.7f),
            "large photo 2x" to SlipImages.jpeg(SlipImages.scale(base, 2.0), 0.8f),
            "noise" to SlipImages.png(SlipImages.noise(base, 40)),
            "blur" to SlipImages.png(SlipImages.blur(base)),
            "low contrast" to SlipImages.png(SlipImages.lowContrast(base, 0.12)),
            "blur + jpeg + small" to SlipImages.jpeg(SlipImages.blur(SlipImages.scale(base, 0.5)), 0.5f),
        )
    }

    @Test
    fun `finds the slip qr across synthetic photo variants`() {
        val failures = mutableListOf<String>()
        for ((name, bytes) in variants()) {
            val found = extract(b64(bytes)).slipPayload
            if (found != payload) failures += name
        }
        assertTrue(failures.isEmpty(), "not found in: $failures")
    }

    @Test
    fun `limits validate their arguments`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { ImageLimits(maxEncodedBytes = 0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { ImageLimits(maxDimension = 0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { ImageLimits(maxPixels = 0) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { ImageLimits(decodeTimeout = Duration.ZERO) }
        assertTrue(ImageLimits().toString().contains("maxEncodedBytes"))
        assertTrue(extract(b64(slipPng())).toString().contains("found=true"))
    }
}
