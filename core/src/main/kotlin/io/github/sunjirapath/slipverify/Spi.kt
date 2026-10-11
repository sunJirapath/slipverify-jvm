package io.github.sunjirapath.slipverify

import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Predicate

/** Turns a base64 image into slip QR candidates. Implemented by the `image-zxing` module. */
public interface QrExtractor {
    /**
     * Never throws. Stops early at the first payload [isSlip] accepts. Image bytes must not be logged.
     */
    public fun extract(
        imageBase64: String,
        isSlip: Predicate<String>,
    ): ExtractionResult
}

/** What a [QrExtractor] found. */
public class ExtractionResult(
    /** Every distinct QR text found, in discovery order. */
    candidates: List<String>,
    /** The candidate [QrExtractor.extract]'s predicate accepted, if any. */
    public val slipPayload: String?,
    /** Why nothing usable came out of the image; `null` when at least one QR was decoded. */
    public val failure: ReasonCode?,
    /** The validated, decoded image so providers that need the picture can receive it. Never persisted. */
    image: ByteArray?,
) {
    public val candidates: List<String> = candidates.toList()
    private val imageBytes: ByteArray? = image?.copyOf()

    public val image: ByteArray? get() = imageBytes?.copyOf()

    override fun toString(): String =
        "ExtractionResult(candidates=${candidates.size}, found=${slipPayload != null}, failure=$failure)"
}

/** What a provider is asked to verify. */
public class ProviderQuery(
    public val slipType: SlipType,
    public val payload: String,
    image: ByteArray?,
    public val expectedAmount: BigDecimal?,
) {
    private val imageBytes: ByteArray? = image?.copyOf()

    /** The slip image when the request came with one; `null` for payload-only requests. */
    public val image: ByteArray? get() = imageBytes?.copyOf()

    override fun toString(): String = "ProviderQuery(slipType=$slipType, hasImage=${imageBytes != null})"
}

/** What a provider confirmed about the transfer. Unmasked: the core masks before exposing it. */
public class VerifiedSlip
    @JvmOverloads
    constructor(
        public val amount: BigDecimal,
        public val transferTime: Instant? = null,
        public val senderName: String? = null,
        public val senderAccount: String? = null,
        public val receiverName: String? = null,
        public val receiverAccount: String? = null,
        public val transRef: String? = null,
        public val sendingBank: String? = null,
        /** True when the provider itself reported the slip was already used. */
        public val duplicateAtProvider: Boolean = false,
    ) {
        override fun toString(): String =
            "VerifiedSlip(amount=$amount, sender=${Masking.party(senderName, senderAccount)}, " +
                "receiver=${Masking.party(receiverName, receiverAccount)})"
    }

/** Kind of answer a provider gave. */
public enum class ProviderOutcomeKind {
    VERIFIED,
    NOT_FOUND,

    /** The provider understood the request and refused it (4xx other than not-found): do not try another provider. */
    REJECTED,

    /** Timeout, network or 5xx: the next provider in the chain may be tried. */
    UNAVAILABLE,
    UNSUPPORTED,
}

/** A provider's answer. Build with the factory methods. */
public class ProviderOutcome private constructor(
    public val kind: ProviderOutcomeKind,
    public val slip: VerifiedSlip?,
    public val reason: ReasonCode?,
) {
    override fun toString(): String = "ProviderOutcome($kind, reason=$reason)"

    public companion object {
        @JvmStatic
        public fun verified(slip: VerifiedSlip): ProviderOutcome =
            ProviderOutcome(ProviderOutcomeKind.VERIFIED, slip, null)

        @JvmStatic
        public fun notFound(): ProviderOutcome =
            ProviderOutcome(ProviderOutcomeKind.NOT_FOUND, null, ReasonCode.PROVIDER_NOT_FOUND)

        /** [reason] is one of the `PROVIDER_*` codes, e.g. `PROVIDER_AUTH_ERROR`, `PROVIDER_PENDING`. */
        @JvmStatic
        public fun rejected(reason: ReasonCode): ProviderOutcome =
            ProviderOutcome(ProviderOutcomeKind.REJECTED, null, reason)

        @JvmStatic
        @JvmOverloads
        public fun unavailable(reason: ReasonCode = ReasonCode.PROVIDER_ERROR): ProviderOutcome =
            ProviderOutcome(ProviderOutcomeKind.UNAVAILABLE, null, reason)

        @JvmStatic
        public fun unsupported(): ProviderOutcome =
            ProviderOutcome(ProviderOutcomeKind.UNSUPPORTED, null, ReasonCode.PROVIDER_UNSUPPORTED_SLIP_TYPE)
    }
}

/**
 * A slip-verification service. Implementations must be thread-safe, apply their own timeouts, never throw for
 * remote failures (return [ProviderOutcome.unavailable]), and never log API keys or slip data.
 */
public interface SlipProvider {
    public val name: String

    /** Whether this provider can verify [type] at all. The verifier only asks providers that say yes. */
    public fun supports(type: SlipType): Boolean

    public fun verify(query: ProviderQuery): ProviderOutcome
}

/** Remembers verified slips so one transfer cannot be credited twice. Must be thread-safe. */
public fun interface DuplicateStore {
    /**
     * Atomically records [key] for [ttl].
     *
     * @return true if the key was new, false if it was already recorded (a duplicate).
     */
    public fun markIfAbsent(
        key: String,
        ttl: Duration,
    ): Boolean
}

/** Single-JVM [DuplicateStore]. Use `store-redis` when running more than one instance. */
public class InMemoryDuplicateStore
    @JvmOverloads
    constructor(
        private val clock: Clock = Clock.systemUTC(),
    ) : DuplicateStore {
        private val entries = ConcurrentHashMap<String, Instant>()

        override fun markIfAbsent(
            key: String,
            ttl: Duration,
        ): Boolean {
            val now = clock.instant()
            var added = false
            entries.compute(key) { _, expiry ->
                if (expiry == null || !expiry.isAfter(now)) {
                    added = true
                    now.plus(ttl)
                } else {
                    expiry
                }
            }
            if (entries.size > PURGE_THRESHOLD) entries.values.removeIf { !it.isAfter(now) }
            return added
        }

        private companion object {
            const val PURGE_THRESHOLD = 10_000
        }
    }
