plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wbconv.wechatvideosaver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wbconv.wechatvideosaver"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        jniLibs {
            // FFmpegKit 与 Vosk 都可能自带 libc++_shared.so，取第一个避免重复冲突
            pickFirsts += setOf("**/libc++_shared.so")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 音频提取（LGPL 版，含 AAC 解码 + PCM 输出）
    implementation("com.arthenica:ffmpeg-kit-min:6.0.LTS")
    // 离线中文语音识别
    implementation("com.alphacephei:vosk-android:0.3.47")
}
