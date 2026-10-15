package com.translator.offline.nlp

object TextNormalizer {

    private val RU_UNITS_MASCULINE = arrayOf("", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")
    private val RU_TEENS = arrayOf("десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать")
    private val RU_TENS = arrayOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
    private val RU_HUNDREDS = arrayOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")

    private val DE_UNITS = arrayOf("", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun")
    private val DE_TEENS = arrayOf("zehn", "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn")
    private val DE_TENS = arrayOf("", "", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig")

    fun normalize(text: String, targetLanguage: String): String {
        var result = text.trim()

        if (targetLanguage.equals("de", ignoreCase = true)) {
            result = result.replace(Regex("(\\b\\d{1,2}):(\\d{2})\\b")) { match ->
                val h = match.groupValues[1].toInt()
                val m = match.groupValues[2].toInt()
                "${deNumberToWords(h)} Uhr ${deNumberToWords(m)}"
            }
            result = result.replace(Regex("(\\d+)\\s*(€|EUR|euro|Евро)")) { match ->
                val num = match.groupValues[1].toInt()
                "${deNumberToWords(num)} Euro"
            }
            result = result.replace(Regex("(\\d+)\\s*(\\$|USD|долларов|доллара|доллар)")) { match ->
                val num = match.groupValues[1].toInt()
                "${deNumberToWords(num)} Dollar"
            }
            result = result.replace(Regex("\\b\\d+\\b")) { match ->
                val num = match.value.toIntOrNull() ?: return@replace match.value
                deNumberToWords(num)
            }
        } else {
            result = result.replace(Regex("(\\b\\d{1,2}):(\\d{2})\\b")) { match ->
                val h = match.groupValues[1].toInt()
                val m = match.groupValues[2].toInt()
                "${ruNumberToWords(h)} ${ruNumberToWords(m)}"
            }
            result = result.replace(Regex("(\\d+)\\s*(€|EUR|euro|евро)")) { match ->
                val num = match.groupValues[1].toInt()
                "${ruNumberToWords(num)} евро"
            }
            result = result.replace(Regex("(\\d+)\\s*(\\$|USD|долларов|доллара|доллар)")) { match ->
                val num = match.groupValues[1].toInt()
                "${ruNumberToWords(num)} долларов"
            }
            result = result.replace(Regex("\\b\\d+\\b")) { match ->
                val num = match.value.toIntOrNull() ?: return@replace match.value
                ruNumberToWords(num)
            }
        }

        result = result.replace(Regex("[<>\"]"), "")
        return result
    }

    private fun ruNumberToWords(num: Int): String {
        if (num == 0) return "ноль"
        if (num < 0 || num > 999) return num.toString()

        val parts = mutableListOf<String>()
        val hundreds = num / 100
        val remainder = num % 100
        val tens = remainder / 10
        val units = remainder % 10

        if (hundreds > 0) parts.add(RU_HUNDREDS[hundreds])

        if (remainder in 10..19) {
            parts.add(RU_TEENS[remainder - 10])
        } else {
            if (tens > 1) parts.add(RU_TENS[tens])
            if (units > 0) parts.add(RU_UNITS_MASCULINE[units])
        }

        return parts.joinToString(" ")
    }

    private fun deNumberToWords(num: Int): String {
        if (num == 0) return "null"
        if (num < 0 || num > 999) return num.toString()

        val parts = mutableListOf<String>()
        val hundreds = num / 100
        val remainder = num % 100
        val tens = remainder / 10
        val units = remainder % 10

        if (hundreds > 0) {
            val hPrefix = if (hundreds == 1) "ein" else DE_UNITS[hundreds]
            parts.add("${hPrefix}hundert")
        }

        if (remainder in 10..19) {
            parts.add(DE_TEENS[remainder - 10])
        } else if (remainder > 0) {
            if (tens == 0) {
                parts.add(if (units == 1) "eins" else DE_UNITS[units])
            } else if (units == 0) {
                parts.add(DE_TENS[tens])
            } else {
                val uStr = if (units == 1) "ein" else DE_UNITS[units]
                parts.add("${uStr}und${DE_TENS[tens]}")
            }
        }

        return parts.joinToString("")
    }
}
