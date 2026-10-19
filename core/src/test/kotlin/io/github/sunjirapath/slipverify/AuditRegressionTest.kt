package io.github.sunjirapath.slipverify

import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditRegressionTest {
    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    @Test
    fun `different Thai vowels and tones must not satisfy receiver rules`() {
        for ((expected, actual) in listOf("ศิริชัย" to "ศรีชัย", "ปิยะ" to "ปียะ", "ปาน" to "ป่าน")) {
            assertFalse(ReceiverMatcher.nameMatches(expected, actual), "$expected != $actual")
        }
    }

    @Test
    fun `different Thai receiver must not produce VALID`() {
        val provider = FakeProvider("audit") { verified(receiverName = "นาย ศรีชัย") }
        val result =
            SlipVerifier.builder().provider(provider).build().verify(
                SlipRequest(
                    payload = Slips.standard(),
                    expectedAmount = BigDecimal("150.00"),
                    expectedReceiver = ReceiverRule("1234", "ศิริชัย"),
                ),
            )
        assertEquals(VerificationStatus.INVALID, result.status)
        assertTrue(ReasonCode.RECEIVER_MISMATCH in result.reasons)
    }

    @Test
    fun `Thai marks survive title spacing and punctuation normalization`() {
        for ((expected, actual) in listOf("ศิริชัย" to "นาย ศิริชัย", "กานต์" to "นางสาว กานต์", "ป่าน" to " น.ส. ป่ า น ")) {
            assertTrue(ReceiverMatcher.nameMatches(expected, actual))
            val provider = FakeProvider("audit") { verified(receiverName = actual) }
            val result =
                SlipVerifier.builder().provider(provider).build().verify(
                    SlipRequest(payload = Slips.standard(), expectedReceiver = ReceiverRule("1234", expected)),
                )
            assertEquals(VerificationStatus.VALID, result.status)
        }
    }

    @Test
    fun `receipt refunds only its original minute and cycle and is idempotent`() {
        val clock = MutableClock(Instant.parse("2026-10-19T10:00:59Z"))
        val guard = ConfiguredQuotaGuard(mapOf("p" to ProviderLimit(maxPerCycle = 3, maxPerMinute = 1)), mapOf("p" to BillingCycle(1)), clock)
        val old = assertNotNull(guard.reserve("p"))
        clock.now = Instant.parse("2026-10-19T10:01:00Z")
        val current = assertNotNull(guard.reserve("p"))
        old.settle(Charge.NO)
        old.settle(Charge.NO)
        assertEquals(1, guard.usedThisCycle("p"))
        assertNull(guard.reserve("p"), "the current minute is still full")
        current.settle(Charge.NO)
        assertEquals(0, guard.usedThisCycle("p"))
        assertNotNull(guard.reserve("p")).settle(Charge.UNKNOWN)
        assertNull(guard.reserve("p"))
    }

    @Test
    fun `settled paid receipt cannot later refund another call`() {
        val guard = ConfiguredQuotaGuard(mapOf("p" to ProviderLimit(maxPerMinute = 1)))
        val paid = assertNotNull(guard.reserve("p"))
        paid.settle(Charge.YES)
        paid.settle(Charge.NO)
        assertNull(guard.reserve("p"))
    }

    @Test
    fun `verifier uses receipt API and settles a free outcome without calling legacy methods`() {
        val charges = mutableListOf<Charge>()
        val guard =
            object : ReservationQuotaGuard {
                override fun reserve(provider: String): QuotaReservation = QuotaReservation { charges += it }

                override fun tryReserve(provider: String): Boolean = error("legacy reservation must not be called")

                override fun settle(
                    provider: String,
                    charge: Charge,
                ): Unit = error("legacy settlement must not be called")
            }
        val provider = FakeProvider("p") { ProviderOutcome.notFound(Charge.NO) }
        val verifier =
            SlipVerifier
                .builder()
                .provider(provider)
                .quotaGuard(guard)
                .build()
        assertEquals(VerificationStatus.INVALID, verifier.verify(SlipRequest(payload = Slips.standard())).status)
        assertEquals(listOf(Charge.NO), charges)
    }

    @Test
    fun `built-in verifier refunds free same-period calls while retaining unknown charges`() {
        val guard = ConfiguredQuotaGuard(mapOf("p" to ProviderLimit(maxPerMinute = 1)))
        val provider = FakeProvider("p") { ProviderOutcome.notFound(Charge.NO) }
        val verifier =
            SlipVerifier
                .builder()
                .provider(provider)
                .quotaGuard(guard)
                .build()
        repeat(3) { assertEquals(VerificationStatus.INVALID, verifier.verify(SlipRequest(payload = Slips.standard())).status) }
        provider.outcome = { error("network failure") }
        assertTrue(verifier.verify(SlipRequest(payload = Slips.standard())).hasReason(ReasonCode.PROVIDER_ERROR))
        assertTrue(verifier.verify(SlipRequest(payload = Slips.standard())).hasReason(ReasonCode.QUOTA_CAPPED))
        assertEquals(4, provider.calls.get())
    }

    @Test
    fun `verifier settles out of order concurrent calls across a minute or cycle boundary`() {
        for ((start, boundary) in listOf(
            "2026-10-19T10:00:59Z" to "2026-10-19T10:01:00Z",
            "2026-10-31T23:59:59Z" to "2026-11-01T00:00:00Z",
        )) {
            val clock = MutableClock(Instant.parse(start))
            val guard = ConfiguredQuotaGuard(mapOf("p" to ProviderLimit(maxPerCycle = 2, maxPerMinute = 1)), mapOf("p" to BillingCycle(1)), clock)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val provider =
                FakeProvider("p") {
                    if (calls.incrementAndGet() == 1) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        ProviderOutcome.notFound(Charge.NO)
                    } else {
                        verified()
                    }
                }
            val verifier =
                SlipVerifier
                    .builder()
                    .provider(provider)
                    .quotaGuard(guard)
                    .build()
            val pool = Executors.newSingleThreadExecutor()
            try {
                val old = pool.submit<SlipVerificationResult> { verifier.verify(SlipRequest(payload = Slips.standard("OLD"))) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                clock.now = Instant.parse(boundary)
                assertEquals(VerificationStatus.VALID, verifier.verify(SlipRequest(payload = Slips.standard())).status)
                release.countDown()
                assertEquals(VerificationStatus.INVALID, old.get(5, TimeUnit.SECONDS).status)
                assertTrue(verifier.verify(SlipRequest(payload = Slips.standard())).hasReason(ReasonCode.QUOTA_CAPPED))
                assertEquals(1, guard.usedThisCycle("p"))
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `legacy free settlement cannot release another minutes reservation`() {
        val clock = MutableClock(Instant.parse("2026-10-19T10:00:59Z"))
        val guard = ConfiguredQuotaGuard(mapOf("p" to ProviderLimit(maxPerMinute = 1)), clock = clock)
        assertTrue(guard.tryReserve("p"))
        clock.now = Instant.parse("2026-10-19T10:01:00Z")
        assertTrue(guard.tryReserve("p"))
        guard.settle("p", Charge.NO)
        assertFalse(guard.tryReserve("p"))
    }

    @Test
    fun `legacy free settlement cannot release another billing cycles reservation`() {
        val clock = MutableClock(Instant.parse("2026-10-31T23:59:59Z"))
        val guard = ConfiguredQuotaGuard(mapOf("p" to ProviderLimit(maxPerCycle = 1)), mapOf("p" to BillingCycle(1)), clock)
        assertTrue(guard.tryReserve("p"))
        clock.now = Instant.parse("2026-11-01T00:00:00Z")
        assertTrue(guard.tryReserve("p"))
        guard.settle("p", Charge.NO)
        assertFalse(guard.tryReserve("p"))
    }

    @Test
    fun `invalid Gregorian billing dates are rejected without clamping`() {
        for (bad in listOf("31/04/2026", "29/02/2026", "30/02/2024", "2026-04-31", "2026-02-29")) {
            assertFailsWith<IllegalArgumentException>(bad) { BillingCycle.parse(bad) }
        }
        assertEquals(29, BillingCycle.parse("29/02/2024").cutoffDay)
        assertEquals(31, BillingCycle.parse("31").cutoffDay)
    }
}
