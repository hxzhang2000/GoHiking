# GoHiking Release 混淆规则（DEV §7.1）
# ✅ 高德 jar 内没有任何 proguard/consumer 规则（T-17 实测），全部手写。

# ---------- 高德 SDK ----------
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**

# 保留反射/序列化用到的注解与内部类（keepattributes 全仓只声明一次）
-keepattributes *Annotation*, InnerClasses, Signature

# kotlinx.serialization：保留 @Serializable 生成的序列化器（导入旧文件的未知子类降级依赖它）
-keepclassmembers class com.gohiking.** {
    *** Companion;
}
-keepclasseswithmembers class com.gohiking.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room 生成的实现
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
