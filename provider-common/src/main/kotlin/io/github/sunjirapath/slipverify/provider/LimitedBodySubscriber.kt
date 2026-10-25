package io.github.sunjirapath.slipverify.provider

import java.io.IOException
import java.net.http.HttpResponse.BodySubscriber
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

/** Cancels oversized responses before the string subscriber can buffer them. */
internal class LimitedBodySubscriber(
    private val delegate: BodySubscriber<String>,
    private val maxBytes: Long,
) : BodySubscriber<String> {
    private var subscription: Flow.Subscription? = null
    private var received = 0L
    private var failed = false

    override fun getBody(): CompletionStage<String> = delegate.body

    @Synchronized
    override fun onSubscribe(subscription: Flow.Subscription) {
        if (failed) {
            subscription.cancel()
            return
        }
        this.subscription = subscription
        delegate.onSubscribe(subscription)
    }

    @Synchronized
    override fun onNext(item: List<ByteBuffer>) {
        if (failed) return
        val bytes = item.sumOf { it.remaining().toLong() }
        if (bytes > maxBytes - received) {
            fail(IOException("Provider response exceeds size limit"))
        } else {
            received += bytes
            delegate.onNext(item)
        }
    }

    @Synchronized
    override fun onError(throwable: Throwable) {
        if (!failed) {
            failed = true
            delegate.onError(throwable)
        }
    }

    @Synchronized
    override fun onComplete() {
        if (!failed) {
            failed = true
            delegate.onComplete()
        }
    }

    @Synchronized
    fun cancel() {
        if (!failed) fail(IOException("Provider request cancelled"))
    }

    private fun fail(cause: IOException) {
        failed = true
        subscription?.cancel()
        delegate.onError(cause)
    }
}
