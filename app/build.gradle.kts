import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 高德 Key 注入：local.properties（gitignored），仓库只保留 local.properties.example（DEV §1.4）。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.gohiking.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gohiking.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0" // 与 PRD 7.3 generator.versionName 一致
        // AGP 8.7.3 < 8.8 → 用 resourceConfigurations；AGP ≥ 8.8 换 androidResources.localeFilters（DEV §7.1 T-19）
        resourceConfigurations += listOf("en", "zh-rCN")
        vectorDrawables.useSupportLibrary = true
        manifestPlaceholders["AMAP_KEY"] = localProps.getProperty("AMAP_KEY_DEBUG").orEmpty()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug" // 包名变化 → 高德 Key 必须单独申请 debug Key（DEV §7.1.1 末注）
            isMinifyEnabled = false
            manifestPlaceholders["AMAP_KEY"] = localProps.getProperty("AMAP_KEY_DEBUG").orEmpty()
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["AMAP_KEY"] = localProps.getProperty("AMAP_KEY_RELEASE").orEmpty()
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    lint {
        // PRD 9.7 / F-I18N-40/41 门禁：硬编码文案与缺失翻译在 CI 变红。
        warningsAsErrors = true
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:resources"))

    implementation(project(":feature:home"))
    implementation(project(":feature:history"))
    implementation(project(":feature:io"))
    implementation(project(":feature:media"))
    implementation(project(":feature:plan"))
    implementation(project(":feature:recording"))
    implementation(project(":feature:settings"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.timber)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
