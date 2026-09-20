# -*- coding: utf-8 -*-
"""M0 验证 · ① 依赖坐标与版本真实性核查（B1 / T-01 / T-10 / T-11 / T-14）

对 §1.3 里每一个「凭经验填的」版本号，去真实仓库取 maven-metadata.xml，
回答两个问题：① 这个版本到底存不存在？② 该 artifact 最新可用版本是什么？
"""
import urllib.request, urllib.error, re, socket, sys, json

socket.setdefaulttimeout(30)
UA = {"User-Agent": "Mozilla/5.0 GoHiking-M0-verify"}

CENTRAL = "https://repo1.maven.org/maven2"
GOOGLE = "https://dl.google.com/dl/android/maven2"
ALIYUN = "https://maven.aliyun.com/repository/public"

# (标签, 仓库, group, artifact, 假设版本)
TARGETS = [
    ("Android Gradle Plugin",   GOOGLE,  "com.android.tools.build", "gradle", "8.7.3"),
    ("Kotlin Gradle Plugin",    CENTRAL, "org.jetbrains.kotlin", "kotlin-gradle-plugin", "2.1.0"),
    ("Compose BOM",             GOOGLE,  "androidx.compose", "compose-bom", "2024.12.01"),
    ("Hilt",                    CENTRAL, "com.google.dagger", "hilt-android", "2.53.1"),
    ("Room",                    GOOGLE,  "androidx.room", "room-runtime", "2.6.1"),
    ("DataStore",               GOOGLE,  "androidx.datastore", "datastore-preferences", "1.1.1"),
    ("kotlinx-serialization",   CENTRAL, "org.jetbrains.kotlinx", "kotlinx-serialization-json", "1.7.3"),
    ("Coil3",                   CENTRAL, "io.coil-kt.coil3", "coil-compose", "3.0.4"),
    ("Timber",                  CENTRAL, "com.jakewharton.timber", "timber", "5.0.1"),
    ("Coroutines",              CENTRAL, "org.jetbrains.kotlinx", "kotlinx-coroutines-android", "1.9.0"),
    ("Navigation Compose",      GOOGLE,  "androidx.navigation", "navigation-compose", "2.8.5"),
    ("Vico compose-m3",         CENTRAL, "com.patrykandpatrick.vico", "compose-m3", "2.0.0-beta.2"),
    ("AppCompat",               GOOGLE,  "androidx.appcompat", "appcompat", "1.7.0"),
    ("core-ktx",                GOOGLE,  "androidx.core", "core-ktx", "1.15.0"),
    ("activity-compose",        GOOGLE,  "androidx.activity", "activity-compose", "1.9.3"),
    ("lifecycle-vm-compose",    GOOGLE,  "androidx.lifecycle", "lifecycle-viewmodel-compose", "2.8.7"),
    ("lifecycle-rt-compose",    GOOGLE,  "androidx.lifecycle", "lifecycle-runtime-compose", "2.8.7"),
    ("exifinterface",           GOOGLE,  "androidx.exifinterface", "exifinterface", "1.3.7"),
    ("KSB plugin marker",       CENTRAL, "com.google.devtools.ksp", "com.google.devtools.ksp.gradle.plugin", "2.1.0-1.0.29"),
    ("hilt plugin marker",      CENTRAL, "com.google.dagger.hilt.android", "com.google.dagger.hilt.android.gradle.plugin", "2.53.1"),
    ("kotlin-compose marker",   CENTRAL, "org.jetbrains.kotlin.plugin.compose", "org.jetbrains.kotlin.plugin.compose.gradle.plugin", "2.1.0"),
    ("高德地图 3D SDK",          ALIYUN,  "com.amap.api", "3dmap", "11.1.0"),
    ("高德定位 SDK",             ALIYUN,  "com.amap.api", "location", "6.4.9"),
]


def fetch(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req) as r:
        return r.status, r.read()


def versions_of(base, group, artifact):
    url = f"{base}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml"
    try:
        st, body = fetch(url)
    except urllib.error.HTTPError as e:
        return None, f"HTTP {e.code}"
    except Exception as e:
        return None, f"{type(e).__name__}"
    txt = body.decode("utf-8", "replace")
    vs = re.findall(r"<version>([^<]+)</version>", txt)
    latest = re.search(r"<latest>([^<]+)</latest>", txt)
    rel = re.search(r"<release>([^<]+)</release>", txt)
    return vs, (rel.group(1) if rel else (latest.group(1) if latest else "?"))


print(f"{'模块':26s} {'假设版本':16s} {'存在?':6s} {'仓库最新':16s} 备注")
print("-" * 110)
bad = []
for label, base, group, art, want in TARGETS:
    vs, info = versions_of(base, group, art)
    if vs is None:
        print(f"{label:26s} {want:16s} {'—':6s} {'取不到':16s} {info}")
        bad.append(f"{label}: metadata 取不到（{info}）")
        continue
    exists = "✅ 有" if want in vs else "❌ 无"
    if want not in vs:
        bad.append(f"{label}: 假设版本 {want} 不存在")
    # 找「同主次版本」的最新，便于替换
    top = [v for v in vs if not re.search(r"alpha|beta|rc|dev|SNAPSHOT", v, re.I)]
    print(f"{label:26s} {want:16s} {exists:6s} {(top[-1] if top else vs[-1]):16s} 共 {len(vs)} 个版本，最新 {vs[-1]}")

print()
print("== 需要改动的项 ==")
print("\n".join(bad) if bad else "无")

# 顺便枚举 com.amap.api 下到底有哪些 artifact（决定能不能全走 Maven）
print()
print("== maven.aliyun.com 上 com.amap.api 的全部 artifact ==")
try:
    st, body = fetch(ALIYUN + "/com/amap/api/")
    htm = body.decode("utf-8", "replace")
    arts = sorted(set(re.findall(r'href="([^"/]+)/"', htm)))
    print(arts)
except Exception as e:
    print("枚举失败:", type(e).__name__, e)
