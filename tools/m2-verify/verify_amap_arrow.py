# -*- coding: utf-8 -*-
"""校验高德三合一 SDK 里 PolylineOptions 的箭头/纹理相关 API 是否真的存在。

L-25：此前 `sys.path` 与 jar 路径都硬编码成本机绝对路径，换一台机器必然 ImportError /
FileNotFoundError，脚本等于不可复用。现在按以下顺序定位：
    1) 命令行参数：`python tools/m2-verify/verify_amap_arrow.py <jar路径>`
    2) 环境变量：`AMAP_JAR=<jar路径>`（jlib 用 `JLIB_DIR` 指向它所在目录）
    3) 都不给：在 ~/.gradle 缓存里按版本 glob 自动定位
定位不到就给出明确报错，而不是抛一段看不懂的堆栈。
"""
import glob
import os
import pathlib
import sys

JLIB_DIR = os.environ.get("JLIB_DIR", "")
if JLIB_DIR:
    sys.path.insert(0, JLIB_DIR)
try:
    from jlib import Jar, dump_methods
except ImportError:
    sys.exit(
        "找不到 jlib。请设置 JLIB_DIR 指向包含 jlib.py 的目录：\n"
        "    JLIB_DIR=/path/to/scripts python tools/m2-verify/verify_amap_arrow.py"
    )

AMAP_VERSION = os.environ.get("AMAP_VERSION", "10.0.700_loc6.4.5_sea9.7.2")

jar_path = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("AMAP_JAR", "")
if not jar_path:
    pattern = os.path.expanduser(
        "~/.gradle/caches/modules-2/files-2.1/com.amap.api/3dmap-location-search/"
        f"{AMAP_VERSION}/*/3dmap-location-search-{AMAP_VERSION}.jar"
    )
    hits = sorted(glob.glob(pattern))
    if len(hits) == 1:
        jar_path = hits[0]
    elif not hits:
        sys.exit(
            f"未在 Gradle 缓存里找到 {AMAP_VERSION} 的 jar。\n"
            "请显式传入路径：python tools/m2-verify/verify_amap_arrow.py <jar路径>"
        )
    else:
        sys.exit("缓存里匹配到多个 jar，请显式指定其一：\n  " + "\n  ".join(hits))

j = Jar(pathlib.Path(jar_path).read_bytes())

path, c = j.one(r"PolylineOptions\.class$")
dump_methods(c, "PolylineOptions", want=["Custom", "Arrow", "arrow", "custom", "Dotted", "dotted", "zIndex", "texture"])

print("=== grep constants ===")
for hit in j.grep("ustomTexture"):
    print(repr(hit))
