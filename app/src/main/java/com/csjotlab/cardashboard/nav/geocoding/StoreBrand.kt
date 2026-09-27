package com.csjotlab.cardashboard.nav.geocoding

/**
 * Store chains the map draws as a recognisable badge instead of a plain pin. A badge is the chain's
 * colours and short name drawn in code — not the chain's logo artwork.
 *
 * [aliases] are matched against the OSM `brand` tag first and the place name second, in English and
 * Japanese, ignoring case, spaces and punctuation.
 */
enum class StoreBrand(val badgeText: String, private vararg val aliases: String) {
    SevenEleven("7-ELEVEN", "7-Eleven", "Seven-Eleven", "セブン-イレブン", "セブンイレブン"),
    FamilyMart("FamilyMart", "FamilyMart", "ファミリーマート", "ファミマ"),
    Lawson("LAWSON", "Lawson", "ローソン"),
    Ministop("MINISTOP", "Ministop", "ミニストップ"),
    DailyYamazaki("DAILY", "Daily Yamazaki", "デイリーヤマザキ", "ヤマザキデイリーストアー"),
    Seicomart("Seicomart", "Seicomart", "セイコーマート"),
    ;

    private val keys by lazy { aliases.map(::normalize) }

    companion object {
        /** The chain named by [brand], else by [name]; null for an independent store or unknown chain. */
        fun match(brand: String?, name: String?): StoreBrand? =
            listOfNotNull(brand, name).firstNotNullOfOrNull { text ->
                val key = normalize(text)
                if (key.isEmpty()) null else entries.firstOrNull { chain -> chain.keys.any { key.contains(it) } }
            }

        private fun normalize(text: String): String =
            text.lowercase().filter { it.isLetterOrDigit() || it == 'ー' }
    }
}
