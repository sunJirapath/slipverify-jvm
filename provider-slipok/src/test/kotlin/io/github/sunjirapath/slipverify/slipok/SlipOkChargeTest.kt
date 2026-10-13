package io.github.sunjirapath.slipverify.slipok

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.github.sunjirapath.promptparse.SlipVerify
import io.github.sunjirapath.slipverify.Charge
import io.github.sunjirapath.slipverify.ProviderQuery
import io.github.sunjirapath.slipverify.SlipType
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Whether a SlipOK answer used quota, per the "Quota Calculation" section of the SlipOK guide v1.13. Callers that cap
 * their spending (QuotaGuard) rely on this: NO gives the reserved unit back, YES and UNKNOWN keep it.
 */
class SlipOkChargeTest {
    private lateinit var server: WireMockServer
    private val payload = SlipVerify.build("004", "SYNTH0099")
    private val path = "/api/line/apikey/BRANCH1"

    @BeforeTest
    fun start() {
        server = WireMockServer(options().dynamicPort()).also { it.start() }
    }

    @AfterTest
    fun stop() = server.stop()

    private fun provider(
        baseUrl: String = "http://localhost:${server.port()}/api/line/apikey",
        timeout: Duration = Duration.ofSeconds(5),
    ) = SlipOkProvider(SlipOkConfig("key", "BRANCH1", baseUrl, timeout))

    private fun query() = ProviderQuery(SlipType.BOT_STANDARD, payload, null, null)

    private fun chargeFor(
        status: Int,
        body: String,
    ): Charge {
        server.stubFor(
            post(urlEqualTo(path)).willReturn(
                aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body),
            ),
        )
        return provider().verify(query()).charge
    }

    private val ok =
        """{"success":true,"data":{"success":true,"transRef":"R1","sendingBank":"004","amount":150,
            "receiver":{"name":"A","account":{"value":"xxx-x-x1234-x"}}}}"""

    @Test
    fun `a confirmed slip is charged`() = assertEquals(Charge.YES, chargeFor(200, ok))

    @Test
    fun `answers about a valid slip are charged`() {
        assertEquals(Charge.YES, chargeFor(400, """{"code":1013,"message":"x"}"""))
        assertEquals(Charge.YES, chargeFor(400, """{"code":1014,"message":"x"}"""))
    }

    @Test
    fun `repeat slips are free`() {
        assertEquals(Charge.NO, chargeFor(400, """{"code":1012,"message":"x"}"""))
        assertEquals(
            Charge.NO,
            chargeFor(400, """{"code":1012,"message":"x","data":{"success":true,"amount":150,"transRef":"R1"}}"""),
        )
    }

    @Test
    fun `lookups that found nothing, bank outages and waits are free`() {
        for (code in listOf(1007, 1008, 1011, 1009, 1010)) {
            assertEquals(Charge.NO, chargeFor(400, """{"code":$code,"message":"x"}"""), "code $code")
        }
    }

    @Test
    fun `input, auth and package errors are free`() {
        for (code in listOf(1000, 1001, 1002, 1003, 1004, 1005, 1006)) {
            assertEquals(Charge.NO, chargeFor(400, """{"code":$code,"message":"x"}"""), "code $code")
        }
        assertEquals(Charge.NO, chargeFor(401, """{"message":"x"}"""))
        assertEquals(Charge.NO, chargeFor(429, """{"message":"x"}"""))
    }

    @Test
    fun `unknown codes, server errors and odd successes stay UNKNOWN`() {
        assertEquals(Charge.UNKNOWN, chargeFor(400, """{"code":9999,"message":"x"}"""))
        assertEquals(Charge.UNKNOWN, chargeFor(500, """{"message":"x"}"""))
        assertEquals(Charge.UNKNOWN, chargeFor(200, """{"success":true,"data":{"success":true}}"""))
        assertEquals(Charge.UNKNOWN, chargeFor(418, """{"message":"x"}"""))
    }

    @Test
    fun `a timeout after sending may have been billed`() {
        server.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withFixedDelay(1500).withStatus(200).withBody(ok)))
        assertEquals(Charge.UNKNOWN, provider(timeout = Duration.ofMillis(200)).verify(query()).charge)
    }

    @Test
    fun `a refused connection never reached SlipOK and is free`() {
        val url = "http://localhost:${server.port()}/api/line/apikey"
        server.stop()
        assertEquals(Charge.NO, provider(url).verify(query()).charge)
    }
}
