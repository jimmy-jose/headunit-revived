plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "org.xs.hulhelper"
    compileSdk = 37

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "org.xs.hulhelper"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // Use defaults
        }

        create("release") {
            // Path relative to the helper app module directory.
            storeFile = file("../hulhelper-release-key.jks")
            storePassword = System.getenv("HEADUNIT_KEYSTORE_PASSWORD")
            keyAlias = "headunit-revived" // Use the same alias as HURev
            keyPassword = System.getenv("HEADUNIT_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true // Enable shrinking for release
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            isDebuggable = true
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

// Set artifact name globally
base {
    archivesName.set("org.xs.hulhelper_${android.defaultConfig.versionName}")
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.linkedin.dexmaker:dexmaker:2.28.6")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("androidx.security:security-crypto:1.1.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
