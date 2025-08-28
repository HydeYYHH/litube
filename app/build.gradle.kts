plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.hhst.youtubelite"
    compileSdk = 35

    lint {
        disable.add("MissingTranslation")
    }

    defaultConfig {
        applicationId = "com.hhst.litube"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "1.6.0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"


        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
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
                isMinifyEnabled = true
                isShrinkResources = true
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

    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    dependencies {
        compileOnly(libs.lombok)
        annotationProcessor(libs.lombok)
        coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
        implementation(libs.newpipeextractor)
        implementation(libs.library)
        implementation(libs.isoparser)
        implementation(libs.gson)
        implementation(libs.commons.io)
        implementation(libs.picasso)
        implementation(libs.media)
        implementation(libs.photoview)
        implementation(libs.appcompat)
        implementation(libs.material)
        implementation(libs.mmkv)
        implementation(libs.activity)
        implementation(libs.constraintlayout)
        implementation(libs.swiperefreshlayout)
        testImplementation(libs.junit)
        testImplementation(libs.mockito.core)
        androidTestImplementation(libs.ext.junit)
        androidTestImplementation(libs.espresso.core)
    }
}