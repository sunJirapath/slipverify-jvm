package io.github.sunjirapath.slipverify

import java.time.Clock
import java.time.Duration
import java.util.function.Predicate

/**
 * Verifies a Thai transfer slip: image or payload, offline parse, optional provider check, then business checks.
 *
 * Without a provider the best possible status is [VerificationStatus.UNVERIFIED]: **a QR on its own proves nothing
 * about a payment.** Instances are immutable and thread-safe.
 */
public class SlipVerifier private constructor(
    private val providers: List<SlipProvider>,
    private val duplicateStore: DuplicateStore?,
    private val qrExtractor: QrExtractor?,
    private val duplicateTtl: Duration,
    private val clock: Clock,
    private val debug: Boolean,
    private val knownSlips: KnownSlips?,
    private val quotaGuard: QuotaGuard?,
) {
    public fun verify(request: SlipRequest): SlipVerificationResult {
        val reasons = ArrayList<ReasonCode>()
        val input = resolveInput(request, reasons) ?: return failed(failureStatus(reasons.first()), reasons)
        val slip = input.slip
        if (slip.crcAutoFixed) reasons += ReasonCode.CRC_AUTOFIXED
        val base = Draft(slip, input.debugPayloads)
        // The caller's own records come first: a slip it already used must not cost a provider call.
        knownSlips?.let { known ->
            val seen =
                try {
                    known.isKnown(slip.type, slip.sendingBank, slip.transRef)
                } catch (
                    @Suppress("TooGenericExceptionCaught", "SwallowedException") e: RuntimeException,
                ) {
                    reasons += ReasonCode.DUPLICATE_STORE_ERROR
                    return base.build(VerificationStatus.ERROR, reasons)
                }
            if (seen) {
                reasons += ReasonCode.DUPLICATE
                return base.build(VerificationStatus.INVALID, reasons)
            }
        }
        if (providers.isEmpty()) {
            reasons += ReasonCode.NO_PROVIDER_CONFIGURED
            return base.build(VerificationStatus.UNVERIFIED, reasons)
        }
        val (outcome, providerName) = askProviders(slip, input, request, reasons)
        return when (outcome?.kind) {
            ProviderOutcomeKind.VERIFIED -> {
                finish(base, outcome.slip!!, providerName, request, reasons)
            }

            ProviderOutcomeKind.NOT_FOUND, ProviderOutcomeKind.REJECTED -> {
                reasons += outcome.reason!!
                base.build(rejectedStatus(outcome), reasons, providerName)
            }

            else -> {
                if (reasons.none { it.isProviderFailure() }) reasons += ReasonCode.PROVIDER_ERROR
                // Nobody could answer: the slip is still only a parsed QR.
                val unsupportedOnly = reasons.contains(ReasonCode.PROVIDER_UNSUPPORTED_SLIP_TYPE) && outcome == null
                base.build(if (unsupportedOnly) VerificationStatus.UNVERIFIED else VerificationStatus.ERROR, reasons)
            }
        }
    }

    private class Input(
        val slip: ParsedSlip,
        val payload: String,
        val image: ByteArray?,
        val debugPayloads: List<String>,
    )

    private inner class Draft(
        val slip: ParsedSlip,
        val debugPayloads: List<String>,
    ) {
        fun build(
            status: VerificationStatus,
            reasons: List<ReasonCode>,
            providerName: String? = null,
            verified: VerifiedSlip? = null,
        ) = SlipVerificationResult(
            status = status,
            slipType = slip.type,
            sendingBank = verified?.sendingBank ?: slip.sendingBank,
            transRef = verified?.transRef ?: slip.transRef,
            amount = verified?.amount,
            transferTime = verified?.transferTime,
            maskedSender = verified?.let { Masking.party(it.senderName, it.senderAccount) },
            maskedReceiver = verified?.let { Masking.party(it.receiverName, it.receiverAccount) },
            providerName = providerName,
            reasons = reasons,
            debugPayloads = if (debug) debugPayloads else emptyList(),
        )
    }

    private fun failed(
        status: VerificationStatus,
        reasons: List<ReasonCode>,
        debugPayloads: List<String> = emptyList(),
    ) = SlipVerificationResult(
        status,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        reasons,
        if (debug) debugPayloads else emptyList(),
    )

    private fun resolveInput(
        request: SlipRequest,
        reasons: MutableList<ReasonCode>,
    ): Input? {
        val payloadText = request.payload?.takeIf { it.isNotBlank() }
        val imageText = request.imageBase64?.takeIf { it.isNotBlank() }
        if (payloadText == null && imageText == null) {
            reasons += ReasonCode.EMPTY_INPUT
            return null
        }
        var image: ByteArray? = null
        var candidates = emptyList<String>()
        var payload = payloadText
        if (imageText != null && qrExtractor != null) {
            val extracted = qrExtractor.extract(imageText, Predicate { SlipPayloads.isSlip(it) })
            image = extracted.image
            candidates = extracted.candidates
            if (payload == null) {
                payload = extracted.slipPayload ?: candidates.firstOrNull()
                if (payload == null) {
                    reasons += extracted.failure ?: ReasonCode.NO_QR_FOUND
                    return null
                }
            }
        } else if (payload == null) {
            // An image was sent but no extractor is configured: refuse loudly rather than guess.
            reasons += ReasonCode.UNSUPPORTED_IMAGE_FORMAT
            return null
        }
        if (candidates.isEmpty()) candidates = listOf(payload)
        return when (val checked = SlipPayloads.check(payload)) {
            is PayloadCheck.Slip -> {
                Input(checked.slip, payload, image, candidates)
            }

            PayloadCheck.CrcInvalid -> {
                reasons += ReasonCode.CRC_INVALID
                null
            }

            PayloadCheck.NotSlip -> {
                reasons += ReasonCode.NOT_A_SLIP_QR
                null
            }
        }
    }

    /** Walks the chain: next provider on timeout/5xx, stop on a definitive answer or a 4xx rejection. */
    private fun askProviders(
        slip: ParsedSlip,
        input: Input,
        request: SlipRequest,
        reasons: MutableList<ReasonCode>,
    ): Pair<ProviderOutcome?, String?> {
        val query = ProviderQuery(slip.type, input.payload, input.image, request.expectedAmount)
        val candidates = providers.filter { it.supports(slip.type) }
        var refused = false
        var last: ProviderOutcome? = null
        for (provider in candidates) {
            val outcome = ask(provider, query, reasons) ?: continue
            when (outcome.kind) {
                ProviderOutcomeKind.VERIFIED, ProviderOutcomeKind.NOT_FOUND, ProviderOutcomeKind.REJECTED -> {
                    return outcome to provider.name
                }

                ProviderOutcomeKind.UNSUPPORTED -> {
                    refused = true
                }

                ProviderOutcomeKind.UNAVAILABLE -> {
                    reasons += outcome.reason ?: ReasonCode.PROVIDER_ERROR
                    last = outcome
                }
            }
        }
        if (candidates.isEmpty() || (refused && last == null)) reasons += ReasonCode.PROVIDER_UNSUPPORTED_SLIP_TYPE
        return last to null
    }

    /** One provider call under the optional quota guard; `null` when the guard refused it (the provider is not asked). */
    private fun ask(
        provider: SlipProvider,
        query: ProviderQuery,
        reasons: MutableList<ReasonCode>,
    ): ProviderOutcome? {
        val guard = quotaGuard
        if (guard != null && !reserve(guard, provider)) {
            if (ReasonCode.QUOTA_CAPPED !in reasons) reasons += ReasonCode.QUOTA_CAPPED
            return null
        }
        val outcome = safeVerify(provider, query)
        guard?.let { settle(it, provider, outcome) }
        return outcome
    }

    private fun reserve(
        guard: QuotaGuard,
        provider: SlipProvider,
    ): Boolean =
        try {
            guard.tryReserve(provider.name)
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: RuntimeException,
        ) {
            // Fail closed: if the cap cannot be consulted, spending money is the worse mistake.
            false
        }

    private fun settle(
        guard: QuotaGuard,
        provider: SlipProvider,
        outcome: ProviderOutcome,
    ) {
        try {
            guard.settle(provider.name, outcome.charge)
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: RuntimeException,
        ) {
            // The reservation stays, which over-counts at worst. A broken guard must not change the result.
        }
    }

    private fun safeVerify(
        provider: SlipProvider,
        query: ProviderQuery,
    ): ProviderOutcome =
        try {
            provider.verify(query)
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: RuntimeException,
        ) {
            // A misbehaving provider must not break the chain. The message is dropped: it may hold slip data.
            ProviderOutcome.unavailable(ReasonCode.PROVIDER_ERROR)
        }

    private fun rejectedStatus(outcome: ProviderOutcome): VerificationStatus =
        when (outcome.reason) {
            // Fresh slip the bank cannot see yet: neither real nor fake.
            ReasonCode.PROVIDER_PENDING -> VerificationStatus.UNVERIFIED

            ReasonCode.PROVIDER_NOT_FOUND, ReasonCode.DUPLICATE,
            ReasonCode.AMOUNT_MISMATCH, ReasonCode.RECEIVER_MISMATCH,
            -> VerificationStatus.INVALID

            // Our credentials, quota or rate limit, not the slip's fault.
            else -> VerificationStatus.ERROR
        }

    private fun finish(
        draft: Draft,
        verified: VerifiedSlip,
        providerName: String?,
        request: SlipRequest,
        reasons: MutableList<ReasonCode>,
    ): SlipVerificationResult {
        identityFailure(draft.slip, verified)?.let { reason ->
            reasons += reason
            return draft.build(failureStatus(reason), reasons, providerName)
        }
        val identity =
            ParsedSlip(draft.slip.type, draft.slip.sendingBank, verified.transRef!!, false)
        val failures = ArrayList<ReasonCode>()
        request.expectedAmount?.let {
            if (it.compareTo(verified.amount) != 0) failures += ReasonCode.AMOUNT_MISMATCH
        }
        request.expectedReceiver?.let {
            if (!ReceiverMatcher.matches(it, verified.receiverName, verified.receiverAccount)) {
                failures += ReasonCode.RECEIVER_MISMATCH
            }
        }
        failures += timeFailures(request.maxAge, verified)
        if (verified.duplicateAtProvider) failures += ReasonCode.DUPLICATE
        // Only a slip that would otherwise be VALID is recorded, so a wrong-amount attempt cannot burn the slip.
        var storeDown = false
        if (failures.isEmpty() && duplicateStore != null) {
            try {
                if (!duplicateStore.markIfAbsent(DuplicateKeys.of(identity), duplicateTtl)) {
                    failures +=
                        ReasonCode.DUPLICATE
                }
            } catch (
                @Suppress("TooGenericExceptionCaught", "SwallowedException") e: RuntimeException,
            ) {
                // Fail closed: without the store we cannot rule out a replay, so the slip is not accepted.
                storeDown = true
                failures += ReasonCode.DUPLICATE_STORE_ERROR
            }
        }
        reasons += failures
        val status =
            when {
                storeDown -> VerificationStatus.ERROR
                failures.isEmpty() -> VerificationStatus.VALID
                else -> VerificationStatus.INVALID
            }
        return draft.build(status, reasons, providerName, verified)
    }

    private fun identityFailure(
        parsed: ParsedSlip,
        verified: VerifiedSlip,
    ): ReasonCode? {
        if (verified.transRef.isNullOrBlank()) return ReasonCode.PROVIDER_ERROR
        return if (verified.transRef != parsed.transRef ||
            (verified.sendingBank != null && verified.sendingBank != parsed.sendingBank)
        ) {
            ReasonCode.TRANSACTION_MISMATCH
        } else {
            null
        }
    }

    private fun failureStatus(reason: ReasonCode): VerificationStatus =
        when (reason) {
            ReasonCode.IMAGE_PROCESSING_BUSY, ReasonCode.PROVIDER_ERROR -> VerificationStatus.ERROR
            else -> VerificationStatus.INVALID
        }

    private fun timeFailures(
        maxAge: Duration?,
        verified: VerifiedSlip,
    ): List<ReasonCode> {
        val time =
            verified.transferTime ?: return if (maxAge != null) listOf(ReasonCode.TIME_UNAVAILABLE) else emptyList()
        val now = clock.instant()
        if (time.isAfter(now.plus(FUTURE_CLOCK_SKEW))) return listOf(ReasonCode.TIME_IN_FUTURE)
        return if (maxAge != null && Duration.between(time, now) > maxAge) listOf(ReasonCode.TOO_OLD) else emptyList()
    }

    private fun ReasonCode.isProviderFailure() = name.startsWith("PROVIDER_") || this == ReasonCode.QUOTA_CAPPED

    /** Fluent configuration. Not thread-safe; build once and share the verifier. */
    public class Builder internal constructor() {
        private val providers = ArrayList<SlipProvider>()
        private var duplicateStore: DuplicateStore? = null
        private var qrExtractor: QrExtractor? = null
        private var duplicateTtl: Duration = DEFAULT_DUPLICATE_TTL
        private var clock: Clock = Clock.systemUTC()
        private var debug: Boolean = false
        private var knownSlips: KnownSlips? = null
        private var quotaGuard: QuotaGuard? = null

        /** Adds a provider. Providers are tried in the order added; later ones are fallbacks. */
        public fun provider(provider: SlipProvider): Builder = apply { providers += provider }

        public fun providers(providers: List<SlipProvider>): Builder = apply { this.providers += providers }

        public fun duplicateStore(store: DuplicateStore?): Builder = apply { duplicateStore = store }

        /** Required to accept `imageBase64`; use `ZxingQrExtractor` from `image-zxing`. */
        public fun qrExtractor(extractor: QrExtractor?): Builder = apply { qrExtractor = extractor }

        /** How long a verified slip is remembered. Default 30 days. */
        public fun duplicateTtl(ttl: Duration): Builder =
            apply {
                require(!ttl.isNegative && !ttl.isZero) { "duplicateTtl must be positive" }
                duplicateTtl = ttl
            }

        public fun clock(clock: Clock): Builder = apply { this.clock = clock }

        /** Include every decoded QR text in results. Off by default: payloads identify people. */
        public fun debug(enabled: Boolean): Builder = apply { debug = enabled }

        /** Checked after the offline parse and before any provider; a hit gives `INVALID` + `DUPLICATE` at no cost. */
        public fun knownSlips(known: KnownSlips?): Builder = apply { knownSlips = known }

        /** Caps paid provider calls; see [QuotaGuard]. A capped provider is skipped and the next one is tried. */
        public fun quotaGuard(guard: QuotaGuard?): Builder = apply { quotaGuard = guard }

        public fun build(): SlipVerifier =
            SlipVerifier(
                providers.toList(),
                duplicateStore,
                qrExtractor,
                duplicateTtl,
                clock,
                debug,
                knownSlips,
                quotaGuard,
            )
    }

    public companion object {
        private val DEFAULT_DUPLICATE_TTL: Duration = Duration.ofDays(30)
        private val FUTURE_CLOCK_SKEW: Duration = Duration.ofMinutes(2)

        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
