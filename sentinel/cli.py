"""Campus Run Sentinel —— 校园体育打卡安全评估工具（CLI）。

子命令：
  apk <file.apk>              加固/壳与可疑资产识别
  trace <trace.csv|.json>     轨迹可信度分析（防作弊）
  evidence <evidence.json>    成绩证据链校验（服务端重算）
  bundle --apk A --trace T --evidence E -o report.md   合并报告
  demo                         生成样例数据并跑通全流程
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import math
import os
import random
import sys
from typing import List

from . import apk_guard, evidence, html_report, mock_trace, report
from .mock_trace import Point

__version__ = "0.1.0"


def _load_trace(path: str) -> List[Point]:
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    if path.lower().endswith(".json"):
        data = json.loads(text)
        pts = data.get("points", data if isinstance(data, list) else [])
        return [Point(t=float(p["t"]), lat=float(p["lat"]), lng=float(p["lng"])) for p in pts]
    pts = []
    reader = csv.DictReader(io.StringIO(text))
    if reader.fieldnames and {"t", "lat", "lng"} <= {x.strip() for x in reader.fieldnames}:
        for row in reader:
            pts.append(Point(t=float(row["t"]), lat=float(row["lat"]), lng=float(row["lng"])))
    else:
        for row in csv.reader(io.StringIO(text)):
            if len(row) >= 3:
                try:
                    pts.append(Point(t=float(row[0]), lat=float(row[1]), lng=float(row[2])))
                except ValueError:
                    continue
    return pts


def _cmd_apk(args) -> int:
    rep = apk_guard.inspect_apk(args.file)
    out = report.render_apk(rep.to_dict())
    print(out)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(out)
    return 0


def _cmd_trace(args) -> int:
    pts = _load_trace(args.file)
    rep = mock_trace.analyze(pts)
    out = report.render_trace(rep.to_dict())
    print(out)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(out)
    return 0


def _cmd_evidence(args) -> int:
    with open(args.file, "r", encoding="utf-8") as f:
        payload = json.load(f)
    rep = evidence.validate(payload)
    out = report.render_evidence(rep.to_dict())
    print(out)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(out)
    return 0


def _cmd_bundle(args) -> int:
    apk_rep = None
    trace_rep = None
    ev_rep = None
    if args.apk:
        apk_rep = apk_guard.inspect_apk(args.apk).to_dict()
    if args.trace:
        trace_rep = mock_trace.analyze(_load_trace(args.trace)).to_dict()
    if args.evidence:
        with open(args.evidence, "r", encoding="utf-8") as f:
            payload = json.load(f)
        ev_rep = evidence.validate(payload).to_dict()
    out = report.render_bundle(apk_rep, trace_rep, ev_rep)
    print(out)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(out)
    return 0


def _cmd_demo(args) -> int:
    d = args.dir or "demo-out"
    os.makedirs(d, exist_ok=True)
    # 1) 真人轨迹：速度带噪、方向有弧、间隔不等
    real = []
    t0 = 1700000000000
    lat, lng = 30.51, 114.41
    for i in range(180):
        t = t0 + i * 1000 + random.randint(-120, 120)
        v = 3.0 + random.uniform(-0.6, 0.6)          # m/s
        lat += (v / 111320.0) * 0.97
        lng += (v / 111320.0) * 0.05 + math.sin(i / 15.0) * 1e-6
        real.append((t, round(lat, 6), round(lng, 6)))
    with open(os.path.join(d, "trace_real.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["t", "lat", "lng"])
        w.writerows(real)

    # 2) 回放轨迹：严格 1Hz、速度恒定为 3.2 m/s、方向不变
    fake = []
    lat2, lng2 = 30.51, 114.41
    for i in range(180):
        t = t0 + i * 1000
        lat2 += 3.2 / 111320.0
        fake.append((t, round(lat2, 6), round(lng2, 6)))
    with open(os.path.join(d, "trace_fake.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["t", "lat", "lng"])
        w.writerows(fake)

    # 3) 证据包：宣称 3000 m / 1200 s，但轨迹实际不满 1 km
    payload = {
        "distance": 3000.0,
        "duration": 1200.0,
        "points": [{"t": t, "lat": la, "lng": ln} for (t, la, ln) in fake[:60]],
    }
    with open(os.path.join(d, "evidence.json"), "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    print(f"[demo] 已生成 {d}/trace_real.csv, trace_fake.csv, evidence.json")
    print("\n== 真人轨迹 ==")
    print(report.render_trace(mock_trace.analyze(_load_trace(os.path.join(d, "trace_real.csv"))).to_dict()))
    print("\n== 回放轨迹 ==")
    print(report.render_trace(mock_trace.analyze(_load_trace(os.path.join(d, "trace_fake.csv"))).to_dict()))
    print("\n== 证据校验 ==")
    print(report.render_evidence(evidence.validate(payload).to_dict()))
    return 0


def _cmd_html(args) -> int:
    apk_rep = None
    trace_rep = None
    ev_rep = None
    if args.apk:
        apk_rep = apk_guard.inspect_apk(args.apk).to_dict()
    if args.trace:
        trace_rep = mock_trace.analyze(_load_trace(args.trace)).to_dict()
    if args.evidence:
        with open(args.evidence, "r", encoding="utf-8") as f:
            payload = json.load(f)
        ev_rep = evidence.validate(payload).to_dict()
    out = html_report.render_html(apk_rep, trace_rep, ev_rep)
    print("HTML 报告已生成" + (f": {args.out}" if args.out else ""))
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(out)
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="sentinel",
        description="Campus Run Sentinel —— 校园体育打卡安全评估工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    p.add_argument("-V", "--version", action="version", version=f"%(prog)s {__version__}")
    sub = p.add_subparsers(dest="cmd", required=True)

    ap = sub.add_parser("apk", help="APK 加固/壳识别")
    ap.add_argument("file")
    ap.add_argument("-o", "--out")
    ap.set_defaults(func=_cmd_apk)

    tp = sub.add_parser("trace", help="轨迹可信度分析")
    tp.add_argument("file")
    tp.add_argument("-o", "--out")
    tp.set_defaults(func=_cmd_trace)

    ep = sub.add_parser("evidence", help="成绩证据校验")
    ep.add_argument("file")
    ep.add_argument("-o", "--out")
    ep.set_defaults(func=_cmd_evidence)

    bp = sub.add_parser("bundle", help="合并报告")
    bp.add_argument("--apk")
    bp.add_argument("--trace")
    bp.add_argument("--evidence")
    bp.add_argument("-o", "--out")
    bp.set_defaults(func=_cmd_bundle)

    dp = sub.add_parser("demo", help="生成样例并跑通全流程")
    dp.add_argument("--dir")
    dp.set_defaults(func=_cmd_demo)

    hp = sub.add_parser("html", help="生成 HTML 报告（单文件，可分享）")
    hp.add_argument("--apk")
    hp.add_argument("--trace")
    hp.add_argument("--evidence")
    hp.add_argument("-o", "--out")
    hp.set_defaults(func=_cmd_html)
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
