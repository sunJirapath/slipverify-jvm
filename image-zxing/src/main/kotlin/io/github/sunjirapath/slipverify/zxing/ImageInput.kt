package io.github.sunjirapath.slipverify.zxing

import io.github.sunjirapath.slipverify.ReasonCode
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.MemoryCacheImageInputStream

/** A failure that maps to a [ReasonCode]. Carries no message so nothing from the input can leak. */
internal class InputRejected(
    val reason: ReasonCode,
) : RuntimeException(reason.name, null, false, false)

/** Base64 text to validated image bytes. Every check runs before any pixel is decoded. */
internal object ImageInput {
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private const val DATA_PREFIX = "data:"

    /** Raw base64, a data URL, whitespace, and URL-safe alphabets are all accepted. */
    fun decodeBase64(
        input: String,
        limits: ImageLimits,
    ): ByteArray {
        // Reject on the raw length first so a huge string is never copied.
        if (input.length > limits.maxEncodedBytes) throw InputRejected(ReasonCode.IMAGE_TOO_LARGE)
        val clean = normalizeAlphabet(stripDataUrl(input.trim()))
        if (clean.isEmpty()) throw InputRejected(ReasonCode.EMPTY_INPUT)
        if (clean.length % 4 == 1) throw InputRejected(ReasonCode.INVALID_BASE64)
        while (clean.length % 4 != 0) clean.append('=')
        return try {
            Base64.getDecoder().decode(clean.toString())
        } catch (
            @Suppress("SwallowedException") e: IllegalArgumentException,
        ) {
            throw InputRejected(ReasonCode.INVALID_BASE64)
        }
    }

    private fun stripDataUrl(text: String): String {
        if (!text.startsWith(DATA_PREFIX, ignoreCase = true)) return text
        val comma = text.indexOf(',')
        if (comma < 0 || !text.substring(0, comma).contains(";base64", ignoreCase = true)) {
            throw InputRejected(ReasonCode.INVALID_BASE64)
        }
        return text.substring(comma + 1)
    }

    /** Drops whitespace and padding, maps the URL-safe alphabet to the standard one, rejects everything else. */
    private fun normalizeAlphabet(text: String): StringBuilder {
        val clean = StringBuilder(text.length)
        for (c in text) {
            when {
                c.isWhitespace() || c == '=' -> Unit
                c == '-' -> clean.append('+')
                c == '_' -> clean.append('/')
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' -> clean.append(c)
                else -> throw InputRejected(ReasonCode.INVALID_BASE64)
            }
        }
        return clean
    }

    /** PNG and JPEG only. HEIC, WebP, GIF, PDFs and anything else are refused by their first bytes. */
    fun requireSupportedFormat(bytes: ByteArray) {
        val ok = startsWith(bytes, PNG) || startsWith(bytes, JPEG)
        if (!ok) throw InputRejected(ReasonCode.UNSUPPORTED_IMAGE_FORMAT)
    }

    /** Reads width and height from the header and refuses oversize images without decoding them. */
    fun decode(
        bytes: ByteArray,
        limits: ImageLimits,
    ): BufferedImage {
        val stream = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        val reader: ImageReader =
            ImageIO.getImageReaders(stream).asSequence().firstOrNull()
                ?: throw InputRejected(ReasonCode.UNSUPPORTED_IMAGE_FORMAT)
        try {
            reader.setInput(stream, true, true)
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0) throw InputRejected(ReasonCode.UNSUPPORTED_IMAGE_FORMAT)
            if (width > limits.maxDimension || height > limits.maxDimension ||
                width.toLong() * height > limits.maxPixels
            ) {
                throw InputRejected(ReasonCode.IMAGE_DIMENSIONS_TOO_LARGE)
            }
            return reader.read(0)
        } catch (
            @Suppress("SwallowedException") e: IOException,
        ) {
            throw InputRejected(ReasonCode.UNSUPPORTED_IMAGE_FORMAT)
        } catch (
            @Suppress("SwallowedException") e: IllegalArgumentException,
        ) {
            throw InputRejected(ReasonCode.UNSUPPORTED_IMAGE_FORMAT)
        } finally {
            reader.dispose()
            stream.close()
        }
    }

    private fun startsWith(
        bytes: ByteArray,
        magic: ByteArray,
    ): Boolean = bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }
}
