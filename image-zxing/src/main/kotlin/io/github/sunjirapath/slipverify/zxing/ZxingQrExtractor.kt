package io.github.sunjirapath.slipverify.zxing

import io.github.sunjirapath.slipverify.ExtractionResult
import io.github.sunjirapath.slipverify.QrExtractor
import io.github.sunjirapath.slipverify.ReasonCode
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Predicate

/**
 * Finds the slip QR in a base64 image using ZXing.
 *
 * Accepts raw base64, data URLs, whitespace and URL-safe base64. PNG and JPEG only: HEIC (iPhone) and WebP are
 * refused with [ReasonCode.UNSUPPORTED_IMAGE_FORMAT], so the frontend should convert them (for example by drawing
 * the picture on a canvas and exporting JPEG). Size, pixel count and time are limited by [ImageLimits].
 * Image bytes are never logged or stored. Instances are thread-safe.
 */
public class ZxingQrExtractor
    @JvmOverloads
    constructor(
        private val limits: ImageLimits = ImageLimits(),
    ) : QrExtractor {
        override fun extract(
            imageBase64: String,
            isSlip: Predicate<String>,
        ): ExtractionResult =
            try {
                val decoded = ImageInput.decodeBase64(imageBase64, limits)
                ImageInput.requireSupportedFormat(decoded)
                scanWithTimeout(decoded, isSlip)
            } catch (
                @Suppress("SwallowedException") e: RejectedExecutionException,
            ) {
                ExtractionResult(emptyList(), null, ReasonCode.IMAGE_PROCESSING_BUSY, null)
            } catch (e: InputRejected) {
                ExtractionResult(emptyList(), null, e.reason, null)
            }

        private fun scanWithTimeout(
            bytes: ByteArray,
            isSlip: Predicate<String>,
        ): ExtractionResult {
            val future =
                POOL.submit<ExtractionResult> {
                    val image = ImageInput.decode(bytes, limits)
                    val scanner = QrScanner(isSlip, limits)
                    scanner.scan(image)
                    val failure = if (scanner.found.isEmpty()) ReasonCode.NO_QR_FOUND else null
                    ExtractionResult(scanner.found.toList(), scanner.slip, failure, bytes.copyOf())
                }
            return try {
                future.get(limits.decodeTimeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (
                @Suppress("SwallowedException") e: TimeoutException,
            ) {
                future.cancel(true)
                ExtractionResult(emptyList(), null, ReasonCode.IMAGE_DECODE_TIMEOUT, null)
            } catch (e: ExecutionException) {
                val cause = e.cause
                if (cause is InputRejected) {
                    ExtractionResult(emptyList(), null, cause.reason, null)
                } else {
                    // Corrupt data that ImageIO or ZXing choked on: treat as an unreadable image, keep no details.
                    ExtractionResult(emptyList(), null, ReasonCode.UNSUPPORTED_IMAGE_FORMAT, null)
                }
            } catch (e: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                ExtractionResult(emptyList(), null, ReasonCode.IMAGE_DECODE_TIMEOUT, null)
            } finally {
                if (future.isCancelled) POOL.remove(future as Runnable)
            }
        }

        internal companion object {
            private const val WORKERS = 4
            private const val QUEUE_CAPACITY = 8
            internal val POOL: ThreadPoolExecutor =
                ThreadPoolExecutor(
                    WORKERS,
                    WORKERS,
                    0L,
                    TimeUnit.MILLISECONDS,
                    ArrayBlockingQueue(QUEUE_CAPACITY),
                    { runnable -> Thread(runnable, "slipverify-zxing").apply { isDaemon = true } },
                    ThreadPoolExecutor.AbortPolicy(),
                )
        }
    }
