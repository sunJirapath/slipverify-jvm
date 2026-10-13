package io.github.sunjirapath.slipverify.zxing

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeReader
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.util.function.Predicate

/**
 * Looks for QR codes in progressively more aggressive variants of one image and stops at the first text
 * [isSlip] accepts. A slip photo often shows a promo or merchant QR too; those are collected but never preferred.
 */
internal class QrScanner(
    private val isSlip: Predicate<String>,
    private val limits: ImageLimits,
) {
    val found = LinkedHashSet<String>()
    var slip: String? = null
        private set

    private val hints =
        mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.ALSO_INVERTED to true,
        )

    /** @return true once a slip QR was found. Honors thread interruption (the decode timeout). */
    fun scan(image: BufferedImage): Boolean {
        for (variant in variants(image)) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (tryVariant(variant())) return true
        }
        return false
    }

    private fun tryVariant(img: BufferedImage): Boolean {
        val source: LuminanceSource = BufferedImageLuminanceSource(img)
        for (binarizer in listOf(::HybridBinarizer, ::GlobalHistogramBinarizer)) {
            val bitmap = BinaryBitmap(binarizer(source))
            for (text in decode(bitmap)) {
                found += text
                if (slip == null && isSlip.test(text)) slip = text
            }
            if (slip != null) return true
        }
        return false
    }

    private fun decode(bitmap: BinaryBitmap): List<String> {
        val texts = ArrayList<String>()
        try {
            QRCodeMultiReader().decodeMultiple(bitmap, hints).mapTo(texts) { it.text }
        } catch (
            @Suppress("SwallowedException") e: ReaderException,
        ) {
            // not found with the multi reader; the single reader below can still succeed
        }
        if (texts.isEmpty()) {
            try {
                texts += QRCodeReader().decode(bitmap, hints).text
            } catch (
                @Suppress("SwallowedException") e: ReaderException,
            ) {
                // nothing in this variant
            }
        }
        return texts
    }

    /** Cheap attempts first. Each variant is built lazily so a quick hit costs nothing extra. */
    internal fun variants(src: BufferedImage): Sequence<() -> BufferedImage> =
        sequence {
            val longest = maxOf(src.width, src.height)
            val shortest = minOf(src.width, src.height)
            yield { src }
            if (longest > LARGE) {
                yield { scaleTo(src, LARGE, limits) }
                yield { scaleTo(src, MEDIUM, limits) }
            }
            if (shortest < SMALL) {
                yield { scaleBy(src, 2.0, limits) }
                yield { scaleBy(src, 3.0, limits) }
            }
            yield { stretchContrast(src) }
            // Slip QR codes sit near the bottom; the crops make the code a bigger share of the frame.
            yield { crop(src, 0.0, 0.5, 1.0, 0.5) }
            yield { crop(src, 0.5, 0.5, 0.5, 0.5) }
            yield { crop(src, 0.0, 0.66, 1.0, 0.34) }
            yield { crop(src, 0.2, 0.2, 0.6, 0.6) }
            yield { scaleBy(crop(src, 0.0, 0.5, 1.0, 0.5), 2.0, limits) }
            for (quarter in 1..3) yield { rotate(src, quarter) }
            yield { rotate(stretchContrast(src), 1) }
        }

    private companion object {
        const val LARGE = 1600
        const val MEDIUM = 1000
        const val SMALL = 700
        const val MIN_SIDE = 60

        fun scaleTo(
            src: BufferedImage,
            longest: Int,
            limits: ImageLimits,
        ): BufferedImage = scaleBy(src, longest.toDouble() / maxOf(src.width, src.height), limits)

        fun scaleBy(
            src: BufferedImage,
            factor: Double,
            limits: ImageLimits,
        ): BufferedImage {
            // Shrinking in halves keeps thin QR modules intact better than one big bicubic step.
            var current = src
            var remaining = factor
            while (remaining < 0.5) {
                current = resample(current, 0.5, limits)
                remaining /= 0.5
            }
            return resample(current, remaining, limits)
        }

        fun resample(
            src: BufferedImage,
            factor: Double,
            limits: ImageLimits,
        ): BufferedImage {
            val boundedFactor =
                minOf(
                    factor,
                    limits.maxDimension.toDouble() / maxOf(src.width, src.height),
                    kotlin.math.sqrt(limits.maxPixels.toDouble() / (src.width.toLong() * src.height)),
                )
            val w = maxOf(1, (src.width * boundedFactor).toInt())
            val h = maxOf(1, (src.height * boundedFactor).toInt())
            val out = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
            val g = out.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.drawImage(
                    src,
                    AffineTransform.getScaleInstance(w.toDouble() / src.width, h.toDouble() / src.height),
                    null,
                )
            } finally {
                g.dispose()
            }
            return out
        }

        fun crop(
            src: BufferedImage,
            x: Double,
            y: Double,
            w: Double,
            h: Double,
        ): BufferedImage {
            val cx = (src.width * x).toInt()
            val cy = (src.height * y).toInt()
            val cw = maxOf(MIN_SIDE.coerceAtMost(src.width - cx), (src.width * w).toInt()).coerceAtMost(src.width - cx)
            val ch =
                maxOf(MIN_SIDE.coerceAtMost(src.height - cy), (src.height * h).toInt()).coerceAtMost(src.height - cy)
            return src.getSubimage(cx, cy, cw, ch)
        }

        fun rotate(
            src: BufferedImage,
            quarters: Int,
        ): BufferedImage {
            val swap = quarters % 2 == 1
            val out =
                BufferedImage(
                    if (swap) src.height else src.width,
                    if (swap) src.width else src.height,
                    BufferedImage.TYPE_BYTE_GRAY,
                )
            val g = out.createGraphics()
            try {
                val t = AffineTransform()
                t.translate(out.width / 2.0, out.height / 2.0)
                t.rotate(Math.PI / 2 * quarters)
                t.translate(-src.width / 2.0, -src.height / 2.0)
                g.drawImage(src, t, null)
            } finally {
                g.dispose()
            }
            return out
        }

        /** Linear stretch between the 2nd and 98th luminance percentile, for washed-out screenshots and photos. */
        fun stretchContrast(src: BufferedImage): BufferedImage {
            val gray = BufferedImage(src.width, src.height, BufferedImage.TYPE_BYTE_GRAY)
            val g = gray.createGraphics()
            try {
                g.drawImage(src, 0, 0, null)
            } finally {
                g.dispose()
            }
            val data = (gray.raster.dataBuffer as java.awt.image.DataBufferByte).data
            val hist = IntArray(256)
            for (b in data) hist[b.toInt() and 0xFF]++
            val total = data.size
            val lo = percentile(hist, total * 0.02)
            val hi = percentile(hist, total * 0.98)
            if (hi - lo < 8) return gray
            val scale = 255.0 / (hi - lo)
            for (i in data.indices) {
                val v = ((data[i].toInt() and 0xFF) - lo) * scale
                data[i] = v.toInt().coerceIn(0, 255).toByte()
            }
            return gray
        }

        fun percentile(
            hist: IntArray,
            target: Double,
        ): Int {
            var sum = 0
            for (i in hist.indices) {
                sum += hist[i]
                if (sum >= target) return i
            }
            return 255
        }
    }
}
