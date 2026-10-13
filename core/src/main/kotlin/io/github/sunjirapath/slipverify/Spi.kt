package io.github.sunjirapath.slipverify

import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
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
        /** Required for acceptance; must match the reference parsed from the submitted QR. */
        public val transRef: String? = null,
        public val sendingBank: String? = null,
        /** True when the provider itself reported the slip was already used. */
        public val duplicateAtProvider: Boolean = false,
    ) {
        override fun toString(): String =
            "VerifiedSlip(amount=$amount, sender=${Masking.party(senderName, senderAccount)}, " +
                "receiver=${Masking.party(receiverName, receiverAccount)})"
    }

/**
 * Whether a provider call used up paid quota. Providers set this so that a caller who caps its spending (see
 * [QuotaGuard]) can give a reservation back when the answer cost nothing.
 */
public enum class Charge {
    /** The call was billed or counted against the quota (for example a confirmed slip). */
    YES,

    /** The provider documents that this answer is free (for example "QR not found", or a bank outage). */
    NO,

    /** Not documented, or the answer may have been lost (timeout). Callers should assume it was charged. */
    UNKNOWN,
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
    /** Whether this call used quota; [Charge.UNKNOWN] unless the provider says otherwise. */
    public val charge: Charge = Charge.UNKNOWN,
) {
    override fun toString(): String = "ProviderOutcome($kind, reason=$reason, charge=$charge)"

    public companion object {
        @JvmStatic
        @JvmOverloads
        public fun verified(
            slip: VerifiedSlip,
            charge: Charge = Charge.UNKNOWN,
        ): ProviderOutcome = ProviderOutcome(ProviderOutcomeKind.VERIFIED, slip, null, charge)

        @JvmStatic
        @JvmOverloads
        public fun notFound(charge: Charge = Charge.UNKNOWN): ProviderOutcome =
            ProviderOutcome(ProviderOutcomeKind.NOT_FOUND, null, ReasonCode.PROVIDER_NOT_FOUND, charge)

        /** [reason] is one of the `PROVIDER_*` codes, e.g. `PROVIDER_AUTH_ERROR`, `PROVIDER_PENDING`. */
        @JvmStatic
        @JvmOverloads
        public fun rejected(
            reason: ReasonCode,
            charge: Charge = Charge.UNKNOWN,
        ): ProviderOutcome = ProviderOutcome(ProviderOutcomeKind.REJECTED, null, reason, charge)

        @JvmStatic
        @JvmOverloads
        public fun unavailable(
            reason: ReasonCode = ReasonCode.PROVIDER_ERROR,
            charge: Charge = Charge.UNKNOWN,
        ): ProviderOutcome = ProviderOutcome(ProviderOutcomeKind.UNAVAILABLE, null, reason, charge)

        @JvmStatic
        public fun unsupported(): ProviderOutcome =
            ProviderOutcome(ProviderOutcomeKind.UNSUPPORTED, null, ReasonCode.PROVIDER_UNSUPPORTED_SLIP_TYPE, Charge.NO)
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

/**
 * Lets the caller answer "have we already used this slip?" from its own records **before** any provider is asked, so
 * a re-scanned slip costs nothing. Slips are identified by what the offline parse yields (the same triple that
 * [DuplicateStore] hashes). Must be thread-safe. If it throws, the slip is refused ([ReasonCode.DUPLICATE_STORE_ERROR]).
 */
public fun interface KnownSlips {
    public fun isKnown(
        type: SlipType,
        sendingBank: String,
        transRef: String,
    ): Boolean
}

/**
 * Caps how many paid provider calls are made. The verifier calls [tryReserve] right before asking a provider and
 * [settle] with what that provider reported ([ProviderOutcome.charge]). Implementations decide what to do on
 * [Charge.NO] (give the unit back) and on [Charge.YES]/[Charge.UNKNOWN] (keep it). Must be thread-safe; a
 * [tryReserve] that throws counts as "cap reached", because failing open would spend money.
 */
public interface QuotaGuard {
    /** True when one more call is allowed (and now reserved); false means the provider must not be asked. */
    public fun tryReserve(provider: String): Boolean

    /** Called exactly once for every successful [tryReserve], even when the provider failed or threw. */
    public fun settle(
        provider: String,
        charge: Charge,
    )
}

/**
 * Short-lived replay protection. Must be thread-safe. This mark is not atomic with the caller's payment transaction:
 * keep a unique transfer identity in the payment database too (see docs/PAYMENT_ACCEPTANCE.md).
 */
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
        private val nextPurge = AtomicReference(Instant.MIN)

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
            val next = nextPurge.get()
            if (entries.size > PURGE_THRESHOLD && !now.isBefore(next) &&
                nextPurge.compareAndSet(next, now.plus(PURGE_INTERVAL))
            ) {
                entries.values.removeIf { !it.isAfter(now) }
            }
            return added
        }

        private companion object {
            const val PURGE_THRESHOLD = 10_000
            val PURGE_INTERVAL: Duration = Duration.ofMinutes(1)
        }
    }
