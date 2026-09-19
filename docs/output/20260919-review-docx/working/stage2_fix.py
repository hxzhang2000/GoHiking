# -*- coding: utf-8 -*-
"""html-review 定向修正：裸值 -> CSS 变量。逐项 assert 落地。"""
import pathlib

P = pathlib.Path(r"D:\hxzhang\MyGithubSoftware\GoHiking\docs\output\20260919-review-docx\working\stage2_typeset.py")
s = P.read_text(encoding="utf-8")

pairs = [
    # 新增变量定义（插在 --color-surface 之后）
    ('    "--color-surface": "#F5F7FA",',
     '    "--color-surface": "#F5F7FA",\n'
     '    "--color-high": "#C62828",\n'
     '    "--color-medium": "#E65100",\n'
     '    "--fs-data": "20pt",\n'
     '    "--spacing-xs": "0.3em",\n'
     '    "--spacing-sm": "0.4em",\n'
     '    "--spacing-md": "0.5em",\n'
     '    "--spacing-lg": "0.6em",\n'
     '    "--spacing-xl": "0.8em",\n'
     '    "--spacing-list": "1.5em",\n'
     '    "--pad-cell": "0.45em 0.6em",\n'
     '    "--pad-quote": "0.6em 1em",\n'
     '    "--pad-card": "0.8em 1em",\n'
     '    "--pad-code": "0.1em 0.35em",'),
    # 逐处替换
    ("margin-bottom: 0.8em;", "margin-bottom: var(--spacing-xl);"),
    ("margin-bottom: 0.5em; font-size: var(--typography-fontSize-h3);",
     "margin-bottom: var(--spacing-md); font-size: var(--typography-fontSize-h3);"),
    ("margin-bottom: 0.5em; }}\n  .cover-subtitle", "margin-bottom: var(--spacing-md); }}\n  .cover-subtitle"),
    ("padding-left: 1.5em; }}\n  .toc-list li", "padding-left: var(--spacing-list); }}\n  .toc-list li"),
    (".toc-list li {{ margin-bottom: 0.3em;", ".toc-list li {{ margin-bottom: var(--spacing-xs);"),
    ("font-style: italic; margin-left: 0.5em; }}", "font-style: italic; margin-left: var(--spacing-md); }}"),
    ("padding: 0.8em 1em; text-align: center;", "padding: var(--pad-card); text-align: center;"),
    ("font-size: 20pt; font-weight: 700;", "font-size: var(--fs-data); font-weight: 700;"),
    ("margin-top: 0.3em; }}", "margin-top: var(--spacing-xs); }}"),
    ('.data-card-high {{ color: #C62828; }}', '.data-card-high {{ color: var(--color-high); }}'),
    ('.data-card-medium {{ color: #E65100; }}', '.data-card-medium {{ color: var(--color-medium); }}'),
    ('.data-card-low {{ color: #1565C0; }}', '.data-card-low {{ color: var(--color-primary); }}'),
    ('.data-card-total {{ color: #1A237E; }}', '.data-card-total {{ color: var(--color-heading); }}'),
    ("padding-left: 0.6em;\n  }}", "padding-left: var(--spacing-lg);\n  }}"),
    ("padding: 0.1em 0.35em; border-radius: 3px; }}", "padding: var(--pad-code); border-radius: 3px; }}"),
    ("padding: 0.45em 0.6em; text-align: left;", "padding: var(--pad-cell); text-align: left;"),
    ("padding: 0.6em 1em;", "padding: var(--pad-quote);"),
    ("margin-bottom: 0.4em; }}", "margin-bottom: var(--spacing-sm); }}"),
    ("margin: 0 0 var(--spacing-paragraph) 1.5em; }}",
     "margin: 0 0 var(--spacing-paragraph) var(--spacing-list); }}"),
]
for old, new in pairs:
    assert old in s, "NOT FOUND: " + old[:60]
    s = s.replace(old, new, 1)

P.write_text(s, encoding="utf-8")
print("patched ok,", len(pairs), "edits applied")
