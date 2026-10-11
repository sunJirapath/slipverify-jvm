package io.github.sunjirapath.slipverify.zxing

import java.time.Duration

/**
 * Safety limits applied before and during image decoding.
 *
 * @property maxEncodedBytes longest accepted base64 text (default 5 MB), checked before any decoding.
 * @property maxDimension longest accepted width or height in pixels, read from the header only.
 * @property maxPixels largest accepted width x height, read from the header only (decompression-bomb guard).
 * @property decodeTimeout wall-clock budget for decoding the image and searching it for QR codes.
 */
public class ImageLimits
    @JvmOverloads
    constructor(
        public val maxEncodedBytes: Int = DEFAULT_MAX_ENCODED_BYTES,
        public val maxDimension: Int = DEFAULT_MAX_DIMENSION,
        public val maxPixels: Long = DEFAULT_MAX_PIXELS,
        public val decodeTimeout: Duration = DEFAULT_TIMEOUT,
    ) {
        init {
            require(maxEncodedBytes > 0) { "maxEncodedBytes must be positive" }
            require(maxDimension > 0) { "maxDimension must be positive" }
            require(maxPixels > 0) { "maxPixels must be positive" }
            require(!decodeTimeout.isNegative && !decodeTimeout.isZero) { "decodeTimeout must be positive" }
        }

        override fun toString(): String =
            "ImageLimits(maxEncodedBytes=$maxEncodedBytes, maxDimension=$maxDimension, " +
                "maxPixels=$maxPixels, decodeTimeout=$decodeTimeout)"

        public companion object {
            public const val DEFAULT_MAX_ENCODED_BYTES: Int = 5 * 1024 * 1024
            public const val DEFAULT_MAX_DIMENSION: Int = 10_000
            public const val DEFAULT_MAX_PIXELS: Long = 25_000_000L
            private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)
        }
    }
