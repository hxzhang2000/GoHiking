# -*- coding: utf-8 -*-
"""M0 验证 · ⑫ 高程数据源连通性 / 配额 / 精度（T-07）

文档 §4.10 写的三级降级第一级是「第三方服务（OpenTopoData / Open-Elevation）」，
但没验证过在国内网络下能不能连通、批量能力如何。PC 上可以直接测（注意：测的是本机网络，
不等于手机蜂窝网络，结论要标注这一点）。
"""
import urllib.request, urllib.error, socket, json, time

socket.setdefaulttimeout(25)
UA = {"User-Agent": "Mozilla/5.0 GoHiking-M0-verify"}

# 取几个山区坐标（爬山场景真正的目标），以及一个平原对照
PTS = [
    ("北京·香山", 39.9926, 116.1916),
    ("泰山·玉皇顶", 36.2561, 117.1011),
    ("黄山·光明顶", 30.1320, 118.1620),
    ("四姑娘山", 31.1000, 102.9000),
    ("珠峰北坡", 28.1400, 86.8500),
    ("平原对照·北京", 39.9090, 116.3970),
]


def call(name, url, parse, timeout=25):
    t0 = time.time()
    try:
        req = urllib.request.Request(url, headers=UA)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read()
        dt = time.time() - t0
        try:
            return True, dt, parse(json.loads(body.decode("utf-8", "replace")))
        except Exception as e:
            return True, dt, f"（返回非预期 JSON: {str(e)[:60]} 原始 {body[:120]!r}）"
    except urllib.error.HTTPError as e:
        return False, time.time() - t0, f"HTTP {e.code} {e.reason}"
    except Exception as e:
        return False, time.time() - t0, f"{type(e).__name__}: {str(e)[:80]}"


print("=" * 92)
print("① Open-Elevation  https://api.open-elevation.com/api/v1/lookup")
for nm, la, lo in PTS:
    ok, dt, val = call(nm, f"https://api.open-elevation.com/api/v1/lookup?locations={la},{lo}",
                       lambda j: j["results"][0]["elevation"])
    print(f"   {nm:14s} {'✅' if ok else '❌'} {dt:5.2f}s  {val}")

print("\n   批量能力（一次请求多点多高）:")
for n in (10, 50, 100, 200):
    locs = "|".join(f"{39.9 + i*0.01},{116.4}" for i in range(n))
    ok, dt, val = call("batch", f"https://api.open-elevation.com/api/v1/lookup?locations={locs}",
                       lambda j: f"返回 {len(j['results'])} 个点", timeout=40)
    print(f"     n={n:4d}  {'✅' if ok else '❌'} {dt:6.2f}s  {val}")

print()
print("=" * 92)
print("② Open-Meteo Elevation  https://api.open-meteo.com/v1/elevation （Copernicus DEM GLO-90）")
for nm, la, lo in PTS:
    ok, dt, val = call(nm, f"https://api.open-meteo.com/v1/elevation?latitude={la}&longitude={lo}",
                       lambda j: j["elevation"])
    print(f"   {nm:14s} {'✅' if ok else '❌'} {dt:5.2f}s  {val}")
lats = ",".join(str(la) for _, la, _ in PTS)
lons = ",".join(str(lo) for _, _, lo in PTS)
ok, dt, val = call("batch", f"https://api.open-meteo.com/v1/elevation?latitude={lats}&longitude={lons}",
                   lambda j: j["elevation"])
print(f"   批量 6 点  {'✅' if ok else '❌'} {dt:5.2f}s  {val}")

print()
print("=" * 92)
print("③ OpenTopoData  https://api.opentopodata.org")
for ds in ("test-dataset", "srtm90m", "srtm30m", "aster30m"):
    ok, dt, val = call(ds, f"https://api.opentopodata.org/v1/{ds}?locations={PTS[1][1]},{PTS[1][2]}",
                       lambda j: j["results"][0]["elevation"], timeout=30)
    print(f"   /v1/{ds:14s} {'✅' if ok else '❌'} {dt:5.2f}s  {val}")

print()
print("=" * 92)
print("④ 其它候选")
cands = [
    ("Open-Meteo 主站可达性", "https://api.open-meteo.com/v1/forecast?latitude=39.9&longitude=116.4&current=temperature_2m",
     lambda j: "ok"),
    ("elevation-api.io（需 key）", "https://elevation-api.io/api/elevation?points=39.9,116.4", lambda j: j),
]
for nm, url, p in cands:
    ok, dt, val = call(nm, url, p)
    print(f"   {nm:26s} {'✅' if ok else '❌'} {dt:5.2f}s  {str(val)[:110]}")
