package io.github.sunjirapath.slipverify.spring

import io.github.sunjirapath.promptparse.SlipVerify
import io.github.sunjirapath.slipverify.DuplicateStore
import io.github.sunjirapath.slipverify.InMemoryDuplicateStore
import io.github.sunjirapath.slipverify.SlipProvider
import io.github.sunjirapath.slipverify.SlipRequest
import io.github.sunjirapath.slipverify.SlipVerifier
import io.github.sunjirapath.slipverify.VerificationStatus
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OptionalClasspathTest {
    private val runner =
        ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(SlipVerifyAutoConfiguration::class.java, SlipVerifyWebAutoConfiguration::class.java),
        )

    @Test
    fun `all optional integrations can be absent together`() {
        FilteredClassLoader(
            "io.github.sunjirapath.slipverify.easyslip",
            "io.github.sunjirapath.slipverify.slipok",
            "io.github.sunjirapath.slipverify.redis",
            "io.github.sunjirapath.slipverify.provider",
            "com.google.gson",
            "io.lettuce",
            "io.micrometer",
            "org.springframework.web",
            "org.springframework.http",
            "org.springframework.boot.web",
            "jakarta.servlet",
        ).use { loader ->
            runner.withClassLoader(loader).withPropertyValues("slipverify.web.enabled=true").run { ctx ->
                assertNull(ctx.startupFailure)
                assertNotNull(ctx.getBean(SlipVerificationService::class.java))
                assertSame(SlipMetrics.NOOP, ctx.getBean(SlipMetrics::class.java))
                assertTrue(ctx.getBeansOfType(SlipProvider::class.java).isEmpty())
                assertTrue(ctx.getBeansOfType(SlipVerifyController::class.java).isEmpty())
            }
        }
    }

    @Test
    fun `missing provider modules are skipped even when credentials are configured`() {
        FilteredClassLoader("io.github.sunjirapath.slipverify.easyslip", "io.github.sunjirapath.slipverify.slipok").use { loader ->
            runner
                .withClassLoader(loader)
                .withPropertyValues(
                    "slipverify.easyslip.api-key=synthetic-key",
                    "slipverify.slipok.api-key=synthetic-key",
                    "slipverify.slipok.branch-id=test",
                ).run { ctx ->
                    assertNull(ctx.startupFailure)
                    assertTrue(ctx.getBeansOfType(SlipProvider::class.java).isEmpty())
                    assertEquals(
                        VerificationStatus.UNVERIFIED,
                        ctx
                            .getBean(SlipVerifier::class.java)
                            .verify(SlipRequest(payload = SlipVerify.build("004", "OPTIONAL")))
                            .status,
                    )
                }
        }
    }

    @Test
    fun `memory works without Redis while explicitly configured Redis fails clearly`() {
        FilteredClassLoader("io.github.sunjirapath.slipverify.redis").use { loader ->
            runner.withClassLoader(loader).run { ctx ->
                assertNull(ctx.startupFailure)
                assertTrue(ctx.getBean(DuplicateStore::class.java) is InMemoryDuplicateStore)
            }
            runner
                .withClassLoader(loader)
                .withPropertyValues(
                    "slipverify.duplicate.store=redis",
                    "slipverify.duplicate.redis-uri=redis://localhost:1",
                ).run { ctx ->
                    assertNotNull(ctx.startupFailure)
                    assertTrue(
                        generateSequence(ctx.startupFailure) { it.cause }.any {
                            it.message?.contains("needs the slipverify-store-redis module") == true
                        },
                    )
                }
        }
    }

    @Test
    fun `missing Micrometer uses no-op metrics`() {
        FilteredClassLoader("io.micrometer").use { loader ->
            runner.withClassLoader(loader).run { ctx ->
                assertNull(ctx.startupFailure)
                assertSame(SlipMetrics.NOOP, ctx.getBean(SlipMetrics::class.java))
                assertNotNull(ctx.getBean(SlipVerificationService::class.java))
            }
        }
    }

    @Test
    fun `missing web dependencies skip the endpoint and retain the core service`() {
        FilteredClassLoader("org.springframework.web", "org.springframework.http", "jakarta.servlet", "org.springframework.boot.web").use { loader ->
            runner.withClassLoader(loader).withPropertyValues("slipverify.web.enabled=true").run { ctx ->
                assertNull(ctx.startupFailure)
                assertNotNull(ctx.getBean(SlipVerificationService::class.java))
                assertTrue(ctx.getBeansOfType(SlipVerifyController::class.java).isEmpty())
            }
        }
    }
}
