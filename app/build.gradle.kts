import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gccwebos.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.gccwebos.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.4.0-mobile-parity"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["connectionAppLabel"] = "@string/app_name"
        val packagedEngine = listOf("arm64-v8a", "x86_64").all {
            rootProject.file("native/build/jniLibs/$it/libgccmihomo.so").isFile
        }
        buildConfigField("boolean", "VPN_ENGINE_PACKAGED", packagedEngine.toString())
    }

    buildTypes {
        debug {
            val candidate = providers.gradleProperty("connectionCandidate").orNull == "true"
            val endpointHostname = candidate && providers.gradleProperty("connectionEndpointHostname").orNull != "false"
            buildConfigField("boolean", "CONNECTION_ENDPOINT_HOSTNAME", endpointHostname.toString())
            val synthetic = providers.gradleProperty("connectionSyntheticAcceptance").orNull == "true"
            require(!synthetic || candidate) { "Synthetic connection acceptance requires a separate candidate package" }
            buildConfigField("boolean", "CONNECTION_SYNTHETIC_ACCEPTANCE", synthetic.toString())
            val storageSynthetic = providers.gradleProperty("storageSyntheticAcceptance").orNull == "true"
            require(!storageSynthetic || candidate) { "Synthetic storage acceptance requires a separate candidate package" }
            buildConfigField("boolean", "STORAGE_SYNTHETIC_ACCEPTANCE", storageSynthetic.toString())
            applicationIdSuffix = when {
                storageSynthetic -> ".candidate.acceptance.session20261004"
                candidate -> ".candidate"
                else -> ".debug"
            }
            versionNameSuffix = if (candidate) "-connection-candidate" else "-debug"
            if (candidate) manifestPlaceholders["connectionAppLabel"] = "飞连 VPN 测试"
            val nativeLab = providers.gradleProperty("nativeVpnLab").orNull == "true"
            buildConfigField("boolean", "NATIVE_VPN_LAB", nativeLab.toString())
            val realTun = providers.gradleProperty("nativeTunAcceptance").orNull == "true"
            require(!realTun || nativeLab) { "Real TUN acceptance requires nativeVpnLab" }
            buildConfigField("boolean", "NATIVE_TUN_ACCEPTANCE", realTun.toString())
            manifestPlaceholders["nativeVpnLabEnabled"] = nativeLab.toString()
        }
        release {
            buildConfigField("boolean", "CONNECTION_ENDPOINT_HOSTNAME", "false")
            buildConfigField("boolean", "CONNECTION_SYNTHETIC_ACCEPTANCE", "false")
            buildConfigField("boolean", "STORAGE_SYNTHETIC_ACCEPTANCE", "false")
            buildConfigField("boolean", "NATIVE_VPN_LAB", "false")
            buildConfigField("boolean", "NATIVE_TUN_ACCEPTANCE", "false")
            isMinifyEnabled = false
            isDebuggable = false
            // No production signing credentials or automatic release signing.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    lint { abortOnError = true }
    sourceSets.getByName("test").resources.directories.add("../native/bridge/testdata")
    // Verified libraries are generated/materialized separately; no old APK is reused.
    sourceSets.getByName("main").jniLibs.directories.add("../native/build/jniLibs")
    sourceSets.getByName("main").assets.directories.add("../native/notices")
    sourceSets.getByName("test").kotlin.directories.add("src/acceptanceFixtures/java")
    if (providers.gradleProperty("nativeTunAcceptance").orNull == "true") {
        sourceSets.getByName("androidTest").kotlin.directories.addAll(listOf("src/acceptanceTest/java", "src/acceptanceFixtures/java"))
        sourceSets.getByName("androidTest").java.directories.add("src/acceptanceTest/java")
        sourceSets.getByName("androidTest").manifest.srcFile("src/acceptanceTest/AndroidManifest.xml")
    }
    if (providers.gradleProperty("connectionSyntheticAcceptance").orNull == "true") {
        sourceSets.getByName("androidTest").kotlin.directories.addAll(listOf("src/connectionAcceptanceTest/java", "src/acceptanceFixtures/java"))
    }
    if (providers.gradleProperty("storageSyntheticAcceptance").orNull == "true") {
        sourceSets.getByName("androidTest").kotlin.directories.add("src/storageAcceptanceTest/java")
    }
}

// A device candidate must contain both verified ABIs, never a UI-only APK.
if (providers.gradleProperty("connectionCandidate").orNull == "true") {
    tasks.register("verifyConnectionCandidateLibraries") {
        doLast {
            val expected = mapOf(
                "arm64-v8a" to "2737f327ecdef298907c6d10913eaf71196d6772b7ab2bb10c0ab2d9b1f37463",
                "x86_64" to "6e8ab0ed35e94cf128ce53523332bd3b50c9e10cb03ac768bd3a55c91ee252c8")
            expected.forEach { (abi, digest) ->
                val file = rootProject.file("native/build/jniLibs/$abi/libgccmihomo.so")
                check(file.isFile) { "Materialize verified native libraries before building a connection candidate" }
                val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }
                check(actual == digest) { "Native library identity mismatch for $abi" }
            }
            listOf("Mihomo-GPL-3.0.txt", "CMFA-GPL-3.0.txt").forEach {
                check(rootProject.file("native/notices/$it").isFile) { "Required native license notice missing" }
            }
        }
    }
    tasks.named("preBuild").configure { dependsOn("verifyConnectionCandidateLibraries") }
}

// Pinned to an official documented stable BOM. Original 2026.09.00 was not
// dependency-resolved here; see CHANGELOG.md. No dynamic dependency versions.
val composeBom = "androidx.compose:compose-bom:2026.06.01"
dependencies {
    implementation(platform(composeBom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.snakeyaml:snakeyaml-engine:2.9")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform(composeBom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Generated by exact-head Kotlin projection tests, consumed by Go offline core tests.
tasks.withType<Test>().configureEach {
    systemProperty("protocolContractOutput", rootProject.file(".build-output/protocol-contract").absolutePath)
}
