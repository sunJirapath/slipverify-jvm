package io.github.sunjirapath.slipverify.provider

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountRegressionTest {
    @Test
    fun `malformed comma grouping must not silently change monetary value`() {
        for (bad in listOf("1,50", "15,,0", "150,", ",150", "1,234,56", "1234,567", "1,234.5,0", "1,234e2")) {
            assertNull(Json.decimal(Json.parseObject("""{"amount":"$bad"}"""), "amount"), bad)
        }
    }

    @Test
    fun `plain exact decimals and correctly grouped numeric strings are supported`() {
        for ((raw, expected) in listOf("150.00" to "150.00", " 1,234.50 " to "1234.50", "1,234,567.89" to "1234567.89", "-1,234.50" to "-1234.50", "1.5e2" to "1.5e2")) {
            assertEquals(BigDecimal(expected), Json.decimal(Json.parseObject("""{"amount":"$raw"}"""), "amount"))
        }
    }
}
