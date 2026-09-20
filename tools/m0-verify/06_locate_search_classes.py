# -*- coding: utf-8 -*-
"""M0 验证 · ⑥ 定位 PoiSearch / Inputtips 到底在哪个 artifact + 正确解析 RouteSearch

上一轮的 parser 有偏移 bug，先修再查；并列出 search jar 的真实包结构。
"""
import sys, os, re, urllib.request, socket, collections

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from jlib import Jar, parse_class, dump_methods  # noqa: E402

socket.setdefaulttimeout(120)
UA = {"User-Agent": "Mozilla/5.0 GoHiking-M0-verify"}
CENTRAL = "https://repo1.maven.org/maven2"
DL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dl")


def load(art, ver):
    p = os.path.join(DL, f"{art}-{ver}.jar")
    if os.path.exists(p) and os.path.getsize(p) > 10000:
        return Jar(open(p, "rb").read())
    blob = urllib.request.urlopen(
        urllib.request.Request(f"{CENTRAL}/com/amap/api/{art}/{ver}/{art}-{ver}.jar", headers=UA),
        timeout=240).read()
    open(p, "wb").write(blob)
    return Jar(blob)


def packages(j, depth=4):
    c = collections.Counter()
    for n in j.names:
        parts = n.split("/")
        c["/".join(parts[:min(depth, len(parts) - 1)])] += 1
    return c


js = load("search", "9.7.1")
print(f"### search:9.7.1  class={len(js.names)}")
print("顶层包分布:")
for k, v in sorted(packages(js, 3).items(), key=lambda x: -x[1])[:18]:
    print(f"   {k:44s} {v}")
print()
print("类名含 poi/pit/ips 的（不分大小写）:")
hits = [n for n in js.names if re.search(r"(?i)poi|tips|geocode", n)]
print(f"   共 {len(hits)} 个")
for n in hits[:30]:
    print("   ", n)

print()
print("=== 3dmap:10.0.600 里是否藏着搜索相关类 ===")
jm = load("3dmap", "10.0.600")
hits2 = [n for n in jm.names if re.search(r"(?i)poi|tips|geocode", n)]
print(f"   {len(hits2)} 个"); [print("   ", n) for n in hits2[:20]]

print()
print("=== 正确解析后的 RouteSearch / WalkRouteQuery ===")
j = js
p, c = j.one(r"services/route/RouteSearch\.class$")
print("RouteSearch 解析:", "OK" if c and "error" not in c else c)
if c and "error" not in c:
    inner = sorted({n.split("$")[-1][:-6] for n in j.find(r"RouteSearch\$[A-Za-z]+\.class$")})
    print(" 内部类:", inner)
    print(" WALK 相关常量:", sorted({v for v in c["utf8"] if "WALK" in v or "MULTI" in v}))
    print(" 与 mode 有关的方法:", [(n, d) for n, d, a in c["methods"] if "Mode" in n][:10])

p, c = j.one(r"services/route/RouteSearch\$WalkRouteQuery\.class$")
print("\nRouteSearch.WalkRouteQuery 解析:", "OK" if c and "error" not in c else c)
if c and "error" not in c:
    for n, d, a in c["methods"]:
        print(f"   {n}{d}")
    print(" 字段:", [(n, d) for n, d, a in c["fields"]])
    print(" utf8 含 MODE 的:", sorted({v for v in c["utf8"] if "MODE" in v or "Mode" in v}))
    print(" utf8 含 WALK 的:", sorted({v for v in c["utf8"] if "WALK" in v}))

p, c = j.one(r"services/route/RouteSearch\$FromAndTo\.class$")
print("\nRouteSearch.FromAndTo:", "OK" if c and "error" not in c else c)
if c and "error" not in c:
    for n, d, a in c["methods"]:
        if n.startswith("<init>") or "LatLon" in n:
            print(f"   {n}{d}")

print("\n全 search 包 grep WALK_MULTI_PATH:", j.grep("WALK_MULTI_PATH", limit=5))
print("全 search 包 grep WALK_DEFAULT:", j.grep("WALK_DEFAULT", limit=5))
print("全 search 包 grep MULTI:", j.grep("MULTI", limit=8))

print()
print("=== 导航包 TravelStrategy ===")
jn = load("navi-3dmap", "10.0.800_3dmap10.0.800")
p, c = jn.one(r"navi/enums/TravelStrategy\.class$")
print("TravelStrategy 解析:", "OK" if c and "error" not in c else c)
if c and "error" not in c:
    print(" 枚举常量:", [n for n, d, a in c["fields"]])
    print(" utf8 常量全集:", sorted(v for v in c["utf8"] if v.isupper() or "_" in v)[:30])
print("全导航包 grep MULTIPLE:", jn.grep("MULTIPLE", limit=8))
p, c = jn.one(r"navi/AMapNavi\.class$")
print("AMapNavi 解析:", "OK" if c and "error" not in c else c)
if c and "error" not in c:
    print(" 步行/多路径相关方法:")
    for n, d, a in c["methods"]:
        if re.search(r"[Ww]alk|Multi|NaviPaths|Strategy", n):
            print(f"   {n}{d}")

print()
print("=== 定位：AMapLocationMode 枚举常量（T-13 定论） ===")
jl = load("location", "6.4.9")
p, c = jl.one(r"location/AMapLocationClientOption\$AMapLocationMode\.class$")
print("解析:", "OK" if c and "error" not in c else c)
if c and "error" not in c:
    print(" 枚举常量:", [n for n, d, a in c["fields"]])
    print(" utf8:", sorted(c["utf8"])[:20])

print()
print("=== 地图：语言相关（T-02） ===")
p, c = jm.one(r"maps/AMap\.class$")
print("AMap.setMapLanguage:", [(n, d) for n, d, a in c["methods"] if "anguage" in n] if c and "error" not in c else c)
print("3dmap 里类名含 Language:", jm.find(r"[Ll]anguage")[:10])
