buildscript {
    repositories {
        maven { url = uri("https://storage.googleapis.com/r8-releases/raw") }
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.r8)
    }
}

// Top-level build file where you can add configuration options common to all subprojects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.hilt) apply false
}