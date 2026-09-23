plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.ytdlp.gui"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.ytdlp.gui"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABI filters required for Python/FFmpeg native libraries
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // Core yt-dlp wrapper library
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")

    // FFmpeg library (required for merging high-res video + audio streams)
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}