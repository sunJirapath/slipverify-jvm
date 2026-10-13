package io.github.sunjirapath.slipverify.spring

import io.github.sunjirapath.promptparse.SlipVerify
import io.github.sunjirapath.slipverify.Charge
import io.github.sunjirapath.slipverify.InMemoryDuplicateStore
import io.github.sunjirapath.slipverify.ProviderOutcome
import io.github.sunjirapath.slipverify.ProviderQuery
import io.github.sunjirapath.slipverify.QuotaGuard
import io.github.sunjirapath.slipverify.ReasonCode
import io.github.sunjirapath.slipverify.SlipProvider
import io.github.sunjirapath.slipverify.SlipRequest
import io.github.sunjirapath.slipverify.SlipType
import io.github.sunjirapath.slipverify.SlipVerificationResult
import io.github.sunjirapath.slipverify.SlipVerifier
import io.github.sunjirapath.slipverify.VerificationStatus
import io.github.sunjirapath.slipverify.VerifiedSlip
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RegressionTest {
    private val request = SlipRequest(payload = SlipVerify.build("004", "METRICS"))
    private val throwingMetrics =
        object : SlipMetrics {
            override fun verification(result: SlipVerificationResult) {
                error("synthetic telemetry failure")
            }

            override fun providerCall(
                provider: String,
                outcome: String,
                nanos: Long,
            ) {
                error("synthetic telemetry failure")
            }
        }

    private fun provider(
        outcome: ProviderOutcome,
        calls: AtomicInteger = AtomicInteger(),
    ): SlipProvider =
        object : SlipProvider {
            override val name = "synthetic"

            override fun supports(type: SlipType) = true

            override fun verify(query: ProviderQuery): ProviderOutcome {
                calls.incrementAndGet()
                return outcome
            }
        }

    private fun confirmed() = ProviderOutcome.verified(VerifiedSlip(BigDecimal.TEN, transRef = "METRICS"), Charge.YES)

    @Test
    fun `verification telemetry failures preserve the result and duplicate status`() {
        val verifier =
            SlipVerifier
                .builder()
                .provider(provider(confirmed()))
                .duplicateStore(InMemoryDuplicateStore())
                .build()
        val service = SlipVerificationService(verifier, throwingMetrics)
        assertEquals(VerificationStatus.VALID, service.verify(request).status)
        val retry = service.verify(request)
        assertEquals(VerificationStatus.INVALID, retry.status)
        assertTrue(retry.hasReason(ReasonCode.DUPLICATE))
    }

    @Test
    fun `provider telemetry failures preserve paid outcomes and cannot trigger fallback`() {
        val calls = AtomicInteger()
        val fallbackCalls = AtomicInteger()
        val charges = ArrayList<Charge>()
        val guard =
            object : QuotaGuard {
                override fun tryReserve(provider: String) = true

                override fun settle(
                    provider: String,
                    charge: Charge,
                ) {
                    charges += charge
                }
            }
        val verifier =
            SlipVerifier
                .builder()
                .provider(MeteredSlipProvider(provider(confirmed(), calls), throwingMetrics))
                .provider(provider(ProviderOutcome.notFound(), fallbackCalls))
                .quotaGuard(guard)
                .build()
        assertEquals(VerificationStatus.VALID, verifier.verify(request).status)
        assertEquals(1, calls.get())
        assertEquals(0, fallbackCalls.get())
        assertEquals(listOf(Charge.YES), charges)
        for (outcome in listOf(ProviderOutcome.notFound(), ProviderOutcome.unavailable(ReasonCode.PROVIDER_TIMEOUT))) {
            assertSame(outcome, MeteredSlipProvider(provider(outcome), throwingMetrics).verify(ProviderQuery(SlipType.BOT_STANDARD, request.payload!!, null, null)))
        }
        val failure = IllegalStateException("synthetic provider failure")
        val failingProvider =
            object : SlipProvider {
                override val name = "broken"

                override fun supports(type: SlipType) = true

                override fun verify(query: ProviderQuery): ProviderOutcome = throw failure
            }
        val caught =
            kotlin.test.assertFailsWith<IllegalStateException> {
                MeteredSlipProvider(failingProvider, throwingMetrics).verify(ProviderQuery(SlipType.BOT_STANDARD, request.payload!!, null, null))
            }
        assertSame(failure, caught)
    }

    @Test
    fun `unknown duplicate store values fail startup instead of silently selecting memory`() {
        val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SlipVerifyAutoConfiguration::class.java))
        for (store in listOf("redsi", "", "other")) {
            runner.withPropertyValues("slipverify.duplicate.store=$store").run { ctx ->
                assertNotNull(ctx.startupFailure)
                assertTrue(
                    generateSequence(ctx.startupFailure) { it.cause }.any {
                        it.message?.contains("slipverify.duplicate.store must be memory or redis") == true
                    },
                )
            }
        }
        runner.withPropertyValues("slipverify.duplicate.store=MEMORY").run { ctx ->
            kotlin.test.assertNull(ctx.startupFailure)
            assertTrue(ctx.getBean(io.github.sunjirapath.slipverify.DuplicateStore::class.java) is InMemoryDuplicateStore)
        }
    }
}
