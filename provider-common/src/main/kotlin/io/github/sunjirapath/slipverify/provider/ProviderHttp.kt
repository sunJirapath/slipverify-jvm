package io.github.sunjirapath.slipverify.provider

import io.github.sunjirapath.slipverify.ReasonCode
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * What came back from one HTTP call. Either [status] and [body] are set, or [transportFailure] says why not.
 * Bodies and URLs never appear in `toString()`: they can carry slip data and API keys.
 */
public class HttpResult private constructor(
    public val status: Int,
    public val body: String,
    public val transportFailure: ReasonCode?,
) {
    public val isServerError: Boolean get() = status >= SERVER_ERROR_FROM

    override fun toString(): String = "HttpResult(status=$status, failure=$transportFailure)"

    internal companion object {
        private const val SERVER_ERROR_FROM = 500

        fun response(
            status: Int,
            body: String,
        ) = HttpResult(status, body, null)

        fun failure(reason: ReasonCode) = HttpResult(0, "", reason)
    }
}

/**
 * Thin wrapper over `java.net.http.HttpClient` shared by provider modules. [send] never throws and maps timeouts
 * and I/O problems to reason codes, without keeping the exception message (it may contain URLs or credentials).
 */
public class ProviderHttp
    @JvmOverloads
    constructor(
        private val client: HttpClient = defaultClient(DEFAULT_CONNECT_TIMEOUT),
    ) {
        public fun send(request: HttpRequest): HttpResult =
            try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                val body = response.body() ?: ""
                if (body.length > MAX_BODY_CHARS) {
                    HttpResult.failure(ReasonCode.PROVIDER_ERROR)
                } else {
                    HttpResult.response(response.statusCode(), body)
                }
            } catch (
                @Suppress("SwallowedException") e: HttpTimeoutException,
            ) {
                HttpResult.failure(ReasonCode.PROVIDER_TIMEOUT)
            } catch (
                @Suppress("SwallowedException") e: IOException,
            ) {
                // Connect timeouts surface as HttpConnectTimeoutException, a subclass of HttpTimeoutException.
                HttpResult.failure(ReasonCode.PROVIDER_ERROR)
            } catch (
                @Suppress("SwallowedException") e: InterruptedException,
            ) {
                Thread.currentThread().interrupt()
                HttpResult.failure(ReasonCode.PROVIDER_ERROR)
            }

        public companion object {
            private const val MAX_BODY_CHARS = 2_000_000
            private val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

            /** A client that never follows redirects, so a key header cannot be forwarded to another host. */
            @JvmStatic
            public fun defaultClient(connectTimeout: Duration): HttpClient =
                HttpClient
                    .newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()
        }
    }
