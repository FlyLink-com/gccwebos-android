plugins { id("com.android.application") }

android {
    namespace = "com.gccwebos.android.ipcqa"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.gccwebos.android.ipcqa"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-e2b-lab"
        testApplicationId = "com.gccwebos.android.ipcqa.test"
        testInstrumentationRunner = "com.gccwebos.android.ipcqa.CopyBoundaryRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

// Deliberately no :app, AndroidX runner, native or network dependencies.
