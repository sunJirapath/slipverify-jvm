package io.github.sunjirapath.slipverify

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderLimitsTest {
    private val bangkok = ZoneId.of("Asia/Bangkok")

    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?) = this

        override fun instant() = now
    }

    private fun at(text: String) = Instant.parse(text)

    @Test
    fun `next billing date 13 11 2026 means a reset every 13th`() {
        val cycle = BillingCycle.parse("13/11/2026", bangkok)
        assertEquals(13, cycle.cutoffDay)
        // 2026-10-13 00:00 Bangkok = 2026-10-12T17:00Z
        assertEquals(at("2026-10-12T17:00:00Z"), cycle.start(at("2026-10-20T00:00:00Z")))
        assertEquals(at("2026-11-12T17:00:00Z"), cycle.nextReset(at("2026-10-20T00:00:00Z")))
        // Before the cutoff day the round began last month.
        assertEquals(at("2026-09-12T17:00:00Z"), cycle.start(at("2026-10-05T00:00:00Z")))
        assertEquals(at("2026-10-12T17:00:00Z"), cycle.nextReset(at("2026-10-05T00:00:00Z")))
        // The instant of the cutoff already belongs to the new round.
        assertEquals(at("2026-10-12T17:00:00Z"), cycle.start(at("2026-10-12T17:00:00Z")))
    }

    @Test
    fun `cutoff past the end of a short month falls on its last day`() {
        val cycle = BillingCycle(31, ZoneOffset.UTC)
        assertEquals(at("2026-04-30T00:00:00Z"), cycle.start(at("2026-05-02T00:00:00Z")))
        assertEquals(at("2026-05-31T00:00:00Z"), cycle.nextReset(at("2026-05-02T00:00:00Z")))
        assertEquals(at("2027-02-28T00:00:00Z"), cycle.nextReset(at("2027-02-01T00:00:00Z")))
    }

    @Test
    fun `cutoff accepts a day or a date and rejects nonsense`() {
        assertEquals(5, BillingCycle.parse("5").cutoffDay)
        assertEquals(13, BillingCycle.parse(" 2026-11-13 ").cutoffDay)
        for (bad in listOf("0", "32", "tomorrow", "", "13/13/2026")) {
            assertFailsWith<IllegalArgumentException>(bad) { BillingCycle.parse(bad) }
        }
    }

    @Test
    fun `cycle limit blocks at the cap and resets on the cutoff day`() {
        val clock = MutableClock(at("2026-11-12T10:00:00Z"))
        val guard =
            ConfiguredQuotaGuard(
                mapOf("slipok" to ProviderLimit(maxPerCycle = 2)),
                mapOf("slipok" to BillingCycle(13, bangkok)),
                clock,
            )
        assertTrue(guard.tryReserve("slipok"))
        assertTrue(guard.tryReserve("SlipOK"))
        assertFalse(guard.tryReserve("slipok"))
        assertEquals(2, guard.usedThisCycle("slipok"))
        // Still the old round one minute before the Bangkok midnight of the 13th.
        clock.now = at("2026-11-12T16:59:00Z")
        assertFalse(guard.tryReserve("slipok"))
        clock.now = at("2026-11-12T17:00:00Z")
        assertTrue(guard.tryReserve("slipok"))
        assertEquals(1, guard.usedThisCycle("slipok"))
    }

    @Test
    fun `minute limit resets every minute and a free answer returns the unit`() {
        val clock = MutableClock(at("2026-10-13T10:00:05Z"))
        val guard = ConfiguredQuotaGuard(mapOf("easyslip" to ProviderLimit(maxPerMinute = 1)), clock = clock)
        assertTrue(guard.tryReserve("easyslip"))
        assertFalse(guard.tryReserve("easyslip"))
        guard.settle("easyslip", Charge.NO)
        assertTrue(guard.tryReserve("easyslip"))
        guard.settle("easyslip", Charge.YES)
        assertFalse(guard.tryReserve("easyslip"))
        clock.now = at("2026-10-13T10:01:00Z")
        assertTrue(guard.tryReserve("easyslip"))
    }

    @Test
    fun `providers without a limit are free and a cycle limit needs a cutoff`() {
        val guard = ConfiguredQuotaGuard(mapOf("slipok" to ProviderLimit(maxPerMinute = 1)))
        repeat(5) { assertTrue(guard.tryReserve("easyslip")) }
        assertFailsWith<IllegalArgumentException> {
            ConfiguredQuotaGuard(mapOf("slipok" to ProviderLimit(maxPerCycle = 10)))
        }
        assertFailsWith<IllegalArgumentException> { ProviderLimit(maxPerCycle = -1) }
    }
}
