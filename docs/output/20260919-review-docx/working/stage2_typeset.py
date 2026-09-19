# -*- coding: utf-8 -*-
"""doc-typeset: md -> business-report HTML (theme: business-modern)"""
import json, re, pathlib, markdown

BASE = pathlib.Path(r"D:\hxzhang\MyGithubSoftware\GoHiking\docs\output\20260919-review-docx")
MD = pathlib.Path(r"D:\hxzhang\MyGithubSoftware\GoHiking\docs\文档审阅报告-2026-09-19.md")
OUT_HTML = BASE / "stage2" / "formatted-文档审阅报告.html"
OUT_TOKENS = BASE / "stage2" / "design_tokens.json"

text = MD.read_text(encoding="utf-8")

# --- 分离头部元信息表与正文 ---
lines = text.splitlines()
meta = {}
body_start = 0
table_rows = []
for i, ln in enumerate(lines):
    if ln.startswith("|"):
        cells = [c.strip() for c in ln.strip("|").split("|")]
        if set(cells) <= {"---", "-"} or all(set(c) <= {"-", ":"} for c in cells):
            continue
        table_rows.append(cells)
    elif table_rows and not ln.startswith("|"):
        body_start = i
        break
for row in table_rows:
    if len(row) >= 2:
        meta[row[0]] = row[1]

body_md = "\n".join(lines[body_start:]).strip()

# --- md -> html body ---
md_ext = ["tables", "sane_lists", "smarty"]
html_body = markdown.markdown(body_md, extensions=md_ext)
html_meta = markdown.markdown(
    "\n".join(f"- **{k}**：{v}" for k, v in meta.items()), extensions=["sane_lists"]
)

# --- TOC：抽取正文 h2 ---
h2s = re.findall(r"<h2>(.*?)</h2>", html_body)
toc_items = "".join(f"<li>{t}</li>" for t in h2s)

# --- 数据卡片（执行摘要 highlights）---
cards = [
    ("7", "High 高危（已核实）", "high"),
    ("21", "Medium 中危", "medium"),
    ("42", "Low 低危", "low"),
    ("70", "发现合计", "total"),
]
cards_html = "".join(
    f'<div class="data-card"><div class="data-card-value data-card-{c[2]}">{c[0]}</div>'
    f'<div class="data-card-label">{c[1]}</div></div>'
    for c in cards
)

summary_text = f"""
<p><strong>{meta.get('结论','')}</strong></p>
<p>{meta.get('审阅方法','')}</p>
<p class="summary-note">{meta.get('取代关系','')}</p>
"""

TOKENS = {
    "--typography-fontFamily-heading": "微软雅黑, Microsoft YaHei, PingFang SC",
    "--typography-fontFamily-body": "微软雅黑, Microsoft YaHei, PingFang SC",
    "--typography-fontFamily-bodyLatin": "Calibri, Helvetica Neue, sans-serif",
    "--typography-fontFamily-code": "Consolas, Source Code Pro, monospace",
    "--typography-fontSize-title": "28pt",
    "--typography-fontSize-subtitle": "18pt",
    "--typography-fontSize-h1": "20pt",
    "--typography-fontSize-coverTitle": "22pt",
    "--typography-fontSize-coverCategory": "14pt",
    "--typography-fontSize-sectionHeader": "13pt",
    "--typography-fontSize-h2": "11pt",
    "--typography-fontSize-h3": "10pt",
    "--typography-fontSize-body": "9pt",
    "--typography-fontSize-small": "8pt",
    "--typography-fontSize-tiny": "7pt",
    "--typography-lineHeight-title": "1.3",
    "--typography-lineHeight-body": "1.6",
    "--typography-fontWeight-heading": "600",
    "--typography-fontWeight-body": "400",
    "--typography-fontWeight-emphasis": "500",
    "--color-primary": "#1565C0",
    "--color-secondary": "#0288D1",
    "--color-accent": "#00897B",
    "--color-text": "#212121",
    "--color-textSecondary": "#616161",
    "--color-heading": "#1A237E",
    "--color-divider": "#E0E0E0",
    "--color-background": "#FFFFFF",
    "--color-surface": "#F5F7FA",
    "--color-high": "#C62828",
    "--color-medium": "#E65100",
    "--fs-data": "20pt",
    "--spacing-xs": "0.3em",
    "--spacing-sm": "0.4em",
    "--spacing-md": "0.5em",
    "--spacing-lg": "0.6em",
    "--spacing-xl": "0.8em",
    "--spacing-list": "1.5em",
    "--pad-cell": "0.45em 0.6em",
    "--pad-quote": "0.6em 1em",
    "--pad-card": "0.8em 1em",
    "--pad-code": "0.1em 0.35em",
    "--spacing-paragraph": "0.75em",
    "--spacing-sectionGap": "2.5em",
    "--spacing-cardPadding": "1.5em",
}
OUT_TOKENS.write_text(
    json.dumps({"genre": "business-report", "theme": "business-modern",
                "css_variables": TOKENS}, ensure_ascii=False, indent=2),
    encoding="utf-8",
)
token_vars = "\n".join(f"    {k}: {v};" for k, v in TOKENS.items())

TITLE = "GoHiking 文档审阅报告"
SUBTITLE = "PRD v1.13 · DEV-DESIGN v1.6 · FEASIBILITY-TERRAIN v1.1"
CATEGORY = "技术文档审阅"

html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta name="docx-page-size" content="A4">
  <title>{TITLE}</title>
  <style>
  @page {{ @bottom-center {{ content: counter(page); }} }}
  @page cover {{ @bottom-center {{ content: none; }} }}
  section[role="cover"] {{ page: cover; }}

  :root {{
{token_vars}
    --fs-h1: var(--typography-fontSize-h1);
    --fs-h2: var(--typography-fontSize-h2);
    --fs-h3: var(--typography-fontSize-h3);
    --fs-body: var(--typography-fontSize-body);
    --fs-small: var(--typography-fontSize-small);
    --ff-heading: var(--typography-fontFamily-heading);
    --ff-body: var(--typography-fontFamily-body);
    --ff-code: var(--typography-fontFamily-code);
    --lh-body: var(--typography-lineHeight-body);
    --fw-bold: var(--typography-fontWeight-heading);
    --color-muted: var(--color-textSecondary);
    --color-border: var(--color-divider);
    --color-bg: var(--color-background);
    --color-highlight: var(--color-surface);
    --color-summary-bg: var(--color-surface);
    --color-summary-border: var(--color-primary);
    --summary-border-width: 4px;
    --radius-card: 8px;
    --spacing-paragraph: var(--spacing-paragraph);
    --spacing-section: var(--spacing-sectionGap);
    --spacing-block: 1em;
    --margin-page: 2.5cm;
  }}
  * {{ box-sizing: border-box; margin: 0; padding: 0; }}
  body {{ font-family: var(--ff-body); font-size: var(--fs-body); line-height: var(--lh-body); color: var(--color-text); background: var(--color-bg); }}

  .report-cover {{
    display: flex; flex-direction: column; justify-content: center; align-items: flex-start;
    min-height: 60vh; padding: var(--margin-page); background: var(--color-surface);
  }}
  .cover-category {{ font-size: var(--typography-fontSize-coverCategory); font-family: var(--ff-heading); font-weight: var(--fw-bold); color: var(--color-primary); letter-spacing: 0.08em; margin-bottom: var(--spacing-xl); }}
  .cover-title {{ font-size: var(--typography-fontSize-coverTitle); font-family: var(--ff-heading); font-weight: var(--fw-bold); color: var(--color-heading); margin-bottom: var(--spacing-md); }}
  .cover-subtitle {{ font-size: var(--typography-fontSize-sectionHeader); color: var(--color-muted); margin-bottom: var(--spacing-section); }}
  .cover-meta {{ font-size: var(--fs-small); color: var(--color-muted); }}

  .doc-toc {{ padding: var(--spacing-block) var(--margin-page); background: var(--color-bg); border-bottom: 1px solid var(--color-border); }}
  .toc-title {{ font-weight: var(--fw-bold); margin-bottom: var(--spacing-md); font-size: var(--typography-fontSize-h3); }}
  .toc-list, .toc-list ol, .toc-list ul {{ list-style: none; list-style-type: none; padding-left: var(--spacing-list); }}
  .toc-list li {{ margin-bottom: var(--spacing-xs); text-indent: 0; font-size: var(--fs-small); }}
  .toc-list a {{ color: var(--color-primary); text-decoration: none; }}

  .executive-summary {{
    margin: var(--spacing-section) var(--margin-page);
    padding: var(--spacing-cardPadding);
    border-left: var(--summary-border-width) solid var(--color-summary-border);
    background: var(--color-summary-bg);
    border-radius: 0 var(--radius-card) var(--radius-card) 0;
  }}
  .summary-header {{ margin-bottom: var(--spacing-block); }}
  .summary-header h2 {{ font-size: var(--typography-fontSize-h2); font-family: var(--ff-heading); color: var(--color-heading); }}
  .summary-badge {{ font-size: var(--fs-small); color: var(--color-muted); font-style: italic; margin-left: var(--spacing-md); }}
  .summary-highlights {{ display: flex; gap: var(--spacing-block); flex-wrap: wrap; margin-bottom: var(--spacing-block); }}
  .summary-note {{ font-size: var(--fs-small); color: var(--color-muted); }}

  .data-card {{
    flex: 1 1 120px; background: var(--color-bg); border: 1px solid var(--color-border);
    border-radius: var(--radius-card); padding: var(--pad-card); text-align: center;
  }}
  .data-card-value {{ font-family: var(--ff-code); font-size: var(--fs-data); font-weight: 700; line-height: 1.2; }}
  .data-card-label {{ font-size: var(--fs-small); color: var(--color-muted); margin-top: var(--spacing-xs); }}
  .data-card-high {{ color: var(--color-high); }}
  .data-card-medium {{ color: var(--color-medium); }}
  .data-card-low {{ color: var(--color-primary); }}
  .data-card-total {{ color: var(--color-heading); }}

  .report-body {{ padding: var(--spacing-section) var(--margin-page); }}
  .report-body h1 {{ font-size: var(--typography-fontSize-h1); font-family: var(--ff-heading); color: var(--color-heading); margin-bottom: var(--spacing-paragraph); }}
  .report-body h2 {{
    font-size: var(--typography-fontSize-h2); font-family: var(--ff-heading); font-weight: var(--fw-bold);
    color: var(--color-heading); margin-top: var(--spacing-section); margin-bottom: var(--spacing-paragraph);
    border-left: 4px solid var(--color-primary); padding-left: var(--spacing-lg);
  }}
  .report-body h3 {{
    font-size: var(--typography-fontSize-h3); font-family: var(--ff-heading); font-weight: var(--fw-bold);
    color: var(--color-text); margin-top: var(--spacing-block); margin-bottom: var(--spacing-paragraph);
  }}
  .report-body p {{ margin-bottom: var(--spacing-paragraph); }}
  .report-body strong {{ font-weight: 600; }}
  .report-body code {{ font-family: var(--ff-code); font-size: var(--fs-small); background: var(--color-surface); padding: var(--pad-code); border-radius: 3px; }}
  .report-body hr {{ border: none; border-top: 1px solid var(--color-border); margin: var(--spacing-block) 0; }}

  .report-body table {{ width: 100%; border-collapse: collapse; margin: var(--spacing-block) 0; font-size: var(--fs-small); }}
  .report-body th, .report-body td {{ border: 1px solid var(--color-border); padding: var(--pad-cell); text-align: left; vertical-align: top; }}
  .report-body th {{ background: var(--color-surface); font-weight: 600; color: var(--color-heading); }}

  .report-body blockquote {{
    margin: var(--spacing-block) 0; padding: var(--pad-quote);
    border-left: 3px solid var(--color-accent); background: var(--color-surface);
    color: var(--color-muted); font-size: var(--fs-small);
  }}
  .report-body blockquote p {{ margin-bottom: var(--spacing-sm); }}
  .report-body ul, .report-body ol {{ margin: 0 0 var(--spacing-paragraph) var(--spacing-list); }}
  .report-body li {{ margin-bottom: 0.3em; }}

  .appendix {{ padding: var(--spacing-section) var(--margin-page); border-top: 1px solid var(--color-border); }}
  </style>
</head>
<body>
  <section role="cover" class="report-cover">
    <p class="cover-category">{CATEGORY}</p>
    <h1 class="cover-title">{TITLE}</h1>
    <p class="cover-subtitle">{SUBTITLE}</p>
    <div class="cover-meta">
      <p>汤圆（AI 协作审阅） | 2026-09-19 | V1.0</p>
    </div>
  </section>

  <section role="body" class="report-body" data-page-restart="1">
    <nav class="doc-toc" aria-label="文档目录">
      <p class="toc-title">目录</p>
      <ol class="toc-list">{toc_items}</ol>
    </nav>

    <div class="executive-summary">
      <div class="summary-header">
        <h2>执行摘要</h2><span class="summary-badge">Executive Summary</span>
      </div>
      <div class="summary-highlights">{cards_html}</div>
      <div class="summary-body">{summary_text}</div>
    </div>

    <main>{html_body}</main>
  </section>
</body>
</html>
"""

OUT_HTML.write_text(html, encoding="utf-8")
print("written:", OUT_HTML, "bytes:", OUT_HTML.stat().st_size)
print("toc items:", len(h2s), "| meta rows:", len(meta))
