package io.github.sunjirapath.slipverify.easyslip

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.github.sunjirapath.promptparse.SlipVerify
import io.github.sunjirapath.slipverify.ExtractionResult
import io.github.sunjirapath.slipverify.InMemoryDuplicateStore
import io.github.sunjirapath.slipverify.ProviderOutcomeKind
import io.github.sunjirapath.slipverify.ProviderQuery
import io.github.sunjirapath.slipverify.QrExtractor
import io.github.sunjirapath.slipverify.ReasonCode
import io.github.sunjirapath.slipverify.ReceiverRule
import io.github.sunjirapath.slipverify.SlipRequest
import io.github.sunjirapath.slipverify.SlipType
import io.github.sunjirapath.slipverify.SlipVerifier
import io.github.sunjirapath.slipverify.VerificationStatus
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.function.Predicate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EasySlipProviderTest {
    private lateinit var server: WireMockServer
    private val payload = SlipVerify.build("004", "SYNTH0001")
    private val apiKey = "test-key-123456"

    @BeforeTest
    fun start() {
        server = WireMockServer(options().dynamicPort()).also { it.start() }
    }

    @AfterTest
    fun stop() = server.stop()

    private fun config(
        timeout: Duration = Duration.ofSeconds(5),
        checkDuplicate: Boolean = false,
        url: String = "http://localhost:${server.port()}/v2",
    ) = EasySlipConfig(apiKey, url, requestTimeout = timeout, checkDuplicate = checkDuplicate)

    private fun provider(
        timeout: Duration = Duration.ofSeconds(5),
        checkDuplicate: Boolean = false,
    ) = EasySlipProvider(config(timeout, checkDuplicate))

    private fun bank(payload: String = this.payload) = ProviderQuery(SlipType.BOT_STANDARD, payload, null, null)

    private fun trueMoney(image: ByteArray?) = ProviderQuery(SlipType.TRUEMONEY, "tm", image, null)

    private fun reply(
        status: Int,
        body: String,
    ) = aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)

    // Field names follow https://document.easyslip.com/en/v2/verify/bank/payload; values are invented.
    private val bankSuccess =
        """
        {"success":true,"message":"Bank slip verified successfully","data":{"isDuplicate":false,"rawSlip":{
          "payload":"x","transRef":"SYNTH0001","date":"2026-10-05T10:15:00+07:00","countryCode":"TH","fee":0,
          "amount":{"amount":150.50,"local":{"amount":0,"currency":""}},
          "sender":{"bank":{"id":"004","name":"KBank","short":"KBANK"},"account":{"name":{"th":"นางสาว สมหญิง รักดี","en":"MISS SOMYING"},"bank":{"type":"BANKAC","account":"xxx-x-x5678-x"}}},
          "receiver":{"bank":{"id":"014","name":"SCB","short":"SCB"},"account":{"name":{"th":"นาย สมชาย ใจดี"},"bank":{"type":"BANKAC","account":"xxx-x-x1234-x"}}}}}}
        """.trimIndent()

    private val trueMoneySuccess =
        """
        {"success":true,"message":"TrueMoney Wallet slip verified successfully","data":{"isDuplicate":false,"amountInSlip":500.00,
          "rawSlip":{"transactionId":"TM123","date":"2026-10-05T10:15:00+07:00","amount":500.00,
          "sender":{"name":"Test Sender"},"receiver":{"name":"Test Receiver","phone":"xxx-xxx-1234"}}}}
        """.trimIndent()

    @Test
    fun `image confirmation cannot be replayed using a different submitted wallet QR`() {
        server.stubFor(post(urlEqualTo("/v2/verify/truewallet")).willReturn(reply(200, trueMoneySuccess)))
        val realPayload = SlipVerify.buildTrueMoney("P2P", "TM123", "05102026")
        val extractor =
            object : QrExtractor {
                override fun extract(
                    imageBase64: String,
                    isSlip: Predicate<String>,
                ) = ExtractionResult(listOf(realPayload), realPayload, null, byteArrayOf(1, 2, 3))
            }
        val v =
            SlipVerifier
                .builder()
                .provider(provider())
                .qrExtractor(extractor)
                .duplicateStore(InMemoryDuplicateStore())
                .build()
        val real = SlipRequest(imageBase64 = "synthetic-image", payload = realPayload)
        assertEquals(VerificationStatus.VALID, v.verify(real).status)
        for (ref in listOf("FAKE1", "FAKE2")) {
            val request = SlipRequest(imageBase64 = "synthetic-image", payload = SlipVerify.buildTrueMoney("P2P", ref, "05102026"))
            val result = v.verify(request)
            assertEquals(VerificationStatus.INVALID, result.status)
            assertTrue(result.hasReason(ReasonCode.TRANSACTION_MISMATCH))
        }
        assertTrue(v.verify(real).hasReason(ReasonCode.DUPLICATE))
    }

    // ---- success ----

    @Test
    fun `bank success is parsed and the request is shaped as documented`() {
        server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(200, bankSuccess)))
        val out = provider().verify(bank())
        assertEquals(ProviderOutcomeKind.VERIFIED, out.kind)
        val slip = out.slip!!
        assertEquals(BigDecimal("150.50"), slip.amount)
        assertEquals(Instant.parse("2026-10-05T03:15:00Z"), slip.transferTime)
        assertEquals("นาย สมชาย ใจดี", slip.receiverName)
        assertEquals("xxx-x-x1234-x", slip.receiverAccount)
        assertEquals("นางสาว สมหญิง รักดี", slip.senderName)
        assertEquals("SYNTH0001", slip.transRef)
        assertEquals("004", slip.sendingBank)
        assertFalse(slip.duplicateAtProvider)
        server.verify(
            postRequestedFor(urlEqualTo("/v2/verify/bank"))
                .withHeader("Authorization", equalTo("Bearer $apiKey"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson("""{"payload":"$payload"}""")),
        )
    }

    @Test
    fun `checkDuplicate is only sent when enabled and isDuplicate is reported`() {
        server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(200, bankSuccess.replace("\"isDuplicate\":false", "\"isDuplicate\":true"))))
        val out = provider(checkDuplicate = true).verify(bank())
        assertTrue(out.slip!!.duplicateAtProvider)
        server.verify(postRequestedFor(urlEqualTo("/v2/verify/bank")).withRequestBody(matchingJsonPath("$.checkDuplicate", equalTo("true"))))
    }

    @Test
    fun `trueMoney sends the image as base64 to the truewallet endpoint`() {
        server.stubFor(post(urlEqualTo("/v2/verify/truewallet")).willReturn(reply(200, trueMoneySuccess)))
        val out = provider().verify(trueMoney(byteArrayOf(1, 2, 3)))
        val slip = out.slip!!
        assertEquals(BigDecimal("500.00"), slip.amount)
        assertEquals("TM123", slip.transRef)
        assertEquals("xxx-xxx-1234", slip.receiverAccount)
        assertEquals("Test Receiver", slip.receiverName)
        assertNull(slip.sendingBank)
        server.verify(postRequestedFor(urlEqualTo("/v2/verify/truewallet")).withRequestBody(equalToJson("""{"base64":"AQID"}""")))
    }

    @Test
    fun `trueMoney without an image is unsupported and sends nothing`() {
        val out = provider().verify(trueMoney(null))
        assertEquals(ProviderOutcomeKind.UNSUPPORTED, out.kind)
        assertEquals(0, server.allServeEvents.size)
    }

    // ---- documented errors ----

    private fun errorBody(code: String) = """{"success":false,"error":{"code":"$code","message":"m"}}"""

    @Test
    fun `documented error codes map to outcomes`() {
        data class Case(
            val status: Int,
            val code: String,
            val kind: ProviderOutcomeKind,
            val reason: ReasonCode,
        )
        val cases =
            listOf(
                Case(404, "SLIP_NOT_FOUND", ProviderOutcomeKind.NOT_FOUND, ReasonCode.PROVIDER_NOT_FOUND),
                Case(404, "SLIP_PENDING", ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_PENDING),
                Case(401, "INVALID_API_KEY", ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_AUTH_ERROR),
                Case(401, "MISSING_API_KEY", ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_AUTH_ERROR),
                Case(403, "IP_NOT_ALLOWED", ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_AUTH_ERROR),
                Case(403, "BRANCH_INACTIVE", ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_AUTH_ERROR),
                Case(403, "QUOTA_EXCEEDED", ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_QUOTA_EXCEEDED),
                Case(429, "RATE_LIMIT_EXCEEDED", ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_RATE_LIMITED),
                Case(400, "VALIDATION_ERROR", ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_ERROR),
                Case(404, "NOT_FOUND", ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_ERROR),
                Case(500, "API_SERVER_ERROR", ProviderOutcomeKind.UNAVAILABLE, ReasonCode.PROVIDER_ERROR),
                Case(500, "INTERNAL_SERVER_ERROR", ProviderOutcomeKind.UNAVAILABLE, ReasonCode.PROVIDER_ERROR),
            )
        for (c in cases) {
            server.resetAll()
            server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(c.status, errorBody(c.code))))
            val out = provider().verify(bank())
            assertEquals(c.kind, out.kind, c.code)
            assertEquals(c.reason, out.reason, c.code)
        }
    }

    @Test
    fun `4xx without a json body and 5xx with html`() {
        server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(aResponse().withStatus(403).withBody("<html>forbidden</html>")))
        assertEquals(ReasonCode.PROVIDER_AUTH_ERROR, provider().verify(bank()).reason)
        server.resetAll()
        server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(aResponse().withStatus(502).withBody("<html>bad gateway</html>")))
        assertEquals(ProviderOutcomeKind.UNAVAILABLE, provider().verify(bank()).kind)
    }

    @Test
    fun `timeout is unavailable with a timeout reason`() {
        server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(200, bankSuccess).withFixedDelay(1500)))
        val out = provider(timeout = Duration.ofMillis(300)).verify(bank())
        assertEquals(ProviderOutcomeKind.UNAVAILABLE, out.kind)
        assertEquals(ReasonCode.PROVIDER_TIMEOUT, out.reason)
    }

    @Test
    fun `connection refused is unavailable`() {
        val p = provider()
        server.stop()
        val out = p.verify(bank())
        assertEquals(ProviderOutcomeKind.UNAVAILABLE, out.kind)
        assertEquals(ReasonCode.PROVIDER_ERROR, out.reason)
    }

    @Test
    fun `malformed or incomplete 200 responses are never trusted`() {
        val bodies =
            listOf(
                "not json at all",
                "[]",
                "",
                """{"success":false,"message":"nope"}""",
                """{"success":true,"data":{"rawSlip":{"transRef":"x"}}}""",
                """{"success":true,"data":{"rawSlip":{"amount":{"amount":"abc"}}}}""",
                """{"success":true}""",
                """{"success":"yes","data":{}}""",
            )
        for (body in bodies) {
            server.resetAll()
            server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(200, body)))
            val out = provider().verify(bank())
            assertEquals(ProviderOutcomeKind.UNAVAILABLE, out.kind, body)
        }
    }

    @Test
    fun `missing optional fields still verify`() {
        server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(200, """{"success":true,"data":{"rawSlip":{"amount":{"amount":"99.00"},"date":"garbage"}}}""")))
        val slip = provider().verify(bank()).slip!!
        assertEquals(BigDecimal("99.00"), slip.amount)
        assertNull(slip.transferTime)
        assertNull(slip.receiverName)
    }

    // ---- through SlipVerifier ----

    @Test
    fun `malformed comma amounts never pass an expected amount rule`() {
        for (amount in listOf("1,50", "15,,0", "150,")) {
            val body = bankSuccess.replace("150.50", "\"$amount\"")
            server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(200, body)))
            val result =
                SlipVerifier
                    .builder()
                    .provider(provider())
                    .build()
                    .verify(SlipRequest(payload = payload, expectedAmount = BigDecimal("150")))
            assertEquals(VerificationStatus.ERROR, result.status, amount)
            assertTrue(result.hasReason(ReasonCode.PROVIDER_ERROR))
        }
        server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(200, bankSuccess.replace("150.50", "\"1,234.50\""))))
        assertEquals(
            VerificationStatus.VALID,
            SlipVerifier
                .builder()
                .provider(provider())
                .build()
                .verify(SlipRequest(payload = payload, expectedAmount = BigDecimal("1234.50")))
                .status,
        )
    }

    @Test
    fun `proxy-only receiver matches the untyped receiving identifier rule`() {
        val body = bankSuccess.replace("\"bank\":{\"type\":\"BANKAC\",\"account\":\"xxx-x-x1234-x\"}", "\"proxy\":{\"type\":\"MSISDN\",\"account\":\"xxx-xxx-1234\"}")
        server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(200, body)))
        assertEquals("xxx-xxx-1234", provider().verify(bank()).slip!!.receiverAccount)
        val verifier = SlipVerifier.builder().provider(provider()).build()
        assertEquals(VerificationStatus.VALID, verifier.verify(SlipRequest(payload = payload, expectedReceiver = ReceiverRule("1234", "สมชาย"))).status)
        for (rule in listOf(ReceiverRule("9999"), ReceiverRule("1234", "ศิริชัย"))) {
            assertTrue(verifier.verify(SlipRequest(payload = payload, expectedReceiver = rule)).hasReason(ReasonCode.RECEIVER_MISMATCH))
        }
    }

    @Test
    fun `bank account takes priority over a conflicting proxy and a masked proxy fails closed`() {
        val bankAccount = "\"bank\":{\"type\":\"BANKAC\",\"account\":\"xxx-x-x1234-x\"}"
        val both = bankSuccess.replace(bankAccount, "$bankAccount,\"proxy\":{\"type\":\"MSISDN\",\"account\":\"xxx-xxx-9999\"}")
        server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(200, both)))
        val verifier = SlipVerifier.builder().provider(provider()).build()
        assertEquals(VerificationStatus.VALID, verifier.verify(SlipRequest(payload = payload, expectedReceiver = ReceiverRule("1234"))).status)
        assertTrue(verifier.verify(SlipRequest(payload = payload, expectedReceiver = ReceiverRule("9999"))).hasReason(ReasonCode.RECEIVER_MISMATCH))
        for (account in listOf("xxx-xxx-xxxx", "xxx-xxx-9999", "")) {
            val proxy = bankSuccess.replace(bankAccount, "\"proxy\":{\"type\":\"MSISDN\",\"account\":\"$account\"}")
            server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(200, proxy)))
            assertTrue(verifier.verify(SlipRequest(payload = payload, expectedReceiver = ReceiverRule("1234"))).hasReason(ReasonCode.RECEIVER_MISMATCH))
        }
    }

    @Test
    fun `a stalled first providers body times out and falls back promptly`() {
        val slow =
            com.sun.net.httpserver.HttpServer
                .create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        slow.createContext("/v2/verify/bank") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { output ->
                    output.write('{'.code)
                    output.flush()
                    Thread.sleep(2000)
                    output.write('}'.code)
                }
            } catch (_: java.io.IOException) {
                // The body deadline closes the first connection.
            } finally {
                exchange.close()
            }
        }
        slow.start()
        try {
            server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(200, bankSuccess)))
            val first = EasySlipProvider(EasySlipConfig("synthetic", "http://127.0.0.1:${slow.address.port}/v2", Duration.ofMillis(300)))
            val verifier =
                SlipVerifier
                    .builder()
                    .provider(first)
                    .provider(provider())
                    .build()
            val started = System.nanoTime()
            val result = verifier.verify(SlipRequest(payload = payload))
            assertEquals(VerificationStatus.VALID, result.status)
            assertTrue(result.hasReason(ReasonCode.PROVIDER_TIMEOUT))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1500)
            server.verify(1, postRequestedFor(urlEqualTo("/v2/verify/bank")))
        } finally {
            slow.stop(0)
        }
    }

    @Test
    fun `verifier reaches VALID and applies business checks to a documented response`() {
        server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(200, bankSuccess)))
        val verifier = SlipVerifier.builder().provider(provider()).build()
        val ok = verifier.verify(SlipRequest(payload = payload, expectedAmount = BigDecimal("150.50")))
        assertEquals(VerificationStatus.VALID, ok.status)
        assertEquals("easyslip", ok.providerName)
        val wrong = verifier.verify(SlipRequest(payload = payload, expectedAmount = BigDecimal("100")))
        assertEquals(listOf(ReasonCode.AMOUNT_MISMATCH), wrong.reasons)
    }

    @Test
    fun `fallback chain moves to the next provider on 5xx but not on not-found`() {
        val second = WireMockServer(options().dynamicPort()).also { it.start() }
        try {
            second.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(200, bankSuccess)))
            val secondProvider = EasySlipProvider(EasySlipConfig("k2", "http://localhost:${second.port()}/v2"))
            server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(503, "")))
            val chain =
                SlipVerifier
                    .builder()
                    .provider(provider())
                    .provider(secondProvider)
                    .build()
            assertEquals(VerificationStatus.VALID, chain.verify(SlipRequest(payload = payload)).status)

            server.resetAll()
            server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(404, errorBody("SLIP_NOT_FOUND"))))
            val before = second.allServeEvents.size
            val notFound = chain.verify(SlipRequest(payload = payload))
            assertEquals(VerificationStatus.INVALID, notFound.status)
            assertEquals(before, second.allServeEvents.size)
        } finally {
            second.stop()
        }
    }

    // ---- secrets and config ----

    @Test
    fun `api key never appears in text output`() {
        val cfg = config()
        assertFalse(cfg.toString().contains(apiKey))
        assertFalse(provider().toString().contains(apiKey))
        server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(reply(401, errorBody("INVALID_API_KEY"))))
        val out = provider().verify(bank())
        assertFalse(out.toString().contains(apiKey))
        assertNotNull(out.reason)
    }

    @Test
    fun `config validation`() {
        assertFailsWith<IllegalArgumentException> { EasySlipConfig(" ") }
        assertFailsWith<IllegalArgumentException> { EasySlipConfig("k", "http://api.example.com/v2") }
        assertFailsWith<IllegalArgumentException> { EasySlipConfig("k", requestTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { EasySlipConfig("k", connectTimeout = Duration.ofSeconds(-1)) }
        assertEquals("https://api.easyslip.com/v2", EasySlipConfig.DEFAULT_BASE_URL)
        assertTrue(EasySlipProvider(EasySlipConfig("k")).supports(SlipType.TRUEMONEY))
    }
}
