package io.github.sunjirapath.slipverify.slipok

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import java.time.Duration
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** `GET .../quota`: field names follow the SlipOK guide v1.13; values are invented. */
class SlipOkQuotaTest {
    private lateinit var server: WireMockServer
    private val path = "/api/line/apikey/BRANCH1/quota"

    @BeforeTest
    fun start() {
        server = WireMockServer(options().dynamicPort()).also { it.start() }
    }

    @AfterTest
    fun stop() = server.stop()

    private fun provider(
        baseUrl: String = "http://localhost:${server.port()}/api/line/apikey",
        timeout: Duration = Duration.ofSeconds(5),
    ) = SlipOkProvider(SlipOkConfig("secret-key", "BRANCH1", baseUrl, timeout))

    private fun reply(
        status: Int,
        body: String,
    ) = server.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(status).withBody(body)))

    @Test
    fun `reads remaining quota and the package end date`() {
        reply(200, """{"success":true,"data":{"quota":87,"overQuota":0,"specialQuota":3,"endDate":"2026-11-13","specialEndDate":null}}""")
        val q = provider().quota()!!
        assertEquals(87, q.remaining)
        assertEquals(0, q.overQuota)
        assertEquals(3, q.specialRemaining)
        assertEquals(LocalDate.parse("2026-11-13"), q.endDate)
        server.verify(getRequestedFor(urlEqualTo(path)).withHeader("x-authorization", equalTo("secret-key")))
        assertFalse(q.toString().contains("secret"))
    }

    @Test
    fun `optional fields may be missing`() {
        reply(200, """{"success":true,"data":{"quota":5,"endDate":"not-a-date"}}""")
        val q = provider().quota()!!
        assertEquals(5, q.remaining)
        assertEquals(0, q.overQuota)
        assertNull(q.endDate)
    }

    @Test
    fun `failures give null instead of throwing`() {
        reply(401, """{"code":1002,"message":"x"}""")
        assertNull(provider().quota())
        reply(200, """{"success":false}""")
        assertNull(provider().quota())
        reply(200, """{"success":true,"data":{}}""")
        assertNull(provider().quota())
        reply(200, "not json")
        assertNull(provider().quota())
        val url = "http://localhost:${server.port()}/api/line/apikey"
        server.stop()
        assertNull(provider(url).quota())
    }
}
