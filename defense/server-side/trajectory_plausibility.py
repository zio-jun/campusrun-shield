#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
campusrun-shield / defense / server-side / trajectory_plausibility.py
====================================================================
校园跑轨迹物理合理性检测（服务端参考实现）

沉淀来源（本项目逆向分析产出）：
  * agent-team/sport/REPORT.md（task-3）
      - R1  超速段无效：瞬时速度超上限 -> "此段距离无效，请减速"
             （对应 APP 字符串 txt_tips_speed_err + raw 音频 speed_too_fast.wav）
      - R2  低速警告：瞬时速度低于下限提示"请提高运动速度"
      - R7  电子围栏：必须沿锻炼区域/道路运动，离开区域警告
      - R12 有效里程 = GPS 有效段累计（超速段剔除），分段(每公里)统计
  * agent-team/runtime/REPORT.md（task-2）
      - APP 使用高德定位 SDK（libAMapSDK_Location_v6_6_0），轨迹点含
        lat/lng/speed/accuracy/time，服务端收到的是客户端上传的轨迹数组。

防御目标（对应攻击方式）：
  * 攻击方式 A（本机虚拟环境）：无 root 真机注入/模拟定位，轨迹通常为
    脚本生成的"完美轨迹"：匀速、直线、采样均匀、无抖动、无 GPS 噪声，
    且往往只伪造坐标而忘记伪造 speed 字段——坐标位移与上报速度不一致。
  * 攻击方式 B（虚拟机）：模拟器内 fake location，轨迹同 A，且时间戳
    单调性/连续性可能出现拼接痕迹（加速跑完或时间回拨）。

本模块全部为服务端判定逻辑，不信任客户端任何字段。
用法：`python trajectory_plausibility.py`（内置合成 demo 数据，直接运行）。
"""

from __future__ import annotations

import json
import math
import hashlib
from dataclasses import dataclass, field
from typing import List, Optional, Tuple

# ---------------------------------------------------------------------------
# 常量（阈值均可被运营配置覆盖；数值依据通用跑步物理规律 + task-3 判定规则）
# ---------------------------------------------------------------------------

# R1: 超速阈值。跑步瞬时速度上限取 6.0 m/s（21.6 km/h，约 2'47"/km 配速），
# 超过即标记"speed_too_fast"段（APP 侧表现为 txt_tips_speed_err 提示 + 语音警告）。
MAX_SPEED_MPS = 6.0
# 自行车/电动车典型速度 7~10 m/s，位于 (6.0, 9.0] 区间则疑似交通工具但保留复议空间。
SUSPECT_VEHICLE_SPEED_MPS = 9.0

# R2: 低速阈值（0.5 m/s 以下视为停止/漂移，不计入有效里程）。
MIN_SPEED_MPS = 0.5

# 加速度物理上限：人类跑步最大加速度约 3~4 m/s^2；超过视为 GPS 跳点或伪造。
MAX_ACCEL_MPS2 = 4.0

# 急停抖动：相邻采样间隔内速度从 >2.5 m/s 掉到 <0.4 m/s 再恢复，记一次 jerk。
JERK_HIGH_MPS = 2.5
JERK_LOW_MPS = 0.4

# 采样间隔：GPS 正常 1Hz；间隔 >10s 视为丢点/拼接（伪造轨迹常见空洞）。
MAX_SAMPLE_GAP_S = 10.0

# 上报速度与位移推算速度允许偏差（m/s）。伪造轨迹（攻击 A/B）常见
# speed 字段恒定但坐标位移速度完全不同（直线拉满坐标却报慢速，或反之）。
SPEED_MISMATCH_MPS = 1.5

# map-matching 简化评分：轨迹点到道路折线的平均距离阈值（米）。
# 校园道路宽度 ~3-8m，GPS 误差 ~5m，取 25m 为宽松阈值、10m 为严格阈值。
ROAD_DIST_LOOSE_M = 25.0
ROAD_DIST_STRICT_M = 10.0

# 重复轨迹：与历史轨迹 DTW/余弦相似度高于该值判重放。
DUP_COSINE_THRESHOLD = 0.98
DUP_DTW_THRESHOLD = 0.05   # 归一化 DTW 距离越小越相似

EARTH_RADIUS_M = 6371000.0


# ---------------------------------------------------------------------------
# 数据结构
# ---------------------------------------------------------------------------

@dataclass
class TrackPoint:
    """单个轨迹点。客户端上报字段：纬度/经度/时间戳(epoch s)/速度(m/s)/精度(m)。"""
    lat: float
    lng: float
    ts: float
    speed: float = -1.0          # -1 表示未上报（由服务端用位移/时间推算）
    accuracy: float = 10.0       # GPS 精度(米)


@dataclass
class SegmentStat:
    """每公里分段统计（R12 有效里程的分段载体，对应 APP 每公里配速列表）。"""
    index: int
    start_ts: float
    end_ts: float
    distance_m: float
    duration_s: float
    pace_s_per_km: float         # 配速（秒/公里）
    mean_speed_mps: float
    max_speed_mps: float
    spikes: int                  # 该段超速尖峰点数
    valid_m: float               # 剔除超速段后的有效距离


@dataclass
class Finding:
    """一条检测发现。severity: info | warn | reject；count 为同类聚合数。"""
    code: str
    severity: str
    detail: str
    count: int = 1


# ---------------------------------------------------------------------------
# 几何/基础工具
# ---------------------------------------------------------------------------

def haversine_m(a: TrackPoint, b: TrackPoint) -> float:
    """两 GPS 点球面距离（米）。"""
    p1, p2 = math.radians(a.lat), math.radians(b.lat)
    dp, dl = math.radians(b.lat - a.lat), math.radians(b.lng - a.lng)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(h))


def point_to_segment_m(p: TrackPoint, s: Tuple[float, float], e: Tuple[float, float]) -> float:
    """点到线段的最短球面近似距离（米）。平面投影（校园尺度误差可忽略）。"""
    lat1, lng1 = s
    lat2, lng2 = e
    dlat = (lat2 - lat1) * math.pi / 180 * EARTH_RADIUS_M
    dlng = (lng2 - lng1) * math.pi / 180 * EARTH_RADIUS_M * math.cos(math.radians(p.lat))
    px = (p.lat - lat1) * math.pi / 180 * EARTH_RADIUS_M
    py = (p.lng - lng1) * math.pi / 180 * EARTH_RADIUS_M * math.cos(math.radians(p.lat))
    vx, vy = dlat, dlng
    wx, wy = px, py
    seg_len2 = vx * vx + vy * vy
    if seg_len2 == 0:
        return math.hypot(wx, wy)
    t = max(0.0, min(1.0, (wx * vx + wy * vy) / seg_len2))
    cx, cy = t * vx, t * vy
    return math.hypot(px - cx, py - cy)


def resample_uniform(points: List[TrackPoint], step_m: float = 25.0) -> List[TrackPoint]:
    """按弧长等距重采样（25m 一个点），用于哈希/相似度归一化。
    消除不同采样率对相似度计算的影响（攻击 A/B 的脚本轨迹采样率恒定，
    与真实设备 1Hz 抖动采样不同，重采样后再比对更公平）。"""
    if len(points) < 2:
        return list(points)
    out: List[TrackPoint] = [points[0]]
    acc = 0.0
    for i in range(1, len(points)):
        d = haversine_m(points[i - 1], points[i])
        if d == 0:
            continue
        need = step_m - acc
        while d >= need:
            ratio = need / d
            out.append(TrackPoint(
                lat=points[i - 1].lat + (points[i].lat - points[i - 1].lat) * ratio,
                lng=points[i - 1].lng + (points[i].lng - points[i - 1].lng) * ratio,
                ts=points[i - 1].ts + (points[i].ts - points[i - 1].ts) * ratio,
            ))
            d -= need
            acc = 0.0
            need = step_m
        acc += d
    return out


def trajectory_hash(points: List[TrackPoint], grid_deg: float = 0.0002) -> str:
    """轨迹网格哈希：把轨迹压成粗粒度网格路径再哈希。
    用途：重复轨迹检测的快速通道（重放攻击 A 的同一个虚拟轨迹文件）。"""
    cells = []
    for p in points:
        cells.append((int(p.lat / grid_deg), int(p.lng / grid_deg)))
    # 去连续重复网格（原地停留/抖动不改变哈希）
    compact = [c for i, c in enumerate(cells) if i == 0 or c != cells[i - 1]]
    return hashlib.sha256(repr(compact).encode()).hexdigest()[:16]


def cosine_similarity(a: List[TrackPoint], b: List[TrackPoint], bins: int = 32) -> float:
    """方向分布余弦相似度：按行进方向角分箱后的直方图余弦。
    两条形状相同的轨迹（无论起终点）会得到高相似度。"""
    def dir_hist(pts: List[TrackPoint]):
        hist = [0.0] * bins
        for i in range(1, len(pts)):
            dy = pts[i].lat - pts[i - 1].lat
            dx = (pts[i].lng - pts[i - 1].lng) * math.cos(math.radians(pts[i].lat))
            if dx == 0 and dy == 0:
                continue
            ang = math.degrees(math.atan2(dy, dx)) % 360.0
            hist[int(ang / 360.0 * bins) % bins] += 1.0
        n = math.sqrt(sum(h * h for h in hist)) or 1.0
        return [h / n for h in hist]
    h1, h2 = dir_hist(a), dir_hist(b)
    return sum(x * y for x, y in zip(h1, h2))


def normalized_dtw(a: List[TrackPoint], b: List[TrackPoint], max_len: int = 600) -> float:
    """归一化 DTW 距离（重采样后逐点坐标距离），0 表示完全一致。
    复杂度 O(n*m)，限制 max_len 采样点规模防止退化。"""
    pa = resample_uniform(a)[:max_len]
    pb = resample_uniform(b)[:max_len]
    n, m = len(pa), len(pb)
    if n < 2 or m < 2:
        return 1.0
    d = [[0.0] * m for _ in range(n)]
    for i in range(n):
        for j in range(m):
            d[i][j] = haversine_m(pa[i], pb[j])
    acc = [[float("inf")] * m for _ in range(n)]
    acc[0][0] = d[0][0]
    for i in range(n):
        for j in range(m):
            if i == 0 and j == 0:
                continue
            prev = float("inf")
            if i > 0:
                prev = min(prev, acc[i - 1][j])
            if j > 0:
                prev = min(prev, acc[i][j - 1])
            if i > 0 and j > 0:
                prev = min(prev, acc[i - 1][j - 1])
            acc[i][j] = d[i][j] + prev
    total = acc[n - 1][m - 1]
    return total / max(n + m, 1) / 1000.0  # 归一化到 ~[0,1]


# ---------------------------------------------------------------------------
# 检测器 1：物理合理性（速度/加速度/急停/采样间隙/上报速度一致性）
# ---------------------------------------------------------------------------

def check_physics(points: List[TrackPoint]) -> Tuple[List[Finding], List[Tuple[int, float]]]:
    """逐段物理检测。速度统一使用位移推算速度 v_calc = dist/dt；
    客户端上报 speed 字段仅用于"一致性交叉校验"（SPEED_MISMATCH）。
    返回 (findings, spikes) —— spikes 为 (点索引, 推算速度) 列表供分段统计用。"""
    findings: List[Finding] = []
    spikes: List[Tuple[int, float]] = []
    jerks = 0
    calc_speeds: List[float] = [0.0] * len(points)
    for i in range(1, len(points)):
        p0, p1 = points[i - 1], points[i]
        dt = p1.ts - p0.ts
        dist = haversine_m(p0, p1)
        if dt <= 0:
            findings.append(Finding("TS_NONMONOTONIC", "reject",
                                    f"点{i}: 时间戳非单调递增 (dt={dt:.2f}s)"))
            continue
        if dt > MAX_SAMPLE_GAP_S:
            findings.append(Finding("SAMPLE_GAP", "warn",
                                    f"点{i}: 采样间隔 {dt:.1f}s > {MAX_SAMPLE_GAP_S}s，疑似丢点/轨迹拼接"))
        v = dist / dt
        calc_speeds[i] = v
        # 上报速度与推算速度交叉校验（攻击 A/B 常只伪造坐标、speed 字段失真）
        if p1.speed >= 0 and abs(p1.speed - v) > SPEED_MISMATCH_MPS:
            findings.append(Finding("SPEED_MISMATCH", "warn",
                                    f"点{i}: 上报速度 {p1.speed:.1f}m/s 与坐标推算速度 "
                                    f"{v:.1f}m/s 偏差超阈值"))
        # R1 超速（speed_too_fast 判定，对应 txt_tips_speed_err）
        if v > MAX_SPEED_MPS:
            spikes.append((i, v))
            sev = "reject" if v > SUSPECT_VEHICLE_SPEED_MPS else "warn"
            findings.append(Finding("SPEED_TOO_FAST", sev,
                                    f"瞬时速度 {v:.1f}m/s ({v*3.6:.0f}km/h) 超跑步上限 "
                                    f"{MAX_SPEED_MPS}m/s，该段距离应判无效"))
        # R2 低速
        elif v < MIN_SPEED_MPS and dist > 0.5:
            findings.append(Finding("SPEED_TOO_SLOW", "info",
                                    f"速度 {v:.2f}m/s 低于下限，该段不计入有效里程"))
        # 加速度（推算速度序列差分）
        if i >= 2:
            dt0 = points[i - 1].ts - points[i - 2].ts
            if dt0 > 0:
                a = (v - calc_speeds[i - 1]) / dt0
                if abs(a) > MAX_ACCEL_MPS2:
                    findings.append(Finding("ACCEL_ANOMALY", "warn",
                                            f"加速度 {a:+.1f}m/s² 超人体上限，疑似 GPS 跳点/注入"))
        # 急停抖动：高速段瞬间归零再恢复
        if i >= 1 and calc_speeds[i - 1] > JERK_HIGH_MPS and v < JERK_LOW_MPS:
            jerks += 1
    if jerks >= 3:
        findings.append(Finding("JERK_PATTERN", "warn",
                                f"检测到 {jerks} 次高速→急停抖动，疑似脚本轨迹开关切换（攻击 A/B 特征）"))
    return findings, spikes


# ---------------------------------------------------------------------------
# 检测器 2：map-matching 简化评分（点到道路折线距离）
# ---------------------------------------------------------------------------

def map_match_score(points: List[TrackPoint], road_polyline: List[Tuple[float, float]]) -> dict:
    """计算轨迹点到最近道路折线的距离分布并评分。
    真实跑步轨迹应贴近道路；虚拟直线轨迹（攻击 A/B 常用）会整体偏离折线。
    返回 {mean_m, rms_m, pct_within_strict, pct_within_loose, score_0_100}"""
    if not road_polyline or len(points) < 2:
        return {"mean_m": -1.0, "rms_m": -1.0, "pct_within_strict": 1.0,
                "pct_within_loose": 1.0, "score_0_100": 100.0}
    segs = list(zip(road_polyline[:-1], road_polyline[1:]))
    dists = []
    for p in points:
        dists.append(min(point_to_segment_m(p, s, e) for s, e in segs))
    mean_m = sum(dists) / len(dists)
    rms_m = math.sqrt(sum(d * d for d in dists) / len(dists))
    strict = sum(1 for d in dists if d <= ROAD_DIST_STRICT_M) / len(dists)
    loose = sum(1 for d in dists if d <= ROAD_DIST_LOOSE_M) / len(dists)
    score = max(0.0, 100.0 * (1.0 - mean_m / ROAD_DIST_LOOSE_M))
    return {"mean_m": round(mean_m, 1), "rms_m": round(rms_m, 1),
            "pct_within_strict": round(strict, 3), "pct_within_loose": round(loose, 3),
            "score_0_100": round(score, 1)}


# ---------------------------------------------------------------------------
# 检测器 3：重复轨迹检测（哈希快筛 + 余弦 + DTW）
# ---------------------------------------------------------------------------

def check_duplicate(points: List[TrackPoint], history: List[List[TrackPoint]]) -> List[Finding]:
    """与历史有效轨迹库比对。历史轨迹为空时直接通过。
    重放攻击（攻击 A：重复提交同一份虚拟轨迹文件）在此拦截。"""
    findings: List[Finding] = []
    if not history:
        return findings
    th = trajectory_hash(points)
    for idx, h in enumerate(history):
        if trajectory_hash(h) == th:
            findings.append(Finding("DUP_HASH", "reject",
                                    f"轨迹网格哈希与历史轨迹#{idx}完全一致，判为轨迹重放"))
            continue
        cos = cosine_similarity(points, h)
        if cos >= DUP_COSINE_THRESHOLD:
            dtw = normalized_dtw(points, h)
            if dtw <= DUP_DTW_THRESHOLD:
                findings.append(Finding("DUP_SHAPE", "reject",
                                        f"与历史轨迹#{idx}形状高度相似 (cos={cos:.3f}, dtw={dtw:.4f})"))
    return findings


# ---------------------------------------------------------------------------
# 分段统计（每公里：距离/时长/配速/均速/尖峰/有效里程）
# ---------------------------------------------------------------------------

def segment_stats(points: List[TrackPoint], spikes: List[Tuple[int, float]]) -> List[SegmentStat]:
    """按 1km 有效里程分段统计（对应 APP 每公里配速列表 item_pace_list）。"""
    segs: List[SegmentStat] = []
    seg_dist = seg_time = seg_valid = 0.0
    seg_max_v = 0.0
    seg_spikes = 0
    seg_start = points[0].ts if points else 0.0
    spike_idx = {i for i, _ in spikes}
    seg_idx = 0
    last_ts = seg_start
    for i in range(1, len(points)):
        p0, p1 = points[i - 1], points[i]
        dt = p1.ts - p0.ts
        d = haversine_m(p0, p1)
        if dt <= 0:
            continue
        v = d / dt
        seg_dist += d
        seg_time += dt
        seg_max_v = max(seg_max_v, v)
        if i in spike_idx:
            seg_spikes += 1
        else:
            seg_valid += d  # 超速点所在段不计入有效里程（R1/R12）
        last_ts = p1.ts
        if seg_dist >= 1000.0:
            segs.append(SegmentStat(
                index=seg_idx, start_ts=seg_start, end_ts=last_ts,
                distance_m=round(seg_dist, 1), duration_s=round(seg_time, 1),
                pace_s_per_km=round(seg_time / (seg_dist / 1000.0), 1),
                mean_speed_mps=round(seg_dist / max(seg_time, 1e-6), 2),
                max_speed_mps=round(seg_max_v, 2), spikes=seg_spikes,
                valid_m=round(seg_valid, 1)))
            seg_idx += 1
            seg_dist = seg_time = seg_valid = 0.0
            seg_max_v = 0.0
            seg_spikes = 0
            seg_start = last_ts
    if seg_dist >= 50:  # 尾段不足 1km 也输出（对应 APP "最后不足一公里耗时"提示）
        segs.append(SegmentStat(
            index=seg_idx, start_ts=seg_start, end_ts=last_ts,
            distance_m=round(seg_dist, 1), duration_s=round(seg_time, 1),
            pace_s_per_km=round(seg_time / (seg_dist / 1000.0), 1),
            mean_speed_mps=round(seg_dist / max(seg_time, 1e-6), 2),
            max_speed_mps=round(seg_max_v, 2), spikes=seg_spikes,
            valid_m=round(seg_valid, 1)))
    return segs


# ---------------------------------------------------------------------------
# 主入口：一条轨迹的完整体检
# ---------------------------------------------------------------------------

def _aggregate(findings: List[Finding]) -> List[Finding]:
    """同类发现聚合（保留 code/severity/首个 detail，累计 count），避免输出洪泛。"""
    merged: dict = {}
    for f in findings:
        key = (f.code, f.severity)
        if key in merged:
            merged[key].count += 1
        else:
            merged[key] = Finding(f.code, f.severity, f.detail, 1)
    # 按严重度排序：reject > warn > info
    order = {"reject": 0, "warn": 1, "info": 2}
    return sorted(merged.values(), key=lambda x: order.get(x.severity, 3))


def analyze_trajectory(
    points: List[TrackPoint],
    road_polyline: Optional[List[Tuple[float, float]]] = None,
    history: Optional[List[List[TrackPoint]]] = None,
) -> dict:
    findings, spikes = check_physics(points)
    mm = map_match_score(points, road_polyline or [])
    dup = check_duplicate(points, history or [])
    findings.extend(dup)
    if mm["score_0_100"] < 60:
        findings.append(Finding("OFF_ROAD", "warn",
                                f"轨迹整体偏离道路折线，map-matching 评分 {mm['score_0_100']}"))
    segs = segment_stats(points, spikes)
    total_m = sum(s.distance_m for s in segs)
    valid_m = sum(s.valid_m for s in segs)
    agg = _aggregate(findings)
    # 超速点占比 >20% 时升级为 reject（整条轨迹匀速超速=脚本轨迹特征，
    # 攻击 A/B 的"匀速直线拉满"模式；局部偶发尖峰保持 warn 供复议）
    for f in agg:
        if f.code == "SPEED_TOO_FAST" and f.severity != "reject":
            over_ratio = f.count / max(len(points), 1)
            if over_ratio > 0.2:
                f.severity = "reject"
                f.detail += f"（超速点占比 {over_ratio:.0%}，符合脚本匀速轨迹特征）"
    rejects = [f for f in agg if f.severity == "reject"]
    warns = [f for f in agg if f.severity == "warn"]
    return {
        "points": len(points),
        "total_distance_m": round(total_m, 1),
        "valid_distance_m": round(valid_m, 1),
        "valid_ratio": round(valid_m / total_m, 3) if total_m else 1.0,
        "map_matching": mm,
        "segments": [s.__dict__ for s in segs],
        "findings": [f.__dict__ for f in agg],
        "verdict": "reject" if rejects else ("suspect" if warns else "ok"),
        "trajectory_hash": trajectory_hash(points),
    }


# ---------------------------------------------------------------------------
# 内置 demo 数据（直接 python 运行）
# ---------------------------------------------------------------------------

def _build_demo_normal() -> List[TrackPoint]:
    """合成一条"正常跑步"轨迹：绕校园操场折线跑约 2.2km，
    采样间隔按速度推算（真实 GPS 1Hz 附近），带 GPS 噪声与速度自然波动，
    含 1 个短促超速尖峰（真实跑步常见，应只剔除尖峰所在段）。"""
    rect = [
        (30.5110, 114.4020), (30.5110, 114.4060), (30.5095, 114.4060), (30.5095, 114.4020),
    ]
    pts: List[TrackPoint] = []
    t = 1000.0
    rng_state = 42  # 固定种子，可复现

    def rng():
        nonlocal rng_state
        rng_state = (rng_state * 1103515245 + 12345) % (2 ** 31)
        return rng_state / (2 ** 31)

    for lap in range(2):
        for (lat0, lng0), (lat1, lng1) in zip(rect, rect[1:] + rect[:1]):
            dlat, dlng = lat1 - lat0, lng1 - lng0
            length = math.hypot(dlat, dlng)
            steps = max(5, int(length / 0.0002))
            for i in range(steps):
                f = i / steps
                noise = (rng() - 0.5) * 0.00002
                pts.append(TrackPoint(
                    lat=lat0 + dlat * f + noise,
                    lng=lng0 + dlng * f + noise,
                    ts=t,
                    speed=2.8 + 0.5 * math.sin(t) + (rng() - 0.5) * 0.6,
                    accuracy=4.0 + rng() * 6.0))
                # 时间步进与位移/速度匹配（段长度数→米：1 度≈111km；每段约 7~8s）
                seg_m_m = (length / steps) * 111000.0
                t += seg_m_m / 2.8
    # 手动插入一个 7.2 m/s 超速尖峰（对标 speed_too_fast：该段剔除即可）
    pts[60].speed = 7.2
    # 让坐标位移也体现尖峰（位移推算速度 ≈8.1m/s 与上报一致 → 尖峰命中且不算 mismatch）
    pts[60].lng += 0.0004
    return pts


def _build_demo_cheat() -> List[TrackPoint]:
    """合成一条"虚拟环境脚本轨迹"（攻击方式 A/B 典型）：
    完美匀速直线、恒定采样、零噪声、上报 speed=6.5 m/s（超跑步上限），
    但坐标位移推算速度仅 ~2 m/s（speed 字段与坐标矛盾），
    且直线轨迹整体偏离道路折线。"""
    pts: List[TrackPoint] = []
    t = 1000.0
    for i in range(300):
        f = i / 299
        pts.append(TrackPoint(
            lat=30.5100 + 0.004 * f,      # 直线横穿校园
            lng=114.4000 + 0.004 * f,
            ts=t, speed=6.5, accuracy=3.0))
        t += 1.0
    return pts


def main() -> None:
    road = [(30.5110, 114.4020), (30.5110, 114.4060),
            (30.5095, 114.4060), (30.5095, 114.4020)]
    normal = _build_demo_normal()
    cheat = _build_demo_cheat()

    print("=" * 70)
    print("demo 1：正常跑步轨迹（含 1 个短促超速尖峰）")
    print("=" * 70)
    r1 = analyze_trajectory(normal, road_polyline=road, history=None)
    print(json.dumps(r1, ensure_ascii=False, indent=2))

    print()
    print("=" * 70)
    print("demo 2：虚拟环境脚本轨迹（上报速度与坐标矛盾 + 偏离道路）")
    print("=" * 70)
    r2 = analyze_trajectory(cheat, road_polyline=road, history=None)
    print(json.dumps(r2, ensure_ascii=False, indent=2))

    print()
    print("=" * 70)
    print("demo 3：轨迹重放（demo2 轨迹再次提交，历史库含其平移样本）")
    print("=" * 70)
    shifted = [TrackPoint(p.lat + 0.00005, p.lng, p.ts + 3600, p.speed, p.accuracy)
               for p in cheat]
    r3 = analyze_trajectory(cheat, road_polyline=road, history=[shifted])
    print(json.dumps({k: v for k, v in r3.items() if k in ("verdict", "trajectory_hash", "findings")},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
