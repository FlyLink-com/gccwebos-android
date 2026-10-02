plugins { id("com.android.application") }

// AGP's automatic Kotlin runtime is unused by this Java-only fixture. Keep the
// exclusion local to APK classpaths; app and compiler/lint tools are unaffected.
val labApkClasspaths = setOf(
    "debugCompileClasspath", "debugRuntimeClasspath",
    "debugAndroidTestCompileClasspath", "debugAndroidTestRuntimeClasspath",
)
configurations.matching { it.name in labApkClasspaths }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

// Reuse only the three public B1 protocol sources, preserving their packages and bytes.
// Sync removes stale generated files; no B1 service, guard, runner or tests enter this module.
val sharedB1Files = listOf(
    "com/gccwebos/android/ipcqa/Wire.java",
    "com/gccwebos/android/ipcqa/CopyLedger.java",
    "com/gccwebos/android/ipcqa/RemoteCopyClient.java",
)
val generatedB1Sources = layout.buildDirectory.dir("generated/sources/b1/java")
val syncSharedB1Sources = tasks.register<Sync>("syncSharedB1Sources") {
    from(rootProject.layout.projectDirectory.dir("copyBoundaryLab/src/main/java")) {
        include(sharedB1Files)
    }
    into(generatedB1Sources)
}

android {
    namespace = "com.gccwebos.android.ipcqa.b2"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.gccwebos.android.ipcqa.b2"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-e2b-b2-lab"
        testApplicationId = "com.gccwebos.android.ipcqa.b2.test"
        testInstrumentationRunner = "com.gccwebos.android.ipcqa.b2.OwnerDeathRunner"
    }
    // AGP 9.2 rejects a Provider in the legacy SourceSet API. The concrete
    // directory is populated by the explicit preBuild -> Sync dependency below.
    sourceSets.getByName("main").java.srcDir(generatedB1Sources.get().asFile)
    compileOptions {
        // The fixture uses Java 8 syntax/APIs. Avoid Java 17 nest-access markers.
        // JDK 17 remains the compiler and host verifier; Java source bytes stay fixed.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildTypes.getByName("debug") {
        // Remove unreferenced generated R/global-synthetics classes before packaging.
        // Rules keep the fixture and B1 protocol members, disable optimization/renaming.
        isMinifyEnabled = true
        proguardFiles("proguard-b2-main.pro")
        testProguardFiles("proguard-b2-test.pro")
    }
    lint { abortOnError = true }
}

tasks.named("preBuild").configure { dependsOn(syncSharedB1Sources) }
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

// AGP app androidTest adds three keep-all rules even when R8 is enabled. Those
// retain the empty generated test R class. Our exact test roots already preserve
// every fixture member. Narrow only that generated rule list, before task input
// snapshots; no class file, DEX, signed APK or inspection policy is edited.
// This internal adapter is deliberately pinned and refuses changed AGP behavior.
afterEvaluate {
    check(androidComponents.pluginVersion.toString() == "9.2.0") {
        "B2 test keep adapter requires the reviewed AGP 9.2.0"
    }
    tasks.named("minifyDebugAndroidTestWithR8").configure {
        check(javaClass.superclass.name == "com.android.build.gradle.internal.tasks.R8Task") {
            "B2 test keep adapter: unexpected task type"
        }
        // The base class layout differs across AGP versions; require the exact
        // actual task getter rather than compiling against a newer base type.
        val ruleValue = javaClass.getMethod("getProguardConfigurations").invoke(this)
        check(ruleValue is MutableList<*> && ruleValue.all { it is String }) {
            "B2 test keep adapter: unexpected generated rule representation"
        }
        @Suppress("UNCHECKED_CAST")
        val generatedRules = ruleValue as MutableList<String>
        val generatedKeepAll = setOf(
            "-keep class * {*;}",
            "-keep interface * {*;}",
            "-keep enum * {*;}",
        )
        val before = generatedRules.toList()
        check("-keepattributes *" in before) { "B2 test attributes rule changed" }
        check(generatedKeepAll.all { rule -> before.count { it == rule } == 1 }) {
            "B2 test keep adapter: generated rules changed; refuse packaging"
        }
        check("-dontshrink" !in before) { "B2 test shrinking disabled; refuse packaging" }
        generatedRules.removeAll(generatedKeepAll)
        check(generatedRules == before.filterNot { it in generatedKeepAll }) {
            "B2 test keep adapter changed an unrelated generated rule"
        }
        extensions.extraProperties.set("b2GeneratedRulesBefore", before)
    }
}

// No application, AndroidX, native, VPN or network dependency is declared.
