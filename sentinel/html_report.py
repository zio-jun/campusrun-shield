"""sentinel.html_report —— 生成单文件 HTML 评估报告（内嵌样式，可直接分享/归档）。"""
from __future__ import annotations

import datetime as _dt
from typing import Any, Dict, Optional

_CSS = """
:root{--bg:#0f172a;--card:#1e293b;--ink:#e2e8f0;--mut:#94a3b8;--ok:#22c55e;--warn:#f59e0b;--bad:#ef4444;--acc:#38bdf8}
*{box-sizing:border-box}
body{margin:0;font:15px/1.7 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Noto Sans SC",sans-serif;background:var(--bg);color:var(--ink);padding:32px}
.wrap{max-width:1000px;margin:0 auto}
h1{font-size:26px;margin:0 0 6px}
h2{font-size:19px;margin:34px 0 12px;padding-bottom:8px;border-bottom:1px solid #334155}
.sub{color:var(--mut);margin-bottom:26px}
.card{background:var(--card);border:1px solid #334155;border-radius:12px;padding:18px 20px;margin:14px 0}
table{width:100%;border-collapse:collapse;margin:10px 0}
th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #334155;font-size:14px}
th{color:var(--mut);font-weight:600}
.ok{color:var(--ok)} .warn{color:var(--warn)} .bad{color:var(--bad)}
.badge{display:inline-block;padding:2px 10px;border-radius:999px;font-size:13px;font-weight:600}
.b-ok{background:rgba(34,197,94,.15);color:var(--ok)}
.b-warn{background:rgba(245,158,11,.15);color:var(--warn)}
.b-bad{background:rgba(239,68,68,.15);color:var(--bad)}
code{background:#0b1220;padding:1px 6px;border-radius:6px;font-size:13px}
.mut{color:var(--mut)}
.score{font-size:34px;font-weight:700;color:var(--acc)}
ul{margin:8px 0 8px 20px}
footer{margin-top:36px;color:var(--mut);font-size:13px}
"""


def _esc(s: Any) -> str:
    return (str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;"))


def _apk_block(apk: Dict[str, Any]) -> str:
    rows = ""
    for s in apk["shells"]:
        rows += f"<tr><td>{_esc(s['vendor'])}</td><td><code>{_esc(', '.join(s['evidence']))}</code></td></tr>"
    if not rows:
        rows = '<tr><td colspan="2" class="mut">未识别到已知加固特征</td></tr>'
    assets = "".join(f"<li><code>{_esc(a)}</code></li>" for a in apk["suspicious_assets"]) or \
             '<li class="mut">无</li>'
    lvl = apk["level"]
    cls = "b-bad" if lvl in ("medium", "strong") else ("b-warn" if lvl == "light" else "b-ok")
    return f"""
<div class="card">
  <p>文件：<code>{_esc(apk['path'])}</code>　大小：{apk['size']/1048576:.2f} MB　DEX：{apk['dex_count']}　
     ABI：{_esc(', '.join(apk['libs'].keys()) or '无')}</p>
  <p>加固等级：<span class="badge {cls}">{_esc(lvl)}</span>　评分 {apk['score']}</p>
  <table><tr><th>厂商</th><th>证据特征</th></tr>{rows}</table>
  <p>可疑资产：</p><ul>{assets}</ul>
</div>"""


def _trace_block(trace: Dict[str, Any]) -> str:
    cls = "bad" if trace["score"] >= 65 else ("warn" if trace["score"] >= 40 else "ok")
    flags = "".join(f"<li>{_esc(f)}</li>" for f in trace["flags"])
    return f"""
<div class="card">
  <div class="score">{trace['score']} <span class="mut" style="font-size:15px">/ 100</span></div>
  <p class="mut">点数 {trace['n']}　时长 {trace['duration_s']} s　里程 {trace['distance_m']} m　
     平均速度 {trace['speed_mean']} m/s　CV {trace['speed_cv']}　熵 {trace['speed_entropy']}</p>
  <ul>{flags}</ul>
</div>"""


def _evidence_block(ev: Dict[str, Any]) -> str:
    rows = ""
    for f in ev["findings"]:
        icon = '<span class="ok">✅</span>' if f["ok"] else '<span class="bad">❌</span>'
        rows += f"<tr><td>{_esc(f['code'])}</td><td>{icon}</td><td>{_esc(f['detail'])}</td></tr>"
    cls = {"pass": "b-ok", "warn": "b-warn", "reject": "b-bad"}[ev["verdict"]]
    return f"""
<div class="card">
  <p>结论：<span class="badge {cls}">{_esc(ev['verdict'])}</span>
     通过 {ev['passed']} 项 / 失败 {ev['failed']} 项</p>
  <table><tr><th>编号</th><th>结果</th><th>说明</th></tr>{rows}</table>
</div>"""


def render_html(apk: Optional[Dict[str, Any]] = None,
                trace: Optional[Dict[str, Any]] = None,
                evidence: Optional[Dict[str, Any]] = None) -> str:
    now = _dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    parts = [
        "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>",
        "<meta name='viewport' content='width=device-width,initial-scale=1'>",
        "<title>Campus Run Sentinel 评估报告</title>",
        f"<style>{_CSS}</style></head><body><div class='wrap'>",
        "<h1>Campus Run Sentinel · 安全评估报告</h1>",
        f"<p class='sub'>生成时间：{now}　工具版本：v0.3.1　项目：zio-jun/campusrun-shield</p>",
    ]
    if apk:
        parts += ["<h2>一、APK 加固评估</h2>", _apk_block(apk)]
    if trace:
        parts += ["<h2>二、轨迹可信度</h2>", _trace_block(trace)]
    if evidence:
        parts += ["<h2>三、成绩证据校验</h2>", _evidence_block(evidence)]
    parts += [
        "<footer>本报告由 Campus Run Sentinel 自动生成；评估仅基于静态特征与数据校验，",
        "不对目标系统做任何侵入性操作。</footer></div></body></html>",
    ]
    return "".join(parts)
