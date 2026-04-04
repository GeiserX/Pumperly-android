plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pumperly.app"
    compileSdk = 36

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
}
