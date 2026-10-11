package io.github.sunjirapath.slipverify.spring

import io.github.sunjirapath.slipverify.ProviderOutcome
import io.github.sunjirapath.slipverify.ProviderQuery
import io.github.sunjirapath.slipverify.SlipProvider
import io.github.sunjirapath.slipverify.SlipType
import io.github.sunjirapath.slipverify.SlipVerificationResult
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

/** Receives measurements. Free of Micrometer types so the starter works without Micrometer. */
public interface SlipMetrics {
    public fun verification(result: SlipVerificationResult)

    public fun providerCall(
        provider: String,
        outcome: String,
        nanos: Long,
    )

    public companion object {
        @JvmField
        public val NOOP: SlipMetrics =
            object : SlipMetrics {
                override fun verification(result: SlipVerificationResult) = Unit

                override fun providerCall(
                    provider: String,
                    outcome: String,
                    nanos: Long,
                ) = Unit
            }
    }
}

/**
 * Micrometer meters (all tags are low-cardinality enums, never slip data):
 * - `slipverify.verifications{status, slip_type}` counter
 * - `slipverify.reasons{reason}` counter
 * - `slipverify.provider.requests{provider, outcome}` timer
 */
public class MicrometerSlipMetrics(
    private val registry: MeterRegistry,
) : SlipMetrics {
    override fun verification(result: SlipVerificationResult) {
        registry
            .counter(
                "slipverify.verifications",
                "status",
                result.status.name,
                "slip_type",
                result.slipType?.name ?: "NONE",
            ).increment()
        for (reason in result.reasons) registry.counter("slipverify.reasons", "reason", reason.name).increment()
    }

    override fun providerCall(
        provider: String,
        outcome: String,
        nanos: Long,
    ) {
        Timer
            .builder("slipverify.provider.requests")
            .tags("provider", provider, "outcome", outcome)
            .register(registry)
            .record(nanos, TimeUnit.NANOSECONDS)
    }
}

/** Times every call and reports its outcome kind, then returns the delegate's answer unchanged. */
internal class MeteredSlipProvider(
    private val delegate: SlipProvider,
    private val metrics: SlipMetrics,
) : SlipProvider {
    override val name: String get() = delegate.name

    override fun supports(type: SlipType): Boolean = delegate.supports(type)

    override fun verify(query: ProviderQuery): ProviderOutcome {
        val start = System.nanoTime()
        var outcome = "ERROR"
        try {
            val result = delegate.verify(query)
            outcome = result.kind.name
            return result
        } finally {
            metrics.providerCall(delegate.name, outcome, System.nanoTime() - start)
        }
    }

    override fun toString(): String = "Metered($delegate)"
}
