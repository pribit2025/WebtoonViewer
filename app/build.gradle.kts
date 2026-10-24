plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nagne.webtoonviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nagne.webtoonviewer"
        minSdk = 26          // Android 8.0 이상 (갤럭시 대부분 지원)
        targetSdk = 35
        // GitHub Actions 실행 번호로 버전을 자동 증가 → 기기에서 "업데이트"로 인식
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.0.$run"
    }

    // 고정 서명 키: 빌드마다 같은 키로 서명해야 지우지 않고 덮어 설치(업데이트)가 되고
    // 기존 열람 기록이 유지된다. (개인용 키 — 저장소를 비공개로 유지할 것)
    signingConfigs {
        create("fixed") {
            storeFile = file("keystore/webtoonviewer.jks")
            storePassword = "webtoonviewer"
            keyAlias = "webtoonviewer"
            keyPassword = "webtoonviewer"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            signingConfig = signingConfigs.getByName("fixed")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
