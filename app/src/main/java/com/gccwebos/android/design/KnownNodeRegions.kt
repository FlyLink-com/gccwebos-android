package com.gccwebos.android.design

import java.security.MessageDigest

// Configured display labels only; not endpoint geography or connection authority.
// Source: proxy-gateway-minimal d49121264f5227338f39729ea34b2dd898a0abf3
// config/proxy-servers.json; exact public UI label hashes matched on 2026-10-04.
// A changed label has no fallback. Source changes require an explicit mapping review.
private val committedNodeRegions = mapOf(
    "32f77fec6f92ed393f41f1e07a6d1972413cdc46d4091a04ad310681e2434f20" to "SG",
    "3ea7a7247196087ed07fce8a87756cd98658f9de0e0bbbfe6d6d29aa506823ef" to "SG",
    "8061742a1d80dc1c00f8f1ed7a3d852f2ecc9db2cf3c978f2317a94d959dce04" to "JP",
    "901bcedcf51ab2b7fe9643afec7f4ca55771bd1f1a6df29b4e649f7e5857dd22" to "JP",
    "c004f46c0b915afc8eb111ac22648bc5d9454d93a6c99b7b5730d84b17234dd3" to "JP",
    "eda739d11c45c47d9d5cb5dffc09537017f97819da2f76b472c3b117f98ebcb2" to "SG",
)

/** Exact UTF-8 identity only: no normalization, endpoint inference, IO or logging. */
internal fun knownNodeRegionCode(name: String, regions: Map<String, String> = committedNodeRegions): String? {
    val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return regions[digest]
}
