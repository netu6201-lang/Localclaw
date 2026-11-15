package com.example.search

import java.util.Locale

/**
 * Bidirectional Hindi (Devanagari) <-> Hinglish / English transliteration, phonetic normalization,
 * and cross-script query expansion engine.
 *
 * Examples:
 * - "भूरी" or "भुरी" -> ["भूरी", "भुरी", "bhuri", "bhoori", ...]
 * - "bhuri" -> ["bhuri", "bhoori", "भूरी", "भुरी"] + phonetic skeleton "bhuri"
 * - "बिजली बिल" -> expands to bijli, bijali, electricity, power, bill, invoice
 */
object HindiTransliterator {

    private val independentVowels = mapOf(
        'अ' to "a",
        'आ' to "a",
        'इ' to "i",
        'ई' to "i",
        'उ' to "u",
        'ऊ' to "u",
        'ऋ' to "ri",
        'ऌ' to "lri",
        'ए' to "e",
        'ऐ' to "ai",
        'ओ' to "o",
        'औ' to "au",
        'ऑ' to "o",
        'ॲ' to "a",
        'ऍ' to "e"
    )

    private val consonants: Map<Char, String> = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "ny",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n", 'ऩ' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ऱ' to "r", 'ल' to "l", 'ळ' to "l", 'ऴ' to "l",
        'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        // Precomposed Nukta consonants
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "gh", '\u095B' to "z",
        '\u095C' to "r", '\u095D' to "dh", '\u095E' to "f", '\u095F' to "y"
    )

    private val matras = mapOf(
        'ा' to "a",
        'ि' to "i",
        'ी' to "i",
        'ु' to "u",
        'ू' to "u",
        'ृ' to "ri",
        'ॄ' to "ri",
        'े' to "e",
        'ै' to "ai",
        'ो' to "o",
        'ौ' to "au",
        'ॉ' to "o",
        'ॅ' to "e"
    )

    private val devanagariDigits = mapOf(
        '०' to '0', '१' to '1', '२' to '2', '३' to '3', '४' to '4',
        '५' to '5', '६' to '6', '७' to '7', '८' to '8', '९' to '9'
    )

    private const val VIRAMA = '्'
    private const val NUKTA = '़'
    private const val ANUSVARA = 'ं'
    private const val CHANDRABINDU = 'ँ'
    private const val VISARGA = 'ः'

    /**
     * Common Hindi & Hinglish words mapped to English search concepts so non-English users
     * can type in Hindi and retrieve English-named/OCR files seamlessly.
     */
    private val bilingualConceptMap: Map<String, List<String>> = mapOf(
        // Bills, receipts, finance
        "बिजली" to listOf("electricity", "electric", "power", "bijli"),
        "bijli" to listOf("electricity", "electric", "power"),
        "बिल" to listOf("bill", "invoice", "statement"),
        "bil" to listOf("bill", "invoice"),
        "रसीद" to listOf("receipt", "invoice", "slip", "rasid", "raseed"),
        "rasid" to listOf("receipt", "invoice", "slip"),
        "raseed" to listOf("receipt", "invoice", "slip"),
        "भुगतान" to listOf("payment", "paid", "upi", "transaction"),
        "पैसे" to listOf("money", "payment", "rupees", "cash"),
        "पैसा" to listOf("money", "payment", "cash"),
        "बैंक" to listOf("bank", "statement", "account"),
        "खाता" to listOf("account", "ledger", "passbook", "khata"),
        "khata" to listOf("account", "ledger", "passbook"),
        "वेतन" to listOf("salary", "payslip", "income"),
        "सैलरी" to listOf("salary", "payslip"),
        "टैक्स" to listOf("tax", "itr", "gst"),
        "कर" to listOf("tax", "gst"),
        "बीमा" to listOf("insurance", "policy", "lic"),
        "किराया" to listOf("rent", "fare", "kiraya"),
        "kiraya" to listOf("rent", "fare"),
        "फीस" to listOf("fee", "fees", "tuition", "challan"),

        // Media & Files
        "फोटो" to listOf("photo", "image", "pic", "picture", "jpg", "png"),
        "फ़ोटो" to listOf("photo", "image", "pic", "picture"),
        "तस्वीर" to listOf("photo", "image", "picture", "tasveer"),
        "छवि" to listOf("image", "photo"),
        "वीडियो" to listOf("video", "mp4", "clip", "movie"),
        "गाना" to listOf("song", "audio", "music", "mp3"),
        "ऑडियो" to listOf("audio", "voice", "recording"),
        "स्क्रीनशॉट" to listOf("screenshot", "screen"),
        "दस्तावेज" to listOf("document", "doc", "pdf", "file"),
        "दस्तावेज़" to listOf("document", "doc", "pdf", "file"),
        "कागज" to listOf("paper", "document", "pdf"),
        "कागज़" to listOf("paper", "document", "pdf"),
        "कागजात" to listOf("documents", "papers", "pdf"),
        "किताब" to listOf("book", "ebook", "pdf", "kitab"),
        "kitab" to listOf("book", "pdf"),
        "नोट्स" to listOf("notes", "note", "txt"),
        "डाउनलोड" to listOf("download", "downloads"),
        "फाइल" to listOf("file", "document"),
        "फ़ाइल" to listOf("file", "document"),
        "फोल्डर" to listOf("folder", "directory"),

        // ID & Official Docs
        "आधार" to listOf("aadhaar", "aadhar", "uidai", "id"),
        "aadhar" to listOf("aadhaar", "uidai"),
        "पैन" to listOf("pan", "pancard", "income"),
        "पासपोर्ट" to listOf("passport", "visa"),
        "लाइसेंस" to listOf("license", "licence", "dl"),
        "प्रमाणपत्र" to listOf("certificate", "cert"),
        "सर्टिफिकेट" to listOf("certificate", "cert"),
        "पहचान" to listOf("identity", "id", "card"),
        "टिकट" to listOf("ticket", "pnr", "irctc", "boarding"),
        "पता" to listOf("address", "location"),

        // Education, Health, Misc
        "स्कूल" to listOf("school", "class", "skul", "skool"),
        "skul" to listOf("school"),
        "skool" to listOf("school"),
        "कॉलेज" to listOf("college", "university"),
        "परीक्षा" to listOf("exam", "test", "paper"),
        "रिजल्ट" to listOf("result", "marksheet", "score"),
        "परिणाम" to listOf("result", "marksheet"),
        "दवा" to listOf("medicine", "medical", "prescription", "pharmacy"),
        "दवाई" to listOf("medicine", "medical", "prescription"),
        "अस्पताल" to listOf("hospital", "clinic", "medical", "report"),
        "रिपोर्ट" to listOf("report", "lab", "test"),
        "घर" to listOf("home", "house", "property"),
        "जमीन" to listOf("land", "property", "registry"),
        "गाड़ी" to listOf("vehicle", "car", "bike", "rc"),
        "मोबाइल" to listOf("mobile", "phone", "recharge"),
        "नंबर" to listOf("number", "contact", "phone"),
        "नाम" to listOf("name"),
        "आज" to listOf("today"),
        "कल" to listOf("yesterday", "tomorrow"),
        "नया" to listOf("new", "latest"),
        "पुरानी" to listOf("old"),
        "पुराना" to listOf("old"),

        // Settings / Colors / Common words
        "भूरी" to listOf("bhuri", "bhoori", "brown"),
        "भुरी" to listOf("bhuri", "bhoori", "brown"),
        "bhuri" to listOf("भूरी", "भुरी", "bhoori"),
        "bhoori" to listOf("भूरी", "भुरी", "bhuri")
    )

    fun containsDevanagari(text: String): Boolean =
        text.any { it in '\u0900'..'\u097F' }

    /**
     * Transliterates Devanagari text into clean phonetic Roman (Latin) script.
     * Example: "भूरी" -> "bhuri", "भुरी" -> "bhuri", "बिजली" -> "bijali", "कमल" -> "kamal".
     */
    fun transliterateDevanagariToRoman(input: String): String {
        if (input.isBlank()) return ""
        val out = StringBuilder(input.length * 2)
        var i = 0
        val n = input.length

        while (i < n) {
            val ch = input[i]

            // Map Devanagari digits
            val digit = devanagariDigits[ch]
            if (digit != null) {
                out.append(digit)
                i++
                continue
            }

            // Independent vowel
            val indVowel = independentVowels[ch]
            if (indVowel != null) {
                out.append(indVowel)
                i++
                continue
            }

            // Check consonant (+ optional nukta)
            val nextCh = if (i + 1 < n) input[i + 1] else '\u0000'
            val combinedChar = if (nextCh == NUKTA) {
                when (ch) {
                    'क' -> '\u0958'
                    'ख' -> '\u0959'
                    'ग' -> '\u095A'
                    'ज' -> '\u095B'
                    'ड' -> '\u095C'
                    'ढ' -> '\u095D'
                    'फ' -> '\u095E'
                    'य' -> '\u095F'
                    else -> ch
                }
            } else {
                ch
            }
            val baseLen = if (nextCh == NUKTA) 2 else 1
            val consRoman = consonants[combinedChar]

            if (consRoman != null) {
                // Handle special conjuncts: क्ष, त्र, ज्ञ, श्र
                val afterBaseIdx = i + baseLen
                if (afterBaseIdx + 1 < n && input[afterBaseIdx] == VIRAMA) {
                    val second = input[afterBaseIdx + 1]
                    val conjunct = when {
                        ch == 'क' && second == 'ष' -> "ksh"
                        ch == 'त' && second == 'र' -> "tr"
                        ch == 'ज' && second == 'ञ' -> "gy"
                        ch == 'श' && second == 'र' -> "shr"
                        else -> null
                    }
                    if (conjunct != null) {
                        out.append(conjunct)
                        i = afterBaseIdx + 2
                        // Check vowel/virama after conjunct
                        val postConj = if (i < n) input[i] else '\u0000'
                        val postMatra = matras[postConj]
                        when {
                            postMatra != null -> {
                                out.append(postMatra)
                                i++
                            }
                            postConj == VIRAMA -> {
                                i++
                            }
                            i < n && isDevanagariWordChar(postConj) -> {
                                out.append('a')
                            }
                        }
                        continue
                    }
                }

                out.append(consRoman)
                i += baseLen

                val after = if (i < n) input[i] else '\u0000'
                val matraRoman = matras[after]
                when {
                    matraRoman != null -> {
                        out.append(matraRoman)
                        i++
                    }
                    after == VIRAMA -> {
                        // Virama suppresses inherent 'a'
                        i++
                    }
                    after == ANUSVARA || after == CHANDRABINDU -> {
                        // Implicit 'a' + nasal if not preceded by matra
                        out.append("an")
                        i++
                    }
                    after == VISARGA -> {
                        out.append("ah")
                        i++
                    }
                    i < n && isDevanagariWordChar(after) -> {
                        // Inherent schwa 'a' before next Devanagari consonant/character
                        out.append('a')
                    }
                    else -> {
                        // Word-final consonant drops inherent schwa in modern Hindi (e.g. बिल -> bil)
                    }
                }
                continue
            }

            when (ch) {
                ANUSVARA, CHANDRABINDU -> out.append('n')
                VISARGA -> out.append('h')
                VIRAMA, NUKTA -> Unit
                else -> out.append(ch.lowercaseChar())
            }
            i++
        }

        return out.toString()
    }

    private fun isDevanagariWordChar(ch: Char): Boolean {
        return ch in consonants || ch in independentVowels || ch in devanagariDigits
    }

    /**
     * Normalizes a token into a script-neutral phonetic skeleton so:
     * "भूरी" == "भुरी" == "bhuri" == "bhoori"
     * "बिजली" == "bijli" == "bijali" (via variant stripping)
     */
    fun toPhoneticSkeleton(raw: String): String {
        val roman = if (containsDevanagari(raw)) {
            transliterateDevanagariToRoman(raw)
        } else {
            raw.lowercase(Locale.US)
        }
        return roman
            .replace(Regex("[^a-z0-9]"), "")
            .replace("oo", "u")
            .replace("ee", "i")
            .replace("aa", "a")
            .replace("ph", "f")
            .replace("w", "v")
            .replace("z", "j")
            .replace(Regex("(.)\\1+"), "$1")
    }

    /**
     * Expands a raw search query into cross-script FTS/matching tokens:
     * - Original tokens (preserving Devanagari and Latin)
     * - Transliterated Roman tokens + vowel-length variants (`bhuri`, `bhoori`)
     * - Bilingual Hindi/Hinglish <-> English concept translations
     */
    fun expandQueryTokens(rawQuery: String): List<String> {
        val cleaned = rawQuery.trim()
        if (cleaned.isEmpty()) return emptyList()

        val rawTokens = cleaned
            .split(Regex("[\\s,.;:!?/\\\\()\\[\\]{}\"']+"))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.lowercase(Locale.US) !in STOP_WORDS }

        val expanded = LinkedHashSet<String>()

        for (token in rawTokens) {
            val lower = token.lowercase(Locale.US)
            expanded += lower

            // Add dictionary matches
            bilingualConceptMap[token]?.forEach { expanded += it }
            bilingualConceptMap[lower]?.forEach { expanded += it }

            if (containsDevanagari(token)) {
                val roman = transliterateDevanagariToRoman(token)
                    .replace(Regex("[^a-z0-9]"), "")
                if (roman.isNotBlank()) {
                    expanded += roman
                    addRomanVowelVariants(roman, expanded)
                    bilingualConceptMap[roman]?.forEach { expanded += it }
                }
                // Include common short/long matra variant in Devanagari too (ी <-> ि, ू <-> ु)
                val shortDeva = token
                    .replace('ी', 'ि')
                    .replace('ू', 'ु')
                    .replace('आ', 'अ')
                val longDeva = token
                    .replace('ि', 'ी')
                    .replace('ु', 'ू')
                if (shortDeva != token) expanded += shortDeva
                if (longDeva != token) expanded += longDeva
            } else {
                addRomanVowelVariants(lower.replace(Regex("[^a-z0-9]"), ""), expanded)
            }
        }

        return expanded.filter { it.length >= 2 || it.all { ch -> ch.isDigit() } }.take(28)
    }

    /**
     * Produces searchable transliterated Roman + concept text to augment search matching
     * against stored filenames & OCR snippets.
     */
    fun buildSearchableRepresentation(text: String): String {
        if (text.isBlank()) return ""
        val lower = text.lowercase(Locale.US)
        if (!containsDevanagari(text)) return lower
        val roman = transliterateDevanagariToRoman(text)
        return "$lower $roman"
    }

    /**
     * Checks whether a candidate file's text matches an expanded query via direct substring,
     * phonetic skeleton, or bilingual concept overlap. Returns a relevance score >= 0f (0f = no match).
     */
    fun scoreCrossScriptMatch(
        rawQuery: String,
        fileName: String,
        extractedText: String,
        filePath: String
    ): Float {
        val tokens = expandQueryTokens(rawQuery)
        if (tokens.isEmpty()) return 0f

        val haystackRaw = "$fileName $extractedText $filePath".lowercase(Locale.US)
        val haystackAugmented = buildSearchableRepresentation(haystackRaw)
        val haystackSkeleton = toPhoneticSkeleton("$fileName ${extractedText.take(600)}")

        var hits = 0f
        for (tok in tokens) {
            if (tok.length < 2) continue
            if (haystackRaw.contains(tok) || haystackAugmented.contains(tok)) {
                hits += if (fileName.lowercase(Locale.US).contains(tok)) 2.0f else 1.0f
            } else {
                val skel = toPhoneticSkeleton(tok)
                if (skel.length >= 3 && haystackSkeleton.contains(skel)) {
                    hits += 0.9f
                }
            }
        }

        return hits
    }

    private fun addRomanVowelVariants(word: String, out: MutableSet<String>) {
        if (word.length < 2) return
        out += word
        // u <-> oo, i <-> ee, ph <-> f, v <-> w
        if (word.contains("u")) out += word.replace("u", "oo")
        if (word.contains("oo")) out += word.replace("oo", "u")
        if (word.contains("i")) out += word.replace("i", "ee")
        if (word.contains("ee")) out += word.replace("ee", "i")
        if (word.contains("ph")) out += word.replace("ph", "f")
        if (word.contains("f")) out += word.replace("f", "ph")
        if (word.contains("v")) out += word.replace("v", "w")
        if (word.contains("w")) out += word.replace("w", "v")
        // Drop internal 'a' schwa sometimes added in Devanagari transliteration (e.g. bijali -> bijli)
        if (word.length >= 5) {
            val noMidSchwa = word[0] + word.substring(1, word.length - 1).replace("a", "") + word.last()
            if (noMidSchwa.length >= 3) out += noMidSchwa
        }
    }

    private val STOP_WORDS = setOf("and", "or", "not", "near", "the", "a", "an", "in", "on", "of", "for", "ka", "ki", "ke", "ko", "me", "se", "hai", "का", "की", "के", "को", "में", "से", "है")
}
