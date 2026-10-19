package io.github.sunjirapath.slipverify.provider

import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.Flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LimitedBodySubscriberTest {
    private class Subscription : Flow.Subscription {
        var cancelled = false

        override fun request(n: Long) = Unit

        override fun cancel() {
            cancelled = true
        }
    }

    @Test
    fun `cancellation before subscription arrival cancels the late subscription`() {
        val subscriber = LimitedBodySubscriber(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), 4)
        subscriber.cancel()
        val subscription = Subscription()
        subscriber.onSubscribe(subscription)
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(97))))
        subscriber.onComplete()
        subscriber.onError(java.io.IOException())
        subscriber.cancel()
        assertTrue(subscription.cancelled)
        assertFailsWith<java.util.concurrent.ExecutionException> { subscriber.body.toCompletableFuture().get() }
    }

    @Test
    fun `cancellation after a partial body rejects later signals and completed body stays completed`() {
        val subscriber = LimitedBodySubscriber(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), 4)
        val subscription = Subscription()
        subscriber.onSubscribe(subscription)
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(97))))
        subscriber.cancel()
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(98))))
        subscriber.onError(java.io.IOException())
        subscriber.onComplete()
        assertTrue(subscription.cancelled)
        assertFailsWith<java.util.concurrent.ExecutionException> { subscriber.body.toCompletableFuture().get() }

        val complete = LimitedBodySubscriber(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), 4)
        val done = Subscription()
        complete.onSubscribe(done)
        complete.onComplete()
        complete.cancel()
        assertFalse(done.cancelled)
        assertEquals("", complete.body.toCompletableFuture().get())
    }

    @Test
    fun `exact byte limit passes while excess bytes cancel the download`() {
        for (excess in listOf(false, true)) {
            val subscriber = LimitedBodySubscriber(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), 4)
            val subscription = Subscription()
            subscriber.onSubscribe(subscription)
            subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(97, 98))))
            subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(99, 100))))
            if (excess) {
                subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(101))))
                subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(102))))
                subscriber.onError(java.io.IOException())
                subscriber.onComplete()
                assertTrue(subscription.cancelled)
                assertFailsWith<java.util.concurrent.ExecutionException> { subscriber.body.toCompletableFuture().get() }
            } else {
                subscriber.onComplete()
                assertFalse(subscription.cancelled)
                assertEquals("abcd", subscriber.body.toCompletableFuture().get())
            }
        }
    }

    @Test
    fun `multi buffer and multibyte text count bytes and preserve failures`() {
        val subscriber = LimitedBodySubscriber(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), 4)
        val subscription = Subscription()
        subscriber.onSubscribe(subscription)
        subscriber.onNext(listOf(ByteBuffer.wrap("ก".toByteArray()), ByteBuffer.wrap("ก".toByteArray())))
        assertTrue(subscription.cancelled)
        assertFailsWith<java.util.concurrent.ExecutionException> { subscriber.body.toCompletableFuture().get() }
        val failed = LimitedBodySubscriber(HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), 4)
        failed.onSubscribe(Subscription())
        failed.onError(java.io.IOException())
        assertFailsWith<java.util.concurrent.ExecutionException> { failed.body.toCompletableFuture().get() }
    }
}
