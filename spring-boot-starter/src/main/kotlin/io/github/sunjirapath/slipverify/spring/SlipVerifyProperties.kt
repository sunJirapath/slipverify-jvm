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

    public class EasySlip {
        /** Setting this enables the provider. */
        public var apiKey: String? = null
        public var baseUrl: String = "https://api.easyslip.com/v2"
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
