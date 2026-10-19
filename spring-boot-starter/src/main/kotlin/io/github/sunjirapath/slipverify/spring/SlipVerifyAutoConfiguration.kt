package io.github.sunjirapath.slipverify.spring

import io.github.sunjirapath.slipverify.BillingCycle
import io.github.sunjirapath.slipverify.ConfiguredQuotaGuard
import io.github.sunjirapath.slipverify.DuplicateStore
import io.github.sunjirapath.slipverify.InMemoryDuplicateStore
import io.github.sunjirapath.slipverify.KnownSlips
import io.github.sunjirapath.slipverify.ProviderLimit
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
        return when {
            d.store.equals("redis", ignoreCase = true) -> redisStore(d)
            d.store.equals("memory", ignoreCase = true) -> InMemoryDuplicateStore()
            else -> error("slipverify.duplicate.store must be memory or redis")
        }
    }

    private fun redisStore(d: SlipVerifyProperties.Duplicate): DuplicateStore {
        check(ClassUtils.isPresent(REDIS_STORE, ClassUtils.getDefaultClassLoader())) {
            "slipverify.duplicate.store=redis needs the slipverify-store-redis module on the classpath"
        }
        val uri = d.redisUri
        check(!uri.isNullOrBlank()) { "slipverify.duplicate.redis-uri is required when the store is redis" }
        return RedisStores.connect(uri)
    }

    @Bean
    @ConditionalOnMissingBean
    public fun slipMetrics(): SlipMetrics = SlipMetrics.NOOP

    /** Built from `slipverify.<provider>.limit.*` and `bill-cutoff`; absent when no limit is configured. */
    @Bean
    @ConditionalOnMissingBean
    public fun slipQuotaGuard(props: SlipVerifyProperties): QuotaGuard? = ProviderLimitsFactory.create(props)

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
            val endpoint = SlipOkEndpoint.resolve(s)
            return SlipOkProvider(
                SlipOkConfig(
                    apiKey = requireNotNull(s.apiKey),
                    branchId = endpoint.branchId,
                    baseUrl = endpoint.baseUrl,
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
        @ConditionalOnMissingBean(SlipMetrics::class)
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
        verifier.verify(request).also { result -> recordMetrics { metrics.verification(result) } }
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
        ) = has(context, "slipverify.slipok.api-key") &&
            (has(context, "slipverify.slipok.branch-id") || has(context, "slipverify.slipok.url"))
    }
}

/** Splits `https://host/api/line/apikey/<BRANCH>` into the base URL and branch id SlipOK needs. */
internal object SlipOkEndpoint {
    class Resolved(
        val baseUrl: String,
        val branchId: String,
    )

    fun resolve(s: SlipVerifyProperties.SlipOk): Resolved {
        val url = s.url?.trim()?.trimEnd('/')
        if (url.isNullOrEmpty()) return Resolved(s.baseUrl, requireNotNull(s.branchId))
        val cut = url.lastIndexOf('/')
        require(cut > url.indexOf("//") + 1 && cut < url.length - 1) {
            "slipverify.slipok.url must end with your branch id, " +
                "https://api.slipok.com/api/line/apikey/<YOUR_BRANCH_ID>"
        }
        return Resolved(url.substring(0, cut), url.substring(cut + 1))
    }
}

/** Turns the per-provider `limit` and `bill-cutoff` settings into a [QuotaGuard]. */
internal object ProviderLimitsFactory {
    fun create(props: SlipVerifyProperties): QuotaGuard? {
        val zone = java.time.ZoneId.of(props.billZone)
        val limits = LinkedHashMap<String, ProviderLimit>()
        val cycles = LinkedHashMap<String, BillingCycle>()
        val configured =
            listOf(
                Triple("easyslip", props.easyslip.limit, props.easyslip.billCutoff),
                Triple("slipok", props.slipok.limit, props.slipok.billCutoff),
            )
        for ((name, limit, cutoff) in configured) {
            if (!cutoff.isNullOrBlank()) cycles[name] = BillingCycle.parse(cutoff, zone)
            if (limit.maxPerCycle != null || limit.maxPerMinute != null) {
                limits[name] = ProviderLimit(limit.maxPerCycle, limit.maxPerMinute)
            }
        }
        return if (limits.isEmpty()) null else ConfiguredQuotaGuard(limits, cycles)
    }
}
