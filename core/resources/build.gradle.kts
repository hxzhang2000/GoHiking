plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.gohiking.core.resources"
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

// F-I18N-09 门禁：中英两份 strings.xml 键集合必须完全对齐（缺失或多出都算失败）。
// CI 在 assembleDebug 前调用 `:core:resources:checkStringKeys`。
val checkStringKeys by tasks.registering {
    group = "verification"
    description = "Verifies en (default) and zh-rCN strings.xml define the exact same key set."
    doLast {
        val en = file("src/main/res/values/strings.xml")
        val zh = file("src/main/res/values-zh-rCN/strings.xml")
        if (!en.exists() || !zh.exists()) {
            throw GradleException("strings.xml missing: en=${en.exists()} zh=${zh.exists()}")
        }
        val keyRegex = Regex("""<string name="([^"]+)"""")
        val enKeys = keyRegex.findAll(en.readText()).map { it.groupValues[1] }.toSet()
        val zhKeys = keyRegex.findAll(zh.readText()).map { it.groupValues[1] }.toSet()
        val missingInZh = enKeys - zhKeys
        val extraInZh = zhKeys - enKeys
        val problems = buildList {
            if (missingInZh.isNotEmpty()) add("missing in zh-rCN: $missingInZh")
            if (extraInZh.isNotEmpty()) add("extra in zh-rCN: $extraInZh")
        }
        if (problems.isNotEmpty()) {
            throw GradleException("F-I18N-09 key alignment failed -> " + problems.joinToString("; "))
        }
        println("checkStringKeys OK: ${enKeys.size} keys aligned (en == zh-rCN)")
    }
}

tasks.named("preBuild") { dependsOn(checkStringKeys) }
