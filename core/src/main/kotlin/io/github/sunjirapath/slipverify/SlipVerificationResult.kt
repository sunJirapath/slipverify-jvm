package io.github.sunjirapath.slipverify

import java.math.BigDecimal
import java.time.Instant

/**
 * The single typed outcome of a verification.
 *
 * `status == VALID` is only ever produced after a provider confirmed the transfer. A slip QR on its own proves nothing
 * about a payment. `toString()` prints masked parties only.
 */
public class SlipVerificationResult(
    public val status: VerificationStatus,
    public val slipType: SlipType?,
    public val sendingBank: String?,
    public val transRef: String?,
    public val amount: BigDecimal?,
    public val transferTime: Instant?,
    public val maskedSender: String?,
    public val maskedReceiver: String?,
    public val providerName: String?,
    reasons: List<ReasonCode>,
    debugPayloads: List<String> = emptyList(),
) {
    public val reasons: List<ReasonCode> = reasons.distinct()

    /** Every QR text found in the image; empty unless the verifier was built with `debug(true)`. */
    public val debugPayloads: List<String> = debugPayloads.toList()

    public val isValid: Boolean get() = status == VerificationStatus.VALID

    public fun hasReason(code: ReasonCode): Boolean = code in reasons

    override fun toString(): String =
        "SlipVerificationResult(status=$status, slipType=$slipType, sendingBank=$sendingBank, " +
            "transRef=${Masking.account(transRef)}, amount=$amount, transferTime=$transferTime, " +
            "sender=$maskedSender, receiver=$maskedReceiver, provider=$providerName, reasons=$reasons)"
}
