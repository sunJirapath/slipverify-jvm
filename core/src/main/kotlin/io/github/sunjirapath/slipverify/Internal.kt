package io.github.sunjirapath.slipverify

import io.github.sunjirapath.promptparse.PromptParse
import io.github.sunjirapath.promptparse.SlipVerify
import java.security.MessageDigest

/** A slip QR understood offline. */
internal class ParsedSlip(
    val type: SlipType,
    val sendingBank: String,
    val transRef: String,
    val crcAutoFixed: Boolean,
)

/** Result of looking at one QR text. */
internal sealed interface PayloadCheck {
    class Slip(
        val slip: ParsedSlip,
    ) : PayloadCheck

    object CrcInvalid : PayloadCheck

    object NotSlip : PayloadCheck
}

internal object SlipPayloads {
    private const val TRUEMONEY_BANK = "truemoney"

    fun check(payload: String): PayloadCheck {
        parse(payload, autoFix = false)?.let { return PayloadCheck.Slip(it) }
        parse(payload, autoFix = true)?.let { return PayloadCheck.Slip(it.withAutoFixed()) }
        return if (looksLikeSlip(payload)) PayloadCheck.CrcInvalid else PayloadCheck.NotSlip
    }

    fun isSlip(payload: String): Boolean = check(payload) is PayloadCheck.Slip

    private fun ParsedSlip.withAutoFixed() = ParsedSlip(type, sendingBank, transRef, crcAutoFixed = true)

    private fun parse(
        payload: String,
        autoFix: Boolean,
    ): ParsedSlip? {
        SlipVerify.parse(payload, autoFix)?.let {
            return ParsedSlip(SlipType.BOT_STANDARD, it.sendingBank, it.transRef, false)
        }
        SlipVerify.parseTrueMoney(payload, autoFix)?.let {
            return ParsedSlip(SlipType.TRUEMONEY, TRUEMONEY_BANK, it.transactionId, false)
        }
        return null
    }

    /** Right structure for a slip QR but the strict parse failed, so the checksum is the problem. */
    private fun looksLikeSlip(payload: String): Boolean {
        val qr = PromptParse.parse(payload, strict = false) ?: return false
        val marker = qr.value("00", "00")
        return marker == "000001" || (marker == "01" && qr.value("00", "01") == "01")
    }
}

internal object ReceiverMatcher {
    private const val MIN_KNOWN_DIGITS = 3
    private val TITLES = listOf("นางสาว", "น.ส.", "นาย", "นาง", "miss", "mrs.", "mrs", "mr.", "mr", "ms.", "ms")

    /**
     * Compares the expected suffix against a possibly masked account (`xxx-x-x1234-x`). The suffix may end anywhere
     * in the visible part, because banks append masked check digits. Every visible digit must agree and at least
     * [MIN_KNOWN_DIGITS] must be visible, so an all-masked account never matches.
     */
    fun accountMatches(
        expectedSuffix: String,
        actual: String?,
    ): Boolean {
        if (actual == null) return false
        val want = expectedSuffix.filter { it.isDigit() }
        val have = actual.filter { it.isDigit() || it == 'x' || it == 'X' || it == '*' }
        if (want.isEmpty() || have.length < want.length) return false
        // The suffix must end the visible digits: only masked check characters may follow it.
        val lastEnd = have.length
        val firstEnd = (have.length - have.reversed().takeWhile { !it.isDigit() }.length).coerceAtLeast(want.length)
        return (firstEnd..lastEnd).any { end -> windowMatches(want, have.substring(end - want.length, end)) }
    }

    /** Every visible digit of [window] equals the wanted digit at that position, and enough digits are visible. */
    private fun windowMatches(
        want: String,
        window: String,
    ): Boolean {
        var known = 0
        for (i in want.indices) {
            val c = window[i]
            if (!c.isDigit()) continue
            if (c != want[i]) return false
            known++
        }
        return known >= minOf(MIN_KNOWN_DIGITS, want.length)
    }

    /** Case-insensitive containment after dropping titles, dots and spaces; a partial provider name also matches. */
    fun nameMatches(
        expected: String,
        actual: String?,
    ): Boolean {
        if (actual == null) return false
        val want = normalize(expected)
        val have = normalize(actual)
        if (want.isEmpty() || have.isEmpty()) return false
        return have.contains(want) || (have.length >= 3 && want.contains(have))
    }

    private fun normalize(value: String): String {
        var s = value.trim().lowercase()
        for (t in TITLES) {
            if (s.startsWith(t)) {
                s = s.removePrefix(t)
                break
            }
        }
        return s.filter { it.isLetterOrDigit() }
    }

    fun matches(
        rule: ReceiverRule,
        name: String?,
        account: String?,
    ): Boolean =
        (rule.accountSuffix == null || accountMatches(rule.accountSuffix, account)) &&
            (rule.nameContains == null || nameMatches(rule.nameContains, name))
}

internal object DuplicateKeys {
    /** Hash of (slipType, sendingBank, transRef): stable, and keeps references out of Redis keys and logs. */
    fun of(slip: ParsedSlip): String {
        val raw = "${slip.type}|${slip.sendingBank}|${slip.transRef}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return "slipverify:" + digest.joinToString("") { "%02x".format(it) }
    }
}
