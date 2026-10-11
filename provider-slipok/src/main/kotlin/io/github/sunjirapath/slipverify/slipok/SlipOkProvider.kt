package io.github.sunjirapath.slipverify.slipok

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
import java.time.Instant

/**
 * Settings for [SlipOkProvider].
 *
 * @property apiKey sent as the `x-authorization` header. Read it from config or the environment. Never printed.
 * @property branchId the SlipOK branch id that is part of the endpoint path.
 * @property baseUrl `https://api.slipok.com/api/line/apikey` by default. Plain `http` only for localhost.
 * @property log send `log: true`. SlipOK then detects duplicates and checks the receiver against the account linked
 *   to the branch, and counts the slip in the shop's report. Off by default; the verifier has its own checks.
 */
public class SlipOkConfig
    @JvmOverloads
    constructor(
        apiKey: String,
        public val branchId: String,
        public val baseUrl: String = DEFAULT_BASE_URL,
        public val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
        public val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
        public val log: Boolean = false,
    ) {
        private val key: String = apiKey

        init {
            require(apiKey.isNotBlank()) { "SlipOK apiKey must not be blank" }
            require(branchId.isNotEmpty() && branchId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                "SlipOK branchId must be letters, digits, '-' or '_'"
            }
            require(!requestTimeout.isNegative && !requestTimeout.isZero) { "requestTimeout must be positive" }
            require(!connectTimeout.isNegative && !connectTimeout.isZero) { "connectTimeout must be positive" }
            val uri = URI.create(baseUrl)
            val local = uri.host == "localhost" || uri.host == "127.0.0.1"
            require(uri.scheme == "https" || (uri.scheme == "http" && local)) { "baseUrl must be https" }
        }

        internal fun apiKey(): String = key

        override fun toString(): String = "SlipOkConfig(baseUrl=$baseUrl, branchId=***, apiKey=***, log=$log)"

        public companion object {
            public const val DEFAULT_BASE_URL: String = "https://api.slipok.com/api/line/apikey"
            private val DEFAULT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
            private val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        }
    }

/**
 * [SlipProvider] for SlipOK (<https://slipok.com/api-documentation/check-slip/>), verifying by QR payload (`data`).
 * Only standard bank slips: SlipOK's docs do not mention TrueMoney, so those are left to other providers.
 */
public class SlipOkProvider
    @JvmOverloads
    constructor(
        private val config: SlipOkConfig,
        private val http: ProviderHttp = ProviderHttp(ProviderHttp.defaultClient(config.connectTimeout)),
    ) : SlipProvider {
        override val name: String = "slipok"

        override fun supports(type: SlipType): Boolean = type == SlipType.BOT_STANDARD

        override fun verify(query: ProviderQuery): ProviderOutcome {
            if (query.slipType != SlipType.BOT_STANDARD) return ProviderOutcome.unsupported()
            val body =
                JsonObject().apply {
                    addProperty("data", query.payload)
                    if (config.log) addProperty("log", true)
                }
            val request =
                HttpRequest
                    .newBuilder(URI.create(config.baseUrl.trimEnd('/') + "/" + config.branchId))
                    .timeout(config.requestTimeout)
                    .header("x-authorization", config.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "slipverify-jvm")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build()
            return interpret(http.send(request))
        }

        private fun interpret(result: HttpResult): ProviderOutcome {
            result.transportFailure?.let { return ProviderOutcome.unavailable(it) }
            val json = Json.parseObject(result.body)
            val code = Json.string(json, "code")
            return when {
                // SlipOK reports failures in the body (`code`, `message`); HTTP statuses per code are undocumented.
                code != null -> mapCode(code, json)

                result.isServerError -> ProviderOutcome.unavailable(ReasonCode.PROVIDER_ERROR)

                result.status == HTTP_OK -> parseSuccess(json)

                else -> byStatus(result.status)
            }
        }

        private fun parseSuccess(json: JsonObject?): ProviderOutcome {
            if (Json.bool(json, "success") != true) return ProviderOutcome.unavailable(ReasonCode.PROVIDER_ERROR)
            val slip = slip(Json.obj(json, "data"), duplicate = false)
            return slip?.let { ProviderOutcome.verified(it) } ?: ProviderOutcome.unavailable(ReasonCode.PROVIDER_ERROR)
        }

        private fun slip(
            data: JsonObject?,
            duplicate: Boolean,
        ): VerifiedSlip? {
            val amount = Json.decimal(data, "amount") ?: return null
            return VerifiedSlip(
                amount = amount,
                transferTime = time(data),
                senderName = name(data, "sender"),
                senderAccount = Json.string(data, "sender", "account", "value"),
                receiverName = name(data, "receiver"),
                receiverAccount =
                    Json.string(data, "receiver", "account", "value")
                        ?: Json.string(data, "receiver", "proxy", "value"),
                transRef = Json.string(data, "transRef"),
                sendingBank = Json.string(data, "sendingBank"),
                duplicateAtProvider = duplicate,
            )
        }

        // `name` may be partial; `displayName` is the fallback. Either is fine for the verifier's partial matching.
        private fun name(
            data: JsonObject?,
            side: String,
        ): String? = Json.string(data, side, "name") ?: Json.string(data, side, "displayName")

        // TODO(verify): the docs list transDate, transTime and transTimestamp without sample values.
        private fun time(data: JsonObject?): Instant? =
            ThaiTime.parse(Json.string(data, "transTimestamp"))
                ?: ThaiTime.parse(
                    listOfNotNull(
                        date(Json.string(data, "transDate")),
                        Json.string(data, "transTime"),
                    ).joinToString("T")
                        .takeIf { it.contains('T') },
                )

        private fun date(raw: String?): String? =
            raw
                ?.takeIf { it.length == COMPACT_DATE && it.all(Char::isDigit) }
                ?.let { "${it.take(YEAR_END)}-${it.substring(YEAR_END, MONTH_END)}-${it.substring(MONTH_END)}" }
                ?: raw

        /** Error table from <https://slipok.com/api-documentation/error-status-code/>. */
        private fun mapCode(
            code: String,
            json: JsonObject?,
        ): ProviderOutcome =
            when (code) {
                "1001", "1002" -> {
                    ProviderOutcome.rejected(ReasonCode.PROVIDER_AUTH_ERROR)
                }

                "1003", "1004" -> {
                    ProviderOutcome.rejected(ReasonCode.PROVIDER_QUOTA_EXCEEDED)
                }

                "1007", "1008", "1011" -> {
                    ProviderOutcome.notFound()
                }

                "1009" -> {
                    ProviderOutcome.unavailable(ReasonCode.PROVIDER_ERROR)
                }

                "1010" -> {
                    ProviderOutcome.rejected(ReasonCode.PROVIDER_PENDING)
                }

                // The slip is real and was seen before; its data (when SlipOK includes it) rides along.
                "1012" -> {
                    slip(Json.obj(json, "data"), duplicate = true)?.let { ProviderOutcome.verified(it) }
                        ?: ProviderOutcome.rejected(ReasonCode.DUPLICATE)
                }

                "1013" -> {
                    ProviderOutcome.rejected(ReasonCode.AMOUNT_MISMATCH)
                }

                "1014" -> {
                    ProviderOutcome.rejected(ReasonCode.RECEIVER_MISMATCH)
                }

                // 1000 (no data), 1005/1006 (image problems) and anything unknown.
                else -> {
                    ProviderOutcome.rejected(ReasonCode.PROVIDER_ERROR)
                }
            }

        private fun byStatus(status: Int): ProviderOutcome =
            when (status) {
                HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> ProviderOutcome.rejected(ReasonCode.PROVIDER_AUTH_ERROR)
                HTTP_TOO_MANY -> ProviderOutcome.rejected(ReasonCode.PROVIDER_RATE_LIMITED)
                else -> ProviderOutcome.rejected(ReasonCode.PROVIDER_ERROR)
            }

        override fun toString(): String = "SlipOkProvider(baseUrl=${config.baseUrl})"

        private companion object {
            const val HTTP_OK = 200
            const val HTTP_UNAUTHORIZED = 401
            const val HTTP_FORBIDDEN = 403
            const val HTTP_TOO_MANY = 429
            const val COMPACT_DATE = 8
            const val YEAR_END = 4
            const val MONTH_END = 6
        }
    }
