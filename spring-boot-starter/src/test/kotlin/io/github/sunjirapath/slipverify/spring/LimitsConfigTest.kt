package io.github.sunjirapath.slipverify.spring

import io.github.sunjirapath.slipverify.ConfiguredQuotaGuard
import io.github.sunjirapath.slipverify.QuotaGuard
import io.github.sunjirapath.slipverify.SlipProvider
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LimitsConfigTest {
    private val runner =
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SlipVerifyAutoConfiguration::class.java))

    @Test
    fun `slipok url carries the branch id`() {
        val props = SlipVerifyProperties().apply { slipok.url = " https://api.slipok.com/api/line/apikey/MY_BRANCH/ " }
        val endpoint = SlipOkEndpoint.resolve(props.slipok)
        assertEquals("https://api.slipok.com/api/line/apikey", endpoint.baseUrl)
        assertEquals("MY_BRANCH", endpoint.branchId)
        for (bad in listOf("https://api.slipok.com", "https://")) {
            val broken = SlipVerifyProperties().apply { slipok.url = bad }
            assertFailsWith<IllegalArgumentException>(bad) { SlipOkEndpoint.resolve(broken.slipok) }
        }
    }

    @Test
    fun `api key and url alone enable slipok without a branch id property`() {
        runner
            .withPropertyValues("slipverify.slipok.api-key=k", "slipverify.slipok.url=https://api.slipok.com/api/line/apikey/B9")
            .run { ctx ->
                assertEquals(null, ctx.startupFailure)
                assertEquals(listOf("slipok"), ctx.getBeansOfType(SlipProvider::class.java).values.map { it.name })
            }
        runner.withPropertyValues("slipverify.slipok.api-key=k").run { ctx ->
            assertTrue(ctx.getBeansOfType(SlipProvider::class.java).isEmpty())
        }
    }

    @Test
    fun `limits and bill cutoff build a quota guard per provider`() {
        runner
            .withPropertyValues(
                "slipverify.slipok.bill-cutoff=13/11/2026",
                "slipverify.slipok.limit.max-per-cycle=500",
                "slipverify.easyslip.limit.max-per-minute=30",
            ).run { ctx ->
                assertEquals(null, ctx.startupFailure)
                assertTrue(ctx.getBean(QuotaGuard::class.java) is ConfiguredQuotaGuard)
            }
    }

    @Test
    fun `no limits means no guard and a user guard wins`() {
        runner.run { ctx -> assertNull(ctx.getBeanProvider(QuotaGuard::class.java).ifAvailable) }
        val own =
            object : QuotaGuard {
                override fun tryReserve(provider: String) = true

                override fun settle(
                    provider: String,
                    charge: io.github.sunjirapath.slipverify.Charge,
                ) = Unit
            }
        runner
            .withBean(QuotaGuard::class.java, { own })
            .withPropertyValues("slipverify.easyslip.limit.max-per-minute=1")
            .run { ctx -> assertSame(own, ctx.getBean(QuotaGuard::class.java)) }
    }

    @Test
    fun `bad limit settings fail startup instead of running unprotected`() {
        runner.withPropertyValues("slipverify.slipok.limit.max-per-cycle=10").run { ctx ->
            assertNotNull(ctx.startupFailure) // per-cycle cap without a bill cutoff
        }
        runner
            .withPropertyValues("slipverify.slipok.bill-cutoff=never", "slipverify.slipok.limit.max-per-minute=1")
            .run { ctx -> assertNotNull(ctx.startupFailure) }
    }
}
