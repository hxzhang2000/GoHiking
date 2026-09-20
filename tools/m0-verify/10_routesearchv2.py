# -*- coding: utf-8 -*-
"""M0 验证 · ⑩ RouteSearch V2 的 AlternativeRoute（T-15 的第四条技术路线）"""
import sys, os, re

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from jlib import Jar  # noqa: E402

DL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dl")
js = Jar(open(os.path.join(DL, "search-9.7.1.jar"), "rb").read())

print("### RouteSearchV2 相关类")
for n in sorted(x for x in js.names if "RouteSearchV2" in x):
    print("   ", n)

for cls, tag in [("com/amap/api/services/route/RouteSearchV2$AlternativeRoute.class", "AlternativeRoute"),
                 ("com/amap/api/services/route/RouteSearchV2$ShowFields.class", "ShowFields"),
                 ("com/amap/api/services/route/RouteSearchV2$WalkRouteQuery.class", "RouteSearchV2.WalkRouteQuery")]:
    p, c = js.one(re.escape(cls))
    print(f"\n### {tag}")
    if c and "error" not in c:
        print("  父类:", c.get("supers"))
        print("  字段:", [(n, d) for n, d, a in c["fields"]])
        for n, d, a in c["methods"]:
            print(f"    {n}{d}")
        print("  utf8 常量:", sorted(v for v in c["utf8"] if re.search(r"^[A-Za-z_]{4,}$", v)))
    else:
        print("   失败:", c)

p, c = js.one(r"services/route/RouteSearchV2\.class$")
if c and "error" not in c:
    print("\n### RouteSearchV2 里 Walk 相关方法")
    for n, d, a in c["methods"]:
        if re.search(r"Walk|Alternative", n):
            print(f"    {n}{d}")
    print("  utf8 里含 WALK/ALTERNATIVE 的常量:",
          sorted({v for v in c["utf8"] if re.search(r"WALK|ALTERNATIVE", v)}))

# 顺便看 RouteSearchV2 WalkRouteResult 之类
for n in sorted(x for x in js.names if re.search(r"route/.*(Walk|Alternative)", x)):
    print("   ", n)

# PoiSearch 的公开方法（PRD 6.2.1 用到的周边检索）
p, c = js.one(r"services/poisearch/PoiSearch\.class$")
print("\n### PoiSearch 方法（确认周边检索 / 搜索）")
if c and "error" not in c:
    for n, d, a in c["methods"]:
        if not n.startswith(("hashCode", "equals", "writeTo", "describe", "clone", "<init>")):
            print(f"    {n}{d}")

p, c = js.one(r"services/poisearch/PoiSearch\$SearchBound\.class$")
print("\n### PoiSearch.SearchBound 构造（周边检索范围）")
if c and "error" not in c:
    for n, d, a in c["methods"]:
        if n.startswith("<init>"):
            print(f"    {n}{d}")

p, c = js.one(r"services/poisearch/PoiSearch\$Query\.class$")
print("\n### PoiSearch.Query 构造（关键词+类型+城市）")
if c and "error" not in c:
    for n, d, a in c["methods"]:
        if n.startswith("<init>") or n in ("setCityLimit", "setPageSize", "setPageNum"):
            print(f"    {n}{d}")

p, c = js.one(r"services/help/Inputtips\.class$")
print("\n### Inputtips 公开方法（输入联想选点）")
if c and "error" not in c:
    for n, d, a in c["methods"]:
        if not n.startswith(("hashCode", "equals", "writeTo", "describe", "clone", "<init>")):
            print(f"    {n}{d}")
