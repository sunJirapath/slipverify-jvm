package io.github.sunjirapath.slipverify

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A monthly billing round that resets on [cutoffDay] at 00:00 in [zone]. A cutoff past the end of a short month
 * (for example 31 in April) falls on that month's last day.
 *
 * "Next billing round 13/11/2026" means a cutoff of 13: the round started 13/10/2026 and the next one starts on
 * 13/11/2026, then every 13th.
 */
public class BillingCycle
    @JvmOverloads
    constructor(
        public val cutoffDay: Int,
        public val zone: ZoneId = ZoneId.of("UTC"),
    ) {
        init {
            require(cutoffDay in 1..MAX_DAY) { "bill cutoff day must be 1..31, was $cutoffDay" }
        }

        /** Start of the round that contains [now]. */
        public fun start(now: Instant): Instant {
            val local = now.atZone(zone)
            val month = YearMonth.from(local)
            val thisMonth = boundary(month)
            return (if (thisMonth.isAfter(local)) boundary(month.minusMonths(1)) else thisMonth).toInstant()
        }

        /** When the quota resets next (the start of the following round). */
        public fun nextReset(now: Instant): Instant {
            val local = now.atZone(zone)
            val month = YearMonth.from(local)
            val thisMonth = boundary(month)
            return (if (thisMonth.isAfter(local)) thisMonth else boundary(month.plusMonths(1))).toInstant()
        }

        private fun boundary(month: YearMonth): ZonedDateTime =
            month.atDay(minOf(cutoffDay, month.lengthOfMonth())).atStartOfDay(zone)

        override fun toString(): String = "BillingCycle(cutoffDay=$cutoffDay, zone=$zone)"

        public companion object {
            private const val MAX_DAY = 31
            private val DATE_FORMATS =
                listOf(
                    DateTimeFormatter.ISO_LOCAL_DATE,
                    DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
                )

            /**
             * Reads `13` (day of month) or a billing date such as `2026-11-13` / `13/11/2026` (its day of month is the
             * cutoff). A Buddhist-era year is not accepted; use the Gregorian year.
             */
            @JvmStatic
            @JvmOverloads
            public fun parse(
                text: String,
                zone: ZoneId = ZoneId.of("UTC"),
            ): BillingCycle {
                val t = text.trim()
                t.toIntOrNull()?.let { return BillingCycle(it, zone) }
                for (format in DATE_FORMATS) {
                    try {
                        return BillingCycle(LocalDate.parse(t, format).dayOfMonth, zone)
                    } catch (
                        @Suppress("SwallowedException") e: DateTimeParseException,
                    ) {
                        // try the next format
                    }
                }
                throw IllegalArgumentException(
                    "bill cutoff must be a day 1..31 or a date like 2026-11-13 or 13/11/2026",
                )
            }
        }
    }

/**
 * How many provider calls one provider may make. A `null` limit is not enforced.
 *
 * @property maxPerCycle calls per billing round; needs a [BillingCycle] to know when it resets.
 * @property maxPerMinute calls per clock minute.
 */
public class ProviderLimit
    @JvmOverloads
    constructor(
        public val maxPerCycle: Long? = null,
        public val maxPerMinute: Long? = null,
    ) {
        init {
            require(maxPerCycle == null || maxPerCycle >= 0) { "maxPerCycle must not be negative" }
            require(maxPerMinute == null || maxPerMinute >= 0) { "maxPerMinute must not be negative" }
        }

        override fun toString(): String = "ProviderLimit(maxPerCycle=$maxPerCycle, maxPerMinute=$maxPerMinute)"
    }

/**
 * A [QuotaGuard] that enforces [ProviderLimit]s per provider name. Each provider can follow its own [BillingCycle].
 * Providers without an entry are not limited. Counts live in this JVM: with several instances, give each its share
 * or implement [QuotaGuard] on a shared store.
 *
 * [SlipVerifier] uses [reserve] to refund free calls only in their original periods. Direct callers should also
 * settle the returned receipt. Legacy [tryReserve]/[settle] calls count attempts conservatively: without a receipt,
 * a concurrent refund cannot safely be attributed, so [settle] retains the reservation even on [Charge.NO].
 */
public class ConfiguredQuotaGuard
    @JvmOverloads
    constructor(
        limits: Map<String, ProviderLimit>,
        cycles: Map<String, BillingCycle> = emptyMap(),
        private val clock: Clock = Clock.systemUTC(),
    ) : ReservationQuotaGuard {
        private val limits = limits.mapKeys { it.key.lowercase() }
        private val cycles = cycles.mapKeys { it.key.lowercase() }
        private val states = ConcurrentHashMap<String, State>()

        private class State {
            var cycleStart: Instant? = null
            var cycleCount = 0L
            var minute = Long.MIN_VALUE
            var minuteCount = 0L
        }

        init {
            for ((name, limit) in this.limits) {
                require(limit.maxPerCycle == null || name in this.cycles) {
                    "provider '$name' has a per-cycle limit but no bill cutoff"
                }
            }
        }

        override fun tryReserve(provider: String): Boolean = reserve(provider) != null

        override fun reserve(provider: String): QuotaReservation? {
            val key = provider.lowercase()
            val limit = limits[key] ?: return QuotaReservation { }
            val state = states.computeIfAbsent(key) { State() }
            synchronized(state) {
                roll(state, key, clock.instant())
                val cycleFull = limit.maxPerCycle?.let { state.cycleCount >= it } ?: false
                val minuteFull = limit.maxPerMinute?.let { state.minuteCount >= it } ?: false
                if (cycleFull || minuteFull) return null
                state.cycleCount++
                state.minuteCount++
                val cycleStart = state.cycleStart
                val minute = state.minute
                val settled = AtomicBoolean()
                return QuotaReservation { charge ->
                    if (settled.compareAndSet(false, true) && charge == Charge.NO) {
                        synchronized(state) {
                            roll(state, key, clock.instant())
                            if (state.cycleStart == cycleStart) state.cycleCount--
                            if (state.minute == minute) state.minuteCount--
                        }
                    }
                }
            }
        }

        override fun settle(
            provider: String,
            charge: Charge,
        ): Unit = Unit

        /** Calls counted in the current billing round, for dashboards and tests. */
        public fun usedThisCycle(provider: String): Long {
            val key = provider.lowercase()
            val state = states[key] ?: return 0
            synchronized(state) {
                roll(state, key, clock.instant())
                return state.cycleCount
            }
        }

        private fun roll(
            state: State,
            key: String,
            now: Instant,
        ) {
            cycles[key]?.let { cycle ->
                val start = cycle.start(now)
                if (state.cycleStart != start) {
                    state.cycleStart = start
                    state.cycleCount = 0
                }
            }
            val minute = now.epochSecond / SECONDS_PER_MINUTE
            if (state.minute != minute) {
                state.minute = minute
                state.minuteCount = 0
            }
        }

        private companion object {
            const val SECONDS_PER_MINUTE = 60L
        }
    }
