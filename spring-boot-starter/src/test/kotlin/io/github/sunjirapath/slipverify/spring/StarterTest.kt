package io.github.sunjirapath.slipverify.spring

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.github.sunjirapath.promptparse.SlipVerify
import io.github.sunjirapath.slipverify.DuplicateStore
import io.github.sunjirapath.slipverify.ProviderOutcome
import io.github.sunjirapath.slipverify.ReasonCode
import io.github.sunjirapath.slipverify.SlipProvider
import io.github.sunjirapath.slipverify.SlipRequest
import io.github.sunjirapath.slipverify.SlipType
import io.github.sunjirapath.slipverify.SlipVerifier
import io.github.sunjirapath.slipverify.VerificationStatus
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post as mvcPost

class StarterTest {
    private lateinit var server: WireMockServer
    private val payload = SlipVerify.build("004", "SPRING0001")

    private val runner =
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SlipVerifyAutoConfiguration::class.java))

    @BeforeTest
    fun start() {
        server = WireMockServer(options().dynamicPort()).also { it.start() }
        server.stubFor(
            post(urlEqualTo("/v2/verify/bank")).willReturn(
                aResponse().withStatus(200).withBody(
                    """{"success":true,"data":{"isDuplicate":false,"rawSlip":{"transRef":"SPRING0001","date":"2026-10-05T10:15:00+07:00","amount":{"amount":150},"receiver":{"account":{"name":{"th":"นาย สมชาย"},"bank":{"account":"xxx-x-x1234-x"}}}}}}""",
                ),
            ),
        )
        server.stubFor(
            post(urlEqualTo("/api/line/apikey/B1")).willReturn(
                aResponse().withStatus(200).withBody("""{"success":true,"data":{"amount":150,"transRef":"SPRING0001"}}"""),
            ),
        )
    }

    @AfterTest
    fun stop() = server.stop()

    private val easy = "slipverify.easyslip.api-key=k1"

    private fun easyUrl() = "slipverify.easyslip.base-url=http://localhost:${server.port()}/v2"

    private fun okProps() =
        arrayOf(
            "slipverify.slipok.api-key=k2",
            "slipverify.slipok.branch-id=B1",
            "slipverify.slipok.base-url=http://localhost:${server.port()}/api/line/apikey",
        )

    @Test
    fun `defaults give an offline verifier that never says VALID`() {
        runner.run { ctx ->
            val v = ctx.getBean(SlipVerifier::class.java)
            val r = v.verify(SlipRequest(payload = payload))
            assertEquals(VerificationStatus.UNVERIFIED, r.status)
            assertTrue(r.hasReason(ReasonCode.NO_PROVIDER_CONFIGURED))
            assertNotNull(ctx.getBean(DuplicateStore::class.java))
        }
    }

    @Test
    fun `an image goes through the zxing extractor`() {
        runner.run { ctx ->
            val b64 = qrPngBase64(payload)
            val r = ctx.getBean(SlipVerifier::class.java).verify(SlipRequest(imageBase64 = b64))
            assertEquals(VerificationStatus.UNVERIFIED, r.status)
            assertEquals("SPRING0001", r.transRef)
        }
    }

    @Test
    fun `configured easyslip is wired and duplicates are caught`() {
        runner.withPropertyValues(easy, easyUrl()).run { ctx ->
            val service = ctx.getBean(SlipVerificationService::class.java)
            val first = service.verify(SlipRequest(payload = payload))
            assertEquals(VerificationStatus.VALID, first.status)
            assertEquals("easyslip", first.providerName)
            assertEquals(listOf(ReasonCode.DUPLICATE), service.verify(SlipRequest(payload = payload)).reasons)
        }
    }

    @Test
    fun `blank keys leave providers off`() {
        runner.withPropertyValues("slipverify.easyslip.api-key=", "slipverify.slipok.api-key=k", "slipverify.slipok.branch-id= ").run { ctx ->
            assertEquals(null, ctx.startupFailure)
            assertTrue(ctx.getBeansOfType(SlipProvider::class.java).isEmpty())
        }
    }

    @Test
    fun `duplicate checking can be switched off`() {
        runner.withPropertyValues(easy, easyUrl(), "slipverify.duplicate.enabled=false").run { ctx ->
            val v = ctx.getBean(SlipVerifier::class.java)
            assertEquals(VerificationStatus.VALID, v.verify(SlipRequest(payload = payload)).status)
            assertEquals(VerificationStatus.VALID, v.verify(SlipRequest(payload = payload)).status)
            assertTrue(ctx.getBeansOfType(DuplicateStore::class.java).isEmpty())
        }
    }

    @Test
    fun `provider order decides who answers first`() {
        val props = arrayOf(easy, easyUrl(), *okProps())
        runner.withPropertyValues(*props, "slipverify.provider-order=slipok,easyslip", "slipverify.duplicate.enabled=false").run { ctx ->
            val r = ctx.getBean(SlipVerifier::class.java).verify(SlipRequest(payload = payload))
            assertEquals("slipok", r.providerName)
        }
        runner.withPropertyValues(*props, "slipverify.provider-order=easyslip,slipok", "slipverify.duplicate.enabled=false").run { ctx ->
            assertEquals("easyslip", ctx.getBean(SlipVerifier::class.java).verify(SlipRequest(payload = payload)).providerName)
        }
    }

    @Test
    fun `fallback chain uses the second provider when the first is down`() {
        server.stubFor(post(urlEqualTo("/v2/verify/bank")).willReturn(aResponse().withStatus(503)))
        runner.withPropertyValues(easy, easyUrl(), *okProps(), "slipverify.duplicate.enabled=false").run { ctx ->
            assertEquals("slipok", ctx.getBean(SlipVerifier::class.java).verify(SlipRequest(payload = payload)).providerName)
        }
    }

    @Test
    fun `known-slips and quota-guard beans are wired into the verifier`() {
        var calls = 0
        val custom =
            object : SlipProvider {
                override val name = "custom"

                override fun supports(type: SlipType) = true

                override fun verify(query: io.github.sunjirapath.slipverify.ProviderQuery): ProviderOutcome {
                    calls++
                    return ProviderOutcome.notFound()
                }
            }
        val known =
            io.github.sunjirapath.slipverify
                .KnownSlips { _, _, ref -> ref == "SPRING0001" }
        val capped =
            object : io.github.sunjirapath.slipverify.QuotaGuard {
                override fun tryReserve(provider: String) = false

                override fun settle(
                    provider: String,
                    charge: io.github.sunjirapath.slipverify.Charge,
                ) = Unit
            }
        runner.withBean("custom", SlipProvider::class.java, { custom }).withBean("known", io.github.sunjirapath.slipverify.KnownSlips::class.java, { known }).run { ctx ->
            val r = ctx.getBean(SlipVerifier::class.java).verify(SlipRequest(payload = payload))
            assertTrue(ReasonCode.DUPLICATE in r.reasons)
            assertEquals(0, calls)
        }
        runner.withBean("custom", SlipProvider::class.java, { custom }).withBean("guard", io.github.sunjirapath.slipverify.QuotaGuard::class.java, { capped }).run { ctx ->
            val r = ctx.getBean(SlipVerifier::class.java).verify(SlipRequest(payload = payload))
            assertTrue(ReasonCode.QUOTA_CAPPED in r.reasons)
            assertEquals(0, calls)
        }
    }

    @Test
    fun `user supplied provider joins the chain`() {
        val custom =
            object : SlipProvider {
                override val name = "custom"

                override fun supports(type: SlipType) = true

                override fun verify(query: io.github.sunjirapath.slipverify.ProviderQuery) =
                    ProviderOutcome.verified(
                        io.github.sunjirapath.slipverify
                            .VerifiedSlip(java.math.BigDecimal.TEN),
                    )
            }
        runner.withBean("custom", SlipProvider::class.java, { custom }).withPropertyValues("slipverify.duplicate.enabled=false").run { ctx ->
            assertEquals("custom", ctx.getBean(SlipVerifier::class.java).verify(SlipRequest(payload = payload)).providerName)
        }
    }

    @Test
    fun `micrometer meters are recorded without slip data`() {
        runner
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withPropertyValues(easy, easyUrl(), "slipverify.duplicate.enabled=false")
            .run { ctx ->
                ctx.getBean(SlipVerificationService::class.java).verify(SlipRequest(payload = payload))
                val registry = ctx.getBean(MeterRegistry::class.java)
                assertEquals(
                    1.0,
                    registry
                        .get("slipverify.verifications")
                        .tag("status", "VALID")
                        .counter()
                        .count(),
                )
                assertEquals(
                    1L,
                    registry
                        .get("slipverify.provider.requests")
                        .tag("provider", "easyslip")
                        .tag("outcome", "VERIFIED")
                        .timer()
                        .count(),
                )
                val allTags = registry.meters.flatMap { m -> m.id.tags.map { it.value } }
                assertTrue(allTags.none { it.contains("SPRING0001") })
            }
    }

    @Test
    fun `switches and misconfiguration`() {
        runner.withPropertyValues("slipverify.enabled=false").run { assertTrue(it.getBeansOfType(SlipVerifier::class.java).isEmpty()) }
        runner.withBean(SlipVerifier::class.java, { SlipVerifier.builder().build() }).run { ctx ->
            assertEquals(1, ctx.getBeansOfType(SlipVerifier::class.java).size)
        }
        runner.withPropertyValues("slipverify.duplicate.store=redis").run { ctx ->
            assertNotNull(ctx.startupFailure)
            assertTrue(generateSequence(ctx.startupFailure) { it.cause }.any { it.message?.contains("redis-uri") == true })
        }
    }

    // ---- web ----

    private fun mvc(maxBytes: Long = 100_000): org.springframework.test.web.servlet.MockMvc {
        val verifier = SlipVerifier.builder().build()
        val controller = SlipVerifyController(SlipVerificationService(verifier, SlipMetrics.NOOP))
        return MockMvcBuilders.standaloneSetup(controller).addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(RequestSizeLimitFilter(maxBytes)).build()
    }

    private fun json(s: String) = mvcPost("/slips/verify").contentType(MediaType.APPLICATION_JSON).content(s)

    @Test
    fun `controller returns a masked typed result`() {
        mvc()
            .perform(json("""{"payload":"$payload"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UNVERIFIED"))
            .andExpect(jsonPath("$.transRef").value("SPRING0001"))
            .andExpect(jsonPath("$.reasons[0]").value("NO_PROVIDER_CONFIGURED"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(payload))))
    }

    @Test
    fun `controller maps receiver and limits`() {
        mvc()
            .perform(json("""{"payload":"$payload","expectedAmount":150.5,"receiver":{"accountSuffix":"1234","nameContains":"x"},"maxAgeSeconds":60}"""))
            .andExpect(status().isOk)
    }

    @Test
    fun `bad input gets generic errors`() {
        mvc().perform(json("{not json")).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("invalid_request"))
        mvc()
            .perform(json("""{"payload":"x","receiver":{"accountSuffix":"12"}}"""))
            .andExpect(status().isBadRequest)
            .andExpect(content().json("""{"error":"invalid_request"}"""))
        mvc().perform(json("""{"expectedAmount":-5,"payload":"x"}""")).andExpect(status().isBadRequest)
    }

    @Test
    fun `size limit rejects before parsing`() {
        mvc(maxBytes = 50).perform(json("""{"payload":"${"a".repeat(200)}"}""")).andExpect(status().isPayloadTooLarge).andExpect(jsonPath("$.error").value("too_large"))
        mvc().perform(mvcPost("/slips/verify").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isLengthRequired)
    }

    @Test
    fun `web auto configuration is opt in`() {
        val web = WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(SlipVerifyAutoConfiguration::class.java, SlipVerifyWebAutoConfiguration::class.java))
        web.run { assertTrue(it.getBeansOfType(SlipVerifyController::class.java).isEmpty()) }
        web.withPropertyValues("slipverify.web.enabled=true").run { ctx ->
            assertNotNull(ctx.getBean(SlipVerifyController::class.java))
            assertNotNull(ctx.getBean("slipVerifySizeLimit"))
        }
    }
}

private fun qrPngBase64(text: String): String {
    val matrix =
        com.google.zxing.qrcode
            .QRCodeWriter()
            .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 400, 400)
    val out = java.io.ByteArrayOutputStream()
    com.google.zxing.client.j2se.MatrixToImageWriter
        .writeToStream(matrix, "PNG", out)
    return java.util.Base64
        .getEncoder()
        .encodeToString(out.toByteArray())
}
