import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 高德 Key 注入：local.properties（gitignored），仓库只保留 local.properties.example（DEV §1.4）。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// release 签名属性（gitignored）：本地 = keystore/gohiking.jks；
// CI 由 workflow 生成（Secrets base64 或临时 keystore，见 .github/workflows/build.yml）。
// 无该文件时 release 退回未签名构建（仅 assemble 不崩，不能分发）。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
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
        versionCode = (project.findProperty("gohiking.versionCode") as String?)?.toInt() ?: 1
        // 应用版本单一事实源 = gradle.properties 的 gohiking.versionName（升级流程见 CHANGELOG.md）
        versionName = (project.findProperty("gohiking.versionName") as String?) ?: "0.3.0"
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

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                // storeFile 相对 app/ 目录解析（本地值 ../keystore/gohiking.jks → 根 keystore/）
                storeFile = project.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 不加 applicationIdSuffix：debug/release 共用 com.gohiking.app，高德单 Key 双 SHA1 生效（用户 2026-09-20 决策）
            isMinifyEnabled = false
            manifestPlaceholders["AMAP_KEY"] = localProps.getProperty("AMAP_KEY_DEBUG").orEmpty()
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["AMAP_KEY"] = localProps.getProperty("AMAP_KEY_RELEASE").orEmpty()
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    lint {
        // PRD 9.7 / F-I18N-40/41 门禁：硬编码文案与缺失翻译在 CI 变红。
        warningsAsErrors = true
        abortOnError = true
        // 版本基线由 DEV §7.1.1 锁定（AGP 8.7.3 + Kotlin 2.1.0 + Room 2.6.1，全部核实过），升级由人工裁定而非 lint 驱动
        disable += "GradleDependency"
        // PRD 9.4：记录页锁定竖屏是有意为之
        disable += "LockedOrientationActivity"
        disable += "DiscouragedApi"
        // targetSdk 35 是 DEV §7.1.1 / PRD 9.4 锁定的基线；lint 数据库随环境认定「最新 API」不同
        // 会在 CI（较新判定）报 OldTargetApi 而本地不报——与 GradleDependency 同理，升级由人工裁定
        disable += "OldTargetApi"
    }

    // APK 命名：GoHiking-debug-v1-0-0.apk（参考 NASMusicTV-release-v2-36-0.apk 格式）
    applicationVariants.all {
        val variantName = name
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.outputFileName =
                "GoHiking-$variantName-v${versionName.replace(".", "-")}.apk"
        }
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
    implementation(project(":core:data"))
    implementation(project(":core:map"))
    implementation(project(":core:location")) // LocationProvider 注入（F-PLAN-07 计划页当前位置）
    implementation(project(":core:database")) // PlannedRouteDao 注入（F-PLAN-14 计划线路落库）
    implementation(project(":core:elevation")) // ElevationRepository 注入（F-PLAN-11/46 高程评估）
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
