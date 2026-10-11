package io.github.sunjirapath.slipverify.zxing

import io.github.sunjirapath.promptparse.SlipVerify
import io.github.sunjirapath.slipverify.InMemoryDuplicateStore
import io.github.sunjirapath.slipverify.ProviderOutcome
import io.github.sunjirapath.slipverify.ProviderQuery
import io.github.sunjirapath.slipverify.ReasonCode
import io.github.sunjirapath.slipverify.SlipProvider
import io.github.sunjirapath.slipverify.SlipRequest
import io.github.sunjirapath.slipverify.SlipType
import io.github.sunjirapath.slipverify.SlipVerifier
import io.github.sunjirapath.slipverify.VerificationStatus
import io.github.sunjirapath.slipverify.VerifiedSlip
import io.github.sunjirapath.slipverify.zxing.SlipImages.b64
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.AlphaChars
import net.jqwik.api.constraints.NumericChars
import net.jqwik.api.constraints.StringLength
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EndToEndTest {
    private class Recording : SlipProvider {
        override val name = "recording"
        var last: ProviderQuery? = null

        override fun supports(type: SlipType) = true

        override fun verify(query: ProviderQuery): ProviderOutcome {
            last = query
            return ProviderOutcome.verified(
                VerifiedSlip(
                    BigDecimal("150.00"),
                    Instant.now(),
                    "Test Sender",
                    "xxx-x-x5678-x",
                    "Test Receiver",
                    "xxx-x-x1234-x",
                ),
            )
        }
    }

    private fun verifier(provider: SlipProvider? = null) =
        SlipVerifier
            .builder()
            .qrExtractor(ZxingQrExtractor())
            .apply { provider?.let { provider(it) } }
            .build()

    private fun image(payload: String?) = b64(SlipImages.png(SlipImages.slip(payload)))

    @Test
    fun `image to VALID through a provider, with the picture forwarded`() {
        val payload = SlipVerify.build("004", "E2E0001")
        val provider = Recording()
        val r = verifier(provider).verify(SlipRequest(imageBase64 = image(payload), expectedAmount = BigDecimal("150")))
        assertEquals(VerificationStatus.VALID, r.status)
        assertEquals("E2E0001", r.transRef)
        assertEquals(payload, provider.last?.payload)
        assertNotNull(provider.last?.image)
    }

    @Test
    fun `image only is UNVERIFIED without a provider`() {
        val r = verifier().verify(SlipRequest(imageBase64 = image(SlipVerify.buildTrueMoney("P2P", "TM1", "05102026"))))
        assertEquals(VerificationStatus.UNVERIFIED, r.status)
        assertEquals(SlipType.TRUEMONEY, r.slipType)
        assertTrue(r.hasReason(ReasonCode.NO_PROVIDER_CONFIGURED))
    }

    @Test
    fun `truncated CRC inside an image is auto fixed`() {
        for (zeros in 1..3) {
            val payload = truncated(zeros)
            val r = verifier().verify(SlipRequest(imageBase64 = image(payload)))
            assertEquals(VerificationStatus.UNVERIFIED, r.status, "zeros=$zeros")
            assertTrue(r.hasReason(ReasonCode.CRC_AUTOFIXED), "zeros=$zeros")
        }
    }

    @Test
    fun `image failures surface as reasons`() {
        val v = verifier(Recording())
        assertEquals(
            listOf(ReasonCode.NO_QR_FOUND),
            v.verify(SlipRequest(imageBase64 = b64(SlipImages.png(blank())))).reasons,
        )
        assertEquals(
            listOf(ReasonCode.NOT_A_SLIP_QR),
            v.verify(SlipRequest(imageBase64 = image(null))).reasons,
        )
        assertEquals(listOf(ReasonCode.INVALID_BASE64), v.verify(SlipRequest(imageBase64 = "%%%")).reasons)
        assertEquals(
            listOf(ReasonCode.IMAGE_DIMENSIONS_TOO_LARGE),
            v.verify(SlipRequest(imageBase64 = b64(SlipImages.bombPng(50_000, 50_000)))).reasons,
        )
        assertEquals(
            listOf(ReasonCode.UNSUPPORTED_IMAGE_FORMAT),
            v.verify(SlipRequest(imageBase64 = b64("hi".toByteArray()))).reasons,
        )
    }

    @Test
    fun `frontend supplied payload wins over the image`() {
        val own = SlipVerify.build("004", "FROMPAYLOAD")
        val provider = Recording()
        val r =
            verifier(
                provider,
            ).verify(SlipRequest(imageBase64 = image(SlipVerify.build("004", "FROMIMAGE")), payload = own))
        assertEquals("FROMPAYLOAD", r.transRef)
    }

    @Test
    fun `duplicate image is caught on the second submission`() {
        val payload = SlipVerify.build("004", "DUP0001")
        val v =
            SlipVerifier
                .builder()
                .qrExtractor(ZxingQrExtractor())
                .provider(Recording())
                .duplicateStore(InMemoryDuplicateStore())
                .build()
        val img = image(payload)
        assertEquals(VerificationStatus.VALID, v.verify(SlipRequest(imageBase64 = img)).status)
        assertEquals(listOf(ReasonCode.DUPLICATE), v.verify(SlipRequest(imageBase64 = img)).reasons)
    }

    @Property(tries = 15)
    fun `any reference survives the render and scan round trip`(
        @ForAll @AlphaChars @NumericChars @StringLength(min = 8, max = 25) ref: String,
    ): Boolean {
        val payload = SlipVerify.build("014", ref)
        val found = ZxingQrExtractor().extract(image(payload)) { SlipVerify.parse(it) != null }.slipPayload
        return found == payload
    }

    private fun blank() =
        java.awt.image.BufferedImage(500, 500, java.awt.image.BufferedImage.TYPE_INT_RGB).also {
            it.createGraphics().apply {
                color = java.awt.Color.WHITE
                fillRect(0, 0, 500, 500)
                dispose()
            }
        }

    private fun truncated(zeros: Int): String {
        for (i in 0 until 200_000) {
            val full = SlipVerify.build("004", "REF$i")
            val crc = full.takeLast(4)
            if (crc.take(zeros).all { it == '0' } && crc[zeros] != '0') return full.dropLast(4) + crc.drop(zeros)
        }
        error("no payload found")
    }
}
