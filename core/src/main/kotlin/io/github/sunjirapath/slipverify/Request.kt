package io.github.sunjirapath.slipverify

import java.math.BigDecimal
import java.time.Duration

/**
 * What the caller expects of the receiving side. Providers return masked data (`xxx-x-x1234-x`, partial names),
 * so both checks are tolerant (digits of a masked account are compared position by position).
 *
 * @property accountSuffix last digits of the expected receiving identifier, at least 3. This rule is untyped:
 *   EasySlip uses the bank account when supplied, otherwise the PromptPay proxy (such as a phone number).
 *   A bank account suffix and a proxy suffix are not interchangeable; configure the identifier the provider returns.
 * @property nameContains part of the expected receiver name (Thai or English, titles ignored).
 */
public class ReceiverRule
    @JvmOverloads
    constructor(
        public val accountSuffix: String? = null,
        public val nameContains: String? = null,
    ) {
        init {
            require(accountSuffix != null || nameContains != null) { "ReceiverRule needs an account suffix or a name" }
            require(accountSuffix == null || accountSuffix.count { it.isDigit() } >= MIN_SUFFIX) {
                "accountSuffix needs at least $MIN_SUFFIX digits"
            }
            require(nameContains == null || nameContains.isNotBlank()) { "nameContains must not be blank" }
        }

        override fun toString(): String =
            "ReceiverRule(accountSuffix=${Masking.account(accountSuffix)}, name=${Masking.name(nameContains)})"

        private companion object {
            const val MIN_SUFFIX = 3
        }
    }

/**
 * One verification request. Provide [imageBase64] (raw base64 or a data URL) or [payload] (the slip QR text, if the
 * frontend already scanned it); when both are given the payload wins and the image is only forwarded to providers
 * that need it.
 */
public class SlipRequest
    @JvmOverloads
    constructor(
        public val imageBase64: String? = null,
        public val payload: String? = null,
        public val expectedAmount: BigDecimal? = null,
        public val expectedReceiver: ReceiverRule? = null,
        public val maxAge: Duration? = null,
    ) {
        init {
            require(maxAge == null || !maxAge.isNegative) { "maxAge must not be negative" }
            require(expectedAmount == null || expectedAmount.signum() > 0) { "expectedAmount must be positive" }
        }

        // Image and payload are personal data: never printed.
        override fun toString(): String =
            "SlipRequest(hasImage=${!imageBase64.isNullOrBlank()}, hasPayload=${!payload.isNullOrBlank()}, " +
                "expectedAmount=$expectedAmount, expectedReceiver=$expectedReceiver, maxAge=$maxAge)"
    }
