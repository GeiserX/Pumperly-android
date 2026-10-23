plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pumperly.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pumperly.app"
        minSdk = 26
        targetSdk = 36

        versionCode = (findProperty("VERSION_CODE") as String).toInt()
        versionName = findProperty("VERSION_NAME") as String
    }

    signingConfigs {
        create("release") {
            val keystorePath = findProperty("PUMPERLY_KEYSTORE_PATH") as String?
                ?: System.getenv("PUMPERLY_KEYSTORE_PATH")
            val keystorePass = findProperty("PUMPERLY_KEYSTORE_PASSWORD") as String?
                ?: System.getenv("PUMPERLY_KEYSTORE_PASSWORD")
            val keyAliasValue = findProperty("PUMPERLY_KEY_ALIAS") as String?
                ?: System.getenv("PUMPERLY_KEY_ALIAS")
            val keyPass = findProperty("PUMPERLY_KEY_PASSWORD") as String?
                ?: System.getenv("PUMPERLY_KEY_PASSWORD")

            if (keystorePath != null && keystorePass != null && keyAliasValue != null && keyPass != null) {
                storeFile = file(keystorePath)
                storePassword = keystorePass
                keyAlias = keyAliasValue
                keyPassword = keyPass
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        // lint.xml raises MissingOnRenderProcessGone to an error. The baseline
        // holds only its two existing occurrences in PumperlyWebView.kt; the
        // shell fix empties it, and any new occurrence fails the build.
        lintConfig = file("lint.xml")
        baseline = file("lint-baseline.xml")
        warningsAsErrors = false
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources and manifest.
            isIncludeAndroidResources = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseConfig = signingConfigs.findByName("release")
            if (releaseConfig?.storeFile != null) {
                signingConfig = releaseConfig
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.swiperefreshlayout)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.glance.appwidget.testing)
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
