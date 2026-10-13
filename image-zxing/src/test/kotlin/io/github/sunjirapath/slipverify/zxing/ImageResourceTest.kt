package io.github.sunjirapath.slipverify.zxing

import io.github.sunjirapath.slipverify.ReasonCode
import io.github.sunjirapath.slipverify.SlipRequest
import io.github.sunjirapath.slipverify.SlipVerifier
import io.github.sunjirapath.slipverify.VerificationStatus
import java.awt.image.BufferedImage
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Predicate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageResourceTest {
    @Test
    fun `saturation refuses work and timed out queued work is removed`() {
        val pool = ZxingQrExtractor.POOL
        val started = CountDownLatch(pool.corePoolSize)
        val release = CountDownLatch(1)
        val blockers =
            (1..pool.corePoolSize).map {
                pool.submit {
                    started.countDown()
                    release.await()
                }
            }
        val queued = ArrayList<java.util.concurrent.Future<*>>()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(8) { queued += pool.submit { release.await() } }
            val image = SlipImages.b64(SlipImages.png(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)))
            val extractor = ZxingQrExtractor(ImageLimits(decodeTimeout = Duration.ofMillis(25)))
            assertEquals(ReasonCode.IMAGE_PROCESSING_BUSY, extractor.extract(image, Predicate { true }).failure)
            val result =
                SlipVerifier
                    .builder()
                    .qrExtractor(extractor)
                    .build()
                    .verify(SlipRequest(imageBase64 = image))
            assertEquals(VerificationStatus.ERROR, result.status)
            assertTrue(result.hasReason(ReasonCode.IMAGE_PROCESSING_BUSY))
            assertEquals(pool.corePoolSize, pool.activeCount)
            queued.forEach { it.cancel(false) }
            pool.purge()
            assertEquals(ReasonCode.IMAGE_DECODE_TIMEOUT, extractor.extract(image, Predicate { true }).failure)
            assertTrue(pool.queue.isEmpty())
        } finally {
            release.countDown()
            queued.forEach { it.cancel(true) }
            blockers.forEach { it.cancel(true) }
            pool.purge()
        }
    }

    @Test
    fun `every generated variant obeys dimension and pixel caps`() {
        val limits = ImageLimits(maxDimension = 600, maxPixels = 24_000)
        for ((width, height) in listOf(600 to 40, 40 to 600, 150 to 160)) {
            val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
            val scanner = QrScanner(Predicate { false }, limits)
            for (variant in scanner.variants(image)) {
                val generated = variant()
                assertTrue(generated.width <= limits.maxDimension && generated.height <= limits.maxDimension)
                assertTrue(generated.width.toLong() * generated.height <= limits.maxPixels)
            }
        }
    }
}
