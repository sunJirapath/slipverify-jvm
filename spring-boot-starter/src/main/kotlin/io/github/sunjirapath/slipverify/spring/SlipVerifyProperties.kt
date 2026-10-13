package io.github.sunjirapath.slipverify.spring

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `slipverify.*` settings. API keys come from configuration or environment variables
 * (`SLIPVERIFY_EASYSLIP_API_KEY`), never from code.
 */
@ConfigurationProperties("slipverify")
public class SlipVerifyProperties {
    /** Master switch. */
    public var enabled: Boolean = true

    /** Include every decoded QR text in results. Leave off in production: payloads identify people. */
    public var debug: Boolean = false

    /** Fallback chain, first to last. Providers that are not configured are skipped. */
    public var providerOrder: List<String> = listOf("easyslip", "slipok")

    public val duplicate: Duplicate = Duplicate()
    public val image: Image = Image()
    public val easyslip: EasySlip = EasySlip()
    public val slipok: SlipOk = SlipOk()
    public val web: Web = Web()

    /** Time zone for every `bill-cutoff` (billing rounds are calendar days in this zone). */
    public var billZone: String = "Asia/Bangkok"

    public class Duplicate {
        public var enabled: Boolean = true

        /** `memory` (one JVM) or `redis` (needs `store-redis` and [redisUri]). */
        public var store: String = "memory"
        public var ttl: Duration = Duration.ofDays(DEFAULT_TTL_DAYS)
        public var redisUri: String? = null

        private companion object {
            const val DEFAULT_TTL_DAYS = 30L
        }
    }

    public class Image {
        public var maxEncodedBytes: Int = io.github.sunjirapath.slipverify.zxing.ImageLimits.DEFAULT_MAX_ENCODED_BYTES
        public var maxDimension: Int = io.github.sunjirapath.slipverify.zxing.ImageLimits.DEFAULT_MAX_DIMENSION
        public var maxPixels: Long = io.github.sunjirapath.slipverify.zxing.ImageLimits.DEFAULT_MAX_PIXELS
        public var decodeTimeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS)

        private companion object {
            const val DEFAULT_TIMEOUT_SECONDS = 10L
        }
    }

    /** Request caps for one provider. Unset means unlimited. */
    public class Limit {
        /** Provider calls allowed per billing round; resets on `bill-cutoff`, so that must be set too. */
        public var maxPerCycle: Long? = null

        /** Provider calls allowed per clock minute. */
        public var maxPerMinute: Long? = null
    }

    public class EasySlip {
        /** Setting this enables the provider. */
        public var apiKey: String? = null
        public var baseUrl: String = "https://api.easyslip.com/v2"

        /**
         * Billing round reset: a day of the month (`13`) or the next billing date (`2026-11-13`, `13/11/2026`),
         * which means "resets every 13th".
         */
        public var billCutoff: String? = null
        public val limit: Limit = Limit()
        public var requestTimeout: Duration = Duration.ofSeconds(DEFAULT_REQUEST_SECONDS)
        public var connectTimeout: Duration = Duration.ofSeconds(DEFAULT_CONNECT_SECONDS)
        public var checkDuplicate: Boolean = false

        private companion object {
            const val DEFAULT_REQUEST_SECONDS = 10L
            const val DEFAULT_CONNECT_SECONDS = 5L
        }
    }

    public class SlipOk {
        /** Setting this (with [branchId]) enables the provider. */
        public var apiKey: String? = null
        public var branchId: String? = null
        public var baseUrl: String = "https://api.slipok.com/api/line/apikey"

        /**
         * Your full endpoint, branch id included: `https://api.slipok.com/api/line/apikey/<YOUR_BRANCH_ID>`.
         * Replaces [baseUrl] and [branchId] when set.
         */
        public var url: String? = null

        /**
         * Billing round reset: a day of the month (`13`) or the next billing date (`2026-11-13`, `13/11/2026`),
         * which means "resets every 13th".
         */
        public var billCutoff: String? = null
        public val limit: Limit = Limit()
        public var requestTimeout: Duration = Duration.ofSeconds(DEFAULT_REQUEST_SECONDS)
        public var connectTimeout: Duration = Duration.ofSeconds(DEFAULT_CONNECT_SECONDS)
        public var log: Boolean = false

        private companion object {
            const val DEFAULT_REQUEST_SECONDS = 10L
            const val DEFAULT_CONNECT_SECONDS = 5L
        }
    }

    public class Web {
        /** Expose the example `POST` endpoint. Off by default: you decide who may call it. */
        public var enabled: Boolean = false
        public var path: String = "/slips/verify"

        /** Largest accepted request body. Must exceed the image limit by the base64/JSON overhead. */
        public var maxRequestBytes: Long = DEFAULT_MAX_REQUEST_BYTES

        private companion object {
            const val DEFAULT_MAX_REQUEST_BYTES = 7L * 1024 * 1024
        }
    }
}
