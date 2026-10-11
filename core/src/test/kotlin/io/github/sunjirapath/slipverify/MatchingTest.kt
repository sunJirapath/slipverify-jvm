package io.github.sunjirapath.slipverify

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.AlphaChars
import net.jqwik.api.constraints.NumericChars
import net.jqwik.api.constraints.StringLength
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchingTest {
    @Test
    fun `account suffix against masked formats`() {
        val cases =
            listOf(
                Triple("1234", "xxx-x-x1234-x", true),
                Triple("1234", "XXX-X-X1234-X", true),
                Triple("1234", "xxx-xxx123-4", true),
                Triple("1234", "123-4-56789-0", false),
                Triple("6789", "123-4-56789-0", false),
                Triple("7890", "123-4-56789-0", true),
                Triple("234", "xxx-x-x1234-x", true),
                Triple("9999", "xxx-x-x1234-x", false),
                Triple("1234", "xxx-x-xxxx4-x", false),
                Triple("1234", "xxx-x-xxxxx-x", false),
                Triple("1234", "xxx", false),
                Triple("12-34", "xxx-x-x1234-x", true),
                Triple("1234", "x*x*x*1234", true),
            )
        for ((want, have, expected) in cases) {
            assertEquals(expected, ReceiverMatcher.accountMatches(want, have), "$want vs $have")
        }
        assertFalse(ReceiverMatcher.accountMatches("1234", null))
        assertFalse(ReceiverMatcher.accountMatches("abc", "1234"))
    }

    @Test
    fun `names in thai and english with titles and partials`() {
        val cases =
            listOf(
                Triple("สมชาย", "นาย สมชาย ใจดี", true),
                Triple("นายสมชาย", "สมชาย ใจดี", true),
                Triple("สมชาย ใจดี", "นาย สมชาย ใ", true),
                Triple("สมชาย", "นางสาว สมหญิง รักดี", false),
                Triple("somchai", "MR. SOMCHAI JAIDEE", true),
                Triple("Somchai Jaidee", "Mr Somchai J", true),
                Triple("jaidee", "Somchai Jaidee", true),
                Triple("สมชายใจดี", "สมชา", true),
                Triple("ใจดี", "ส", false),
            )
        for ((want, have, expected) in cases) {
            assertEquals(expected, ReceiverMatcher.nameMatches(want, have), "$want vs $have")
        }
        assertFalse(ReceiverMatcher.nameMatches("x", null))
        assertFalse(ReceiverMatcher.nameMatches("นาย", "นาย"))
    }

    @Test
    fun `combined rule needs both parts`() {
        val rule = ReceiverRule("1234", "สมชาย")
        assertTrue(ReceiverMatcher.matches(rule, "นาย สมชาย", "xxx-x-x1234-x"))
        assertFalse(ReceiverMatcher.matches(rule, "นาย สมชาย", "xxx-x-x9999-x"))
        assertFalse(ReceiverMatcher.matches(rule, "นาย สมหมาย", "xxx-x-x1234-x"))
    }

    @Test
    fun `masking`() {
        assertEquals("xxxxxx6789", Masking.account("0123456789"))
        assertEquals("xxx-x-x1234-x", Masking.account("xxx-x-x1234-x"))
        assertEquals("xxx-x-xx789-0", Masking.account("123-4-56789-0"))
        assertEquals("xxx", Masking.account("123"))
        assertEquals("นา***", Masking.name("นายสมชาย"))
        assertEquals("***", Masking.name("ab"))
        assertNull(Masking.name(" "))
        assertNull(Masking.account(null))
        assertEquals("So*** (xxxx5678)", Masking.party("Somchai", "12345678"))
        assertEquals("So***", Masking.party("Somchai", null))
        assertEquals("xxxx5678", Masking.party(null, "12345678"))
        assertNull(Masking.party(null, null))
    }

    @Test
    fun `masking never keeps more than four digits`() {
        val masked = Masking.account("123-4-56789-0")!!
        assertEquals(4, masked.count { it.isDigit() })
    }

    @Property
    fun `a rule built from the real account always matches its masked form`(
        @ForAll @NumericChars @StringLength(min = 10, max = 12) account: String,
    ): Boolean {
        val masked = "xxx-x-x${account.takeLast(4)}-x"
        return ReceiverMatcher.accountMatches(account.takeLast(4), masked)
    }

    @Property
    fun `masked account never exposes more than four digits`(
        @ForAll @NumericChars @StringLength(min = 5, max = 16) account: String,
    ): Boolean = (Masking.account(account) ?: "").count { it.isDigit() } <= 4

    @Property
    fun `name rules never match an unrelated string`(
        @ForAll @AlphaChars @StringLength(min = 4, max = 12) a: String,
    ): Boolean = !ReceiverMatcher.nameMatches(a, "zzzzzzzz".takeIf { !a.lowercase().contains("z") } ?: "qqqqqqqq")
}
