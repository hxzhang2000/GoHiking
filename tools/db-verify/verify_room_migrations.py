#!/usr/bin/env python3
"""
Room 迁移验证（N-35 的替代覆盖）。

为什么要有这个脚本
------------------
DEV §3.1 要求「每个迁移配一个 MigrationTestHelper 测试」，但那需要 Android
插桩环境（真机/模拟器），本机跑不了，于是仓库里实际上一个迁移测试都没有 ——
MIGRATION_1_2 与 MIGRATION_2_3 都是**零覆盖**上线的。

Room 在运行时会拿迁移后的真实 schema 与 exportSchema 生成的 N.json 逐项比对，
不一致就直接抛 "Migration didn't properly handle ..."，用户升级即崩溃。
最典型的翻车是**索引名对不上**（Room 的命名规则是 `index_<表名>_<列名>`，
复合索引用 `_` 连接列名，顺序敏感）。

本脚本用 Python 自带的 sqlite3 复现这条校验链路，不需要 Android：
    按 (N-1).json 建库 → 执行 Migrations.kt 里的 SQL → 与 N.json 逐项比对

用法
----
    python tools/db-verify/verify_room_migrations.py          # 校验全部迁移
    python tools/db-verify/verify_room_migrations.py --verbose # 打印每个迁移的 SQL

退出码 0 = 全部通过，1 = 有迁移未通过。建议接进 CI（无需 Android SDK）。
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCHEMA_DIR = REPO / "core/database/schemas/com.gohiking.core.database.GhDatabase"
MIGRATIONS_KT = REPO / "core/database/src/main/kotlin/com/gohiking/core/database/Migrations.kt"


# ---------------------------------------------------------------- schema ----

def load_schema(version: int) -> dict:
    p = SCHEMA_DIR / f"{version}.json"
    if not p.exists():
        sys.exit(f"缺少 schema 文件：{p}（需要 exportSchema=true 构建一次才会生成）")
    return json.loads(p.read_text(encoding="utf-8"))["database"]


def create_sql(entity: dict) -> str:
    """按 Room schema JSON 生成 CREATE TABLE。"""
    cols = []
    for f in entity["fields"]:
        c = f"`{f['columnName']}` {f['affinity']}"
        if f.get("notNull"):
            c += " NOT NULL"
        if f.get("defaultValue") is not None:
            c += f" DEFAULT {f['defaultValue']}"
        cols.append(c)
    pk = entity.get("primaryKey", {}).get("columnNames", [])
    if pk:
        cols.append("PRIMARY KEY(" + ",".join(f"`{c}`" for c in pk) + ")")
    # Room schema JSON 的外键字段是 table / columns / referencedColumns（不是 *ColumnNames）
    for fk in entity.get("foreignKeys", []):
        cols.append(
            f"FOREIGN KEY(`{fk['columns'][0]}`) REFERENCES `{fk['table']}`"
            f"(`{fk['referencedColumns'][0]}`) ON UPDATE {fk['onUpdate']}"
            f" ON DELETE {fk['onDelete']}"
        )
    return f"CREATE TABLE `{entity['tableName']}` (" + ", ".join(cols) + ")"


def build_db(schema: dict) -> sqlite3.Connection:
    """按给定 schema 建一个内存库（等价于用户在版本 N 时的数据库）。"""
    con = sqlite3.connect(":memory:")
    for e in schema["entities"]:
        con.execute(create_sql(e))
        for idx in e.get("indices", []):
            # 优先用 Room 自己生成的 createSql（含 ${TABLE_NAME} 占位），比手工拼更保真
            sql = idx.get("createSql")
            if sql:
                # createSql 里形如 ON `${TABLE_NAME}`，占位符已被反引号包裹，别再套一层
                con.execute(sql.replace("${TABLE_NAME}", e["tableName"]))
            else:
                cols = ",".join(f"`{c}`" for c in idx["columnNames"])
                con.execute(f"CREATE INDEX `{idx['name']}` ON `{e['tableName']}` ({cols})")
    con.commit()
    return con


# ------------------------------------------------------------- 读取实际库 ----

def read_db(con: sqlite3.Connection) -> dict:
    """读出当前库的实际 schema，归一化为可比对的结构。"""
    tables = {}
    rows = con.execute(
        "SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
    ).fetchall()
    for name, _sql in rows:
        cols = {}
        for r in con.execute(f"PRAGMA table_info(`{name}`)").fetchall():
            # r = (cid, name, type, notnull, dflt_value, pk)
            cols[r[1]] = {"affinity": r[2].upper(), "notNull": bool(r[3])}
        pk = [r[1] for r in con.execute(f"PRAGMA table_info(`{name}`)").fetchall() if r[5]]
        tables[name] = {"columns": cols, "primaryKey": sorted(pk)}

    indices = {}
    for iname, tname, sql in con.execute(
        "SELECT name, tbl_name, sql FROM sqlite_master WHERE type='index'"
        " AND sql IS NOT NULL"
    ).fetchall():
        m = re.search(r"\((.*)\)\s*$", sql or "", re.S)
        col_list = []
        if m:
            col_list = [c.strip().strip("`").strip('"') for c in m.group(1).split(",")]
        indices[iname] = {"table": tname, "columns": col_list}
    return {"tables": tables, "indices": indices}


def expected_from_schema(schema: dict) -> dict:
    tables = {}
    for e in schema["entities"]:
        cols = {
            f["columnName"]: {
                "affinity": f["affinity"].upper(),
                "notNull": bool(f.get("notNull")),
            }
            for f in e["fields"]
        }
        tables[e["tableName"]] = {
            "columns": cols,
            "primaryKey": sorted(e.get("primaryKey", {}).get("columnNames", [])),
        }
    indices = {}
    for e in schema["entities"]:
        for idx in e.get("indices", []):
            indices[idx["name"]] = {"table": e["tableName"], "columns": list(idx["columnNames"])}
    return {"tables": tables, "indices": indices}


def diff(actual: dict, expected: dict) -> list[str]:
    problems = []
    a_t, e_t = actual["tables"], expected["tables"]
    for t in sorted(set(a_t) | set(e_t)):
        if t not in a_t:
            problems.append(f"缺表 {t}")
        elif t not in e_t:
            problems.append(f"多出表 {t}")
        else:
            for c in sorted(set(a_t[t]["columns"]) | set(e_t[t]["columns"])):
                if c not in a_t[t]["columns"]:
                    problems.append(f"{t}.{c} 缺列")
                elif c not in e_t[t]["columns"]:
                    problems.append(f"{t}.{c} 多出列")
                else:
                    av, ev = a_t[t]["columns"][c], e_t[t]["columns"][c]
                    if av["affinity"] != ev["affinity"]:
                        problems.append(f"{t}.{c} 类型 {av['affinity']} != 期望 {ev['affinity']}")
                    if av["notNull"] != ev["notNull"]:
                        problems.append(
                            f"{t}.{c} NOT NULL={av['notNull']} != 期望 {ev['notNull']}"
                        )
            if a_t[t]["primaryKey"] != e_t[t]["primaryKey"]:
                problems.append(f"{t} 主键 {a_t[t]['primaryKey']} != 期望 {e_t[t]['primaryKey']}")

    a_i, e_i = actual["indices"], expected["indices"]
    for i in sorted(set(e_i)):
        if i not in a_i:
            problems.append(f"缺索引 {i}（Room 期望在 {e_i[i]['table']}({','.join(e_i[i]['columns'])})）")
        elif a_i[i]["columns"] != e_i[i]["columns"]:
            problems.append(
                f"索引 {i} 列 {a_i[i]['columns']} != 期望 {e_i[i]['columns']}（顺序敏感）"
            )
    return problems


# ---------------------------------------------------- 解析 Migrations.kt ----

STR_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')


def parse_migrations() -> list[tuple[int, int, list[str]]]:
    """从 Migrations.kt 提取 (from, to, [sql...])。

    只支持 `db.execSQL("..." + "...")` 这一种写法（纯字符串拼接）。
    若将来改用了变量或 trimIndent 原始字符串，本脚本会报告解析失败——
    那时请改成手工维护 SQL，别让它静默跳过。
    """
    src = MIGRATIONS_KT.read_text(encoding="utf-8")
    out = []
    # 逐个 object : Migration(a, b) { ... } 块
    for m in re.finditer(
        r"object\s*:\s*Migration\((\d+),\s*(\d+)\)\s*\{(.*?)\n\}", src, re.S
    ):
        frm, to, body = int(m.group(1)), int(m.group(2)), m.group(3)
        sqls = []
        for call in re.finditer(r"db\.execSQL\((.*?)\)\s*(?:\n|$)", body, re.S):
            parts = STR_RE.findall(call.group(1))
            if not parts:
                sys.exit(
                    f"MIGRATION_{frm}_{to} 里有无法解析的 execSQL：{call.group(1)[:80]!r}\n"
                    "本脚本只支持纯字符串字面量拼接。"
                )
            sql = "".join(p.encode().decode("unicode_escape") for p in parts)
            sqls.append(sql)
        if not sqls:
            sys.exit(f"MIGRATION_{frm}_{to} 没解析到任何 SQL —— 脚本会静默跳过，这是不允许的")
        out.append((frm, to, sqls))
    if not out:
        sys.exit("没有解析到任何迁移，请检查 Migrations.kt 结构是否变了")
    return out


# ------------------------------------------------------------------ main ----

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verbose", action="store_true", help="打印每个迁移执行的 SQL")
    args = ap.parse_args()

    migrations = parse_migrations()
    print(f"从 Migrations.kt 解析到 {len(migrations)} 个迁移\n")

    failed = 0
    for frm, to, sqls in migrations:
        print(f"── MIGRATION_{frm}_{to} " + "─" * 40)
        src_schema = load_schema(frm)
        con = build_db(src_schema)

        # 先确认起点与 (frm).json 一致，否则后面的比对结论不可信
        start_problems = diff(read_db(con), expected_from_schema(src_schema))
        if start_problems:
            print(f"  ⚠ 起点 schema（{frm}.json）本身就还原不出来，比对不可信：")
            for p in start_problems[:5]:
                print(f"      - {p}")

        for s in sqls:
            if args.verbose:
                print(f"   SQL: {s}")
            try:
                con.execute(s)
            except sqlite3.Error as e:
                print(f"  ✗ SQL 执行失败：{e}\n      {s}")
                failed += 1
                break
        else:
            con.commit()
            problems = diff(read_db(con), expected_from_schema(load_schema(to)))
            if problems:
                failed += 1
                print(f"  ✗ 迁移后 schema 与 {to}.json 不一致（Room 运行时会拒绝）：")
                for p in problems:
                    print(f"      - {p}")
            else:
                n_idx = len(read_db(con)["indices"])
                print(f"  ✓ 与 {to}.json 一致（{len(read_db(con)['tables'])} 表 / {n_idx} 索引）")
        print()

    if failed:
        print(f"✗ {failed} 个迁移未通过")
        return 1
    print("✓ 全部迁移通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
