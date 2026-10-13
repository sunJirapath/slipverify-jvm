package io.github.sunjirapath.slipverify

import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegressionTest {
    private val now = Instant.parse("2026-10-13T05:00:00Z")

    private fun verifier(
        slip: VerifiedSlip,
        store: DuplicateStore? = null,
    ): SlipVerifier {
        val provider =
            object : SlipProvider {
                override val name = "synthetic"

                override fun supports(type: SlipType) = true

                override fun verify(query: ProviderQuery) = ProviderOutcome.verified(slip)
            }
        return SlipVerifier
            .builder()
            .provider(provider)
            .duplicateStore(store)
            .clock(Clock.fixed(now, ZoneOffset.UTC))
            .build()
    }

    @Test
    fun `mismatched and missing identities never mark a slip`() {
        var marks = 0
        val store = DuplicateStore { _, _ -> true.also { marks++ } }
        val request = SlipRequest(payload = Slips.standard("EXPECTED"))
        for (slip in listOf(
            VerifiedSlip(BigDecimal.TEN, transRef = "OTHER"),
            VerifiedSlip(BigDecimal.TEN, transRef = "EXPECTED", sendingBank = "014"),
        )) {
            val result = verifier(slip, store).verify(request)
            assertEquals(VerificationStatus.INVALID, result.status)
            assertTrue(result.hasReason(ReasonCode.TRANSACTION_MISMATCH))
        }
        for (ref in listOf(null, "", " ")) {
            val result = verifier(VerifiedSlip(BigDecimal.TEN, transRef = ref), store).verify(request)
            assertEquals(VerificationStatus.ERROR, result.status)
            assertTrue(result.hasReason(ReasonCode.PROVIDER_ERROR))
        }
        assertEquals(0, marks)
    }

    @Test
    fun `same confirmed wallet transfer cannot be replayed under another QR identity`() {
        val v = verifier(VerifiedSlip(BigDecimal.TEN, transRef = "REAL"), InMemoryDuplicateStore())
        val real = SlipRequest(payload = Slips.trueMoney("REAL"))
        assertEquals(VerificationStatus.VALID, v.verify(real).status)
        for (ref in listOf("FAKE1", "FAKE2")) {
            val result = v.verify(SlipRequest(payload = Slips.trueMoney(ref)))
            assertEquals(VerificationStatus.INVALID, result.status)
            assertTrue(result.hasReason(ReasonCode.TRANSACTION_MISMATCH))
        }
        assertTrue(v.verify(real).hasReason(ReasonCode.DUPLICATE))
    }

    @Test
    fun `matching provider identity retains the existing duplicate key`() {
        var key: String? = null
        val store = DuplicateStore { k, _ -> true.also { key = k } }
        val payload = Slips.standard("EXPECTED")
        val result =
            verifier(VerifiedSlip(BigDecimal.TEN, transRef = "EXPECTED", sendingBank = Slips.BANK), store)
                .verify(SlipRequest(payload = payload))
        assertEquals(VerificationStatus.VALID, result.status)
        assertEquals(DuplicateKeys.of((SlipPayloads.check(payload) as PayloadCheck.Slip).slip), key)
    }

    @Test
    fun `future time is refused even without maxAge and two minutes of skew are allowed`() {
        for (maxAge in listOf(null, Duration.ofMinutes(30))) {
            for (seconds in listOf(121L, 365L * 86400)) {
                val v = verifier(VerifiedSlip(BigDecimal.TEN, now.plusSeconds(seconds), transRef = "TIME"))
                val result = v.verify(SlipRequest(payload = Slips.standard("TIME"), maxAge = maxAge))
                assertEquals(VerificationStatus.INVALID, result.status)
                assertTrue(result.hasReason(ReasonCode.TIME_IN_FUTURE))
            }
        }
        val v = verifier(VerifiedSlip(BigDecimal.TEN, now.plusSeconds(120), transRef = "TIME"))
        assertEquals(VerificationStatus.VALID, v.verify(SlipRequest(payload = Slips.standard("TIME"), maxAge = Duration.ZERO)).status)
    }

    @Test
    fun `in memory duplicate marking admits exactly one concurrent caller`() {
        val store = InMemoryDuplicateStore()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val calls = (1..32).map { pool.submit<Boolean> { store.markIfAbsent("same", Duration.ofMinutes(1)) } }
            assertEquals(1, calls.count { it.get() })
            assertFalse(store.markIfAbsent("same", Duration.ofMinutes(1)))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `large stores purge expired entries at most once per minute`() {
        var time = now
        val clock =
            object : Clock() {
                override fun getZone() = ZoneOffset.UTC

                override fun withZone(zone: java.time.ZoneId?) = this

                override fun instant() = time
            }
        val store = InMemoryDuplicateStore(clock)
        repeat(10_001) { store.markIfAbsent("old$it", Duration.ofSeconds(10)) }
        // Retention is the behavior under test; there is no public collection API.
        val field = InMemoryDuplicateStore::class.java.getDeclaredField("entries").apply { isAccessible = true }
        val retained = field.get(store) as Map<*, *>
        time = now.plusSeconds(11)
        store.markIfAbsent("fresh", Duration.ofHours(1))
        assertEquals(10_002, retained.size)
        time = now.plusSeconds(60)
        store.markIfAbsent("fresh2", Duration.ofHours(1))
        assertEquals(2, retained.size)
        assertFalse(store.markIfAbsent("fresh", Duration.ofHours(1)))
    }
}
