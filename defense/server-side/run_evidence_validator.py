#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
campusrun-shield / defense / server-side / run_evidence_validator.py
===================================================================
校园跑成绩证据交叉校验器（服务端参考实现，成绩有效性评分 0-100）

沉淀来源（本项目逆向分析产出）：
  * agent-team/sport/REPORT.md（task-3）
      - R5  步频异常=疑似交通工具：dlg_step_rate_invalid 对话框内容直接复用
             suspect_use_bicycle（"系统根据运动数据判断您的本次运动存在使用
             自行车或其他交通工具"）→ 速度/步频交叉是防作弊核心判定。
      - R1/R2 speed_too_fast / min_speed：瞬时速度上下限判定（txt_tips_speed_err、
             min_speed 字符串 + speed_too_fast.wav 音频）。
      - R10 时间校准：current_time_millis"当前手机时间不准，请先校准后再开始锻炼"。
      - R3  里程下限：sport_distance_commit_tip 距离过短不记录。
      - R8  人脸核验防代跑：起点/终点/点位人脸 + 自拍照片必传（no_take_photo_tip）。
  * agent-team/runtime/REPORT.md（task-2）
      - 客户端上报链路为 Retrofit/OkHttp（CommonInterceptor1 拦截器），
        服务端接收 JSON：轨迹点、步频序列、设备/服务端时间、照片等。

防御目标（对应攻击方式）：
  * 攻击方式 A（本机虚拟环境）：模拟定位 + 传感器伪造通常只伪造"轨迹速度"，
    步频序列要么缺失、要么是常数/随机数——与 GPS 速度物理关系不成立，
    交叉校验即可识破。
  * 攻击方式 B（虚拟机）：模拟器传感器（加速度计）同样可伪造，但步频与
    速度长期一致性、时间戳对齐（VM 时钟漂移）更难保持。

用法：`python run_evidence_validator.py`（内置 3 组合成 demo，直接运行）。
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from typing import List, Optional

# ---------------------------------------------------------------------------
# 阈值常量
# ---------------------------------------------------------------------------

# 速度上下限（对标 task-3 R1/R2；与 trajectory_plausibility 保持一致）
MAX_SPEED_MPS = 6.0
MIN_SPEED_MPS = 0.5

# 步频合理区间（步/分）。正常跑步 150~200；步行 100~130；
# 自行车骑行无步频或伪步频 <90/>220，骑车时手机在口袋也会产生 60~110 的摆动伪步频。
CADENCE_RUN_MIN = 130.0
CADENCE_RUN_MAX = 210.0

# 步长合理区间（米）。步长 = 速度(m/s)*60/步频(步/分)。
# 跑步步长 0.7~1.6m；超过 1.8m 或低于 0.3m 视为速度/步频不匹配（骑车摆动伪步频
# 对应步长会异常大，正是 suspect_use_bicycle 的物理本质）。
STRIDE_MIN_M = 0.30
STRIDE_MAX_M = 1.80

# 瞬时速度尖峰检测：滚动窗口（5 点）z-score 阈值。
SPIKE_ZSCORE = 3.0
SPIKE_WINDOW = 5

# 时间一致性：设备时间与服务器时间偏差上限（秒）——对标 current_time_millis。
MAX_DEVICE_CLOCK_SKEW_S = 120.0
# 上传延迟：运动结束到数据到达服务器的时长上限（小时）。
MAX_UPLOAD_DELAY_H = 24.0

# 里程下限（对标 sport_distance_commit_tip）：低于该值直接判无效。
MIN_TOTAL_DISTANCE_M = 500.0

# 评分权重（0-100）
W_SCORE_BASE = 100.0
PENALTY_SPIKE = 8.0        # 每个超速尖峰扣分
PENALTY_STRIDE = 12.0      # 步长异常段扣分
PENALTY_CADENCE = 8.0      # 步频异常段扣分
PENALTY_CLOCK = 15.0       # 时钟偏差扣分
PENALTY_NO_CADENCE = 10.0  # 缺失步频序列扣分
PENALTY_NO_PHOTO = 20.0    # 缺失自拍照片扣分（R8）


# ---------------------------------------------------------------------------
# 数据结构
# ---------------------------------------------------------------------------

@dataclass
class RunEvidence:
    """一次跑步成绩上传包（客户端上报结构，字段均可为空/伪造）。"""
    user_id: str = "demo"
    device_ts_start: float = 0.0        # 设备侧开始时间（epoch s）
    device_ts_end: float = 0.0          # 设备侧结束时间
    server_receive_ts: float = 0.0      # 服务端接收时间
    speed_series: List[float] = field(default_factory=list)   # 瞬时速度序列 m/s（1Hz）
    cadence_series: List[int] = field(default_factory=list)   # 步频序列 步/分（1Hz，可缺）
    total_distance_m: float = 0.0       # 客户端声明总里程
    photo_count: int = 0                # 自拍照片数（起点/终点/点位）
    face_checks: int = 0                # 已通过的人脸核验次数
    required_face_checks: int = 2       # 任务要求的人脸核验次数（起点+终点）


@dataclass
class Deduction:
    """一条扣分/判废原因。"""
    code: str
    points: float
    reason: str
    fatal: bool = False


# ---------------------------------------------------------------------------
# 检测 1：GPS 速度 vs 步频×步长 交叉校验（R5 自行车判定的物理实现）
# ---------------------------------------------------------------------------

def cross_check_speed_cadence(ev: RunEvidence) -> List[Deduction]:
    """对每个对齐采样点计算步长 = v*60/cadence，检查是否落在合理区间。
    骑车/乘车场景：GPS 速度 6~9 m/s 但伪步频只有 80~110 → 步长 3~7m 荒谬，
    直接命中"疑似交通工具"（suspect_use_bicycle）。"""
    out: List[Deduction] = []
    n = min(len(ev.speed_series), len(ev.cadence_series))
    if n == 0:
        out.append(Deduction("NO_CADENCE", PENALTY_NO_CADENCE,
                             "未上报步频序列，无法交叉校验（攻击 A/B 常伪造纯 GPS 轨迹）"))
        return out
    bad_stride = bad_cadence = 0
    for i in range(n):
        v = ev.speed_series[i]
        c = ev.cadence_series[i]
        if v < MIN_SPEED_MPS or c <= 0:
            continue
        stride = v * 60.0 / c
        if stride > STRIDE_MAX_M or stride < STRIDE_MIN_M:
            bad_stride += 1
        if c > CADENCE_RUN_MAX or c < CADENCE_RUN_MIN:
            bad_cadence += 1
    ratio_stride = bad_stride / n
    ratio_cadence = bad_cadence / n
    if ratio_stride > 0.25:
        out.append(Deduction(
            "SUSPECT_VEHICLE", PENALTY_STRIDE, fatal=True,
            reason=(f"速度/步频交叉校验失败：{bad_stride}/{n} 个采样点步长超合理区间 "
                    f"(>1.8m 或 <0.3m)，物理特征符合自行车/交通工具（对标 APP "
                    f"suspect_use_bicycle 判定），且比例 {ratio_stride:.0%} 超阈值")))
    elif bad_stride > 0:
        out.append(Deduction("STRIDE_ANOMALY", PENALTY_STRIDE,
                             f"{bad_stride} 个采样点步长异常（比例 {ratio_stride:.0%}），疑似局部作弊"))
    if ratio_cadence > 0.5:
        out.append(Deduction("CADENCE_ANOMALY", PENALTY_CADENCE,
                             f"步频序列 {ratio_cadence:.0%} 超出跑步区间，疑似伪造/常数步频"))
    return out


# ---------------------------------------------------------------------------
# 检测 2：瞬时速度尖峰（speed_too_fast 对标）
# ---------------------------------------------------------------------------

def check_speed_spikes(ev: RunEvidence) -> List[Deduction]:
    """滚动窗口 z-score 检测速度尖峰 + 绝对阈值双重判定。
    脚本轨迹（攻击 A/B）往往恒速，一旦局部修改速度会形成孤立尖峰；
    真实跑步速度连续渐变，z-score 不会突变。"""
    out: List[Deduction] = []
    vs = ev.speed_series
    spikes = 0
    for i in range(SPIKE_WINDOW, len(vs)):
        window = vs[i - SPIKE_WINDOW:i]
        mean = sum(window) / len(window)
        std = math.sqrt(sum((x - mean) ** 2 for x in window) / len(window)) or 1e-6
        z = abs(vs[i] - mean) / std
        if z > SPIKE_ZSCORE and vs[i] > MAX_SPEED_MPS:
            spikes += 1
    # 绝对阈值统计（R1）
    over = sum(1 for v in vs if v > MAX_SPEED_MPS)
    if spikes > 0 or over > 0:
        out.append(Deduction("SPEED_TOO_FAST", min(PENALTY_SPIKE * spikes, 30.0),
                             f"瞬时速度尖峰 {spikes} 处、超上限点 {over} 处"
                             f"（对标 APP speed_too_fast 音频警告与 txt_tips_speed_err，"
                             f"该段距离应判无效）"))
    return out


# ---------------------------------------------------------------------------
# 检测 3：时间一致性（R10 对标 current_time_millis）
# ---------------------------------------------------------------------------

def check_time_consistency(ev: RunEvidence) -> List[Deduction]:
    out: List[Deduction] = []
    if ev.device_ts_end <= ev.device_ts_start:
        return [Deduction("TS_INVALID", 30.0, "设备时间区间无效（结束早于开始）", fatal=True)]
    skew = abs(ev.device_ts_end - ev.server_receive_ts)
    if skew > MAX_DEVICE_CLOCK_SKEW_S:
        out.append(Deduction("CLOCK_SKEW", PENALTY_CLOCK,
                             f"设备时间与服务器时间偏差 {skew:.0f}s"
                             f"（对标 APP current_time_millis 时间校准提示），疑似改时作弊"))
    delay_h = (ev.server_receive_ts - ev.device_ts_end) / 3600.0
    if delay_h < 0 or delay_h > MAX_UPLOAD_DELAY_H:
        out.append(Deduction("UPLOAD_DELAY", 10.0,
                             f"上传延迟异常（{delay_h:.1f}h），疑似补录/重放"))
    # 跑步时长合理性：按平均速度估算
    dur_h = (ev.device_ts_end - ev.device_ts_start) / 3600.0
    if dur_h > 4.0:
        out.append(Deduction("DURATION_TOO_LONG", 5.0,
                             f"单次运动时长 {dur_h:.1f}h 异常（对标 APP 最长锻炼时间限制）"))
    return out


# ---------------------------------------------------------------------------
# 检测 4：里程/照片/人脸（R3/R8）
# ---------------------------------------------------------------------------

def check_evidence_requirements(ev: RunEvidence) -> List[Deduction]:
    out: List[Deduction] = []
    if ev.total_distance_m < MIN_TOTAL_DISTANCE_M:
        out.append(Deduction("DISTANCE_TOO_SHORT", 100.0,
                             f"总里程 {ev.total_distance_m:.0f}m < {MIN_TOTAL_DISTANCE_M}m"
                             f"（对标 sport_distance_commit_tip 距离过短不记录）", fatal=True))
    if ev.photo_count <= 0:
        out.append(Deduction("NO_SELFIE", PENALTY_NO_PHOTO,
                             "未上传自拍照片（对标 no_take_photo_tip：不传照片本次运动视为无效）"))
    if ev.face_checks < ev.required_face_checks:
        out.append(Deduction("FACE_NOT_VERIFIED", 15.0,
                             f"人脸核验 {ev.face_checks}/{ev.required_face_checks} 未达标"
                             f"（起点/终点 face_start/face_end_title 核验环节）"))
    return out


# ---------------------------------------------------------------------------
# 主入口：评分 0-100
# ---------------------------------------------------------------------------

def validate_run(ev: RunEvidence) -> dict:
    deductions: List[Deduction] = []
    deductions += check_speed_spikes(ev)
    deductions += cross_check_speed_cadence(ev)
    deductions += check_time_consistency(ev)
    deductions += check_evidence_requirements(ev)

    score = W_SCORE_BASE
    fatal = []
    for d in deductions:
        score -= d.points
        if d.fatal:
            fatal.append(d.code)
    score = max(0.0, round(score, 1))

    verdict = "reject" if fatal or score < 60 else ("suspect" if score < 85 else "pass")
    return {
        "user_id": ev.user_id,
        "score": score,
        "verdict": verdict,
        "deductions": [d.__dict__ for d in deductions],
        "summary": {
            "speed_samples": len(ev.speed_series),
            "cadence_samples": len(ev.cadence_series),
            "total_distance_m": ev.total_distance_m,
            "photo_count": ev.photo_count,
            "face_checks": f"{ev.face_checks}/{ev.required_face_checks}",
        },
    }


# ---------------------------------------------------------------------------
# 内置 demo 数据
# ---------------------------------------------------------------------------

def _demo_ok() -> RunEvidence:
    """正常跑步：速度 2.5~3.5 m/s 渐变、步频 165~185、步长 ~1.1m、照片人脸齐。"""
    n = 1200
    speeds = [2.8 + 0.4 * math.sin(i / 60.0) + ((i * 7) % 5) / 100.0 for i in range(n)]
    cadence = [int(170 + 12 * math.sin(i / 40.0) + (i % 3)) for i in range(n)]
    return RunEvidence(
        user_id="demo-ok",
        device_ts_start=1_800_000_000.0,
        device_ts_end=1_800_000_000.0 + n,
        server_receive_ts=1_800_000_000.0 + n + 30,
        speed_series=speeds, cadence_series=cadence,
        total_distance_m=sum(speeds), photo_count=3, face_checks=2)


def _demo_bicycle() -> RunEvidence:
    """骑车作弊（R5）：速度 6~8 m/s（超跑步上限），伪步频 85~100
    → 步长 4~5m 荒谬，命中 SUSPECT_VEHICLE。"""
    n = 600
    speeds = [7.0 + 0.5 * math.sin(i / 20.0) for i in range(n)]
    cadence = [int(92 + 8 * math.sin(i / 15.0)) for i in range(n)]
    return RunEvidence(
        user_id="demo-bicycle",
        device_ts_start=1_800_100_000.0,
        device_ts_end=1_800_100_000.0 + n,
        server_receive_ts=1_800_100_000.0 + n + 60,
        speed_series=speeds, cadence_series=cadence,
        total_distance_m=sum(speeds), photo_count=1, face_checks=2)


def _demo_mock() -> RunEvidence:
    """虚拟环境作弊（攻击 A/B）：恒速直线 + 时钟偏差 + 无步频 + 无照片。"""
    n = 800
    speeds = [3.0] * n
    speeds[400] = 9.0  # 单点注入尖峰
    return RunEvidence(
        user_id="demo-mock",
        device_ts_start=1_800_200_000.0,
        device_ts_end=1_800_200_000.0 + n,
        server_receive_ts=1_800_200_000.0 + n + 300,  # 设备时钟比服务器快 300s
        speed_series=speeds, cadence_series=[],
        total_distance_m=sum(speeds), photo_count=0, face_checks=1)


def main() -> None:
    for name, ev in [("正常跑步", _demo_ok()),
                     ("骑车作弊（步频交叉校验拦截）", _demo_bicycle()),
                     ("虚拟环境作弊（恒速+尖峰+时钟偏差+无照片）", _demo_mock())]:
        print("=" * 70)
        print(f"demo：{name}")
        print("=" * 70)
        print(json.dumps(validate_run(ev), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
