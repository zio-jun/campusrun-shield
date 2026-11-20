"""sentinel.mock_trace —— 轨迹可信度分析（防作弊评估）。

输入：跑步轨迹点序列（经纬度 + 时间戳），输出：风险评分与判定依据。

核心指标（全部可解释、可复算）：
- 速度序列：均值/方差/极值；「钳制平台」检测（大量速度贴近同一上下界 = 疑似匀速回放）
- 加速度：突变比例
- 转角分布：折角占比（直线拼接/画圈切角特征）
- 时间间隔：等间隔占比（机器生成轨迹常见）
- 轨迹熵：速度分桶熵（真人 > 伪轨迹）
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import List, Sequence, Tuple

EARTH_R = 6371000.0


@dataclass
class Point:
    t: float      # 毫秒时间戳
    lat: float
    lng: float


@dataclass
class TraceReport:
    n: int = 0
    duration_s: float = 0.0
    distance_m: float = 0.0
    speeds: List[float] = field(default_factory=list)
    speed_mean: float = 0.0
    speed_std: float = 0.0
    speed_cv: float = 0.0
    accel_spikes: int = 0
    sharp_turns: int = 0
    equal_interval_ratio: float = 0.0
    speed_entropy: float = 0.0
    clamp_ratio: float = 0.0
    score: int = 0
    flags: List[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        d = self.__dict__.copy()
        d.pop("speeds", None)
        return d


def haversine(a: Point, b: Point) -> float:
    dlat = math.radians(b.lat - a.lat)
    dlng = math.radians(b.lng - a.lng)
    la1, la2 = math.radians(a.lat), math.radians(b.lat)
    h = math.sin(dlat / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin(dlng / 2) ** 2
    return 2 * EARTH_R * math.asin(min(1.0, math.sqrt(h)))


def _entropy(values: Sequence[float], bins: int = 8) -> float:
    if not values:
        return 0.0
    lo, hi = min(values), max(values)
    if hi - lo < 1e-9:
        return 0.0
    counts = [0] * bins
    for v in values:
        idx = min(bins - 1, int((v - lo) / (hi - lo) * bins))
        counts[idx] += 1
    total = len(values)
    ent = 0.0
    for c in counts:
        if c:
            p = c / total
            ent -= p * math.log2(p)
    return ent


def analyze(points: List[Point]) -> TraceReport:
    rep = TraceReport(n=len(points))
    if len(points) < 3:
        rep.flags.append("点数不足（<3），无法评估")
        rep.score = 100
        return rep

    speeds: List[float] = []
    dts: List[float] = []
    segs: List[float] = []
    turns: List[float] = []
    for i in range(1, len(points)):
        dt = (points[i].t - points[i - 1].t) / 1000.0
        d = haversine(points[i - 1], points[i])
        dts.append(dt)
        segs.append(d)
        if dt > 0:
            speeds.append(d / dt)

    total_d = sum(segs)
    rep.distance_m = round(total_d, 2)
    rep.duration_s = round((points[-1].t - points[0].t) / 1000.0, 2)
    rep.speeds = speeds
    if speeds:
        mean = sum(speeds) / len(speeds)
        var = sum((s - mean) ** 2 for s in speeds) / len(speeds)
        rep.speed_mean = round(mean, 3)
        rep.speed_std = round(math.sqrt(var), 3)
        rep.speed_cv = round(math.sqrt(var) / mean, 3) if mean > 1e-9 else 0.0

    # 加速度突变：|Δv| > 3 m/s 的相邻对
    rep.accel_spikes = sum(1 for i in range(1, len(speeds)) if abs(speeds[i] - speeds[i - 1]) > 3)

    # 转角：相邻段方位角变化 > 45° 视为折角
    for i in range(1, len(segs)):
        brg1 = _bearing(points[i - 1], points[i])
        brg2 = _bearing(points[i], points[i + 1])
        diff = abs((brg2 - brg1 + 180) % 360 - 180)
        if diff > 45:
            turns.append(diff)
    rep.sharp_turns = len(turns)

    # 等间隔占比
    if len(dts) >= 4:
        med = sorted(dts)[len(dts) // 2]
        if med > 0:
            eq = sum(1 for d in dts if abs(d - med) / med < 0.05)
            rep.equal_interval_ratio = round(eq / len(dts), 2)

    rep.speed_entropy = round(_entropy(speeds), 2)

    # 钳制平台检测：若存在一个速度值在 ±0.05 内聚集占比 > 40%，判为钳制
    if speeds:
        best = 0.0
        for s in set(round(x, 1) for x in speeds):
            c = sum(1 for x in speeds if abs(x - s) <= 0.05)
            best = max(best, c / len(speeds))
        rep.clamp_ratio = round(best, 2)

    rep.score, rep.flags = _score(rep)
    return rep


def _bearing(a: Point, b: Point) -> float:
    la1, la2 = math.radians(a.lat), math.radians(b.lat)
    dl = math.radians(b.lng - a.lng)
    y = math.sin(dl) * math.cos(la2)
    x = math.cos(la1) * math.sin(la2) - math.sin(la1) * math.cos(la2) * math.cos(dl)
    brg = math.degrees(math.atan2(y, x))
    return (brg + 360) % 360


def _score(rep: TraceReport) -> Tuple[int, List[str]]:
    score = 0
    flags: List[str] = []

    if 0 < rep.speed_mean < 0.8 or rep.speed_mean > 8:
        score += 25
        flags.append(f"平均速度异常：{rep.speed_mean} m/s（人跑步通常 1.5~6 m/s）")
    if rep.speed_cv < 0.05 and rep.n > 10:
        score += 20
        flags.append(f"速度过于恒定：CV={rep.speed_cv}（真人配速波动通常 > 5%）")
    if rep.clamp_ratio > 0.4:
        score += 25
        flags.append(f"速度钳制特征：{int(rep.clamp_ratio*100)}% 的点速度贴近同一值（匀速回放特征）")
    if rep.accel_spikes > max(2, rep.n // 20):
        score += 15
        flags.append(f"加速度突变过多：{rep.accel_spikes} 次（真人步态平滑）")
    if rep.equal_interval_ratio > 0.9 and rep.n > 20:
        score += 10
        flags.append(f"时间间隔高度等间隔：{int(rep.equal_interval_ratio*100)}%（机器生成特征）")
    if rep.speed_entropy < 1.2 and rep.n > 15:
        score += 10
        flags.append(f"速度分布熵偏低：{rep.speed_entropy}（分布过于集中）")
    if rep.sharp_turns > rep.n * 0.5:
        score += 10
        flags.append(f"折角占比过高：{rep.sharp_turns}/{rep.n}（疑似直线拼接或画圈切角）")

    score = min(100, score)
    if not flags:
        flags.append("未发现明显异常特征")
    return score, flags
