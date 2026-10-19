package io.github.sunjirapath.slipverify.spring

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CustomMetricsRegressionTest {
    @Test
    fun `user metrics take priority when a Micrometer registry is also present`() {
        val metrics = SlipMetrics.NOOP
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SlipVerifyAutoConfiguration::class.java))
            .withBean("customMetrics", SlipMetrics::class.java, { metrics })
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .run { context ->
                assertNull(context.startupFailure)
                assertEquals(1, context.getBeansOfType(SlipMetrics::class.java).size)
                assertSame(metrics, context.getBean(SlipMetrics::class.java))
            }
    }
}
