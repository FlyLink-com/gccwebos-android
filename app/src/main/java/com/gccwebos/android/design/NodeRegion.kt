package com.gccwebos.android.design

import java.util.Locale

/** A label from the node name, never endpoint geography, entitlement or selection authority. */
internal data class NodeRegion(val isoCode: String, val emoji: String, val displayName: String)

private data class RegionNames(val code: String, val chinese: String, val aliases: List<String>)

private val regionNames = listOf(
    RegionNames("HK", "香港", listOf("香港", "Hong Kong")),
    RegionNames("TW", "台湾", listOf("台湾", "台灣", "臺灣", "Taiwan")),
    RegionNames("JP", "日本", listOf("日本", "Japan", "NRT")),
    RegionNames("KR", "韩国", listOf("韩国", "韓國", "南韩", "南韓", "South Korea", "Republic of Korea")),
    RegionNames("SG", "新加坡", listOf("新加坡", "Singapore", "SGP")),
    RegionNames("US", "美国", listOf("美国", "美國", "United States of America", "United States")),
    RegionNames("GB", "英国", listOf("英国", "英國", "United Kingdom", "Great Britain")),
    RegionNames("DE", "德国", listOf("德国", "德國", "Germany")),
    RegionNames("CA", "加拿大", listOf("加拿大", "Canada")),
    RegionNames("FR", "法国", listOf("法国", "法國", "France")),
    RegionNames("AU", "澳大利亚", listOf("澳大利亚", "澳大利亞", "澳洲", "Australia")),
    RegionNames("NL", "荷兰", listOf("荷兰", "荷蘭", "Netherlands")),
    RegionNames("IN", "印度", listOf("印度", "India")),
    RegionNames("ID", "印度尼西亚", listOf("印度尼西亚", "印度尼西亞", "印尼", "Indonesia")),
    RegionNames("MY", "马来西亚", listOf("马来西亚", "馬來西亞", "Malaysia")),
    RegionNames("TH", "泰国", listOf("泰国", "泰國", "Thailand")),
    RegionNames("VN", "越南", listOf("越南", "Vietnam", "Viet Nam")),
    RegionNames("PH", "菲律宾", listOf("菲律宾", "菲律賓", "Philippines")),
    RegionNames("CN", "中国", listOf("中国", "中國", "China")),
    RegionNames("MO", "澳门", listOf("澳门", "澳門", "Macao", "Macau")),
    RegionNames("NZ", "新西兰", listOf("新西兰", "紐西蘭", "新西蘭", "New Zealand")),
    RegionNames("CH", "瑞士", listOf("瑞士", "Switzerland")),
    RegionNames("SE", "瑞典", listOf("瑞典", "Sweden")),
    RegionNames("FI", "芬兰", listOf("芬兰", "芬蘭", "Finland")),
    RegionNames("NO", "挪威", listOf("挪威", "Norway")),
    RegionNames("DK", "丹麦", listOf("丹麦", "丹麥", "Denmark")),
    RegionNames("IT", "意大利", listOf("意大利", "義大利", "Italy")),
    RegionNames("ES", "西班牙", listOf("西班牙", "Spain")),
    RegionNames("RU", "俄罗斯", listOf("俄罗斯", "俄羅斯", "Russia")),
    RegionNames("BR", "巴西", listOf("巴西", "Brazil")),
    RegionNames("TR", "土耳其", listOf("土耳其", "Turkey", "Türkiye")),
    RegionNames("AE", "阿联酋", listOf("阿联酋", "阿聯酋", "United Arab Emirates")),
    RegionNames("KP", "朝鲜", listOf("朝鲜", "朝鮮", "North Korea")),
)
private val knownRegionNames = regionNames.associate { it.code to it.chinese }
private val isoRegionCodes = Locale.getISOCountries().toSet()
private val aliases = regionNames.flatMap { region -> region.aliases.map { it to region.code } }
    .sortedByDescending { it.first.length }
    .map { (alias, code) ->
        val latin = alias.any { it in 'A'..'Z' || it in 'a'..'z' }
        val pattern = if (latin) "(?<![\\p{L}])${Regex.escape(alias)}(?![\\p{L}])" else Regex.escape(alias)
        Regex(pattern, RegexOption.IGNORE_CASE) to code
    }
private val bracketedCode = Regex("(?:\\[([A-Za-z]{2})]|\\(([A-Za-z]{2})\\)|【([A-Za-z]{2})】|（([A-Za-z]{2})）)")
private val prefixCode = Regex("^([A-Z]{2})(?=$|[^\\p{L}])")
private val routeCode = Regex("(?:[-_/|→>]\\s*)([A-Z]{2})(?=$|[^\\p{L}])")

/**
 * Name for display next to the region badge. The badge already shows the flag, so a leading flag emoji
 * in the provider's name is dropped here (display only: selection, probing and admission keep the full name).
 * Names without a recognised region, or consisting only of a flag, are returned unchanged.
 */
internal fun nodeDisplayName(name: String): String {
    if (nodeRegion(name) == null) return name
    val points = name.codePoints().toArray()
    var index = 0
    while (index < points.size && points[index] in 0x1F1E6..0x1F1FF) index++
    if (index == 0 || index % 2 != 0) return name
    val rest = String(points, index, points.size - index).trim()
    return rest.ifEmpty { name }
}

/** Conservative local matching. Conflicting or malformed flag labels deliberately stay neutral. */
internal fun nodeRegion(name: String): NodeRegion? = nodeRegion(name) { knownNodeRegionCode(it) }

internal fun nodeRegion(name: String, knownRegionLookup: (String) -> String?): NodeRegion? {
    if (name.isBlank() || name.toByteArray(Charsets.UTF_8).size > 128 || name.any { it.isISOControl() }) return null
    val codePoints = name.codePoints().toArray()
    // Invisible direction/tag controls must not disguise the meaning of an explicit label.
    if (codePoints.any { it in 0xD800..0xDFFF || Character.getType(it) == Character.FORMAT.toInt() }) return null
    val found = mutableSetOf<String>()
    knownRegionLookup(name)?.takeIf { it in isoRegionCodes }?.let(found::add)
    var point = 0
    while (point < codePoints.size) {
        if (codePoints[point] !in 0x1F1E6..0x1F1FF) { point++; continue }
        val start = point
        while (point < codePoints.size && codePoints[point] in 0x1F1E6..0x1F1FF) point++
        if ((point - start) % 2 != 0) return null
        for (index in start until point step 2) {
            val code = buildString {
                append(('A'.code + codePoints[index] - 0x1F1E6).toChar())
                append(('A'.code + codePoints[index + 1] - 0x1F1E6).toChar())
            }
            if (code !in isoRegionCodes) return null
            found += code
        }
    }
    // Long names own their spans: 印度尼西亚 must not also match 印度.
    val occupied = BooleanArray(name.length)
    for ((pattern, code) in aliases) {
        for (match in pattern.findAll(name)) {
            if (match.range.any { occupied[it] }) continue
            found += code
            match.range.forEach { occupied[it] = true }
        }
    }
    for (match in bracketedCode.findAll(name)) {
        val code = match.groupValues.drop(1).first { it.isNotEmpty() }.uppercase(Locale.ROOT)
        if (code in isoRegionCodes) found += code
    }
    // Bare ISO labels must be an uppercase prefix or an explicit route segment, not a word substring.
    val trimmed = name.trim()
    prefixCode.find(trimmed)?.groupValues?.get(1)?.takeIf { it in isoRegionCodes }?.let(found::add)
    routeCode.findAll(trimmed).map { it.groupValues[1] }.filter { it in isoRegionCodes }.forEach(found::add)
    val code = found.singleOrNull() ?: return null
    val emoji = buildString { code.forEach { appendCodePoint(0x1F1E6 + it.code - 'A'.code) } }
    val displayName = knownRegionNames[code] ?: Locale("", code).getDisplayCountry(Locale.SIMPLIFIED_CHINESE)
    return NodeRegion(code, emoji, displayName)
}
