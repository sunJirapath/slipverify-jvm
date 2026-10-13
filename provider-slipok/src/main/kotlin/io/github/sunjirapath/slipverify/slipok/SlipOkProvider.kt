package io.github.sunjirapath.slipverify.slipok

import com.google.gson.JsonObject
import io.github.sunjirapath.slipverify.Charge
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
import java.time.LocalDate
import java.time.format.DateTimeParseException

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
 * What `GET .../{branchId}/quota` reports (SlipOK guide v1.13, "Check Slip Quota").
 *
 * @property remaining slips left in the current package.
 * @property overQuota slips used beyond the package (billed).
 * @property specialRemaining remaining special quota.
 * @property endDate when the current package ends; the quota resets with the next package period. `null` if absent.
 */
public class SlipOkQuota(
    public val remaining: Int,
    public val overQuota: Int,
    public val specialRemaining: Int,
    public val endDate: LocalDate?,
) {
    override fun toString(): String = "SlipOkQuota(remaining=$remaining, overQuota=$overQuota, endDate=$endDate)"
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

        /**
         * Reads the package quota. Free (the guide does not bill this call). Never throws: any failure, a malformed
         * answer or a missing field gives `null`, so a caller must not treat `null` as "quota exhausted".
         */
        public fun quota(): SlipOkQuota? {
            val request =
                HttpRequest
                    .newBuilder(URI.create(config.baseUrl.trimEnd('/') + "/" + config.branchId + "/quota"))
                    .timeout(config.requestTimeout)
                    .header("x-authorization", config.apiKey())
                    .header("Accept", "application/json")
                    .header("User-Agent", "slipverify-jvm")
                    .GET()
                    .build()
            val result = http.send(request)
            if (result.transportFailure != null || result.status != HTTP_OK) return null
            val json = Json.parseObject(result.body)
            if (Json.bool(json, "success") != true) return null
            val data = Json.obj(json, "data")
            val remaining = Json.decimal(data, "quota")?.toInt() ?: return null
            return SlipOkQuota(
                remaining = remaining,
                overQuota = Json.decimal(data, "overQuota")?.toInt() ?: 0,
                specialRemaining = Json.decimal(data, "specialQuota")?.toInt() ?: 0,
                endDate = parseDate(Json.string(data, "endDate")),
            )
        }

        private fun parseDate(raw: String?): LocalDate? =
            try {
                raw?.let { LocalDate.parse(it) }
            } catch (
                @Suppress("SwallowedException") e: DateTimeParseException,
            ) {
                null
            }

        private fun interpret(result: HttpResult): ProviderOutcome {
            // A request that never reached SlipOK cost nothing; a timeout after sending may have been billed.
            result.transportFailure?.let {
                return ProviderOutcome.unavailable(it, if (result.notSent) Charge.NO else Charge.UNKNOWN)
            }
            val json = Json.parseObject(result.body)
            val code = Json.string(json, "code")
            return when {
                // SlipOK reports failures in the body (`code`, `message`); HTTP statuses per code are undocumented.
                code != null -> mapCode(code, json)

                result.isServerError -> ProviderOutcome.unavailable(ReasonCode.PROVIDER_ERROR, Charge.UNKNOWN)

                result.status == HTTP_OK -> parseSuccess(json)

                else -> byStatus(result.status)
            }
        }

        private fun parseSuccess(json: JsonObject?): ProviderOutcome {
            if (Json.bool(json, "success") != true) return ProviderOutcome.unavailable(ReasonCode.PROVIDER_ERROR)
            val slip = slip(Json.obj(json, "data"), duplicate = false)
            // A confirmed slip counts against the package (repeats too, unless `log` is on).
            return slip?.let { ProviderOutcome.verified(it, Charge.YES) }
                ?: ProviderOutcome.unavailable(ReasonCode.PROVIDER_ERROR, Charge.UNKNOWN)
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

        /**
         * Error table from <https://slipok.com/api-documentation/error-status-code/>. [Charge] follows the
         * "Quota Calculation" section of the SlipOK guide v1.13: only a valid slip is counted, and 1009 is
         * stated to be free; image, QR, auth and package errors never reach the slip lookup.
         */
        private fun mapCode(
            code: String,
            json: JsonObject?,
        ): ProviderOutcome =
            when (code) {
                "1001", "1002" -> {
                    ProviderOutcome.rejected(ReasonCode.PROVIDER_AUTH_ERROR, Charge.NO)
                }

                "1003", "1004" -> {
                    ProviderOutcome.rejected(ReasonCode.PROVIDER_QUOTA_EXCEEDED, Charge.NO)
                }

                "1007", "1008", "1011" -> {
                    ProviderOutcome.notFound(Charge.NO)
                }

                "1009" -> {
                    ProviderOutcome.unavailable(ReasonCode.PROVIDER_ERROR, Charge.NO)
                }

                "1010" -> {
                    ProviderOutcome.rejected(ReasonCode.PROVIDER_PENDING, Charge.NO)
                }

                // The slip is real and was seen before; its data (when SlipOK includes it) rides along.
                // Repeats are only reported when `log` is on, and the guide says they are not billed.
                "1012" -> {
                    slip(Json.obj(json, "data"), duplicate = true)?.let { ProviderOutcome.verified(it, Charge.NO) }
                        ?: ProviderOutcome.rejected(ReasonCode.DUPLICATE, Charge.NO)
                }

                // 1013/1014 are answers about a valid slip (the guide counts a receiver mismatch as used).
                "1013" -> {
                    ProviderOutcome.rejected(ReasonCode.AMOUNT_MISMATCH, Charge.YES)
                }

                "1014" -> {
                    ProviderOutcome.rejected(ReasonCode.RECEIVER_MISMATCH, Charge.YES)
                }

                // 1000 (no data), 1005/1006 (image problems): rejected before any lookup. Unknown codes stay UNKNOWN.
                "1000", "1005", "1006" -> {
                    ProviderOutcome.rejected(ReasonCode.PROVIDER_ERROR, Charge.NO)
                }

                else -> {
                    ProviderOutcome.rejected(ReasonCode.PROVIDER_ERROR)
                }
            }

        private fun byStatus(status: Int): ProviderOutcome =
            when (status) {
                HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> ProviderOutcome.rejected(ReasonCode.PROVIDER_AUTH_ERROR, Charge.NO)
                HTTP_TOO_MANY -> ProviderOutcome.rejected(ReasonCode.PROVIDER_RATE_LIMITED, Charge.NO)
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
