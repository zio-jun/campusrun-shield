"""sentinel.report —— 把各类检测结果渲染为 Markdown 报告。"""
from __future__ import annotations

import datetime as _dt
from typing import Any, Dict


def _fmt_bool(ok: bool) -> str:
    return "✅" if ok else "❌"


def render_apk(rep: Dict[str, Any]) -> str:
    lines = [
        "# APK 加固评估报告",
        "",
        f"- 文件：`{rep['path']}`",
        f"- 大小：{rep['size'] / 1048576:.2f} MB，条目 {rep['entry_count']} 个，DEX {rep['dex_count']} 个",
        f"- v1 签名：{'有' if rep['v1_signed'] else '无'}；ABI：{', '.join(rep['libs'].keys()) or '无 native 库'}",
        "",
        "## 加固识别",
    ]
    if rep["shells"]:
        lines.append("| 厂商 | 证据特征 |")
        lines.append("|---|---|")
        for s in rep["shells"]:
            lines.append(f"| {s['vendor']} | {', '.join(s['evidence'])} |")
        lines.append("")
        lines.append(f"**综合等级：{rep['level']}（评分 {rep['score']}）**")
    else:
        lines.append("未识别到已知加固厂商特征（可能未加固或使用了未知壳）。")
        lines.append("")
        lines.append(f"**综合等级：{rep['level']}（评分 {rep['score']}）**")

    if rep["suspicious_assets"]:
        lines += ["", "## 可疑资产", ""]
        for a in rep["suspicious_assets"]:
            lines.append(f"- `{a}`")
    return "\n".join(lines)


def render_trace(rep: Dict[str, Any]) -> str:
    lines = [
        "# 轨迹可信度评估报告",
        "",
        f"- 点数：{rep['n']}；时长：{rep['duration_s']} s；里程：{rep['distance_m']} m",
        f"- 平均速度：{rep['speed_mean']} m/s；标准差：{rep['speed_std']}；变异系数：{rep['speed_cv']}",
        f"- 速度熵：{rep['speed_entropy']}；钳制占比：{rep['clamp_ratio']}；等间隔占比：{rep['equal_interval_ratio']}",
        f"- 折角数：{rep['sharp_turns']}；加速度突变：{rep['accel_spikes']}",
        "",
        f"## 风险评分：{rep['score']} / 100",
        "",
    ]
    for f in rep["flags"]:
        lines.append(f"- {f}")
    return "\n".join(lines)


def render_evidence(rep: Dict[str, Any]) -> str:
    lines = [
        "# 成绩证据校验报告",
        "",
        f"- 结论：**{rep['verdict']}**（通过 {rep['passed']} 项 / 失败 {rep['failed']} 项）",
        "",
        "| 检查项 | 结果 | 说明 |",
        "|---|---|---|",
    ]
    for f in rep["findings"]:
        lines.append(f"| {f['code']} | {_fmt_bool(f['ok'])} | {f['detail']} |")
    return "\n".join(lines)


def render_bundle(apk: Dict[str, Any] | None, trace: Dict[str, Any] | None,
                  evidence: Dict[str, Any] | None) -> str:
    parts = [
        "# 校园跑安全评估总报告",
        "",
        f"- 生成时间：{_dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        f"- 工具：Campus Run Sentinel v0.1.0",
        "",
        "<!-- 分区：APK 加固 / 轨迹可信度 / 成绩证据 -->",
    ]
    if apk:
        parts += ["", "## 一、APK 加固", "", render_apk(apk).split("\n", 1)[1]]
    if trace:
        parts += ["", "## 二、轨迹可信度", "", render_trace(trace).split("\n", 1)[1]]
    if evidence:
        parts += ["", "## 三、成绩证据", "", render_evidence(evidence).split("\n", 1)[1]]
    return "\n".join(parts)
