plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gohiking.feature.plan"
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
    implementation(project(":core:map")) // AmapSearchClient（F-PLAN-05）+ 地图渲染
    implementation(project(":core:location")) // LocationProvider（F-PLAN-07 当前位置）
    implementation(project(":core:common")) // Formatters（线路卡片距离/耗时，任务 #19）
    implementation(project(":core:database")) // PlannedRouteDao（F-PLAN-14 计划线路落库）
    implementation(project(":core:elevation")) // RouteEvaluator（F-PLAN-11/46 爬升与难度估算）

    // 地图类型显式声明（implementation 非传递，前车之鉴 ×3）；高德统一走三合一包（D-17）
    implementation(libs.amap.combined)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
}