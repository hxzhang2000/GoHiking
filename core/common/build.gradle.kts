plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // N-36：PolylineJson 改用 kotlinx.serialization（纯 Kotlin），
    // 这样它能脱离 Android 框架在 JVM 单测里被测（原先的 org.json 是 Android 自带类，
    // 在单测里调用会抛 "not mocked"）
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.gohiking.core.common"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.javax.inject)
    implementation(libs.timber)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
