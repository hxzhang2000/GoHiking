# -*- coding: utf-8 -*-
"""M0 验证 · ⑧ 用修好的解析器重做关键核查（T-13 / T-15 / T-02 / PRD 6.2.1 类存在性）"""
import sys, os, re, collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from jlib import Jar  # noqa: E402

DL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dl")


def load(art, ver):
    return Jar(open(os.path.join(DL, f"{art}-{ver}.jar"), "rb").read())


def pkg_list(j, prefix, limit=40):
    out = sorted(n for n in j.names if n.startswith(prefix))
    for n in out[:limit]:
        print("     ", n)
    if len(out) > limit:
        print(f"      …… 共 {len(out)} 个")
    return out


js = load("search", "9.7.1")
print("### search:9.7.1  class =", len(js.names))
print("  services/poi/ :"); pkg_list(js, "com/amap/api/services/poi/")
print("  services/help/ :"); pkg_list(js, "com/amap/api/services/help/")
print("  services/route/ :"); pkg_list(js, "com/amap/api/services/route/", limit=60)

print("\n[PRD 6.2.1] 选点依赖类最终定位:")
for path, tag in [("com/amap/api/services/poi/PoiSearch.class", "PoiSearch"),
                  ("com/amap/api/services/poi/PoiSearch$Query.class", "PoiSearch.Query"),
                  ("com/amap/api/services/search/SearchBound.class", "SearchBound"),
                  ("com/amap/api/services/core/PoiItem.class", "PoiItem"),
                  ("com/amap/api/services/core/Tip.class", "Tip"),
                  ("com/amap/api/services/help/Inputtips.class", "Inputtips"),
                  ("com/amap/api/services/help/InputtipsQuery.class", "InputtipsQuery"),
                  ("com/amap/api/services/geocoder/GeocodeSearch.class", "GeocodeSearch")]:
    print(f"    {tag:20s} {'✅ ' + path if path in js.names else '❌ 不在 search 包里'}")

print("\n[T-15] RouteSearch 内部类:")
inner = sorted({n.split("$")[-1][:-6] for n in js.names if re.search(r"services/route/RouteSearch\$", n)})
print("     ", inner)

p, c = js.one(r"services/route/RouteSearch\.class$")
if c and "error" not in c:
    print("  RouteSearch 里 WALK*/MULTI* 相关字符串常量:")
    print("     ", sorted({v for v in c["utf8"] if re.search(r"WALK|MULTI", v)}))
    print("  setWalkRouteQuery / mode 相关方法:")
    for n, d, a in c["methods"]:
        if re.search(r"Walk|Mode", n):
            print(f"      {n}{d}")

p, c = js.one(r"services/route/RouteSearch\$WalkRouteQuery\.class$")
print("\n[T-15②] RouteSearch.WalkRouteQuery 的构造与方法（决定要不要传 mode）：")
if c and "error" not in c:
    for n, d, a in c["methods"]:
        print(f"      {n}{d}")
    print("  字段:", [(n, d) for n, d, a in c["fields"]])
    print("  utf8 里含 MODE/多路径字样的:", sorted({v for v in c["utf8"] if re.search(r"MODE|MULTI|WALK", v)}))
else:
    print("     解析失败:", c)

print("\n[T-15①⑤] 全 search 包 WALK_MULTI_PATH 字样:",
      js.grep("WALK_MULTI_PATH", limit=5) or "❌ 未出现")
print("            全 search 包出现 'MULTI' 的类:", js.grep("MULTI", limit=8) or "❌ 未出现")
print("            全 search 包出现 'WALK_DEFAULT' 的类:", js.grep("WALK_DEFAULT", limit=5) or "❌ 未出现")

p, c = js.one(r"services/route/WalkPath\.class$")
if c and "error" not in c:
    print("\n  WalkPath 方法（看能否拿到多条方案与每条的距离/时长）:")
    for n, d, a in c["methods"][:25]:
        print(f"      {n}{d}")

# ---------------- 导航包 ----------------
jn = load("navi-3dmap", "10.0.800_3dmap10.0.800")
print("\n### navi-3dmap  class =", len(jn.names))
p, c = jn.one(r"navi/enums/TravelStrategy\.class$")
print("[T-15④] TravelStrategy 枚举常量:")
if c and "error" not in c:
    print("     ", [n for n, d, a in c["fields"]])
    print("      utf8:", sorted(v for v in c["utf8"] if re.search(r"^[A-Z_]{3,}$", v)))
else:
    print("     失败:", c)
p, c = jn.one(r"navi/AMapNavi\.class$")
if c and "error" not in c:
    print("  AMapNavi 步行/多路径相关方法:")
    for n, d, a in c["methods"]:
        if re.search(r"[Ww]alk|Multi|NaviPaths|Strategy", n):
            print(f"      {n}{d}")
print("  全导航包出现 'MULTIPLE' 的类:", jn.grep("MULTIPLE", limit=8) or "❌ 未出现")

# ---------------- 定位包 ----------------
jl = load("location", "6.4.9")
print("\n### location:6.4.9")
p, c = jl.one(r"location/AMapLocationClientOption\$AMapLocationMode\.class$")
print("[T-13] AMapLocationMode 枚举常量:")
if c and "error" not in c:
    print("     ", [n for n, d, a in c["fields"]])
    print("      utf8 全集:", sorted(c["utf8"]))
else:
    print("     失败:", c)

# ---------------- 地图包 ----------------
jm = load("3dmap", "10.0.600")
print("\n### 3dmap:10.0.600")
p, c = jm.one(r"maps/AMap\.class$")
print("[T-02] AMap.setMapLanguage 签名:", [(n, d) for n, d, a in c["methods"] if "anguage" in n] if c and "error" not in c else c)
print("      3dmap 内类名含 Language:", jm.find(r"[Ll]anguage")[:10])
print("      全包出现 'zh_cn' 的类:", jm.grep("zh_cn", limit=5) or "无")
print("      全包出现 'en' 的类（前 5）:", jm.grep("en", limit=5))
p, c = jm.one(r"maps/MapsInitializer\.class$")
print("[T-02] MapsInitializer.setTerrainEnable / setContourLIneEnable 是否存在:")
if c and "error" not in c:
    for n, d, a in c["methods"]:
        if re.search(r"Terrain|Contour|Language", n):
            print(f"      {n}{d}")
