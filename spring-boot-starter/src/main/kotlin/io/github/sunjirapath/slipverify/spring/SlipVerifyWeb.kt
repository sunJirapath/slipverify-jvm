package io.github.sunjirapath.slipverify.spring

import io.github.sunjirapath.slipverify.ReceiverRule
import io.github.sunjirapath.slipverify.SlipRequest
import io.github.sunjirapath.slipverify.SlipVerificationResult
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** JSON body of the example endpoint. Send `image` (base64 or data URL) or `payload` (already scanned). */
public class VerifyRequestBody {
    public var image: String? = null
    public var payload: String? = null
    public var expectedAmount: BigDecimal? = null
    public var receiver: Receiver? = null
    public var maxAgeSeconds: Long? = null

    public class Receiver {
        public var accountSuffix: String? = null
        public var nameContains: String? = null
    }
}

/** What the example endpoint returns. Parties are masked; no payload, image or provider detail is included. */
public class VerifyResponseBody(
    public val status: String,
    public val reasons: List<String>,
    public val slipType: String?,
    public val sendingBank: String?,
    public val transRef: String?,
    public val amount: BigDecimal?,
    public val transferTime: Instant?,
    public val sender: String?,
    public val receiver: String?,
    public val provider: String?,
) {
    internal companion object {
        fun of(r: SlipVerificationResult) =
            VerifyResponseBody(
                r.status.name,
                r.reasons.map { it.name },
                r.slipType?.name,
                r.sendingBank,
                r.transRef,
                r.amount,
                r.transferTime,
                r.maskedSender,
                r.maskedReceiver,
                r.providerName,
            )
    }
}

/**
 * Example controller (`slipverify.web.enabled=true`). Copy it into your app and add authentication and rate
 * limiting: this endpoint is public as shipped. Clients only ever see generic error bodies; details go to the
 * server log at DEBUG.
 */
@RestController
@RequestMapping("\${slipverify.web.path:/slips/verify}")
public class SlipVerifyController(
    private val service: SlipVerificationService,
) {
    private val log = LoggerFactory.getLogger(SlipVerifyController::class.java)

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    public fun verify(
        @RequestBody body: VerifyRequestBody,
    ): VerifyResponseBody {
        val rule = body.receiver?.takeIf { it.accountSuffix != null || it.nameContains != null }
        val request =
            SlipRequest(
                imageBase64 = body.image,
                payload = body.payload,
                expectedAmount = body.expectedAmount,
                expectedReceiver = rule?.let { ReceiverRule(it.accountSuffix, it.nameContains) },
                maxAge = body.maxAgeSeconds?.let { Duration.ofSeconds(it) },
            )
        return VerifyResponseBody.of(service.verify(request))
    }

    @ExceptionHandler(
        IllegalArgumentException::class,
        org.springframework.http.converter.HttpMessageNotReadableException::class,
    )
    public fun badRequest(e: Exception): ResponseEntity<Map<String, String>> {
        log.debug("rejected request: {}", e.javaClass.simpleName)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to "invalid_request"))
    }

    @ExceptionHandler(Exception::class)
    public fun serverError(e: Exception): ResponseEntity<Map<String, String>> {
        log.debug("verification failed", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("error" to "internal_error"))
    }
}

/** Refuses oversized or length-less bodies before any JSON is read. */
public class RequestSizeLimitFilter(
    private val maxBytes: Long,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val length = request.contentLengthLong
        when {
            length < 0 -> reject(response, HttpStatus.LENGTH_REQUIRED)
            length > maxBytes -> reject(response, HttpStatus.PAYLOAD_TOO_LARGE)
            else -> chain.doFilter(request, response)
        }
    }

    private fun reject(
        response: HttpServletResponse,
        status: HttpStatus,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write(
            """{"error":"${if (status == HttpStatus.PAYLOAD_TOO_LARGE) "too_large" else "length_required"}"}""",
        )
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.method != "POST"
}

@AutoConfiguration(after = [SlipVerifyAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RestController::class)
@ConditionalOnProperty(prefix = "slipverify.web", name = ["enabled"], havingValue = "true")
public class SlipVerifyWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(SlipVerificationService::class)
    public fun slipVerifyController(service: SlipVerificationService): SlipVerifyController =
        SlipVerifyController(service)

    @Bean
    public fun slipVerifySizeLimit(props: SlipVerifyProperties): FilterRegistrationBean<RequestSizeLimitFilter> =
        FilterRegistrationBean(RequestSizeLimitFilter(props.web.maxRequestBytes)).apply {
            addUrlPatterns(props.web.path)
        }
}
