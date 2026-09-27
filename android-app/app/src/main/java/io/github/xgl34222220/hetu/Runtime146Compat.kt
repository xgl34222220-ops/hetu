package io.github.xgl34222220.hetu

internal fun countryEmoji(code: String): String {
    val upper = code.trim().uppercase(java.util.Locale.ROOT)
    if (upper.length != 2 || upper.any { it !in 'A'..'Z' }) return ""
    return String(Character.toChars(0x1F1E6 + upper[0].code - 'A'.code)) +
        String(Character.toChars(0x1F1E6 + upper[1].code - 'A'.code))
}
