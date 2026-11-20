"""性能基准：轨迹分析与证据校验的吞吐量测试（纯标准库，可直接复现）。

用法： python bench/bench.py [点数]
输出： 各阶段耗时与吞吐，结果可写入 README 的性能章节。
"""
import csv
import io
import math
import os
import sys
import time

# 允许从 bench/ 目录直接运行：把项目根加入 sys.path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sentinel import evidence, mock_trace
from sentinel.mock_trace import Point


def gen_points(n: int):
    pts = []
    lat, lng = 30.51, 114.41
    t0 = 1700000000000
    for i in range(n):
        lat += 3.0 / 111320.0
        lng += math.sin(i / 40.0) * 1e-6
        pts.append(Point(t=t0 + i * 1000, lat=lat, lng=lng))
    return pts


def bench_analyze(n: int):
    pts = gen_points(n)
    t = time.perf_counter()
    rep = mock_trace.analyze(pts)
    dt = time.perf_counter() - t
    return dt, rep


def bench_evidence(n: int):
    pts = gen_points(n)
    payload = {
        "distance": (n - 1) * 3.0,
        "duration": (n - 1) * 1.0,
        "points": [{"t": p.t, "lat": p.lat, "lng": p.lng} for p in pts],
    }
    t = time.perf_counter()
    rep = evidence.validate(payload)
    dt = time.perf_counter() - t
    return dt, rep


def bench_csv_roundtrip(n: int):
    """CSV 序列化 + 解析一轮（模拟真实文件输入）。"""
    pts = gen_points(n)
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["t", "lat", "lng"])
    for p in pts:
        w.writerow([p.t, p.lat, p.lng])
    data = buf.getvalue()

    t = time.perf_counter()
    rows = list(csv.DictReader(io.StringIO(data)))
    pts2 = [Point(t=float(r["t"]), lat=float(r["lat"]), lng=float(r["lng"])) for r in rows]
    dt = time.perf_counter() - t
    return dt, len(pts2)


if __name__ == "__main__":
    import sys
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 100_000
    print(f"基准点数: {n}\n")
    print(f"{'阶段':<24}{'耗时(s)':>10}{'吞吐(点/s)':>14}")
    print("-" * 50)
    for name, fn in [
        ("轨迹分析 analyze()", lambda: bench_analyze(n)),
        ("证据校验 validate()", lambda: bench_evidence(n)),
        ("CSV 解析 roundtrip", lambda: bench_csv_roundtrip(n)),
    ]:
        dt, out = fn()
        print(f"{name:<24}{dt:>10.3f}{n / dt:>14,.0f}")
    print("\n说明：单线程、CPython，结果与机器性能相关；用于观察算法复杂度而非绝对数值。")
