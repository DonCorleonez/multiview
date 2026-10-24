plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Version from CI run number so every build installs over the previous one.
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()

android {
    namespace = "id.my.doncorleonez.multiview"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.my.doncorleonez.multiview"
        minSdk = 29
        targetSdk = 35
        versionCode = 1 + runNumber
        versionName = "1.0.$runNumber"
    }

    // Release signing from environment (CI secrets). Without them, release falls back to the debug key.
    val ksPath = System.getenv("MV_KEYSTORE")
    signingConfigs {
        if (ksPath != null && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("MV_STORE_PASSWORD")
                keyAlias = System.getenv("MV_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "multiview"
                keyPassword = System.getenv("MV_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: System.getenv("MV_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi", "GradleDependency", "NewerVersionAvailable", "SetTextI18n", "HardcodedText")
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.annotation.experimental)
    testImplementation(libs.junit)
}
