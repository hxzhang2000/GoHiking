plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gohiking.core.database"
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

// PRD 7.1：导出 Room schema，供迁移测试使用。
// ⚠ KSP 扩展必须注册在【项目级顶层】（与 android {} 平级）——DEV §7.1（审阅 H-2）。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DI：DatabaseModule 提供 GhDatabase 与各 DAO（DEV §2.2）
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
