plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gohiking.feature.media"
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
    implementation(project(":core:data")) // MediaRepository / MediaClusterer（M4-B2）
    implementation(project(":core:database")) // MediaIndexEntity（UI 直接用实体，项目惯例）
    implementation(project(":core:common")) // Formatters
    implementation(project(":core:map")) // AmapSearchClient 逆地理（F-MEDIA-23）

    implementation(libs.amap.combined) // 照片地图（P-12）；高德统一三合一（D-17）
    implementation(libs.coil.compose) // 缩略图（F-MEDIA-14/22）
    implementation(libs.coil.video)
    implementation(libs.androidx.activity.compose) // rememberLauncherForActivityResult 媒体权限（F-MEDIA-04）
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
}