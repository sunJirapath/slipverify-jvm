package io.github.sunjirapath.slipverify

/** Kind of slip, taken from the mini QR printed on it. */
public enum class SlipType {
    /** Standard Bank of Thailand slip-verify QR (API type `000001`). */
    BOT_STANDARD,

    /** TrueMoney wallet slip QR. */
    TRUEMONEY,
}

/** Overall outcome of [SlipVerifier.verify]. */
public enum class VerificationStatus {
    /** A provider confirmed the transfer and every requested business check passed. */
    VALID,

    /** The slip is unreadable, not found by the provider, or failed a business check. See the reasons. */
    INVALID,

    /** The QR parsed, but nothing proved the payment (no provider configured or none could be asked). */
    UNVERIFIED,

    /** Verification could not complete because of an infrastructure problem (provider down, timeout). */
    ERROR,
}

/** Machine-readable explanation attached to a [SlipVerificationResult]. */
public enum class ReasonCode(
    /** True for codes that only inform and never make a result worse. */
    public val isInfo: Boolean = false,
) {
    // Input and image
    EMPTY_INPUT,
    INVALID_BASE64,
    UNSUPPORTED_IMAGE_FORMAT,
    IMAGE_TOO_LARGE,
    IMAGE_DIMENSIONS_TOO_LARGE,
    IMAGE_DECODE_TIMEOUT,

    // QR and payload
    NO_QR_FOUND,
    NOT_A_SLIP_QR,
    CRC_INVALID,
    CRC_AUTOFIXED(isInfo = true),

    // Provider
    NO_PROVIDER_CONFIGURED(isInfo = true),
    PROVIDER_NOT_FOUND,
    PROVIDER_PENDING,
    PROVIDER_AUTH_ERROR,
    PROVIDER_QUOTA_EXCEEDED,
    PROVIDER_RATE_LIMITED,
    PROVIDER_TIMEOUT,
    PROVIDER_ERROR,
    PROVIDER_UNSUPPORTED_SLIP_TYPE,

    /** The caller's own [QuotaGuard] refused the call, so the provider was not asked (no money spent). */
    QUOTA_CAPPED,

    // Business checks
    AMOUNT_MISMATCH,
    RECEIVER_MISMATCH,
    TOO_OLD,
    TIME_UNAVAILABLE,
    DUPLICATE,

    /** The duplicate store failed, so uniqueness could not be proven. The slip is not accepted. */
    DUPLICATE_STORE_ERROR,

    /** Image worker capacity is exhausted; the caller may retry later. */
    IMAGE_PROCESSING_BUSY,

    /** The provider confirmed a different transfer than the submitted QR. */
    TRANSACTION_MISMATCH,

    /** Transfer time exceeds the two-minute future clock-skew allowance. */
    TIME_IN_FUTURE,
}
