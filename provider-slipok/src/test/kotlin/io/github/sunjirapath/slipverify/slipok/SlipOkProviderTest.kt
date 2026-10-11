package io.github.sunjirapath.slipverify.slipok

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.github.sunjirapath.promptparse.SlipVerify
import io.github.sunjirapath.slipverify.ProviderOutcomeKind
import io.github.sunjirapath.slipverify.ProviderQuery
import io.github.sunjirapath.slipverify.ReasonCode
import io.github.sunjirapath.slipverify.ReceiverRule
import io.github.sunjirapath.slipverify.SlipRequest
import io.github.sunjirapath.slipverify.SlipType
import io.github.sunjirapath.slipverify.SlipVerifier
import io.github.sunjirapath.slipverify.VerificationStatus
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlipOkProviderTest {
    private lateinit var server: WireMockServer
    private val payload = SlipVerify.build("004", "SYNTH0002")
    private val apiKey = "slipok-secret-key"
    private val path = "/api/line/apikey/BRANCH1"

    @BeforeTest
    fun start() {
        server = WireMockServer(options().dynamicPort()).also { it.start() }
    }

    @AfterTest
    fun stop() = server.stop()

    private fun provider(
        timeout: Duration = Duration.ofSeconds(5),
        log: Boolean = false,
    ) = SlipOkProvider(
        SlipOkConfig(apiKey, "BRANCH1", "http://localhost:${server.port()}/api/line/apikey", timeout, log = log),
    )

    private fun query() = ProviderQuery(SlipType.BOT_STANDARD, payload, null, null)

    private fun reply(
        status: Int,
        body: String,
    ) = aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)

    // Field names follow https://slipok.com/api-documentation/check-slip/ ; values are invented.
    private val success =
        """
        {"success":true,"data":{"success":true,"message":"ok","language":"TH","receivingBank":"014","sendingBank":"004",
         "transRef":"SYNTH0002","transDate":"20261005","transTime":"10:15:00","transTimestamp":"2026-10-05T10:15:00+07:00",
         "sender":{"displayName":"น.ส. สมหญิง ร","name":"นางสาว สมหญิง รักดี","proxy":{"type":null,"value":null},"account":{"type":"BANKAC","value":"xxx-x-x5678-x"}},
         "receiver":{"displayName":"นาย สมชาย ใ","name":"นาย สมชาย ใจดี","proxy":{"type":"NATID","value":"x-xxxx-xxxxx-12-3"},"account":{"type":"BANKAC","value":"xxx-x-x1234-x"}},
         "amount":150.00,"paidLocalAmount":150.00,"paidLocalCurrency":"THB","countryCode":"TH","transFeeAmount":0,"ref1":"","ref2":"","ref3":"","toMerchantId":""}}
        """.trimIndent()

    @Test
    fun `success is parsed and the request is shaped as documented`() {
        server.stubFor(post(urlEqualTo(path)).willReturn(reply(200, success)))
        val out = provider().verify(query())
        assertEquals(ProviderOutcomeKind.VERIFIED, out.kind)
        val slip = out.slip!!
        assertEquals(BigDecimal("150.00"), slip.amount)
        assertEquals(Instant.parse("2026-10-05T03:15:00Z"), slip.transferTime)
        assertEquals("นาย สมชาย ใจดี", slip.receiverName)
        assertEquals("xxx-x-x1234-x", slip.receiverAccount)
        assertEquals("004", slip.sendingBank)
        assertEquals("SYNTH0002", slip.transRef)
        server.verify(
            postRequestedFor(urlEqualTo(path))
                .withHeader("x-authorization", equalTo(apiKey))
                .withRequestBody(equalToJson("""{"data":"$payload"}""")),
        )
    }

    @Test
    fun `log flag is only sent when enabled`() {
        server.stubFor(post(urlEqualTo(path)).willReturn(reply(200, success)))
        provider(log = true).verify(query())
        server.verify(postRequestedFor(urlEqualTo(path)).withRequestBody(equalToJson("""{"data":"$payload","log":true}""")))
    }

    @Test
    fun `date and time fields are used when there is no timestamp`() {
        val body = success.replace(""""transTimestamp":"2026-10-05T10:15:00+07:00",""", "")
        server.stubFor(post(urlEqualTo(path)).willReturn(reply(200, body)))
        assertEquals(Instant.parse("2026-10-05T03:15:00Z"), provider().verify(query()).slip!!.transferTime)
        server.resetAll()
        val noTime = body.replace(""""transDate":"20261005","transTime":"10:15:00",""", "")
        server.stubFor(post(urlEqualTo(path)).willReturn(reply(200, noTime)))
        assertNull(provider().verify(query()).slip!!.transferTime)
    }

    private fun err(code: Int) = """{"code":$code,"message":"m"}"""

    @Test
    fun `documented error codes map to outcomes`() {
        data class Case(
            val code: Int,
            val kind: ProviderOutcomeKind,
            val reason: ReasonCode,
        )
        val cases =
            listOf(
                Case(1000, ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_ERROR),
                Case(1001, ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_AUTH_ERROR),
                Case(1002, ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_AUTH_ERROR),
                Case(1003, ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_QUOTA_EXCEEDED),
                Case(1004, ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_QUOTA_EXCEEDED),
                Case(1005, ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_ERROR),
                Case(1007, ProviderOutcomeKind.NOT_FOUND, ReasonCode.PROVIDER_NOT_FOUND),
                Case(1008, ProviderOutcomeKind.NOT_FOUND, ReasonCode.PROVIDER_NOT_FOUND),
                Case(1009, ProviderOutcomeKind.UNAVAILABLE, ReasonCode.PROVIDER_ERROR),
                Case(1010, ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_PENDING),
                Case(1011, ProviderOutcomeKind.NOT_FOUND, ReasonCode.PROVIDER_NOT_FOUND),
                Case(1012, ProviderOutcomeKind.REJECTED, ReasonCode.DUPLICATE),
                Case(1013, ProviderOutcomeKind.REJECTED, ReasonCode.AMOUNT_MISMATCH),
                Case(1014, ProviderOutcomeKind.REJECTED, ReasonCode.RECEIVER_MISMATCH),
                Case(9999, ProviderOutcomeKind.REJECTED, ReasonCode.PROVIDER_ERROR),
            )
        for (status in listOf(200, 400)) {
            for (c in cases) {
                server.resetAll()
                server.stubFor(post(urlEqualTo(path)).willReturn(reply(status, err(c.code))))
                val out = provider().verify(query())
                assertEquals(c.kind, out.kind, "${c.code}/$status")
                assertEquals(c.reason, out.reason, "${c.code}/$status")
            }
        }
    }

    @Test
    fun `duplicate with slip data keeps the data and flags the duplicate`() {
        val dup = """{"code":1012,"message":"dup","data":{"amount":150.00,"transRef":"SYNTH0002","receiver":{"name":"นาย สมชาย"}}}"""
        server.stubFor(post(urlEqualTo(path)).willReturn(reply(400, dup)))
        val out = provider().verify(query())
        assertEquals(ProviderOutcomeKind.VERIFIED, out.kind)
        assertTrue(out.slip!!.duplicateAtProvider)
        val v =
            SlipVerifier
                .builder()
                .provider(provider())
                .build()
                .verify(SlipRequest(payload = payload))
        assertEquals(VerificationStatus.INVALID, v.status)
        assertEquals(listOf(ReasonCode.DUPLICATE), v.reasons)
    }

    @Test
    fun `provider-reported mismatch and duplicate without data become INVALID in the verifier`() {
        for (code in listOf(1012, 1013, 1014)) {
            server.resetAll()
            server.stubFor(post(urlEqualTo(path)).willReturn(reply(400, err(code))))
            val v =
                SlipVerifier
                    .builder()
                    .provider(provider())
                    .build()
                    .verify(SlipRequest(payload = payload))
            assertEquals(VerificationStatus.INVALID, v.status, "$code")
        }
    }

    @Test
    fun `http status fallbacks without an error code`() {
        val cases =
            mapOf(
                401 to ReasonCode.PROVIDER_AUTH_ERROR,
                403 to ReasonCode.PROVIDER_AUTH_ERROR,
                429 to ReasonCode.PROVIDER_RATE_LIMITED,
                400 to ReasonCode.PROVIDER_ERROR,
            )
        for ((status, reason) in cases) {
            server.resetAll()
            server.stubFor(post(urlEqualTo(path)).willReturn(reply(status, "")))
            val out = provider().verify(query())
            assertEquals(ProviderOutcomeKind.REJECTED, out.kind)
            assertEquals(reason, out.reason)
        }
        server.resetAll()
        server.stubFor(post(urlEqualTo(path)).willReturn(reply(503, "<html/>")))
        assertEquals(ProviderOutcomeKind.UNAVAILABLE, provider().verify(query()).kind)
    }

    @Test
    fun `timeout and connection failure are unavailable`() {
        server.stubFor(post(urlEqualTo(path)).willReturn(reply(200, success).withFixedDelay(1500)))
        val slow = provider(timeout = Duration.ofMillis(300)).verify(query())
        assertEquals(ReasonCode.PROVIDER_TIMEOUT, slow.reason)
        val p = provider()
        server.stop()
        assertEquals(ProviderOutcomeKind.UNAVAILABLE, p.verify(query()).kind)
    }

    @Test
    fun `malformed or incomplete 200 responses are never trusted`() {
        for (body in listOf("nonsense", "[]", "", """{"success":false}""", """{"success":true,"data":{"transRef":"x"}}""", """{"success":true}""")) {
            server.resetAll()
            server.stubFor(post(urlEqualTo(path)).willReturn(reply(200, body)))
            assertEquals(ProviderOutcomeKind.UNAVAILABLE, provider().verify(query()).kind, body)
        }
    }

    @Test
    fun `trueMoney is not supported and nothing is sent`() {
        val p = provider()
        assertFalse(p.supports(SlipType.TRUEMONEY))
        assertEquals(ProviderOutcomeKind.UNSUPPORTED, p.verify(ProviderQuery(SlipType.TRUEMONEY, "x", byteArrayOf(1), null)).kind)
        assertEquals(0, server.allServeEvents.size)
    }

    @Test
    fun `receiver rule works against masked slipok data`() {
        server.stubFor(post(urlEqualTo(path)).willReturn(reply(200, success)))
        val v = SlipVerifier.builder().provider(provider()).build()
        assertEquals(VerificationStatus.VALID, v.verify(SlipRequest(payload = payload, expectedReceiver = ReceiverRule("1234", "สมชาย"))).status)
        assertEquals(VerificationStatus.INVALID, v.verify(SlipRequest(payload = payload, expectedReceiver = ReceiverRule("9999"))).status)
    }

    @Test
    fun `secrets stay out of text and config is validated`() {
        val cfg = SlipOkConfig(apiKey, "BRANCH1")
        assertFalse(cfg.toString().contains(apiKey))
        assertFalse(cfg.toString().contains("BRANCH1"))
        assertFailsWith<IllegalArgumentException> { SlipOkConfig("", "b") }
        assertFailsWith<IllegalArgumentException> { SlipOkConfig("k", "") }
        assertFailsWith<IllegalArgumentException> { SlipOkConfig("k", "a/../b") }
        assertFailsWith<IllegalArgumentException> { SlipOkConfig("k", "b", "http://example.com/x") }
        assertFailsWith<IllegalArgumentException> { SlipOkConfig("k", "b", requestTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { SlipOkConfig("k", "b", connectTimeout = Duration.ZERO) }
        assertTrue(provider().toString().startsWith("SlipOkProvider"))
        assertEquals("slipok", provider().name)
    }
}
