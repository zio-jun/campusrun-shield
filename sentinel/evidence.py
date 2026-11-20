"""sentinel.evidence —— 成绩证据链校验（服务端重算视角）。

输入：一次成绩提交的"证据包"（轨迹点 + 汇总字段）。
输出：逐项一致性检查结论 —— 对应"服务端不信任客户端汇总字段"的防御原则。
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Any, Dict, List

EARTH_R = 6371000.0


@dataclass
class Finding:
    code: str
    ok: bool
    detail: str


@dataclass
class EvidenceReport:
    findings: List[Finding] = field(default_factory=list)
    passed: int = 0
    failed: int = 0

    def add(self, code: str, ok: bool, detail: str) -> None:
        self.findings.append(Finding(code, ok, detail))
        if ok:
            self.passed += 1
        else:
            self.failed += 1

    @property
    def verdict(self) -> str:
        return "pass" if self.failed == 0 else ("warn" if self.failed <= 2 else "reject")

    def to_dict(self) -> dict:
        return {
            "verdict": self.verdict,
            "passed": self.passed,
            "failed": self.failed,
            "findings": [
                {"code": f.code, "ok": f.ok, "detail": f.detail} for f in self.findings
            ],
        }


def _haversine(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    dlat = math.radians(lat2 - lat1)
    dlng = math.radians(lng2 - lng1)
    la1, la2 = math.radians(lat1), math.radians(lat2)
    h = math.sin(dlat / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin(dlng / 2) ** 2
    return 2 * EARTH_R * math.asin(min(1.0, math.sqrt(h)))


def validate(payload: Dict[str, Any], dist_tolerance: float = 0.02,
             max_speed: float = 10.0) -> EvidenceReport:
    """payload 结构：

    {
      "distance": 3000.0,          # 客户端上报里程（米）
      "duration": 1200.0,          # 客户端上报用时（秒）
      "points": [{"t": 1700000000000, "lat": 30.51, "lng": 114.41}, ...]
    }
    """
    rep = EvidenceReport()
    points = payload.get("points") or []
    n = len(points)
    rep.add("E001", n >= 10,
            f"轨迹点数：{n}" + ("" if n >= 10 else "（少于 10 点，无法支撑成绩）"))

    if len(points) >= 2:
        recomputed = 0.0
        for i in range(1, len(points)):
            a, b = points[i - 1], points[i]
            recomputed += _haversine(a["lat"], a["lng"], b["lat"], b["lng"])
        declared = float(payload.get("distance") or 0)
        if declared > 0:
            diff = abs(recomputed - declared) / declared
            rep.add("E002", diff <= dist_tolerance,
                    f"里程一致性：上报 {declared:.1f} m / 重算 {recomputed:.1f} m / 偏差 {diff*100:.2f}%")
        else:
            rep.add("E002", False, "缺少上报里程字段")

        ts = [p["t"] for p in points]
        monotonic = all(ts[i] >= ts[i - 1] for i in range(1, len(ts)))
        span = (ts[-1] - ts[0]) / 1000.0 if ts else 0
        rep.add("E003", monotonic and span > 0,
                f"时间戳单调性：{'通过' if monotonic else '出现倒序'}；时间跨度 {span:.0f} s")

        declared_dur = float(payload.get("duration") or 0)
        if declared_dur > 0:
            dur_diff = abs(span - declared_dur)
            rep.add("E004", dur_diff <= max(30.0, declared_dur * 0.05),
                    f"用时一致性：上报 {declared_dur:.0f} s / 轨迹跨度 {span:.0f} s / 差 {dur_diff:.0f} s")
        else:
            rep.add("E004", False, "缺少上报用时字段")

        avg_speed = (recomputed / span) if span > 0 else 0.0
        rep.add("E005", 1.0 <= avg_speed <= max_speed,
                f"平均速度：{avg_speed:.2f} m/s（合理区间 1~{max_speed:.0f}）")

        max_inst = 0.0
        for i in range(1, len(points)):
            dt = (points[i]["t"] - points[i - 1]["t"]) / 1000.0
            if dt > 0:
                v = _haversine(points[i - 1]["lat"], points[i - 1]["lng"],
                               points[i]["lat"], points[i]["lng"]) / dt
                max_inst = max(max_inst, v)
        rep.add("E006", max_inst <= max_speed,
                f"瞬时最大速度：{max_inst:.2f} m/s（上限 {max_speed:.0f}）")
    else:
        rep.add("E002", False, "轨迹点不足，无法重算里程")
    return rep
