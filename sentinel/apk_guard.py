"""sentinel.apk_guard —— APK 加固/壳与可疑资产识别。

纯标准库实现：不反编译、不执行任何代码，只做"包体指纹"层面的静态评估。
用于安全评估流水线的第一环：快速判断一个 APK 是否加固、加固厂商是谁、
是否存在驻留原包 / 加密资产等可疑特征。
"""
from __future__ import annotations

import json
import os
import zipfile
from dataclasses import dataclass, field
from typing import Dict, List

SIG_FILE = os.path.join(os.path.dirname(__file__), "rules", "shells.json")


@dataclass
class ShellHit:
    vendor_id: str
    vendor: str
    evidence: List[str] = field(default_factory=list)
    weight: int = 3


@dataclass
class ApkReport:
    path: str
    size: int = 0
    entry_count: int = 0
    shells: List[ShellHit] = field(default_factory=list)
    suspicious_assets: List[str] = field(default_factory=list)
    dex_count: int = 0
    v1_signed: bool = False
    libs: Dict[str, int] = field(default_factory=dict)
    score: int = 0
    level: str = "none"

    def to_dict(self) -> dict:
        return {
            "path": self.path,
            "size": self.size,
            "entry_count": self.entry_count,
            "dex_count": self.dex_count,
            "v1_signed": self.v1_signed,
            "libs": self.libs,
            "shells": [
                {"id": s.vendor_id, "vendor": s.vendor, "evidence": s.evidence}
                for s in self.shells
            ],
            "suspicious_assets": self.suspicious_assets,
            "score": self.score,
            "level": self.level,
        }


def load_rules(path: str = SIG_FILE) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def _match_markers(entries: List[str], markers: List[str]) -> List[str]:
    """marker 命中规则：条目名包含 marker 子串（大小写不敏感）。"""
    lowered = [e.lower() for e in entries]
    hits = []
    for m in markers:
        ml = m.lower()
        for e in lowered:
            if ml in e:
                hits.append(m)
                break
    return hits


def inspect_apk(path: str, rules_path: str = SIG_FILE) -> ApkReport:
    rules = load_rules(rules_path)
    rep = ApkReport(path=path)
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        rep.size = os.path.getsize(path)
        rep.entry_count = len(names)
        rep.dex_count = sum(1 for n in names if n.startswith("classes") and n.endswith(".dex"))
        rep.v1_signed = any(n.upper().startswith("META-INF/") and n.upper().endswith(
            (".RSA", ".DSA", ".EC", ".SF")) for n in names)
        for n in names:
            if n.startswith("lib/") and n.count("/") >= 2:
                abi = n.split("/")[1]
                rep.libs[abi] = rep.libs.get(abi, 0) + 1
            low = n.lower()
            if low.endswith((".vd", ".data")) and ("csn" in low or "unknown/" in low or "shell" in low):
                rep.suspicious_assets.append(n)
            if low.startswith("assets/") and low.endswith(".apk"):
                rep.suspicious_assets.append(n)

        for vid, cfg in rules.items():
            hits = _match_markers(names, cfg.get("markers", []))
            if hits:
                rep.shells.append(ShellHit(vendor_id=vid, vendor=cfg.get("name", vid),
                                           evidence=hits, weight=int(cfg.get("weight", 3))))

    rep.score = sum(s.weight for s in rep.shells)
    # 可疑资产本身加分：加密资产 + 驻留原包是"对抗强度"信号
    if any("csn" in a.lower() or a.lower().endswith(".vd") for a in rep.suspicious_assets):
        rep.score += 2
    if any(a.lower().startswith("assets/") and a.lower().endswith(".apk") for a in rep.suspicious_assets):
        rep.score += 2
    rep.level = _level(rep.score)
    return rep


def _level(score: int) -> str:
    if score >= 8:
        return "strong"     # 强加固
    if score >= 5:
        return "medium"     # 中加固
    if score >= 1:
        return "light"      # 轻加固
    return "none"
