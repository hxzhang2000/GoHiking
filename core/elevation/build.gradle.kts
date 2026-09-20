plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt) // ElevationRepository @Singleton @Inject（DEV §3.3 DI 表）
}

android {
    namespace = "com.gohiking.core.elevation"
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
    implementation(project(":core:model")) // LatLngValue / Difficulty
    implementation(project(":core:common")) // GhResult / GhError
    implementation(project(":core:database")) // ElevationCacheDao（DEV §4.10 三级降级）
    implementation(project(":core:location")) // CoordinateConverter（GCJ→WGS）/ ThresholdAccumulator
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
