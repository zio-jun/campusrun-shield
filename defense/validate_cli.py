#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CampusRun Defender 校验入口（防护参考实现的验证器，不是独立 App）

这不是一个"软件产品"，而是 defense/ 参考实现的统一验证入口：
  demo        跑两个服务端校验器的内置 demo
  trajectory  对给定轨迹 JSON 做物理/地图匹配/重放分析
  evidence    对给定成绩上报包做步频交叉/尖峰/时间/证据评分
  all         两者都跑并输出合并结论
"""
import argparse
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
TRAJ_PATH = HERE / "server-side" / "trajectory_plausibility.py"
EVID_PATH = HERE / "server-side" / "run_evidence_validator.py"


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod          # dataclass 需要模块已注册
    spec.loader.exec_module(mod)
    return mod


def load_points(path: str):
    data = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    traj = load("traj", TRAJ_PATH)
    pts = None

    if isinstance(data, list):
        pts = data
    elif isinstance(data, dict) and "points" in data:
        pts = data["points"]
    elif isinstance(data, dict) and data.get("shape") == "circle":
        gen = load("gen_route", HERE.parent / "offense" / "ondevice" / "gen_route.py")
        c = data["center"]
        pts = gen.circle((c["lat"], c["lng"]), data["radius_m"], data.get("segments", 72))
    elif isinstance(data, dict) and data.get("shape") == "stadium":
        gen = load("gen_route", HERE.parent / "offense" / "ondevice" / "gen_route.py")
        a, b = data["a"], data["b"]
        pts = gen.stadium((a["lat"], a["lng"]), (b["lat"], b["lng"]),
                          data["radius_m"], data.get("spacing_m", 5.0))

    if not pts:
        sys.exit("无法解析轨迹文件：需要 points 数组，或 shape=circle/stadium 配置")

    out, t = [], 0.0
    for p in pts:
        if isinstance(p, (list, tuple)):
            lat, lng, extra = float(p[0]), float(p[1]), {}
        else:
            lat, lng, extra = float(p["lat"]), float(p["lng"]), p
        ts = float(extra.get("ts", t))
        out.append(traj.TrackPoint(lat=lat, lng=lng, ts=ts,
                                   speed=float(extra.get("speed", -1.0)),
                                   accuracy=float(extra.get("accuracy", 10.0))))
        t = ts + 1.0
    return out


def cmd_demo(args):
    for m in (TRAJ_PATH, EVID_PATH):
        print(f"=== {m.name} ===")
        subprocess.run([sys.executable, str(m), "--demo"], check=False)


def cmd_trajectory(args):
    traj = load("traj", TRAJ_PATH)
    points = load_points(args.file)
    road = json.loads(Path(args.road).read_text(encoding="utf-8")) if args.road else None
    hist = []
    if args.history:
        h = json.loads(Path(args.history).read_text(encoding="utf-8"))
        hist = [load_points(x) if isinstance(x, str) else x for x in h]
    result = traj.analyze_trajectory(points, road_polyline=road, history=hist)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return result


def cmd_evidence(args):
    evd = load("evd", EVID_PATH)
    data = json.loads(Path(args.file).read_text(encoding="utf-8-sig"))
    ev = evd.RunEvidence(**data)
    result = evd.validate_run(ev)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return result


def cmd_all(args):
    r1 = cmd_trajectory(args)
    print()
    r2 = cmd_evidence(args)
    verdict = "reject" if (r1.get("verdict") == "reject" or r2.get("verdict") == "reject") else \
              ("suspect" if (r1.get("verdict") == "suspect" or r2.get("verdict") == "suspect") else "ok")
    print(f"\n合并结论: {verdict}")


def main():
    ap = argparse.ArgumentParser(description="CampusRun Defender 校验入口")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("demo", help="跑内置 demo").set_defaults(func=cmd_demo)

    p = sub.add_parser("trajectory", help="轨迹合理性分析")
    p.add_argument("file")
    p.add_argument("--road")
    p.add_argument("--history")
    p.set_defaults(func=cmd_trajectory)

    p = sub.add_parser("evidence", help="成绩证据校验")
    p.add_argument("file")
    p.set_defaults(func=cmd_evidence)

    p = sub.add_parser("all", help="轨迹+证据联合校验")
    p.add_argument("file")
    p.add_argument("--road")
    p.add_argument("--history")
    p.set_defaults(func=cmd_all)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
