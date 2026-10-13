package io.github.sunjirapath.slipverify.spring

import io.github.sunjirapath.slipverify.DuplicateStore
import io.github.sunjirapath.slipverify.InMemoryDuplicateStore
import io.github.sunjirapath.slipverify.KnownSlips
import io.github.sunjirapath.slipverify.QrExtractor
import io.github.sunjirapath.slipverify.QuotaGuard
import io.github.sunjirapath.slipverify.SlipProvider
import io.github.sunjirapath.slipverify.SlipRequest
import io.github.sunjirapath.slipverify.SlipVerificationResult
import io.github.sunjirapath.slipverify.SlipVerifier
import io.github.sunjirapath.slipverify.easyslip.EasySlipConfig
import io.github.sunjirapath.slipverify.easyslip.EasySlipProvider
import io.github.sunjirapath.slipverify.slipok.SlipOkConfig
import io.github.sunjirapath.slipverify.slipok.SlipOkProvider
import io.github.sunjirapath.slipverify.zxing.ImageLimits
import io.github.sunjirapath.slipverify.zxing.ZxingQrExtractor
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.util.ClassUtils
import org.springframework.util.StringUtils

/** Wires a [SlipVerifier] from `slipverify.*` properties and whatever providers are on the classpath. */
@AutoConfiguration(
    afterName = [
        "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
    ],
)
@ConditionalOnClass(SlipVerifier::class)
@ConditionalOnProperty(prefix = "slipverify", name = ["enabled"], matchIfMissing = true)
@EnableConfigurationProperties(SlipVerifyProperties::class)
public class SlipVerifyAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun slipQrExtractor(props: SlipVerifyProperties): QrExtractor =
        ZxingQrExtractor(
            ImageLimits(
                props.image.maxEncodedBytes,
                props.image.maxDimension,
                props.image.maxPixels,
                props.image.decodeTimeout,
            ),
        )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "slipverify.duplicate", name = ["enabled"], matchIfMissing = true)
    public fun slipDuplicateStore(props: SlipVerifyProperties): DuplicateStore {
        val d = props.duplicate
        return if (d.store.equals("redis", ignoreCase = true)) redisStore(d) else InMemoryDuplicateStore()
    }

    private fun redisStore(d: SlipVerifyProperties.Duplicate): DuplicateStore {
        check(ClassUtils.isPresent(REDIS_STORE, javaClass.classLoader)) {
            "slipverify.duplicate.store=redis needs the slipverify-store-redis module on the classpath"
        }
        val uri = d.redisUri
        check(!uri.isNullOrBlank()) { "slipverify.duplicate.redis-uri is required when the store is redis" }
        return RedisStores.connect(uri)
    }

    @Bean
    @ConditionalOnMissingBean
    public fun slipMetrics(): SlipMetrics = SlipMetrics.NOOP

    @Bean
    @ConditionalOnMissingBean
    @Suppress("LongParameterList") // Spring injects each optional collaborator by type; there is no useful grouping.
    public fun slipVerifier(
        props: SlipVerifyProperties,
        providers: ObjectProvider<SlipProvider>,
        extractor: QrExtractor,
        store: ObjectProvider<DuplicateStore>,
        metrics: SlipMetrics,
        knownSlips: ObjectProvider<KnownSlips>,
        quotaGuard: ObjectProvider<QuotaGuard>,
    ): SlipVerifier {
        val order = props.providerOrder.map { it.lowercase() }
        val chain =
            providers
                .orderedStream()
                .toList()
                .sortedBy { p -> order.indexOf(p.name).let { if (it < 0) Int.MAX_VALUE else it } }
                .map { MeteredSlipProvider(it, metrics) }
        return SlipVerifier
            .builder()
            .providers(chain)
            .qrExtractor(extractor)
            .duplicateStore(store.ifAvailable)
            .knownSlips(knownSlips.ifAvailable)
            .quotaGuard(quotaGuard.ifAvailable)
            .duplicateTtl(props.duplicate.ttl)
            .debug(props.debug)
            .build()
    }

    @Bean
    @ConditionalOnMissingBean
    public fun slipVerificationService(
        verifier: SlipVerifier,
        metrics: SlipMetrics,
    ): SlipVerificationService = SlipVerificationService(verifier, metrics)

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(EasySlipProvider::class)
    public class EasySlipConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = ["easySlipProvider"])
        @Conditional(HasText.EasySlip::class)
        public fun easySlipProvider(props: SlipVerifyProperties): SlipProvider {
            val e = props.easyslip
            return EasySlipProvider(
                EasySlipConfig(
                    apiKey = requireNotNull(e.apiKey),
                    baseUrl = e.baseUrl,
                    requestTimeout = e.requestTimeout,
                    connectTimeout = e.connectTimeout,
                    checkDuplicate = e.checkDuplicate,
                ),
            )
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SlipOkProvider::class)
    public class SlipOkConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = ["slipOkProvider"])
        @Conditional(HasText.SlipOk::class)
        public fun slipOkProvider(props: SlipVerifyProperties): SlipProvider {
            val s = props.slipok
            return SlipOkProvider(
                SlipOkConfig(
                    apiKey = requireNotNull(s.apiKey),
                    branchId = requireNotNull(s.branchId),
                    baseUrl = s.baseUrl,
                    requestTimeout = s.requestTimeout,
                    connectTimeout = s.connectTimeout,
                    log = s.log,
                ),
            )
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry::class)
    public class MicrometerConfiguration {
        @Bean
        @ConditionalOnBean(MeterRegistry::class)
        public fun micrometerSlipMetrics(registry: MeterRegistry): SlipMetrics = MicrometerSlipMetrics(registry)
    }

    private companion object {
        const val REDIS_STORE = "io.github.sunjirapath.slipverify.redis.RedisDuplicateStore"
    }
}

/** Keeps the Redis class out of this class's constants so the starter loads without the Redis module. */
internal object RedisStores {
    fun connect(uri: String): DuplicateStore =
        io.github.sunjirapath.slipverify.redis.RedisDuplicateStore
            .connect(uri)
}

/** [SlipVerifier.verify] plus metrics. Inject this (or the verifier) into your own code. */
public class SlipVerificationService(
    private val verifier: SlipVerifier,
    private val metrics: SlipMetrics,
) {
    public fun verify(request: SlipRequest): SlipVerificationResult =
        verifier.verify(request).also { metrics.verification(it) }
}

/** A provider is enabled only by non-blank settings, so an unset `${ENV_VAR:}` placeholder means "off". */
internal object HasText {
    private fun has(
        context: ConditionContext,
        vararg keys: String,
    ) = keys.all { StringUtils.hasText(context.environment.getProperty(it)) }

    class EasySlip : Condition {
        override fun matches(
            context: ConditionContext,
            metadata: AnnotatedTypeMetadata,
        ) = has(context, "slipverify.easyslip.api-key")
    }

    class SlipOk : Condition {
        override fun matches(
            context: ConditionContext,
            metadata: AnnotatedTypeMetadata,
        ) = has(context, "slipverify.slipok.api-key", "slipverify.slipok.branch-id")
    }
}
