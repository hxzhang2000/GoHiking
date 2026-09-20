# -*- coding: utf-8 -*-
"""M0 验证 · ②b 解包高德 SDK 的 jar（公网 Maven 实际发布物是 jar，不是 aar）

回答：T-05 ClusterOverlay / T-06+T-16 隐私接口 / T-13 常量拼写 / T-17 混淆规则 /
      T-02 setMapLanguage / T-15 多路径 API 面 / PRD 6.2.1 的 Inputtips+PoiSearch 是否在包里
"""
import urllib.request, urllib.error, re, io, os, socket, zipfile, struct

socket.setdefaulttimeout(90)
UA = {"User-Agent": "Mozilla/5.0 GoHiking-M0-verify"}
CENTRAL = "https://repo1.maven.org/maven2"
OUT = r"D:\hxzhang\MyGithubSoftware\GoHiking\.tmp\verify-m0\dl"
os.makedirs(OUT, exist_ok=True)


def fetch(url, timeout=180):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def meta(group, art, repo=CENTRAL):
    try:
        t = fetch(f"{repo}/{group.replace('.', '/')}/{art}/maven-metadata.xml").decode("utf-8", "replace")
        return re.findall(r"<version>([^<]+)</version>", t)
    except Exception as e:
        return None


def parse_class(data):
    cp, p = {}, 8
    cp_count = struct.unpack_from(">H", data, p)[0]; p += 2
    i = 1
    while i < cp_count:
        tag = data[p]; p += 1
        if tag == 1:
            ln = struct.unpack_from(">H", data, p)[0]; p += 2
            cp[i] = data[p:p + ln].decode("utf-8", "replace"); p += ln
        elif tag in (7, 8, 16, 19, 20):
            p += 2
        elif tag == 15:
            p += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            p += 4
        elif tag in (5, 6):
            p += 8; i += 1
        else:
            raise ValueError(f"cp tag {tag}")
        i += 1

    def u2():
        nonlocal p
        v = struct.unpack_from(">H", data, p)[0]; p += 2
        return v

    def skip_attrs():
        nonlocal p
        for _ in range(u2()):
            p += 2
            ln = struct.unpack_from(">I", data, p)[0]; p += 4
            p += ln

    p += 2
    this_cls = u2(); p += 2
    for _ in range(u2()):
        p += 2

    def members():
        nonlocal p
        res = []
        for _ in range(u2()):
            acc = struct.unpack_from(">H", data, p)[0]; p += 2
            ni, di = u2(), u2()
            res.append((cp.get(ni, "?"), cp.get(di, "?"), acc))
            skip_attrs()
        return res

    fields, methods = members(), members()
    return {"name": cp.get(this_cls, "?"), "fields": fields, "methods": methods,
            "utf8": set(v for v in cp.values() if isinstance(v, str))}


class Jar:
    def __init__(self, blob, label):
        self.z = zipfile.ZipFile(io.BytesIO(blob))
        self.label = label
        self.entries = self.z.namelist()
        self.names = [n for n in self.entries if n.endswith(".class")]
        self._c = {}

    def get(self, path):
        if path not in self._c:
            try:
                self._c[path] = parse_class(self.z.read(path))
            except Exception as e:
                self._c[path] = {"error": f"{type(e).__name__}: {e}"}
        return self._c[path]

    def find(self, pattern):
        return [n for n in self.names if re.search(pattern, n)]

    def methods_of(self, pattern):
        f = self.find(pattern)
        return self.get(f[0]) if f else None

    def grep(self, needle, limit=10):
        out = []
        for n in self.names:
            c = self.get(n)
            if "utf8" in c and any(needle in v for v in c["utf8"]):
                out.append(n)
                if len(out) >= limit:
                    break
        return out


def show(c, want, label):
    if not c:
        print(f"    ❌ {label}: 类不存在")
        return
    if "error" in c:
        print(f"    [!] {label}: {c['error']}")
        return
    hit = [(n, d) for n, d, a in c["methods"] if any(w.lower() in n.lower() for w in want)]
    print(f"    {label} → {c['name']}")
    for n, d in hit:
        print(f"        {n}{d}")
    if not hit:
        print(f"        （无 {want} 相关方法）")


# ============ ① 有哪些高德 artifact ============
print("== 高德各 artifact 在 Maven Central 的可用性 ==")
for art in ["3dmap", "location", "search", "navi", "navi-3dmap", "3dmap-location", "sdk"]:
    v = meta("com.amap.api", art)
    print(f"  com.amap.api:{art:14s} {'✅ ' + str(len(v)) + ' 个版本, 最新 ' + v[-1] if v else '❌ 不存在'}")

# ============ ② 地图 SDK ============
MAPVER = "10.0.600"
print(f"\n{'='*96}\n### com.amap.api:3dmap:{MAPVER}（公网最新）")
blob = fetch(f"{CENTRAL}/com/amap/api/3dmap/{MAPVER}/3dmap-{MAPVER}.jar")
open(os.path.join(OUT, f"3dmap-{MAPVER}.jar"), "wb").write(blob)
print(f"  下载 {len(blob)/1024/1024:.1f} MB")
j = Jar(blob, "3dmap")
print(f"  jar 内 class {len(j.names)} 个")

print("\n  [T-17] 混淆规则是否内嵌（R8 会读 META-INF/proguard/ 与 META-INF/com.android.tools/）:")
pro = [e for e in j.entries if re.search(r"proguard|com\.android\.tools|META-INF/", e, re.I)]
print("    ", pro[:20] if pro else "❌ jar 内无任何 proguard/META-INF 规则 → 必须手写 keep 规则")

print("\n  [T-05] Cluster 相关类（决定自研聚类还是用官方）:")
cf = j.find(r"Cluster|cluster")
print("    ", cf[:25] if cf else "❌ 无 Cluster 类")

print("\n  [T-06/T-16] Map 版隐私接口（MapsInitializer）:")
mi = j.methods_of(r"maps/MapsInitializer\.class$")
show(mi, ["Privacy"], "MapsInitializer")
if mi and "error" not in mi:
    print("        该类方法名全集:", sorted({n for n, d, a in mi["methods"]}))

print("\n  [T-02] 地图语言切换 API 面（存在 ≠ 生效，生效仍需真机）:")
amap = j.methods_of(r"maps/AMap\.class$")
if amap and "error" not in amap:
    print("        AMap 中 Language 相关方法:",
          [(n, d) for n, d, a in amap["methods"] if "anguage" in n])
print("        MapLanguage 类:", j.find(r"MapLanguage\.class$") or "❌ 无")

print("\n  [T-15] 步行多路径 API 面:")
wq = j.methods_of(r"services/route/WalkRouteQuery\.class$")
if wq and "error" not in wq:
    print("        WalkRouteQuery 构造/方法（看是否带 mode）:")
    for n, d, a in wq["methods"]:
        print(f"          {n}{d}")
rs = j.methods_of(r"services/route/RouteSearch\.class$")
if rs and "error" not in rs:
    print("        RouteSearch 的 WALK* 字段:")
    for n, d, a in rs["fields"]:
        if "ALK" in n:
            print(f"          {n}  ({d})")
    print("        utf8 里是否出现 WALK_MULTI_PATH:",
          "✅ 有" if any("WALK_MULTI_PATH" in v for v in rs["utf8"]) else "❌ 无")
print("        WalkPath / WalkStep 类:", j.find(r"services/route/Walk.*\.class$")[:8])

print("\n  [PRD 6.2.1] 搜索选点依赖的类是否都在:")
for pat, tag in [(r"services/poi/PoiSearch\.class$", "PoiSearch"),
                 (r"services/inputtips/Inputtips\.class$", "Inputtips"),
                 (r"services/inputtips/InputtipsQuery\.class$", "InputtipsQuery"),
                 (r"services/poi/PoiItem\.class$", "PoiItem"),
                 (r"services/search/SearchBound\.class$", "SearchBound")]:
    print(f"        {tag:14s} {'✅' if j.find(pat) else '❌ 缺失'}")

# ============ ③ 定位 SDK ============
LOCVER = "6.4.9"
print(f"\n{'='*96}\n### com.amap.api:location:{LOCVER}")
blob = fetch(f"{CENTRAL}/com/amap/api/location/{LOCVER}/location-{LOCVER}.jar")
open(os.path.join(OUT, f"location-{LOCVER}.jar"), "wb").write(blob)
print(f"  下载 {len(blob)/1024:.0f} KB")
jl = Jar(blob, "location")
print(f"  jar 内 class {len(jl.names)} 个")
print("  [T-06] AMapLocationClient 隐私接口:")
show(jl.methods_of(r"location/AMapLocationClient\.class$"), ["Privacy"], "AMapLocationClient")
print("\n  [T-13] 定位模式常量拼写:")
opt = jl.methods_of(r"location/AMapLocationClientOption\.class$")
if opt and "error" not in opt:
    flds = [n for n, d, a in opt["fields"]]
    print("        含 Accuracy 的字段:", [x for x in flds if "ccuracy" in x])
    print("        ", "✅ `Hight_Accuracy` 仍在（高德原文拼写）" if any(x == "Hight_Accuracy" for x in flds)
          else "⚠ 未见 Hight_Accuracy")
print("        AMapLocationClientOption 方法名里含 LocationMode 的:",
      [(n, d) for n, d, a in (opt or {"methods": []})["methods"] if "Mode" in n][:12])
