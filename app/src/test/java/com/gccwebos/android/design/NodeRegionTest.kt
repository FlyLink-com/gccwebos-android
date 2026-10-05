package com.gccwebos.android.design

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class NodeRegionTest {
    private fun assertRegion(code: String, name: String) {
        val actual = requireNotNull(nodeRegion(name)) { "Expected $code for synthetic label" }
        assertEquals(code, actual.isoCode)
        assertEquals(2, actual.emoji.codePointCount(0, actual.emoji.length))
        assertFalse(actual.displayName.isBlank())
    }

    @Test fun commonChineseRegionLabelsUseTheirOwnTerritoryCodes() {
        mapOf("香港" to "HK", "台湾" to "TW", "臺灣" to "TW", "日本" to "JP", "韩国" to "KR",
            "新加坡" to "SG", "美国" to "US", "英国" to "GB", "德国" to "DE", "加拿大" to "CA",
            "法国" to "FR", "澳大利亚" to "AU", "荷兰" to "NL", "印度" to "IN").forEach { (name, code) ->
            assertRegion(code, "$name 合成节点 01")
        }
        assertEquals("香港", nodeRegion("香港")?.displayName)
        assertEquals("台湾", nodeRegion("台湾")?.displayName)
    }

    @Test fun explicitEnglishNamesAreCaseInsensitiveAndKeepWordBoundaries() {
        mapOf("Hong Kong" to "HK", "taiwan" to "TW", "JAPAN" to "JP", "South Korea" to "KR",
            "SINGAPORE" to "SG", "United States" to "US", "United Kingdom" to "GB", "Germany" to "DE",
            "Canada" to "CA", "France" to "FR", "Australia" to "AU", "Netherlands" to "NL", "India" to "IN")
            .forEach { (name, code) -> assertRegion(code, "$name-01") }
        listOf("Japanology", "Canadian", "Singaporean", "Indianapolis", "Korea").forEach { assertNull(nodeRegion(it)) }
    }

    @Test fun documentedRegionCodesUseTheExistingCaseInsensitiveLetterBoundaries() {
        listOf("SGP", "sgp-01", "synthetic_sGp_relay", "[SGP] synthetic", "SGP01")
            .forEach { assertRegion("SG", it) }
        listOf("NRT", "nrt-01", "synthetic_nRt_relay", "[NRT] synthetic", "NRT01")
            .forEach { assertRegion("JP", it) }
        assertEquals("新加坡", nodeRegion("sgp-01")?.displayName)
        assertEquals("日本", nodeRegion("nrt-01")?.displayName)
    }

    @Test fun regionCodesInsideLatinOrUnicodeWordsStayNeutral() {
        listOf("ASGP", "SGPROXY", "syntheticSGP01", "sgpword", "ANRT", "NRTunnel",
            "syntheticNRT01", "nrtword", "节点SGP", "NRT节点", "éSGP", "NRTé")
            .forEach { assertNull(nodeRegion(it)) }
    }

    @Test fun regionCodeConflictsStayNeutralAndEquivalentLabelsAgree() {
        listOf("SGP-NRT", "sgp [JP]", "nrt Singapore", "🇸🇬 NRT", "SGP-日本")
            .forEach { assertNull(nodeRegion(it)) }
        assertRegion("SG", "SGP Singapore [SG]")
        assertRegion("JP", "NRT Japan [JP]")
    }

    @Test fun isoCodesNeedAnExplicitPrefixOrBracketsAndNeverMatchWordFragments() {
        listOf("JP", "JP01", "JP-01", "JP_01", "JP 01", "[jp] synthetic", "合成【JP】", "合成（jp）", "synthetic (JP)")
            .forEach { assertRegion("JP", it) }
        listOf("USAGE", "business", "us synthetic", "JPN01", "ZZ-01", "[ZZ]", "EU-01", "[UN]", "[UK]",
            "Tokyo", "192.0.2.1", "synthetic.example.invalid", "London Premium").forEach { assertNull(nodeRegion(it)) }
    }

    @Test fun supportedIsoFlagPairsUseTheirFullUnicodeCodePoints() {
        mapOf("🇭🇰" to "HK", "🇹🇼" to "TW", "🇯🇵" to "JP", "🇰🇷" to "KR", "🇸🇬" to "SG", "🇺🇸" to "US",
            "🇬🇧" to "GB", "🇩🇪" to "DE", "🇨🇦" to "CA", "🇫🇷" to "FR", "🇦🇺" to "AU", "🇳🇱" to "NL",
            "🇮🇳" to "IN", "🇿🇦" to "ZA").forEach { (flag, code) ->
            assertRegion(code, "$flag synthetic-01")
            assertEquals(flag, nodeRegion(flag)?.emoji)
        }
    }

    @Test fun sameRegionRepeatedByDifferentLabelsIsStillUnambiguous() {
        assertRegion("JP", "🇯🇵 Japan 日本 [JP] 合成01")
        assertRegion("HK", "[HK] 香港 Hong Kong")
        assertRegion("US", "United States of America [US]")
    }

    @Test fun conflictingLabelsAndMultiRegionRoutesStayNeutral() {
        listOf("香港转日本", "Hong Kong → Japan", "[JP] 美国", "🇯🇵🇺🇸", "🇯🇵 香港", "JP-HK",
            "HK / US", "JP → US", "🇺🇸 Japan", "印度尼西亚/印度").forEach { assertNull(it, nodeRegion(it)) }
    }

    @Test fun longRegionNamesDoNotAccidentallyMatchAnotherShortRegionName() {
        assertRegion("ID", "印度尼西亚 01")
        assertRegion("ID", "印度尼西亞 01")
        assertRegion("ID", "Indonesia-01")
        assertRegion("KP", "North Korea-01")
        assertRegion("KR", "Republic of Korea-01")
    }

    @Test fun invalidFlagsOrHiddenDirectionMarkersDoNotGrantARegion() {
        listOf("🇯", "🇯🇵🇺", "🇿🇿", "🇪🇺", "🇺🇳", "🇿🇿 Japan", "JP\u202e", "JP\u200d", "Japan\n01",
            "[JP]\u0000", "Japan\uD800", "", "  ").forEach { assertNull(nodeRegion(it)) }
    }

    @Test fun matchingIsBoundedAndDoesNotDependOnTheDeviceLanguage() {
        assertNull(nodeRegion("日本" + "x".repeat(123))) // 129 UTF-8 bytes.
        assertRegion("JP", "日本" + "x".repeat(122))
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertRegion("SG", "SINGAPORE-01")
            assertRegion("IN", "[in] synthetic")
            assertEquals("新加坡", nodeRegion("SINGAPORE")?.displayName)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun metadataResolutionDoesNotRewriteTheNodeName() {
        val name = "🇯🇵 Japan 01 · synthetic"
        val original = name.toByteArray(Charsets.UTF_8)
        repeat(2) { assertEquals(NodeRegion("JP", "🇯🇵", "日本"), nodeRegion(name)) }
        assertArrayEquals(original, name.toByteArray(Charsets.UTF_8))
    }
}
