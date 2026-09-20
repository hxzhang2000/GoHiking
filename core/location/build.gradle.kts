plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gohiking.core.location"
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
    implementation(project(":core:common"))
    // 定位 API 用 3dmap 内嵌的 com.amap.api.location.*：3dmap 10.0.600 已含完整定位实现
    // （col/3l 602 类），再引 location:6.4.9 会与 3dmap 重复 32 个类导致 checkDuplicateClasses 失败
    // （2026-09-20 实测裁定，libs.versions.toml 同步注明）
    implementation(libs.amap.map3d)
    implementation(libs.play.services.location) // Fused 备源（D-08）；GMS 不可用时运行时降级
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.javax.inject)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.timber)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
