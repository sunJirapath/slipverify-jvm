package io.github.sunjirapath.slipverify

import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlipVerifierTest {
    private val now = Instant.parse("2026-10-05T03:20:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun verifier(
        vararg providers: SlipProvider,
        store: DuplicateStore? = null,
        debug: Boolean = false,
    ) = SlipVerifier
        .builder()
        .apply {
            providers.forEach { provider(it) }
            duplicateStore(store)
            clock(clock)
            debug(debug)
        }.build()

    private fun ok() = FakeProvider("A") { verified() }

    // ---- offline only: never VALID ----

    @Test
    fun `no provider caps status at UNVERIFIED for both slip types`() {
        for (payload in listOf(Slips.standard(), Slips.trueMoney())) {
            val r = verifier().verify(SlipRequest(payload = payload))
            assertEquals(VerificationStatus.UNVERIFIED, r.status)
            assertTrue(r.hasReason(ReasonCode.NO_PROVIDER_CONFIGURED))
            assertNull(r.amount)
            assertFalse(r.isValid)
        }
    }

    @Test
    fun `offline result carries slip type bank and reference`() {
        val r = verifier().verify(SlipRequest(payload = Slips.standard("REF123")))
        assertEquals(SlipType.BOT_STANDARD, r.slipType)
        assertEquals("004", r.sendingBank)
        assertEquals("REF123", r.transRef)
        val tm = verifier().verify(SlipRequest(payload = Slips.trueMoney("TM99")))
        assertEquals(SlipType.TRUEMONEY, tm.slipType)
        assertEquals("TM99", tm.transRef)
    }

    @Test
    fun `truncated CRC is auto fixed and reported`() {
        for (zeros in 1..3) {
            val r = verifier().verify(SlipRequest(payload = Slips.truncatedCrc(zeros)))
            assertEquals(VerificationStatus.UNVERIFIED, r.status, "zeros=$zeros")
            assertTrue(r.hasReason(ReasonCode.CRC_AUTOFIXED), "zeros=$zeros")
        }
        val intact = verifier().verify(SlipRequest(payload = Slips.standard()))
        assertFalse(intact.hasReason(ReasonCode.CRC_AUTOFIXED))
    }

    @Test
    fun `bad CRC and foreign QRs are rejected with distinct reasons`() {
        val cases =
            mapOf(
                Slips.badCrc() to ReasonCode.CRC_INVALID,
                "https://example.com/promo" to ReasonCode.NOT_A_SLIP_QR,
                "000201010211" + "5802TH6304ABCD" to ReasonCode.NOT_A_SLIP_QR,
            )
        for ((payload, reason) in cases) {
            val r = verifier(ok()).verify(SlipRequest(payload = payload))
            assertEquals(VerificationStatus.INVALID, r.status, payload)
            assertEquals(listOf(reason), r.reasons, payload)
        }
    }

    @Test
    fun `empty request and image without extractor`() {
        assertEquals(listOf(ReasonCode.EMPTY_INPUT), verifier().verify(SlipRequest()).reasons)
        assertEquals(listOf(ReasonCode.EMPTY_INPUT), verifier().verify(SlipRequest(payload = "  ")).reasons)
        val r = verifier().verify(SlipRequest(imageBase64 = "AAAA"))
        assertEquals(VerificationStatus.INVALID, r.status)
        assertEquals(listOf(ReasonCode.UNSUPPORTED_IMAGE_FORMAT), r.reasons)
    }

    // ---- provider ----

    @Test
    fun `provider success is VALID with masked parties`() {
        val r = verifier(ok()).verify(SlipRequest(payload = Slips.standard()))
        assertEquals(VerificationStatus.VALID, r.status)
        assertEquals(BigDecimal("150.00"), r.amount)
        assertEquals("A", r.providerName)
        assertEquals(now.minusSeconds(1200), r.transferTime)
        assertEquals("xxx-x-x1234-x", r.maskedReceiver?.substringAfter("(")?.removeSuffix(")"))
        assertTrue(r.maskedReceiver!!.startsWith("นา***"))
        assertFalse(r.toString().contains("สมชาย"))
        assertFalse(r.toString().contains("สมหญิง"))
    }

    @Test
    fun `provider answers map to statuses`() {
        val cases =
            listOf(
                ProviderOutcome.notFound() to (VerificationStatus.INVALID to ReasonCode.PROVIDER_NOT_FOUND),
                ProviderOutcome.rejected(ReasonCode.PROVIDER_PENDING) to
                    (VerificationStatus.UNVERIFIED to ReasonCode.PROVIDER_PENDING),
                ProviderOutcome.rejected(ReasonCode.PROVIDER_AUTH_ERROR) to
                    (VerificationStatus.ERROR to ReasonCode.PROVIDER_AUTH_ERROR),
                ProviderOutcome.rejected(ReasonCode.PROVIDER_QUOTA_EXCEEDED) to
                    (VerificationStatus.ERROR to ReasonCode.PROVIDER_QUOTA_EXCEEDED),
                ProviderOutcome.unavailable() to (VerificationStatus.ERROR to ReasonCode.PROVIDER_ERROR),
                ProviderOutcome.unavailable(ReasonCode.PROVIDER_TIMEOUT) to
                    (VerificationStatus.ERROR to ReasonCode.PROVIDER_TIMEOUT),
            )
        for ((outcome, expected) in cases) {
            val r = verifier(FakeProvider("A") { outcome }).verify(SlipRequest(payload = Slips.standard()))
            assertEquals(expected.first, r.status, outcome.toString())
            assertTrue(r.hasReason(expected.second), outcome.toString())
            assertNull(r.amount)
        }
    }

    @Test
    fun `throwing provider is treated as unavailable`() {
        val boom = FakeProvider("A") { error("secret slip data") }
        val r = verifier(boom).verify(SlipRequest(payload = Slips.standard()))
        assertEquals(VerificationStatus.ERROR, r.status)
        assertEquals(listOf(ReasonCode.PROVIDER_ERROR), r.reasons)
        assertFalse(r.toString().contains("secret"))
    }

    // ---- routing and fallback ----

    @Test
    fun `falls back on unavailable and returns the second provider answer`() {
        val a = FakeProvider("A") { ProviderOutcome.unavailable(ReasonCode.PROVIDER_TIMEOUT) }
        val b = FakeProvider("B") { verified() }
        val r = verifier(a, b).verify(SlipRequest(payload = Slips.standard()))
        assertEquals(VerificationStatus.VALID, r.status)
        assertEquals("B", r.providerName)
        assertEquals(1, a.calls.get())
        assertEquals(1, b.calls.get())
    }

    @Test
    fun `does not fall back after a 4xx style rejection or not found`() {
        for (first in listOf(ProviderOutcome.rejected(ReasonCode.PROVIDER_AUTH_ERROR), ProviderOutcome.notFound())) {
            val a = FakeProvider("A") { first }
            val b = FakeProvider("B") { verified() }
            verifier(a, b).verify(SlipRequest(payload = Slips.standard()))
            assertEquals(0, b.calls.get(), first.toString())
        }
    }

    @Test
    fun `all providers down gives ERROR with every reason`() {
        val a = FakeProvider("A") { ProviderOutcome.unavailable(ReasonCode.PROVIDER_TIMEOUT) }
        val b = FakeProvider("B") { ProviderOutcome.unavailable(ReasonCode.PROVIDER_ERROR) }
        val r = verifier(a, b).verify(SlipRequest(payload = Slips.standard()))
        assertEquals(VerificationStatus.ERROR, r.status)
        assertEquals(listOf(ReasonCode.PROVIDER_TIMEOUT, ReasonCode.PROVIDER_ERROR), r.reasons)
    }

    @Test
    fun `routes each slip type only to providers that support it`() {
        val botOnly = FakeProvider("bot", setOf(SlipType.BOT_STANDARD)) { verified() }
        val both = FakeProvider("both") { verified() }
        val r = verifier(botOnly, both).verify(SlipRequest(payload = Slips.trueMoney()))
        assertEquals("both", r.providerName)
        assertEquals(0, botOnly.calls.get())
        assertEquals(SlipType.TRUEMONEY, both.lastQuery?.slipType)
    }

    @Test
    fun `no provider supports the type leaves the slip UNVERIFIED`() {
        val botOnly = FakeProvider("bot", setOf(SlipType.BOT_STANDARD)) { verified() }
        val r = verifier(botOnly).verify(SlipRequest(payload = Slips.trueMoney()))
        assertEquals(VerificationStatus.UNVERIFIED, r.status)
        assertEquals(listOf(ReasonCode.PROVIDER_UNSUPPORTED_SLIP_TYPE), r.reasons)
    }

    @Test
    fun `provider that refuses the query itself is reported as unsupported`() {
        val picky = FakeProvider("picky") { ProviderOutcome.unsupported() }
        val r = verifier(picky).verify(SlipRequest(payload = Slips.trueMoney()))
        assertEquals(VerificationStatus.UNVERIFIED, r.status)
        assertEquals(listOf(ReasonCode.PROVIDER_UNSUPPORTED_SLIP_TYPE), r.reasons)
    }

    // ---- business checks ----

    private data class Case(
        val name: String,
        val request: SlipRequest,
        val outcome: ProviderOutcome,
        val status: VerificationStatus,
        val reasons: List<ReasonCode>,
    )

    @Test
    fun `business rules table`() {
        val p = Slips.standard()
        val rule = { s: String?, n: String? -> ReceiverRule(s, n) }
        val cases =
            listOf(
                Case(
                    "amount equal ignoring scale",
                    SlipRequest(payload = p, expectedAmount = BigDecimal("150")),
                    verified("150.00"),
                    VerificationStatus.VALID,
                    emptyList(),
                ),
                Case(
                    "amount differs",
                    SlipRequest(payload = p, expectedAmount = BigDecimal("150.01")),
                    verified("150.00"),
                    VerificationStatus.INVALID,
                    listOf(ReasonCode.AMOUNT_MISMATCH),
                ),
                Case(
                    "receiver suffix ok",
                    SlipRequest(payload = p, expectedReceiver = rule("1234", null)),
                    verified(),
                    VerificationStatus.VALID,
                    emptyList(),
                ),
                Case(
                    "receiver suffix wrong",
                    SlipRequest(payload = p, expectedReceiver = rule("9999", null)),
                    verified(),
                    VerificationStatus.INVALID,
                    listOf(ReasonCode.RECEIVER_MISMATCH),
                ),
                Case(
                    "receiver thai name ok",
                    SlipRequest(payload = p, expectedReceiver = rule(null, "สมชาย")),
                    verified(),
                    VerificationStatus.VALID,
                    emptyList(),
                ),
                Case(
                    "receiver name wrong",
                    SlipRequest(payload = p, expectedReceiver = rule(null, "สมศักดิ์")),
                    verified(),
                    VerificationStatus.INVALID,
                    listOf(ReasonCode.RECEIVER_MISMATCH),
                ),
                Case(
                    "receiver data missing fails closed",
                    SlipRequest(payload = p, expectedReceiver = rule("1234", null)),
                    verified(receiverAccount = null),
                    VerificationStatus.INVALID,
                    listOf(ReasonCode.RECEIVER_MISMATCH),
                ),
                Case(
                    "age within",
                    SlipRequest(payload = p, maxAge = Duration.ofMinutes(30)),
                    verified(time = now.minusSeconds(600)),
                    VerificationStatus.VALID,
                    emptyList(),
                ),
                Case(
                    "age exceeded",
                    SlipRequest(payload = p, maxAge = Duration.ofMinutes(30)),
                    verified(time = now.minusSeconds(3600)),
                    VerificationStatus.INVALID,
                    listOf(ReasonCode.TOO_OLD),
                ),
                Case(
                    "age without time fails closed",
                    SlipRequest(payload = p, maxAge = Duration.ofMinutes(30)),
                    verified(time = null),
                    VerificationStatus.INVALID,
                    listOf(ReasonCode.TIME_UNAVAILABLE),
                ),
                Case(
                    "provider says duplicate",
                    SlipRequest(payload = p),
                    verified(duplicate = true),
                    VerificationStatus.INVALID,
                    listOf(ReasonCode.DUPLICATE),
                ),
                Case(
                    "several failures at once",
                    SlipRequest(
                        payload = p,
                        expectedAmount = BigDecimal("1"),
                        expectedReceiver = rule("0000", null),
                        maxAge = Duration.ofSeconds(1),
                    ),
                    verified(),
                    VerificationStatus.INVALID,
                    listOf(ReasonCode.AMOUNT_MISMATCH, ReasonCode.RECEIVER_MISMATCH, ReasonCode.TOO_OLD),
                ),
            )
        for (c in cases) {
            val r = verifier(FakeProvider("A") { c.outcome }).verify(c.request)
            assertEquals(c.status, r.status, c.name)
            assertEquals(c.reasons, r.reasons, c.name)
        }
    }

    @Test
    fun `duplicate store flags the second identical slip`() {
        val store = InMemoryDuplicateStore()
        val v = verifier(ok(), store = store)
        val req = SlipRequest(payload = Slips.standard("SAME"))
        assertEquals(VerificationStatus.VALID, v.verify(req).status)
        val second = v.verify(req)
        assertEquals(VerificationStatus.INVALID, second.status)
        assertEquals(listOf(ReasonCode.DUPLICATE), second.reasons)
        assertEquals(VerificationStatus.VALID, v.verify(SlipRequest(payload = Slips.standard("OTHER"))).status)
    }

    @Test
    fun `duplicate key is per slip type and bank`() {
        val store = InMemoryDuplicateStore()
        val v = verifier(ok(), store = store)
        assertEquals(VerificationStatus.VALID, v.verify(SlipRequest(payload = Slips.standard("X1"))).status)
        assertEquals(VerificationStatus.VALID, v.verify(SlipRequest(payload = Slips.trueMoney("X1"))).status)
    }

    @Test
    fun `a failed amount check does not burn the slip`() {
        val store = InMemoryDuplicateStore()
        val v = verifier(ok(), store = store)
        val p = Slips.standard("BURN")
        assertEquals(
            VerificationStatus.INVALID,
            v.verify(SlipRequest(payload = p, expectedAmount = BigDecimal("1"))).status,
        )
        assertEquals(
            VerificationStatus.VALID,
            v.verify(SlipRequest(payload = p, expectedAmount = BigDecimal("150"))).status,
        )
    }

    @Test
    fun `duplicate store is not touched when the provider did not verify`() {
        var marks = 0
        val store = DuplicateStore { _, _ -> true.also { marks++ } }
        verifier(
            FakeProvider("A") {
                ProviderOutcome.notFound()
            },
            store = store,
        ).verify(SlipRequest(payload = Slips.standard()))
        verifier(store = store).verify(SlipRequest(payload = Slips.standard()))
        assertEquals(0, marks)
    }

    @Test
    fun `in memory store expires entries`() {
        var t = Instant.parse("2026-01-01T00:00:00Z")
        val clock =
            object : Clock() {
                override fun getZone() = ZoneOffset.UTC

                override fun withZone(zone: java.time.ZoneId?) = this

                override fun instant() = t
            }
        val store = InMemoryDuplicateStore(clock)
        assertTrue(store.markIfAbsent("k", Duration.ofMinutes(1)))
        assertFalse(store.markIfAbsent("k", Duration.ofMinutes(1)))
        t = t.plusSeconds(61)
        assertTrue(store.markIfAbsent("k", Duration.ofMinutes(1)))
    }

    // ---- request/response hygiene ----

    @Test
    fun `payload is forwarded and image is optional`() {
        val p = FakeProvider("A") { verified() }
        verifier(p).verify(SlipRequest(payload = Slips.standard("FWD")))
        assertEquals(Slips.standard("FWD"), p.lastQuery?.payload)
        assertNull(p.lastQuery?.image)
        assertNotNull(p.lastQuery.toString())
    }

    @Test
    fun `debug payloads are only returned when enabled`() {
        val payload = Slips.standard()
        assertEquals(emptyList(), verifier(debug = false).verify(SlipRequest(payload = payload)).debugPayloads)
        assertEquals(listOf(payload), verifier(debug = true).verify(SlipRequest(payload = payload)).debugPayloads)
    }

    @Test
    fun `request validation and toString hide personal data`() {
        val req =
            SlipRequest(
                imageBase64 = "SECRETIMAGE",
                payload = Slips.standard(),
                expectedReceiver = ReceiverRule("123456", "สมชาย"),
            )
        assertFalse(req.toString().contains("SECRETIMAGE"))
        assertFalse(req.toString().contains("สมชาย"))
        assertFalse(req.toString().contains("123456"))
        kotlin.test.assertFailsWith<IllegalArgumentException> { ReceiverRule() }
        kotlin.test.assertFailsWith<IllegalArgumentException> { ReceiverRule("12") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { ReceiverRule(nameContains = " ") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { SlipRequest(maxAge = Duration.ofSeconds(-1)) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { SlipRequest(expectedAmount = BigDecimal.ZERO) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { SlipVerifier.builder().duplicateTtl(Duration.ZERO) }
    }

    @Test
    fun `extractor failures and candidates flow into the result`() {
        val payload = Slips.standard("IMG")
        val extractor =
            object : QrExtractor {
                override fun extract(
                    imageBase64: String,
                    isSlip: java.util.function.Predicate<String>,
                ) = when (imageBase64) {
                    "none" -> ExtractionResult(emptyList(), null, ReasonCode.NO_QR_FOUND, null)
                    "decoy" -> ExtractionResult(listOf("https://promo.example"), null, null, byteArrayOf(1))
                    else -> ExtractionResult(listOf("https://promo.example", payload), payload, null, byteArrayOf(1, 2))
                }
            }
        val provider = FakeProvider("A") { verified() }
        val v =
            SlipVerifier
                .builder()
                .provider(provider)
                .qrExtractor(extractor)
                .debug(true)
                .build()
        assertEquals(listOf(ReasonCode.NO_QR_FOUND), v.verify(SlipRequest(imageBase64 = "none")).reasons)
        assertEquals(listOf(ReasonCode.NOT_A_SLIP_QR), v.verify(SlipRequest(imageBase64 = "decoy")).reasons)
        val ok = v.verify(SlipRequest(imageBase64 = "good"))
        assertEquals(VerificationStatus.VALID, ok.status)
        assertEquals(listOf("https://promo.example", payload), ok.debugPayloads)
        assertEquals(2, provider.lastQuery?.image?.size)
        val fail =
            SlipVerifier
                .builder()
                .qrExtractor(extractor)
                .build()
                .verify(SlipRequest(imageBase64 = "none"))
        assertEquals(emptyList(), fail.debugPayloads)
    }
}
