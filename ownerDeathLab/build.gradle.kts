plugins { id("com.android.application") }

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
    sourceSets.getByName("main").java.srcDir(generatedB1Sources)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
}

tasks.named("preBuild").configure { dependsOn(syncSharedB1Sources) }
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

// No application, AndroidX, native, VPN or network dependency is declared.
