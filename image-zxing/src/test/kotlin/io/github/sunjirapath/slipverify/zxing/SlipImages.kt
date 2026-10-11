package io.github.sunjirapath.slipverify.zxing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Builds fake slips with Java2D. Nothing here resembles a real person's slip: all text is invented. */
object SlipImages {
    const val DECOY = "https://example.com/promo?campaign=synthetic"

    fun qr(
        text: String,
        modulePx: Int,
    ): BufferedImage {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val img = BufferedImage(matrix.width * modulePx, matrix.height * modulePx, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, img.width, img.height)
        g.color = Color.BLACK
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) g.fillRect(x * modulePx, y * modulePx, modulePx, modulePx)
            }
        }
        g.dispose()
        return img
    }

    /** A 1080x2200 phone-screenshot-like slip: header, logo box, text, the slip QR bottom right and a decoy bottom left. */
    fun slip(
        payload: String?,
        decoy: Boolean = true,
        width: Int = 1080,
        height: Int = 2200,
    ): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        g.paint = GradientPaint(0f, 0f, Color(60, 40, 140), 0f, 420f, Color(110, 80, 200))
        g.fillRect(0, 0, width, 420)
        g.color = Color.WHITE
        g.fillRoundRect(60, 90, 220, 220, 40, 40)
        g.color = Color(60, 40, 140)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 56)
        g.drawString("LOGO", 100, 220)
        g.color = Color.WHITE
        g.drawString("Transfer complete", 340, 220)
        g.color = Color.DARK_GRAY
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 48)
        val lines =
            listOf(
                "From: Test Sender xxx-x-x5678-x",
                "To: Test Receiver xxx-x-x1234-x",
                "Amount: 150.00 THB",
                "Ref: SYNTHETIC",
            )
        lines.forEachIndexed { i, s -> g.drawString(s, 80, 560 + i * 110) }
        val modulePx = maxOf(4, width / 100)
        if (decoy) {
            val d = qr(DECOY, modulePx)
            g.drawImage(d, 80, height - d.height - 120, null)
        }
        if (payload != null) {
            val q = qr(payload, modulePx)
            g.drawImage(q, width - q.width - 80, height - q.height - 120, null)
        }
        g.dispose()
        return img
    }

    fun png(img: BufferedImage): ByteArray = encode(img, "png", null)

    fun jpeg(
        img: BufferedImage,
        quality: Float = 0.9f,
    ): ByteArray = encode(img, "jpg", quality)

    private fun encode(
        img: BufferedImage,
        format: String,
        quality: Float?,
    ): ByteArray {
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            drawImage(img, 0, 0, null)
            dispose()
        }
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName(format).next()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val param = writer.defaultWriteParam
            if (quality != null) {
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                param.compressionQuality = quality
            }
            writer.write(null, IIOImage(rgb, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun rotate(
        img: BufferedImage,
        degrees: Double,
        background: Color = Color(30, 30, 30),
        pad: Boolean = false,
    ): BufferedImage {
        val rad = Math.toRadians(degrees)
        val sin = Math.abs(Math.sin(rad))
        val cos = Math.abs(Math.cos(rad))
        val w =
            if (pad ||
                degrees % 90.0 != 0.0
            ) {
                (img.width * cos + img.height * sin).toInt()
            } else if (degrees % 180.0 ==
                0.0
            ) {
                img.width
            } else {
                img.height
            }
        val h =
            if (pad ||
                degrees % 90.0 != 0.0
            ) {
                (img.width * sin + img.height * cos).toInt()
            } else if (degrees % 180.0 ==
                0.0
            ) {
                img.height
            } else {
                img.width
            }
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = background
        g.fillRect(0, 0, w, h)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        val t = AffineTransform()
        t.translate(w / 2.0, h / 2.0)
        t.rotate(rad)
        t.translate(-img.width / 2.0, -img.height / 2.0)
        g.drawImage(img, t, null)
        g.dispose()
        return out
    }

    fun scale(
        img: BufferedImage,
        factor: Double,
    ): BufferedImage {
        var current = img
        var f = factor
        while (f < 0.5) {
            current = scaleOnce(current, 0.5)
            f /= 0.5
        }
        return scaleOnce(current, f)
    }

    private fun scaleOnce(
        img: BufferedImage,
        f: Double,
    ): BufferedImage {
        val op = AffineTransformOp(AffineTransform.getScaleInstance(f, f), AffineTransformOp.TYPE_BICUBIC)
        val out = BufferedImage((img.width * f).toInt(), (img.height * f).toInt(), BufferedImage.TYPE_INT_RGB)
        return op.filter(img, out)
    }

    /** Photo: the slip placed on a dark table, tilted, then shrunk to a typical upload size. */
    fun photo(
        slip: BufferedImage,
        tiltDegrees: Double,
    ): BufferedImage {
        val tilted = rotate(slip, tiltDegrees)
        return scale(tilted, 0.45)
    }

    fun noise(
        img: BufferedImage,
        amplitude: Int,
        seed: Long = 7,
    ): BufferedImage {
        val rnd = Random(seed)
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                val n = rnd.nextInt(amplitude * 2 + 1) - amplitude

                fun ch(shift: Int) = (((rgb shr shift) and 0xFF) + n).coerceIn(0, 255)
                out.setRGB(x, y, (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
            }
        }
        return out
    }

    fun blur(img: BufferedImage): BufferedImage {
        val kernel = Kernel(3, 3, FloatArray(9) { 1f / 9f })
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            drawImage(img, 0, 0, null)
            dispose()
        }
        return ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null).filter(rgb, null)
    }

    /** Squeezes luminance into a narrow band around mid-grey. */
    fun lowContrast(
        img: BufferedImage,
        factor: Double,
    ): BufferedImage {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)

                fun ch(shift: Int) = (170 + (((rgb shr shift) and 0xFF) - 128) * factor).toInt().coerceIn(0, 255)
                out.setRGB(x, y, (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
            }
        }
        return out
    }

    /** A PNG that claims to be [width] x [height] but has no pixel data: the classic decompression-bomb header. */
    fun bombPng(
        width: Int,
        height: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream()
        ihdr.write("IHDR".toByteArray())

        fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        ihdr.write(int(width))
        ihdr.write(int(height))
        ihdr.write(byteArrayOf(8, 2, 0, 0, 0))
        val crc =
            java.util.zip
                .CRC32()
                .apply { update(ihdr.toByteArray()) }
                .value
                .toInt()
        out.write(int(13))
        out.write(ihdr.toByteArray())
        out.write(int(crc))
        val iend =
            java.util.zip
                .CRC32()
                .apply { update("IEND".toByteArray()) }
                .value
                .toInt()
        out.write(int(0))
        out.write("IEND".toByteArray())
        out.write(int(iend))
        return out.toByteArray()
    }
}
