import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing settings live outside the repo. Point P3K_SIGNING_SETTINGS at the JSON file
// (storeFile, storePassword, keyAlias, keyPassword); without it, release builds are left unsigned.
val releaseSigningSettings: Map<*, *>? = System.getenv("P3K_SIGNING_SETTINGS")
    ?.let { file(it) }
    ?.takeIf { it.exists() }
    ?.let { JsonSlurper().parse(it) as Map<*, *> }

android {
    namespace = "com.p3kIndustries.P3kBT2PC"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.p3kIndustries.P3kBT2PC"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    signingConfigs {
        if (releaseSigningSettings != null) {
            create("release") {
                storeFile = file(releaseSigningSettings["storeFile"] as String)
                storePassword = releaseSigningSettings["storePassword"] as String
                keyAlias = releaseSigningSettings["keyAlias"] as String
                keyPassword = releaseSigningSettings["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningSettings != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)



    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

}
