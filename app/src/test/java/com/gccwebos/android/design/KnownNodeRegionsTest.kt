package com.gccwebos.android.design

import org.junit.Assert.*
import org.junit.Test

/** Synthetic labels only. Real configured labels never appear in test source or output. */
class KnownNodeRegionsTest {
    private val synthetic = "fixture-node-alpha"
    private val regions = mapOf(
        "97183739793f975357f7f4127a4e74823f9d013436bce12df5801fe50f664957" to "JP",
    )

    @Test fun exactSyntheticUtf8IdentityResolvesWithoutNormalizingTheName() {
        assertEquals("JP", knownNodeRegionCode(synthetic, regions))
        listOf("Fixture-node-alpha", "FIXTURE-NODE-ALPHA", "$synthetic ", " $synthetic", "$synthetic-2")
            .forEach { assertNull(knownNodeRegionCode(it, regions)) }
        assertEquals("JP", knownNodeRegionCode(synthetic, regions))
    }

    @Test fun unknownAndUnmappedNamesDoNotAcquireAConfiguredRegion() {
        assertNull(knownNodeRegionCode("fixture-node-other", regions))
        assertNull(knownNodeRegionCode(synthetic, emptyMap()))
        assertNull(knownNodeRegionCode(synthetic))
        assertNull(nodeRegion("fixture-node-other") { knownNodeRegionCode(it, regions) })
    }

    @Test fun aKnownLabelSuppliesOnlyPresentationAndRenamingRemovesTheFallback() {
        val original = synthetic.toByteArray(Charsets.UTF_8)
        assertEquals(NodeRegion("JP", "🇯🇵", "日本"),
            nodeRegion(synthetic) { knownNodeRegionCode(it, regions) })
        assertNull(nodeRegion("$synthetic-renamed") { knownNodeRegionCode(it, regions) })
        assertArrayEquals(original, synthetic.toByteArray(Charsets.UTF_8))
    }

    @Test fun explicitConflictingRegionStaysNeutralWhileMatchingRegionAgrees() {
        val conflicts = mapOf(
            "b74a37558e4a0c72242e5fbd4419ea0b385e1418213f15940362c55547fa5544" to "JP",
            "d3aae8a354c0e1de0ef6e4e0fc1a87ea338f55685a237cd887bf9c9836695844" to "JP",
        )
        assertNull(nodeRegion("SGP fixture-node-alpha") { knownNodeRegionCode(it, conflicts) })
        assertEquals("JP", nodeRegion("NRT fixture-node-alpha") { knownNodeRegionCode(it, conflicts) }?.isoCode)
        assertNull(nodeRegion("🇿🇿 fixture-node-alpha") { "JP" })
    }

    @Test fun invalidLabelsAreRejectedBeforeConsultingKnownMetadata() {
        var lookups = 0
        listOf("", " ", "x".repeat(129), "fixture\nlabel", "fixture\u202e", "fixture\uD800")
            .forEach { assertNull(nodeRegion(it) { lookups++; "JP" }) }
        assertEquals(0, lookups)
    }
}
