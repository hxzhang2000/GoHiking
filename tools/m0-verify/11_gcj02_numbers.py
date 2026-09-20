# -*- coding: utf-8 -*-
"""M0 验证 · ⑪ 坐标转换的「数」到底是什么（B2 / B3 / B4 + T-09 的算法侧）

这三个都是**纯算法属性**，不需要真机、不需要 Key，PC 上算一遍就有权威答案：
  B3：GCJ-02 相对 WGS-84 的偏移量真实区间（原文拍「300–800m」，后放宽为「100–1000m」）
  B4：「用 GCJ-02 算距离」的误差到底多大（原文写 < 0.5%，未实测）
  B2：GCJ→WGS 迭代反解需要几次收敛（原文写「实测 < 4 次」，是编的）
"""
import math
import random

PI = math.pi
A = 6378245.0
EE = 0.00669342162296594323


def out_of_china(lat, lon):
    return not (72.004 <= lon <= 137.8347 and 0.8293 <= lat <= 55.8271)


def _tlat(x, y):
    r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
    r += (20.0 * math.sin(6.0 * x * PI) + 20.0 * math.sin(2.0 * x * PI)) * 2.0 / 3.0
    r += (20.0 * math.sin(y * PI) + 40.0 * math.sin(y / 3.0 * PI)) * 2.0 / 3.0
    r += (160.0 * math.sin(y / 12.0 * PI) + 320 * math.sin(y * PI / 30.0)) * 2.0 / 3.0
    return r


def _tlon(x, y):
    r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
    r += (20.0 * math.sin(6.0 * x * PI) + 20.0 * math.sin(2.0 * x * PI)) * 2.0 / 3.0
    r += (20.0 * math.sin(x * PI) + 40.0 * math.sin(x / 3.0 * PI)) * 2.0 / 3.0
    r += (150.0 * math.sin(x / 12.0 * PI) + 300.0 * math.sin(x / 30.0 * PI)) * 2.0 / 3.0
    return r


def wgs2gcj(lat, lon):
    if out_of_china(lat, lon):
        return lat, lon
    dlat, dlon = _tlat(lon - 105.0, lat - 35.0), _tlon(lon - 105.0, lat - 35.0)
    rl = lat / 180.0 * PI
    m = math.sin(rl)
    m = 1 - EE * m * m
    sm = math.sqrt(m)
    dlat = (dlat * 180.0) / ((A * (1 - EE)) / (m * sm) * PI)
    dlon = (dlon * 180.0) / (A / sm * math.cos(rl) * PI)
    return lat + dlat, lon + dlon


def gcj2wgs(lat, lon, eps=1e-9, max_iter=60):
    """迭代反解：返回 (lat, lon, 迭代次数, 残差)"""
    tlat, tlon = lat, lon
    for it in range(1, max_iter + 1):
        glat, glon = wgs2gcj(tlat, tlon)
        dlat, dlon = glat - lat, glon - lon
        if abs(dlat) < eps and abs(dlon) < eps:
            return tlat, tlon, it, math.hypot(dlat, dlon)
        tlat -= dlat
        tlon -= dlon
    return tlat, tlon, max_iter, math.hypot(glat - lat, glon - lon)


def meters(lat1, lon1, lat2, lon2):
    R = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))


def pct(xs, p):
    xs = sorted(xs)
    i = min(len(xs) - 1, max(0, int(round(p / 100 * (len(xs) - 1)))))
    return xs[i]


# ==================== B3：偏移量真实区间 ====================
print("=" * 92)
print("### B3  GCJ-02 相对 WGS-84 的偏移量（中国大陆网格采样，0.5° 步长）")
offs = []
pts = []
x, lat = 73.5, 18.0
lats = [18.0 + 0.5 * i for i in range(int((53.5 - 18.0) / 0.5) + 1)]
lons = [73.5 + 0.5 * i for i in range(int((135.0 - 73.5) / 0.5) + 1)]
for la in lats:
    for lo in lons:
        gla, glo = wgs2gcj(la, lo)
        d = meters(la, lo, gla, glo)
        offs.append(d)
        pts.append((la, lo, d))

print(f"  采样点 {len(offs)} 个")
print(f"  最小 {min(offs):8.1f} m   最大 {max(offs):8.1f} m")
mn = min(pts, key=lambda t: t[2])
mx = max(pts, key=lambda t: t[2])
print(f"    最小出现在 ({mn[0]:.1f}, {mn[1]:.1f})   最大出现在 ({mx[0]:.1f}, {mx[1]:.1f})")
print(f"  P5 {pct(offs,5):8.1f} m   P25 {pct(offs,25):8.1f} m   中位 {pct(offs,50):8.1f} m"
      f"   P75 {pct(offs,75):8.1f} m   P95 {pct(offs,95):8.1f} m")
print(f"  均值 {sum(offs)/len(offs):8.1f} m")
low = [p for p in pts if p[2] < 100]
print(f"  偏移 < 100 m 的采样点：{len(low)} 个（占 {len(low)/len(pts)*100:.1f}%）"
      + (f"，例如 ({low[0][0]:.1f},{low[0][1]:.1f})={low[0][2]:.1f}m" if low else ""))
print("  主要城市示例：")
for nm, la, lo in [("北京", 39.909, 116.397), ("上海", 31.230, 121.474), ("广州", 23.129, 113.264),
                   ("成都", 30.572, 104.066), ("拉萨", 29.652, 91.132), ("乌鲁木齐", 43.826, 87.617),
                   ("哈尔滨", 45.803, 126.535), ("三亚", 18.252, 109.512), ("喀什", 39.470, 75.990)]:
    gla, glo = wgs2gcj(la, lo)
    print(f"    {nm:6s} WGS({la:.4f},{lo:.4f}) → GCJ({gla:.6f},{glo:.6f})  偏移 {meters(la, lo, gla, glo):6.1f} m"
          f"   Δ=({gla-la:+.5f},{glo-lo:+.5f})")

print("\n  偏移量分布直方图（每 50 m 一档）：")
import collections
h = collections.Counter(int(d // 50) * 50 for d in offs)
for k in sorted(h):
    print(f"    {k:5d}-{k+50:5d} m  {'█' * max(1, round(h[k] / max(h.values()) * 50))} {h[k]}")

# ==================== B4：用 GCJ-02 算距离的误差 ====================
print()
print("=" * 92)
print("### B4  用 GCJ-02 算距离 vs 用 WGS-84 算距离的相对误差")
random.seed(20260919)
CITIES = [(39.909, 116.397), (31.230, 121.474), (23.129, 113.264), (30.572, 104.066),
          (29.652, 91.132), (43.826, 87.617), (45.803, 126.535), (18.252, 109.512),
          (34.341, 108.940), (36.067, 120.383), (25.040, 102.712), (28.682, 115.858)]
rel = []
absm = []
for cla, clo in CITIES:
    for _ in range(300):
        # 在以城市为中心 0~5km 范围取两个点
        for _ in range(1):
            a1, o1 = cla + random.uniform(-0.045, 0.045), clo + random.uniform(-0.045, 0.045)
            a2, o2 = a1 + random.uniform(-0.02, 0.02), o1 + random.uniform(-0.02, 0.02)
            d_wgs = meters(a1, o1, a2, o2)
            if d_wgs < 800:
                continue
            g1, g2 = wgs2gcj(a1, o1), wgs2gcj(a2, o2)
            d_gcj = meters(*g1, *g2)
            rel.append(abs(d_gcj - d_wgs) / d_wgs * 100)
            absm.append(abs(d_gcj - d_wgs))
print(f"  样本 {len(rel)} 组（1–5 km 尺度）")
print(f"  相对误差  中位 {pct(rel,50):.4f}%   P95 {pct(rel,95):.4f}%   最大 {max(rel):.4f}%")
print(f"  绝对误差  中位 {pct(absm,50):.2f} m   P95 {pct(absm,95):.2f} m   最大 {max(absm):.2f} m")
verdict = "成立" if max(rel) < 0.5 else "不成立"
print(f"  → 原文断言「< 0.5%」是否成立：{verdict}（实测最大 {max(rel):.4f}%）")

# 顺便看短距离（100–500m 徒步相邻点）的误差，这是轨迹累计距离真正关心的尺度
short_rel = []
for cla, clo in CITIES:
    for _ in range(300):
        a1, o1 = cla + random.uniform(-0.02, 0.02), clo + random.uniform(-0.02, 0.02)
        a2, o2 = a1 + random.uniform(-0.003, 0.003), o1 + random.uniform(-0.003, 0.003)
        d1 = meters(a1, o1, a2, o2)
        if d1 < 100 or d1 > 500:
            continue
        d2 = meters(*wgs2gcj(a1, o1), *wgs2gcj(a2, o2))
        short_rel.append(abs(d2 - d1) / d1 * 100)
print(f"  相邻点尺度（100–500 m）相对误差 中位 {pct(short_rel,50):.4f}%"
      f"  P95 {pct(short_rel,95):.4f}%  最大 {max(short_rel):.4f}%（{len(short_rel)} 组）")

# 长距离也一样
long_rel = []
for _ in range(300):
    a1, o1 = random.uniform(20, 45), random.uniform(80, 125)
    a2, o2 = a1 + random.uniform(-3, 3), o1 + random.uniform(-3, 3)
    d1 = meters(a1, o1, a2, o2)
    d2 = meters(*wgs2gcj(a1, o1), *wgs2gcj(a2, o2))
    long_rel.append(abs(d2 - d1) / d1 * 100)
print(f"  跨城尺度（~300km）相对误差 中位 {pct(long_rel,50):.4f}%  最大 {max(long_rel):.4f}%")

# ==================== B2：迭代收敛次数 ====================
print()
print("=" * 92)
print("### B2  GCJ→WGS 迭代反解的收敛次数与残差")
for eps in (1e-6, 1e-8, 1e-9, 1e-12):
    its = []
    resid = []
    random.seed(7)
    for _ in range(500):
        la, lo = random.uniform(20, 45), random.uniform(80, 125)
        gla, glo = wgs2gcj(la, lo)
        rla, rlo, it, res = gcj2wgs(gla, glo, eps=eps)
        its.append(it)
        resid.append(meters(la, lo, rla, rlo))
    print(f"  eps={eps:<8g} 迭代次数 中位 {pct(its,50)}  最大 {max(its)}"
          f"   |  还原出的 WGS 与原 WGS 误差 中位 {pct(resid,50)*1000:.3f} mm  最大 {max(resid)*1000:.3f} mm")

# 单步「近似反解」的误差（若为了省 CPU 只迭代 1 次）
random.seed(11)
one_step = []
for _ in range(500):
    la, lo = random.uniform(20, 45), random.uniform(80, 125)
    gla, glo = wgs2gcj(la, lo)
    wla, wlo, it, _ = gcj2wgs(gla, glo, eps=1e-9)
    # 只迭代一次：把 gcj 减去一次偏移量
    dla, dlo = wgs2gcj(gla, glo)
    s1 = (gla - (dla - gla), glo - (dlo - glo))
    one_step.append(meters(la, lo, *s1))
print(f"  只迭代 1 次时误差 中位 {pct(one_step,50):.2f} m  最大 {max(one_step):.2f} m"
      f"  → 单步不可用（这就是为什么必须迭代/查表）")
