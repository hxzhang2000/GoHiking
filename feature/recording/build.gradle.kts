plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gohiking.feature.recording"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:resources"))
    implementation(project(":core:data")) // RecordingSession / TripRepository（M1 切片：UI 直读会话状态）
    implementation(project(":core:common")) // Formatters（记录页统计展示）
    implementation(project(":core:database")) // TrackPointEntity / TripEntity（地图轨迹与保存弹窗）
    implementation(project(":core:map")) // PolylineArrowTexture（F-PLAN-37 方向箭头纹理）

    implementation(libs.amap.combined) // 记录页实时轨迹地图（F-REC-03）；高德统一三合一（D-17）

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended) // P-08 控制条图标
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
}