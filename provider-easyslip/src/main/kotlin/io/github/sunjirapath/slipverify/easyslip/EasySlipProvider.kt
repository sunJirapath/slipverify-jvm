package io.github.sunjirapath.slipverify.easyslip

import com.google.gson.JsonObject
import io.github.sunjirapath.slipverify.ProviderOutcome
import io.github.sunjirapath.slipverify.ProviderQuery
import io.github.sunjirapath.slipverify.ReasonCode
import io.github.sunjirapath.slipverify.SlipProvider
import io.github.sunjirapath.slipverify.SlipType
import io.github.sunjirapath.slipverify.VerifiedSlip
import io.github.sunjirapath.slipverify.provider.HttpResult
import io.github.sunjirapath.slipverify.provider.Json
import io.github.sunjirapath.slipverify.provider.ProviderHttp
import io.github.sunjirapath.slipverify.provider.ThaiTime
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import java.util.Base64

/**
 * Settings for [EasySlipProvider].
 *
 * @property apiKey bearer token from the EasySlip developer portal. Read it from config or the environment, never
 *   from source. It is never printed.
 * @property baseUrl API root, `https://api.easyslip.com/v2` by default. Plain `http` is accepted only for localhost.
 * @property requestTimeout per-request budget; a timeout lets the verifier fall back to the next provider.
 * @property checkDuplicate ask EasySlip to flag slips it has seen before (`checkDuplicate: true`). Independent of the
 *   verifier's own duplicate store.
 */
public class EasySlipConfig
    @JvmOverloads
    constructor(
        apiKey: String,
        public val baseUrl: String = DEFAULT_BASE_URL,
        public val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
        public val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
        public val checkDuplicate: Boolean = false,
    ) {
        private val key: String = apiKey

        init {
            require(apiKey.isNotBlank()) { "EasySlip apiKey must not be blank" }
            require(!requestTimeout.isNegative && !requestTimeout.isZero) { "requestTimeout must be positive" }
            require(!connectTimeout.isNegative && !connectTimeout.isZero) { "connectTimeout must be positive" }
            val uri = URI.create(baseUrl)
            val local = uri.host == "localhost" || uri.host == "127.0.0.1"
            require(uri.scheme == "https" || (uri.scheme == "http" && local)) { "baseUrl must be https" }
        }

        internal fun apiKey(): String = key

        override fun toString(): String = "EasySlipConfig(baseUrl=$baseUrl, apiKey=***, requestTimeout=$requestTimeout)"

        public companion object {
            public const val DEFAULT_BASE_URL: String = "https://api.easyslip.com/v2"
            private val DEFAULT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
            private val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        }
    }

/**
 * [SlipProvider] for EasySlip API v2 (<https://document.easyslip.com/en/v2/>).
 *
 * - Bank slips are verified by QR payload (`POST /verify/bank`).
 * - TrueMoney slips have no payload endpoint at EasySlip, so the **image** is sent as base64
 *   (`POST /verify/truewallet`). Without an image the provider answers "unsupported".
 *
 * Amount and receiver matching are left to the verifier, so the same rules apply to every provider.
 */
public class EasySlipProvider
    @JvmOverloads
    constructor(
        private val config: EasySlipConfig,
        private val http: ProviderHttp = ProviderHttp(ProviderHttp.defaultClient(config.connectTimeout)),
    ) : SlipProvider {
        override val name: String = "easyslip"

        override fun supports(type: SlipType): Boolean = type == SlipType.BOT_STANDARD || type == SlipType.TRUEMONEY

        override fun verify(query: ProviderQuery): ProviderOutcome {
            val image = query.image
            val (path, body) =
                when (query.slipType) {
                    SlipType.BOT_STANDARD -> {
                        "/verify/bank" to bankBody(query.payload)
                    }

                    SlipType.TRUEMONEY -> {
                        if (image == null) return ProviderOutcome.unsupported()
                        "/verify/truewallet" to imageBody(image)
                    }
                }
            val result = http.send(request(path, body))
            return interpret(result, query.slipType)
        }

        private fun bankBody(payload: String): JsonObject =
            JsonObject().apply {
                addProperty("payload", payload)
                if (config.checkDuplicate) addProperty("checkDuplicate", true)
            }

        private fun imageBody(image: ByteArray): JsonObject =
            JsonObject().apply {
                addProperty("base64", Base64.getEncoder().encodeToString(image))
                if (config.checkDuplicate) addProperty("checkDuplicate", true)
            }

        private fun request(
            path: String,
            body: JsonObject,
        ): HttpRequest =
            HttpRequest
                .newBuilder(URI.create(config.baseUrl.trimEnd('/') + path))
                .timeout(config.requestTimeout)
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "slipverify-jvm")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()

        private fun interpret(
            result: HttpResult,
            type: SlipType,
        ): ProviderOutcome {
            result.transportFailure?.let { return ProviderOutcome.unavailable(it) }
            val json = Json.parseObject(result.body)
            return when {
                result.isServerError -> ProviderOutcome.unavailable(ReasonCode.PROVIDER_ERROR)
                result.status == HTTP_OK -> parseSuccess(json, type)
                else -> parseError(result.status, Json.string(json, "error", "code"))
            }
        }

        private fun parseSuccess(
            json: JsonObject?,
            type: SlipType,
        ): ProviderOutcome {
            if (Json.bool(json, "success") != true) return ProviderOutcome.unavailable(ReasonCode.PROVIDER_ERROR)
            val slip =
                when (type) {
                    SlipType.BOT_STANDARD -> bankSlip(json)
                    SlipType.TRUEMONEY -> trueMoneySlip(json)
                }
            // 200 without an amount is not a usable answer: treat as a provider fault, not as proof.
            return slip?.let { ProviderOutcome.verified(it) } ?: ProviderOutcome.unavailable(ReasonCode.PROVIDER_ERROR)
        }

        private fun bankSlip(json: JsonObject?): VerifiedSlip? {
            val raw = Json.obj(json, "data", "rawSlip")
            val amount = Json.decimal(raw, "amount", "amount") ?: return null
            return VerifiedSlip(
                amount = amount,
                transferTime = ThaiTime.parse(Json.string(raw, "date")),
                senderName = name(raw, "sender"),
                senderAccount = Json.string(raw, "sender", "account", "bank", "account"),
                receiverName = name(raw, "receiver"),
                receiverAccount = Json.string(raw, "receiver", "account", "bank", "account"),
                transRef = Json.string(raw, "transRef"),
                sendingBank = Json.string(raw, "sender", "bank", "id"),
                duplicateAtProvider = Json.bool(json, "data", "isDuplicate") == true,
            )
        }

        private fun trueMoneySlip(json: JsonObject?): VerifiedSlip? {
            val raw = Json.obj(json, "data", "rawSlip")
            val amount = Json.decimal(raw, "amount") ?: Json.decimal(json, "data", "amountInSlip") ?: return null
            return VerifiedSlip(
                amount = amount,
                transferTime = ThaiTime.parse(Json.string(raw, "date")),
                senderName = Json.string(raw, "sender", "name"),
                receiverName = Json.string(raw, "receiver", "name"),
                // The wallet "account" is the receiver's phone number.
                receiverAccount = Json.string(raw, "receiver", "phone"),
                transRef = Json.string(raw, "transactionId"),
                duplicateAtProvider = Json.bool(json, "data", "isDuplicate") == true,
            )
        }

        private fun name(
            raw: JsonObject?,
            side: String,
        ): String? = Json.string(raw, side, "account", "name", "th") ?: Json.string(raw, side, "account", "name", "en")

        /** Error table from <https://document.easyslip.com/en/reference/error-codes>. */
        private fun parseError(
            status: Int,
            code: String?,
        ): ProviderOutcome =
            when {
                code == "SLIP_NOT_FOUND" -> {
                    ProviderOutcome.notFound()
                }

                code == "SLIP_PENDING" -> {
                    ProviderOutcome.rejected(ReasonCode.PROVIDER_PENDING)
                }

                code == "QUOTA_EXCEEDED" -> {
                    ProviderOutcome.rejected(ReasonCode.PROVIDER_QUOTA_EXCEEDED)
                }

                code == "RATE_LIMIT_EXCEEDED" || status == HTTP_TOO_MANY -> {
                    ProviderOutcome.rejected(
                        ReasonCode.PROVIDER_RATE_LIMITED,
                    )
                }

                status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN -> {
                    ProviderOutcome.rejected(
                        ReasonCode.PROVIDER_AUTH_ERROR,
                    )
                }

                else -> {
                    ProviderOutcome.rejected(ReasonCode.PROVIDER_ERROR)
                }
            }

        override fun toString(): String = "EasySlipProvider(baseUrl=${config.baseUrl})"

        private companion object {
            const val HTTP_OK = 200
            const val HTTP_UNAUTHORIZED = 401
            const val HTTP_FORBIDDEN = 403
            const val HTTP_TOO_MANY = 429
        }
    }
