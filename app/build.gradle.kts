plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionMajor = 0
val versionMinor = 1
val versionPatch = 0
val versionNameStr = "$versionMajor.$versionMinor.$versionPatch"
val versionCodeInt = versionMajor * 10000 + versionMinor * 100 + versionPatch

android {
    namespace = "com.goai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.goai"
        minSdk = 26
        targetSdk = 34
        versionCode = versionCodeInt
        versionName = versionNameStr

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("SIGNING_STORE_FILE")
            val storePasswordStr = System.getenv("SIGNING_STORE_PASSWORD")
            val keyAliasStr = System.getenv("SIGNING_KEY_ALIAS")
            val keyPasswordStr = System.getenv("SIGNING_KEY_PASSWORD")

            if (storeFilePath != null && storeFilePath.isNotEmpty()) {
                storeFile = file(storeFilePath)
                storePassword = storePasswordStr ?: ""
                keyAlias = keyAliasStr ?: ""
                keyPassword = keyPasswordStr ?: ""
            }
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
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            }
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    lint {
        abortOnError = false
        xmlReport = true
        htmlReport = true
        lintConfig = file("lint.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
}
