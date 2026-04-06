plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.hhst.youtubelite"
    compileSdk = 36

    installation {
        installOptions.add("-t")
    }

    lint {
        disable.add("MissingTranslation")
        disable.add("ExtraTranslation")
        abortOnError = false
    }

    defaultConfig {
        applicationId = "com.google.LitePipe"
        minSdk = 26
        targetSdk = 36
        versionCode = 110
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }

    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.core.splashscreen)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    implementation(libs.newpipeextractor)
    implementation(libs.isoparser)
    implementation(libs.gson)
    implementation(libs.glide)
    annotationProcessor(libs.glide.compiler)
    implementation(libs.media)
    implementation(libs.photoview)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.mmkv)
    implementation(libs.activity)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.constraintlayout)
    implementation(libs.swiperefreshlayout)
    implementation(libs.webkit)
    implementation(libs.viewpager2)
    implementation(libs.recyclerview)
    implementation(libs.hilt.android)
    annotationProcessor(libs.hilt.compiler)
    implementation(libs.commons.io)
    implementation(libs.documentfile)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
