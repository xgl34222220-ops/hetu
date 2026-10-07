package io.github.xgl34222220.hetu.home

import java.util.Locale

/**
 * Region of a node, read from its name. Used to pick the flag next to nodes and exit addresses.
 *
 * Order of evidence: a flag emoji in the name (airports almost always prefix one), then the
 * longest matching keyword. Latin keywords need a word boundary so “US” never matches “PLUS”,
 * and codes of up to three letters must be written in capitals so “in” or “us” in a sentence
 * is not read as a country.
 */
internal object HomeRegions {
    private val table = listOf(
        "HK" to listOf("香港", "港", "HK", "HKG", "HONG KONG", "HONGKONG"),
        "TW" to listOf("台湾", "台灣", "台", "TW", "TWN", "TAIWAN", "台北", "新北"),
        "JP" to listOf("日本", "日", "JP", "JPN", "JAPAN", "东京", "東京", "大阪", "TOKYO", "OSAKA"),
        "SG" to listOf("新加坡", "狮城", "獅城", "SG", "SGP", "SINGAPORE"),
        "KR" to listOf("韩国", "韓國", "韩", "KR", "KOR", "KOREA", "首尔", "首爾", "SEOUL"),
        "US" to listOf("美国", "美國", "美", "US", "USA", "UNITED STATES", "AMERICA", "洛杉矶", "硅谷", "西雅图", "纽约", "圣何塞", "LOS ANGELES", "SEATTLE", "NEW YORK", "SAN JOSE"),
        "GB" to listOf("英国", "英國", "英", "GB", "UK", "GBR", "UNITED KINGDOM", "BRITAIN", "伦敦", "倫敦", "LONDON"),
        "DE" to listOf("德国", "德國", "德", "DE", "DEU", "GERMANY", "法兰克福", "FRANKFURT"),
        "FR" to listOf("法国", "法國", "法", "FR", "FRA", "FRANCE", "巴黎", "PARIS"),
        "AU" to listOf("澳大利亚", "澳大利亞", "澳洲", "澳", "AU", "AUS", "AUSTRALIA", "悉尼", "SYDNEY"),
        "CA" to listOf("加拿大", "CA", "CAN", "CANADA", "多伦多", "温哥华", "TORONTO", "VANCOUVER"),
        "NL" to listOf("荷兰", "荷蘭", "NL", "NLD", "NETHERLANDS", "阿姆斯特丹", "AMSTERDAM"),
        "RU" to listOf("俄罗斯", "俄羅斯", "俄", "RU", "RUS", "RUSSIA", "莫斯科", "MOSCOW"),
        "IN" to listOf("印度", "IN", "IND", "INDIA", "孟买", "MUMBAI"),
        "TR" to listOf("土耳其", "TR", "TUR", "TURKEY", "TURKIYE", "伊斯坦布尔", "ISTANBUL"),
        "IT" to listOf("意大利", "義大利", "ITA", "ITALY", "米兰", "MILAN"),
        "ES" to listOf("西班牙", "ES", "ESP", "SPAIN", "马德里", "MADRID"),
        "CH" to listOf("瑞士", "CH", "CHE", "SWITZERLAND", "苏黎世", "ZURICH"),
        "SE" to listOf("瑞典", "SE", "SWE", "SWEDEN", "斯德哥尔摩", "STOCKHOLM"),
        "NO" to listOf("挪威", "NOR", "NORWAY", "奥斯陆", "OSLO"),
        "FI" to listOf("芬兰", "芬蘭", "FI", "FIN", "FINLAND", "赫尔辛基", "HELSINKI"),
        "DK" to listOf("丹麦", "丹麥", "DK", "DNK", "DENMARK", "哥本哈根", "COPENHAGEN"),
        "PL" to listOf("波兰", "波蘭", "PL", "POL", "POLAND", "华沙", "WARSAW"),
        "UA" to listOf("乌克兰", "烏克蘭", "UA", "UKR", "UKRAINE", "基辅", "KYIV"),
        "IE" to listOf("爱尔兰", "愛爾蘭", "IE", "IRL", "IRELAND", "都柏林", "DUBLIN"),
        "AT" to listOf("奥地利", "奧地利", "AUT", "AUSTRIA", "维也纳", "VIENNA"),
        "BE" to listOf("比利时", "比利時", "BEL", "BELGIUM", "布鲁塞尔", "BRUSSELS"),
        "HU" to listOf("匈牙利", "HU", "HUN", "HUNGARY", "布达佩斯", "BUDAPEST"),
        "LU" to listOf("卢森堡", "盧森堡", "LU", "LUX", "LUXEMBOURG"),
        "ID" to listOf("印度尼西亚", "印尼", "IDN", "INDONESIA", "雅加达", "JAKARTA"),
        "TH" to listOf("泰国", "泰國", "TH", "THA", "THAILAND", "曼谷", "BANGKOK"),
        "VN" to listOf("越南", "VN", "VNM", "VIETNAM", "胡志明", "河内"),
        "BR" to listOf("巴西", "BR", "BRA", "BRAZIL", "圣保罗", "SAO PAULO"),
        "AR" to listOf("阿根廷", "AR", "ARG", "ARGENTINA"),
        "AE" to listOf("阿联酋", "阿聯酋", "迪拜", "杜拜", "AE", "ARE", "UAE", "DUBAI"),
        "CN" to listOf("中国", "中國", "回国", "CN", "CHN", "CHINA", "上海", "北京", "广州", "深圳", "杭州"),
    )

    private val cache = object : LinkedHashMap<String, String>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 512
    }

    /** ISO 3166 alpha-2 code, or an empty string when the name gives nothing away. */
    fun codeOf(name: String): String {
        if (name.isBlank()) return ""
        synchronized(cache) { cache[name]?.let { return it } }
        val code = fromEmoji(name) ?: fromKeywords(name)
        synchronized(cache) { cache[name] = code }
        return code
    }

    /**
     * [name] without a leading flag emoji. Used where a drawn flag already sits next to the text,
     * so “🇭🇰 香港 01” does not show two flags. A name that is only a flag is returned as it is.
     */
    fun withoutFlag(name: String): String {
        val trimmed = name.trimStart()
        if (trimmed.length < 4) return name
        val first = trimmed.codePointAt(0)
        if (first - 0x1F1E6 !in 0..25) return name
        val second = trimmed.codePointAt(Character.charCount(first))
        if (second - 0x1F1E6 !in 0..25) return name
        val rest = trimmed.substring(Character.charCount(first) + Character.charCount(second)).trimStart(' ', '|', '-', '·', '\u3000')
        return rest.ifEmpty { name }
    }

    /** Two regional-indicator symbols in a row spell a country code: 🇭🇰 → HK. */
    private fun fromEmoji(name: String): String? {
        var index = 0
        var first = -1
        while (index < name.length) {
            val point = name.codePointAt(index)
            val letter = point - 0x1F1E6
            if (letter in 0..25) {
                if (first >= 0) return "" + ('A' + first) + ('A' + letter)
                first = letter
            } else first = -1
            index += Character.charCount(point)
        }
        return null
    }

    private fun fromKeywords(name: String): String {
        val upper = name.uppercase(Locale.ROOT)
        var best = ""
        var bestLength = 0
        for ((code, keys) in table) for (key in keys) {
            if (key.length <= bestLength) continue
            val latin = key.all { it in 'A'..'Z' || it == ' ' }
            val hit = when {
                !latin -> name.contains(key)
                key.length <= 3 -> containsWord(name, key)
                else -> containsWord(upper, key)
            }
            if (hit) { best = code; bestLength = key.length }
        }
        return best
    }

    /** [key] occurs in [text] with no Latin letter directly before or after it. */
    private fun containsWord(text: String, key: String): Boolean {
        var from = 0
        while (true) {
            val at = text.indexOf(key, from)
            if (at < 0) return false
            val before = if (at == 0) ' ' else text[at - 1]
            val after = if (at + key.length >= text.length) ' ' else text[at + key.length]
            if (!before.isLatinLetter() && !after.isLatinLetter()) return true
            from = at + 1
        }
    }

    private fun Char.isLatinLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'
}
