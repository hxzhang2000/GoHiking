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
        // L-21：原正则会连注释里的 <string name="..."> 一起匹配 —— 把某个键注释掉，
        // 门禁依然「通过」，等于可以静默绕过。先剥离 XML 注释再提取键。
        val commentRegex = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
        fun keysOf(f: java.io.File): Set<String> {
            val text = f.readText().let { commentRegex.replace(it, "") }
            // 顺带抓出重名键：两份文件各自内部重名时，后面的会静默覆盖前面的
            val all = Regex("""<string name="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()
            val dup = all.groupBy { it }.filter { it.value.size > 1 }.keys
            if (dup.isNotEmpty()) {
                throw GradleException("${f.name} 存在重名 string 键：$dup")
            }
            return all.toSet()
        }
        val enKeys = keysOf(en)
        val zhKeys = keysOf(zh)
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
