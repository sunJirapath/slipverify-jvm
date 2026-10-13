package io.github.sunjirapath.slipverify

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [KnownSlips] (free duplicate check before any provider) and [QuotaGuard] (cap on paid provider calls). */
class GuardTest {
    private class RecordingGuard(
        private val allow: (String) -> Boolean = { true },
    ) : QuotaGuard {
        val reserved = CopyOnWriteArrayList<String>()
        val settled = CopyOnWriteArrayList<Pair<String, Charge>>()

        override fun tryReserve(provider: String): Boolean {
            if (!allow(provider)) return false
            reserved += provider
            return true
        }

        override fun settle(
            provider: String,
            charge: Charge,
        ) {
            settled += provider to charge
        }
    }

    private fun request() = SlipRequest(payload = Slips.standard())

    private fun verifiedAs(charge: Charge) =
        ProviderOutcome.verified(
            VerifiedSlip(amount = java.math.BigDecimal("150.00"), transferTime = java.time.Instant.now()),
            charge,
        )

    @Test
    fun `a known slip is refused before any provider is asked`() {
        val provider = FakeProvider("p") { verified() }
        var asked: Triple<SlipType, String, String>? = null
        val verifier =
            SlipVerifier
                .builder()
                .provider(provider)
                .knownSlips { type, bank, ref ->
                    asked = Triple(type, bank, ref)
                    true
                }.build()
        val result = verifier.verify(request())
        assertEquals(VerificationStatus.INVALID, result.status)
        assertTrue(ReasonCode.DUPLICATE in result.reasons)
        assertEquals(0, provider.calls.get())
        assertEquals(Triple(SlipType.BOT_STANDARD, Slips.BANK, "014091108414AOR00011"), asked)
    }

    @Test
    fun `an unknown slip goes on to the provider`() {
        val provider = FakeProvider("p") { verified() }
        val verifier =
            SlipVerifier
                .builder()
                .provider(provider)
                .knownSlips { _, _, _ -> false }
                .build()
        assertEquals(VerificationStatus.VALID, verifier.verify(request()).status)
        assertEquals(1, provider.calls.get())
    }

    @Test
    fun `a failing known-slips lookup fails closed without a provider call`() {
        val provider = FakeProvider("p") { verified() }
        val verifier =
            SlipVerifier
                .builder()
                .provider(provider)
                .knownSlips { _, _, _ -> error("db down") }
                .build()
        val result = verifier.verify(request())
        assertEquals(VerificationStatus.ERROR, result.status)
        assertTrue(ReasonCode.DUPLICATE_STORE_ERROR in result.reasons)
        assertEquals(0, provider.calls.get())
    }

    @Test
    fun `known slips are checked even when no provider is configured`() {
        val verifier = SlipVerifier.builder().knownSlips { _, _, _ -> true }.build()
        val result = verifier.verify(request())
        assertEquals(VerificationStatus.INVALID, result.status)
        assertTrue(ReasonCode.DUPLICATE in result.reasons)
    }

    @Test
    fun `a capped provider is not asked and the result is ERROR with QUOTA_CAPPED`() {
        val provider = FakeProvider("p") { verified() }
        val guard = RecordingGuard { false }
        val result =
            SlipVerifier
                .builder()
                .provider(provider)
                .quotaGuard(guard)
                .build()
                .verify(request())
        assertEquals(VerificationStatus.ERROR, result.status)
        assertTrue(ReasonCode.QUOTA_CAPPED in result.reasons)
        assertFalse(ReasonCode.PROVIDER_ERROR in result.reasons)
        assertEquals(0, provider.calls.get())
        assertTrue(guard.settled.isEmpty())
    }

    @Test
    fun `reserve and settle happen once per provider call with the reported charge`() {
        val provider = FakeProvider("p") { verifiedAs(Charge.YES) }
        val guard = RecordingGuard()
        val result =
            SlipVerifier
                .builder()
                .provider(provider)
                .quotaGuard(guard)
                .build()
                .verify(request())
        assertEquals(VerificationStatus.VALID, result.status)
        assertEquals(listOf("p"), guard.reserved)
        assertEquals(listOf("p" to Charge.YES), guard.settled)
    }

    @Test
    fun `a free answer is settled as NO so the caller can give the unit back`() {
        val provider = FakeProvider("p") { ProviderOutcome.notFound(Charge.NO) }
        val guard = RecordingGuard()
        val result =
            SlipVerifier
                .builder()
                .provider(provider)
                .quotaGuard(guard)
                .build()
                .verify(request())
        assertEquals(VerificationStatus.INVALID, result.status)
        assertEquals(listOf("p" to Charge.NO), guard.settled)
    }

    @Test
    fun `a provider that throws is settled as UNKNOWN`() {
        val provider = FakeProvider("p") { error("boom") }
        val guard = RecordingGuard()
        val result =
            SlipVerifier
                .builder()
                .provider(provider)
                .quotaGuard(guard)
                .build()
                .verify(request())
        assertEquals(VerificationStatus.ERROR, result.status)
        assertEquals(listOf("p" to Charge.UNKNOWN), guard.settled)
    }

    @Test
    fun `a capped first provider falls through to the next`() {
        val first = FakeProvider("first") { verified() }
        val second = FakeProvider("second") { verifiedAs(Charge.YES) }
        val guard = RecordingGuard { it != "first" }
        val result =
            SlipVerifier
                .builder()
                .providers(listOf(first, second))
                .quotaGuard(guard)
                .build()
                .verify(request())
        assertEquals(VerificationStatus.VALID, result.status)
        assertEquals("second", result.providerName)
        assertEquals(0, first.calls.get())
        assertTrue(ReasonCode.QUOTA_CAPPED in result.reasons)
    }

    @Test
    fun `a guard that throws on reserve is treated as capped`() {
        val provider = FakeProvider("p") { verified() }
        val guard =
            object : QuotaGuard {
                override fun tryReserve(provider: String): Boolean = error("redis down")

                override fun settle(
                    provider: String,
                    charge: Charge,
                ) = Unit
            }
        val result =
            SlipVerifier
                .builder()
                .provider(provider)
                .quotaGuard(guard)
                .build()
                .verify(request())
        assertEquals(VerificationStatus.ERROR, result.status)
        assertTrue(ReasonCode.QUOTA_CAPPED in result.reasons)
        assertEquals(0, provider.calls.get())
    }

    @Test
    fun `a guard that throws on settle does not change the result`() {
        val provider = FakeProvider("p") { verifiedAs(Charge.YES) }
        val guard =
            object : QuotaGuard {
                override fun tryReserve(provider: String): Boolean = true

                override fun settle(
                    provider: String,
                    charge: Charge,
                ): Unit = error("redis down")
            }
        val result =
            SlipVerifier
                .builder()
                .provider(provider)
                .quotaGuard(guard)
                .build()
                .verify(request())
        assertEquals(VerificationStatus.VALID, result.status)
    }

    @Test
    fun `unsupported providers are never reserved`() {
        val provider = FakeProvider("tm", setOf(SlipType.TRUEMONEY)) { verified() }
        val guard = RecordingGuard()
        SlipVerifier
            .builder()
            .provider(provider)
            .quotaGuard(guard)
            .build()
            .verify(request())
        assertTrue(guard.reserved.isEmpty())
    }

    @Test
    fun `outcome factories default to UNKNOWN and unsupported is free`() {
        assertEquals(Charge.UNKNOWN, ProviderOutcome.notFound().charge)
        assertEquals(Charge.UNKNOWN, ProviderOutcome.rejected(ReasonCode.PROVIDER_ERROR).charge)
        assertEquals(Charge.UNKNOWN, ProviderOutcome.unavailable().charge)
        assertEquals(Charge.NO, ProviderOutcome.unsupported().charge)
        assertTrue(ProviderOutcome.unavailable(ReasonCode.PROVIDER_TIMEOUT, Charge.NO).toString().contains("charge=NO"))
    }
}
