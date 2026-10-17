import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "io.github.marquesource.pixora"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.marquesource.pixora"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Yayın imzası: proje köküne (git'e girmeyen) keystore.properties koyulursa kullanılır.
    // storeFile=/yol/anahtar.jks, storePassword=..., keyAlias=..., keyPassword=...
    val keystorePropsFile = rootProject.file("keystore.properties")
    val releaseSigning = if (keystorePropsFile.exists()) {
        val props = Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else null

    buildTypes {
        release {
            if (releaseSigning != null) signingConfig = releaseSigning
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}")
        }
        jniLibs {
            useLegacyPackaging = false
            // Sistem kütüphaneleri (libOpenCL/libvulkan) APK'ya alınmaz; telefonda zaten var
            excludes += listOf("**/libOpenCL.so", "**/libvulkan.so")
            pickFirsts += listOf("**/libc++_shared.so")
        }
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    androidResources {
        noCompress += listOf("tflite")
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")

    // Lifecycle & Coroutines
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Preferences & Exif
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Glide
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-android-compiler:2.51.1")

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // LiteRT (TensorFlow Lite'ın yeni adı; aynı org.tensorflow.lite API'si) — sahne, Zero-DCE, MiDaS modelleri.
    // TFLite 2.16 yerel kütüphaneleri Android 15'in 16 KB bellek sayfalarına hizalı değildi.
    // select-tf-ops (68 MB) kaldırıldı: modellerin hiçbiri TF "Flex" operasyonu kullanmıyor.
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    implementation("com.google.ai.edge.litert:litert-api:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.2")
    implementation("com.google.ai.edge.litert:litert-gpu-api:1.4.2")

    // OpenCV (resmi Android paketi; 16 KB sayfa hizalı). Eskiden 2021 tarihli quickbird 4.5.3 kullanılıyordu.
    implementation("org.opencv:opencv:4.13.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
