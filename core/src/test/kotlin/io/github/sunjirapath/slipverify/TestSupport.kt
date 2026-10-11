package io.github.sunjirapath.slipverify

import io.github.sunjirapath.promptparse.SlipVerify
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

object Slips {
    const val BANK = "004"

    fun standard(ref: String = "014091108414AOR00011"): String = SlipVerify.build(BANK, ref)

    fun trueMoney(id: String = "51005913123456"): String = SlipVerify.buildTrueMoney("P2P", id, "05102026")

    /** A standard slip whose CRC starts with [zeros] zero digits and has them dropped, as some bank apps do. */
    fun truncatedCrc(zeros: Int): String {
        for (i in 0 until 200_000) {
            val full = standard("REF$i")
            val crc = full.takeLast(4)
            if (crc.take(zeros).all { it == '0' } && crc[zeros] != '0') return full.dropLast(4) + crc.drop(zeros)
        }
        error("no payload with $zeros leading CRC zeros found")
    }

    /** A valid slip with one payload character changed so the CRC no longer matches. */
    fun badCrc(): String {
        val s = standard()
        val flipped = if (s[20] == 'A') 'B' else 'A'
        return s.substring(0, 20) + flipped + s.substring(21)
    }
}

/** Scripted provider: returns [outcome] and counts calls. */
class FakeProvider(
    override val name: String,
    private val types: Set<SlipType> = SlipType.values().toSet(),
    var outcome: () -> ProviderOutcome,
) : SlipProvider {
    val calls = AtomicInteger()
    var lastQuery: ProviderQuery? = null

    override fun supports(type: SlipType) = type in types

    override fun verify(query: ProviderQuery): ProviderOutcome {
        calls.incrementAndGet()
        lastQuery = query
        return outcome()
    }
}

fun verified(
    amount: String = "150.00",
    time: Instant? = Instant.parse("2026-10-05T03:00:00Z"),
    receiverName: String? = "นาย สมชาย ใจดี",
    receiverAccount: String? = "xxx-x-x1234-x",
    duplicate: Boolean = false,
) = ProviderOutcome.verified(
    VerifiedSlip(
        amount = BigDecimal(amount),
        transferTime = time,
        senderName = "นางสาว สมหญิง รักดี",
        senderAccount = "xxx-x-x5678-x",
        receiverName = receiverName,
        receiverAccount = receiverAccount,
        duplicateAtProvider = duplicate,
    ),
)
