package com.gccwebos.android.portal

import org.junit.Assert.*
import org.junit.Test

/** Synthetic URI fixtures only; never use an account's subscription capability. */
class SubscriptionOriginPolicyTest {
    private val origin = "https://sub.invalid"
    private val policy = OriginPolicy("https://portal.invalid", setOf(origin))
    private fun accepted(path: String) {
        val value = origin + path
        assertTrue("Accepted capability must remain byte-for-byte unchanged", policy.subscription(value) == value)
    }
    @Test fun acceptsDefaultBackendYamlArtifactAndLegacyBareArtifact() {
        accepted("/sub/a/synthetic_artifact-123.yaml")
        accepted("/sub/a/synthetic_artifact-123")
    }
    @Test fun rejectsOtherExtensionsAndEncodedOrAmbiguousPaths() {
        val paths = listOf("/sub/a/.yaml", "/sub/a/fixture.yml", "/sub/a/fixture.YAML",
            "/sub/a/fixture.yaml.yaml", "/sub/a/fixture.json", "/sub/a/fixture.yaml/",
            "/sub/a/fixture%2Eyaml", "/sub/a/fixture%2fyaml", "/sub/a/../fixture.yaml",
            "/sub/a/fixture.yaml/extra", "/sub/a/fixture.yaml?format=clash", "/sub/a/fixture.yaml#part")
        paths.forEachIndexed { index, path ->
            try { policy.subscription(origin + path); fail("Rejected path fixture $index was accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun yamlSuffixDoesNotBroadenHttpsOriginOrCredentialBoundary() {
        val path = "/sub/a/synthetic_artifact.yaml"
        val values = listOf("http://sub.invalid" + path, "https://foreign.invalid" + path,
            "https://sub.invalid:444" + path, "https://user@sub.invalid" + path,
            "https://sub.invalid.foreign.invalid" + path, "https://sub.invalid" + path + "\n")
        values.forEachIndexed { index, value ->
            try { policy.subscription(value); fail("Rejected origin fixture $index was accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
