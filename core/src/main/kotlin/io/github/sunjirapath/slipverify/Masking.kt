package io.github.sunjirapath.slipverify

/**
 * Masks personal data (PDPA) for logs, `toString()` and results. Never throws and accepts `null`.
 */
public object Masking {
    private const val KEEP_ACCOUNT_DIGITS = 4
    private const val KEEP_NAME_CHARS = 2

    /** Keeps the last four digits: `0123456789` becomes `xxxxxx6789`. Short values are fully masked. */
    @JvmStatic
    public fun account(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val visible = value.count { it.isLetterOrDigit() && !it.isMasked() }
        var seen = 0
        // Short values are fully masked, unless a bank already masked them and left a short tail.
        val keepTail = visible > KEEP_ACCOUNT_DIGITS || value.any { it.isMasked() }
        val out = StringBuilder(value.length)
        for (c in value) {
            if (!c.isLetterOrDigit() || c.isMasked()) {
                out.append(c)
            } else {
                out.append(if (keepTail && seen >= visible - KEEP_ACCOUNT_DIGITS) c else 'x')
                seen++
            }
        }
        return out.toString()
    }

    /** Keeps the first two characters: `Somchai` becomes `So***`. */
    @JvmStatic
    public fun name(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        return if (trimmed.length <= KEEP_NAME_CHARS) "***" else trimmed.take(KEEP_NAME_CHARS) + "***"
    }

    /** `Name (account)` with both parts masked, or `null` when neither is known. */
    @JvmStatic
    public fun party(
        name: String?,
        account: String?,
    ): String? {
        val n = name(name)
        val a = account(account)
        return when {
            n != null && a != null -> "$n ($a)"
            else -> n ?: a
        }
    }

    private fun Char.isMasked(): Boolean = this == 'x' || this == 'X' || this == '*'
}
