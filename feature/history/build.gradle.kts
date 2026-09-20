plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gohiking.feature.history"
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
    implementation(project(":core:data")) // TripRepository（P-10 最小切片）
    implementation(project(":core:database")) // TripEntity / TripSummaryRow（VM 内映射 UI 模型）
    implementation(project(":core:common")) // Formatters / PolylineSimplifier

    implementation(libs.amap.combined) // 详情页地图（F-HIS-21）；高德统一三合一（D-17）
    implementation(libs.vico.compose.m3) // 详情页图表（F-HIS-23/24，M4-A；DEV §1.3 锁定 2.1.4）
    implementation(libs.coil.compose) // 照片缩略图条（F-HIS-28 / F-MEDIA-40）
    implementation(libs.coil.video) // 视频首帧（F-MEDIA-22）

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
}