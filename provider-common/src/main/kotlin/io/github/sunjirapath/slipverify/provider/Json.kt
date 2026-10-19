package io.github.sunjirapath.slipverify.provider

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Null-safe readers over Gson's tree API: a missing or mistyped field is `null`, never an exception. */
public object Json {
    private val GROUPED_DECIMAL = Regex("[+-]?[0-9]{1,3}(,[0-9]{3})+(\\.[0-9]+)?")

    /** The object in [text], or `null` if it is not valid JSON or not an object. */
    @JvmStatic
    public fun parseObject(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        return try {
            JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
        } catch (
            @Suppress("SwallowedException") e: JsonParseException,
        ) {
            null
        }
    }

    /** Follows [path] through nested objects; `null` if any step is missing or JSON null. */
    @JvmStatic
    public fun get(
        root: JsonObject?,
        vararg path: String,
    ): JsonElement? {
        var current: JsonElement? = root
        for (key in path) {
            val obj = current?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            current = obj.get(key)
        }
        return current?.takeUnless { it.isJsonNull }
    }

    @JvmStatic
    public fun obj(
        root: JsonObject?,
        vararg path: String,
    ): JsonObject? = get(root, *path)?.takeIf { it.isJsonObject }?.asJsonObject

    @JvmStatic
    public fun string(
        root: JsonObject?,
        vararg path: String,
    ): String? = primitive(get(root, *path))?.asString?.takeIf { it.isNotBlank() }

    @JvmStatic
    public fun bool(
        root: JsonObject?,
        vararg path: String,
    ): Boolean? = primitive(get(root, *path))?.takeIf { it.isBoolean }?.asBoolean

    /** Accepts JSON numbers and numeric strings (`"150.00"`, `"1,234.50"`), keeping the exact decimal. */
    @JvmStatic
    public fun decimal(
        root: JsonObject?,
        vararg path: String,
    ): BigDecimal? {
        val p = primitive(get(root, *path)) ?: return null
        return try {
            when {
                p.isNumber -> {
                    p.asBigDecimal
                }

                p.isString -> {
                    val value = p.asString.trim()
                    if (',' in value && !GROUPED_DECIMAL.matches(value)) return null
                    BigDecimal(value.replace(",", ""))
                }

                else -> {
                    null
                }
            }
        } catch (
            @Suppress("SwallowedException") e: NumberFormatException,
        ) {
            null
        }
    }

    private fun primitive(e: JsonElement?): JsonPrimitive? = e?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
}

/** Parses the timestamps providers return. Zone-less values are Thai local time. */
public object ThaiTime {
    private val BANGKOK: ZoneId = ZoneId.of("Asia/Bangkok")

    /** ISO instant, ISO offset date-time, or zone-less `yyyy-MM-dd[T ]HH:mm[:ss]` read as Asia/Bangkok. */
    @JvmStatic
    public fun parse(text: String?): Instant? {
        val s = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return attempt { Instant.parse(s) }
            ?: attempt { OffsetDateTime.parse(s).toInstant() }
            ?: attempt { LocalDateTime.parse(s.replace(' ', 'T')).atZone(BANGKOK).toInstant() }
    }

    private inline fun attempt(block: () -> Instant): Instant? =
        try {
            block()
        } catch (
            @Suppress("SwallowedException") e: DateTimeParseException,
        ) {
            null
        }
}
