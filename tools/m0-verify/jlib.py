# -*- coding: utf-8 -*-
"""共享的极简 Java class / jar 解析工具（M0 验证脚本用）"""
import io, struct, zipfile, re


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
    this_cls = u2()
    super_cls = u2()
    supers = [cp.get(this_cls, "?"), cp.get(super_cls, "?")]
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
    return {"name": supers[0], "supers": supers[1], "fields": fields, "methods": methods,
            "utf8": set(v for v in cp.values() if isinstance(v, str))}


class Jar:
    def __init__(self, blob):
        self.z = zipfile.ZipFile(io.BytesIO(blob))
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

    def one(self, pattern):
        f = self.find(pattern)
        return (f[0], self.get(f[0])) if f else (None, None)

    def grep(self, needle, limit=10):
        out = []
        for n in self.names:
            c = self.get(n)
            if "utf8" in c and any(needle in v for v in c["utf8"]):
                out.append(n)
                if len(out) >= limit:
                    break
        return out


def dump_methods(c, label, want=None, pat=None):
    if not c:
        print(f"    ❌ {label}: 类不存在")
        return
    if "error" in c:
        print(f"    [!] {label}: {c['error']}")
        return
    print(f"    {label} → {c['name']}   父类 {c.get('supers')}")
    for n, d, a in c["methods"]:
        if want and not any(w.lower() in n.lower() for w in want):
            continue
        if pat and not re.search(pat, n):
            continue
        print(f"        {n}{d}")
