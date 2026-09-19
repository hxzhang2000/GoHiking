// GoHiking 根构建脚本：只集中声明插件版本，各模块按需 apply。
// 版本基线见 docs/DEV-DESIGN.md §1.3（M0 锁定：AGP 8.7.3 + Gradle 8.9 + JDK 17 + Kotlin 2.1.0）。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
