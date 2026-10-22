package com.blaze.assistant.browser

/**
 * Countries VPN providers have servers in, with what location masking pretends to be there:
 * the capital's coordinates, its time zone and the main language.
 */
object Countries {
    data class Country(val code: String, val name: String, val lat: Double, val lon: Double, val tz: String, val lang: String) {
        val flag: String get() = flag(code)
    }

    fun flag(code: String): String = if (code.length != 2) "\uD83C\uDF10" else
        code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

    val all: List<Country> = listOf(
        Country("AE", "United Arab Emirates", 24.4539, 54.3773, "Asia/Dubai", "ar-AE"),
        Country("AL", "Albania", 41.3275, 19.8187, "Europe/Tirane", "sq-AL"),
        Country("AR", "Argentina", -34.6037, -58.3816, "America/Argentina/Buenos_Aires", "es-AR"),
        Country("AT", "Austria", 48.2082, 16.3738, "Europe/Vienna", "de-AT"),
        Country("AU", "Australia", -33.8688, 151.2093, "Australia/Sydney", "en-AU"),
        Country("BE", "Belgium", 50.8503, 4.3517, "Europe/Brussels", "nl-BE"),
        Country("BG", "Bulgaria", 42.6977, 23.3219, "Europe/Sofia", "bg-BG"),
        Country("BR", "Brazil", -23.5505, -46.6333, "America/Sao_Paulo", "pt-BR"),
        Country("BW", "Botswana", -24.6282, 25.9231, "Africa/Gaborone", "en-BW"),
        Country("CA", "Canada", 43.6532, -79.3832, "America/Toronto", "en-CA"),
        Country("CH", "Switzerland", 47.3769, 8.5417, "Europe/Zurich", "de-CH"),
        Country("CL", "Chile", -33.4489, -70.6693, "America/Santiago", "es-CL"),
        Country("CO", "Colombia", 4.7110, -74.0721, "America/Bogota", "es-CO"),
        Country("CY", "Cyprus", 35.1856, 33.3823, "Asia/Nicosia", "el-CY"),
        Country("CZ", "Czechia", 50.0755, 14.4378, "Europe/Prague", "cs-CZ"),
        Country("DE", "Germany", 50.1109, 8.6821, "Europe/Berlin", "de-DE"),
        Country("DK", "Denmark", 55.6761, 12.5683, "Europe/Copenhagen", "da-DK"),
        Country("EE", "Estonia", 59.4370, 24.7536, "Europe/Tallinn", "et-EE"),
        Country("EG", "Egypt", 30.0444, 31.2357, "Africa/Cairo", "ar-EG"),
        Country("ES", "Spain", 40.4168, -3.7038, "Europe/Madrid", "es-ES"),
        Country("FI", "Finland", 60.1699, 24.9384, "Europe/Helsinki", "fi-FI"),
        Country("FR", "France", 48.8566, 2.3522, "Europe/Paris", "fr-FR"),
        Country("GB", "United Kingdom", 51.5074, -0.1278, "Europe/London", "en-GB"),
        Country("GH", "Ghana", 5.6037, -0.1870, "Africa/Accra", "en-GH"),
        Country("GR", "Greece", 37.9838, 23.7275, "Europe/Athens", "el-GR"),
        Country("HK", "Hong Kong", 22.3193, 114.1694, "Asia/Hong_Kong", "zh-HK"),
        Country("HR", "Croatia", 45.8150, 15.9819, "Europe/Zagreb", "hr-HR"),
        Country("HU", "Hungary", 47.4979, 19.0402, "Europe/Budapest", "hu-HU"),
        Country("ID", "Indonesia", -6.2088, 106.8456, "Asia/Jakarta", "id-ID"),
        Country("IE", "Ireland", 53.3498, -6.2603, "Europe/Dublin", "en-IE"),
        Country("IL", "Israel", 32.0853, 34.7818, "Asia/Jerusalem", "he-IL"),
        Country("IN", "India", 19.0760, 72.8777, "Asia/Kolkata", "en-IN"),
        Country("IS", "Iceland", 64.1466, -21.9426, "Atlantic/Reykjavik", "is-IS"),
        Country("IT", "Italy", 45.4642, 9.1900, "Europe/Rome", "it-IT"),
        Country("JP", "Japan", 35.6762, 139.6503, "Asia/Tokyo", "ja-JP"),
        Country("KE", "Kenya", -1.2921, 36.8219, "Africa/Nairobi", "en-KE"),
        Country("KR", "South Korea", 37.5665, 126.9780, "Asia/Seoul", "ko-KR"),
        Country("LT", "Lithuania", 54.6872, 25.2797, "Europe/Vilnius", "lt-LT"),
        Country("LU", "Luxembourg", 49.6116, 6.1319, "Europe/Luxembourg", "fr-LU"),
        Country("LV", "Latvia", 56.9496, 24.1052, "Europe/Riga", "lv-LV"),
        Country("MD", "Moldova", 47.0105, 28.8638, "Europe/Chisinau", "ro-MD"),
        Country("MX", "Mexico", 19.4326, -99.1332, "America/Mexico_City", "es-MX"),
        Country("MY", "Malaysia", 3.1390, 101.6869, "Asia/Kuala_Lumpur", "ms-MY"),
        Country("NG", "Nigeria", 6.5244, 3.3792, "Africa/Lagos", "en-NG"),
        Country("NL", "Netherlands", 52.3676, 4.9041, "Europe/Amsterdam", "nl-NL"),
        Country("NO", "Norway", 59.9139, 10.7522, "Europe/Oslo", "nb-NO"),
        Country("NZ", "New Zealand", -36.8485, 174.7633, "Pacific/Auckland", "en-NZ"),
        Country("PE", "Peru", -12.0464, -77.0428, "America/Lima", "es-PE"),
        Country("PH", "Philippines", 14.5995, 120.9842, "Asia/Manila", "en-PH"),
        Country("PL", "Poland", 52.2297, 21.0122, "Europe/Warsaw", "pl-PL"),
        Country("PT", "Portugal", 38.7223, -9.1393, "Europe/Lisbon", "pt-PT"),
        Country("RO", "Romania", 44.4268, 26.1025, "Europe/Bucharest", "ro-RO"),
        Country("RS", "Serbia", 44.7866, 20.4489, "Europe/Belgrade", "sr-RS"),
        Country("SA", "Saudi Arabia", 24.7136, 46.6753, "Asia/Riyadh", "ar-SA"),
        Country("SE", "Sweden", 59.3293, 18.0686, "Europe/Stockholm", "sv-SE"),
        Country("SG", "Singapore", 1.3521, 103.8198, "Asia/Singapore", "en-SG"),
        Country("SI", "Slovenia", 46.0569, 14.5058, "Europe/Ljubljana", "sl-SI"),
        Country("SK", "Slovakia", 48.1486, 17.1077, "Europe/Bratislava", "sk-SK"),
        Country("TH", "Thailand", 13.7563, 100.5018, "Asia/Bangkok", "th-TH"),
        Country("TR", "Turkey", 41.0082, 28.9784, "Europe/Istanbul", "tr-TR"),
        Country("TW", "Taiwan", 25.0330, 121.5654, "Asia/Taipei", "zh-TW"),
        Country("UA", "Ukraine", 50.4501, 30.5234, "Europe/Kyiv", "uk-UA"),
        Country("US", "United States", 40.7128, -74.0060, "America/New_York", "en-US"),
        Country("VN", "Vietnam", 10.8231, 106.6297, "Asia/Ho_Chi_Minh", "vi-VN"),
        Country("ZA", "South Africa", -26.2041, 28.0473, "Africa/Johannesburg", "en-ZA"),
        Country("ZM", "Zambia", -15.3875, 28.3228, "Africa/Lusaka", "en-ZM"),
    )

    private val map = all.associateBy { it.code }
    fun byCode(code: String?): Country? = code?.let { map[it.uppercase()] }

    /** "Germany", "de", "DE", "germany" → Germany. */
    fun find(q: String): Country? {
        val s = q.trim()
        if (s.isEmpty()) return null
        byCode(if (s.equals("uk", true)) "GB" else s)?.let { if (s.length == 2) return it }
        return all.firstOrNull { it.name.equals(s, true) } ?: all.firstOrNull { it.name.startsWith(s, true) }
            ?: when (s.lowercase()) { "usa", "america", "united states of america" -> map["US"]; "england", "britain" -> map["GB"]; "holland" -> map["NL"]; else -> null }
    }
}
