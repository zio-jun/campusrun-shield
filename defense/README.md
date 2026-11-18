# campusrun-shield · defense/（防护参考实现）

> 2026 华科优秀开源项目参赛作品「campusrun-shield」防御侧目录。
> 本项目源自对华中科技大学校园跑 APP（`net.crigh.hzkjsport` v1.1.0）的逆向分析，
> 将攻击面研究沉淀为**可运行的分层防护参考实现**。全部代码仅供授权评估、
> 教学与自建防护系统使用。

## 1. 目录结构

```
defense/
├── README.md                              ← 本文件
├── server-side/
│   ├── trajectory_plausibility.py        轨迹物理合理性检测（可直接 python 运行）
│   └── run_evidence_validator.py         成绩证据交叉校验 + 0-100 评分（可直接 python 运行）
└── client-side/
    ├── AntiMockDetector.kt               模拟定位检测（系统 API + 高德 SDK + 包名黑名单 + 多源交叉）
    ├── AntiEmulatorDetector.kt           模拟器/VM/多开指纹检测
    ├── IntegrityGuard.kt                 签名/DEX 自校验 + 注入/调试/root 检测
    └── native/
        └── metric_guard.cpp              native 里程/步数累计 + HMAC 签名（JNI）
```

## 2. 分层防护总览

```
┌─────────────────────────────────────────────────────────────────┐
│ 第 4 层 · 服务端裁决（server-side/）                              │
│   trajectory_plausibility.py：速度/加速度/急停/采样间隙/          │
│     map-matching/重复轨迹（哈希+余弦+DTW）/每公里分段统计          │
│   run_evidence_validator.py：GPS速度×步频步长交叉/瞬时尖峰/       │
│     时间一致性/里程照片人脸要求/0-100 评分                         │
├─────────────────────────────────────────────────────────────────┤
│ 第 3 层 · native 指标护栏（client-side/native/）                 │
│   metric_guard.cpp：里程/步数 native 累计、超速段剔除、            │
│     HMAC-SHA256 结果签名（防 Java 层 Hook 篡改）                  │
├─────────────────────────────────────────────────────────────────┤
│ 第 2 层 · 环境完整性（client-side/）                              │
│   AntiMockDetector：定位伪造四路信号                              │
│   AntiEmulatorDetector：VM/多开指纹加权评分                       │
│   IntegrityGuard：重打包/注入/调试/root                           │
├─────────────────────────────────────────────────────────────────┤
│ 第 1 层 · 业务判定规则（源自逆向分析）                             │
│   R1 speed_too_fast 超速段无效 / R2 低速警告 / R5 步频=自行车判定 │
│   R7 电子围栏 / R8 人脸核验 / R9 锻炼时段 / R10 时间校准 / R11 环境反作弊 │
└─────────────────────────────────────────────────────────────────┘
```

## 3. 判定规则 ↔ 代码映射（task-3 沉淀）

| 规则（agent-team/sport/REPORT.md） | APP 证据 | 防御实现位置 |
|---|---|---|
| R1 超速段无效（speed_too_fast） | `txt_tips_speed_err` + `speed_too_fast.wav` | `trajectory_plausibility.py::check_physics`（SPEED_TOO_FAST，>20% 占比升级 reject）；`run_evidence_validator.py::check_speed_spikes`；`metric_guard.cpp::nativeAccumulate` |
| R2 低速警告 | `min_speed` 字符串 | `check_physics`（SPEED_TOO_SLOW）；`nativeAccumulate` |
| R5 步频异常=自行车/交通工具 | `dlg_step_rate_invalid` 复用 `suspect_use_bicycle` | `run_evidence_validator.py::cross_check_speed_cadence`（步长 0.3~1.8m 区间，SUSPECT_VEHICLE）；`metric_guard.cpp::nativeStrideCheck` |
| R7 电子围栏/道路 | `txt_tips_outof_range` | `trajectory_plausibility.py::map_match_score`（点到折线距离评分，OFF_ROAD） |
| R8 人脸/自拍必传 | `no_take_photo_tip`、`face_start/end_title` | `run_evidence_validator.py::check_evidence_requirements`（NO_SELFIE/FACE_NOT_VERIFIED） |
| R10 时间校准 | `current_time_millis` | `check_time_consistency`（CLOCK_SKEW/UPLOAD_DELAY） |
| R11 环境反作弊 | `frida_detected`/`xposed_detected`/`simulator_detected`/`app_open_more`/`being_traced` | `AntiMockDetector`/`AntiEmulatorDetector`/`IntegrityGuard` |
| R12 有效里程=超速段剔除 | 每公里配速列表 `item_pace_list` | `segment_stats`（valid_m）+ `nativeAccumulate`（valid_dist_m） |
| R3 里程下限 | `sport_distance_commit_tip` | `check_evidence_requirements`（DISTANCE_TOO_SHORT） |

## 4. 检测点 ↔ 代码映射（task-2 沉淀）

| 实证检测点（agent-team/runtime/REPORT.md） | 日志证据 | 防御实现 |
|---|---|---|
| fakelocation 包检查（BaseActivity.onResume） | `NameNotFoundException: com.lerist.fakelocation` | `AntiMockDetector`（MOCK_APP_INSTALLED 黑名单，含该包） |
| root 检测（执行 su） | `AppUtils.isAppRoot() → Cannot run program "su"` | `IntegrityGuard.isRooted()`（su 路径 + Magisk 痕迹，免 spawn） |
| Xposed 检测 | `ClassNotFoundException: de.robv.android.xposed.XposedBridge` | `IntegrityGuard.isXposedActive()` |
| frida 字符串/端口 | csn 资产 10+ 处 frida 字符串；frida-server 默认 27042 | `IntegrityGuard`（MAPS_FRIDA + 端口探测） |
| 调试检测 | `being_traced`；壳 ptrace 被 SELinux 拒绝 | `IntegrityGuard.isDebugged()`（TracerPid） |
| 模拟器检测 | `simulator_detected`；真机基线 ro.boot.hardware=qcom | `AntiEmulatorDetector`（Build/prop/传感器/基带/文件系统指纹） |
| 多开检测 | `app_open_more` | `AntiEmulatorDetector`（DUAL_APP_INSTALLED） |
| 传感器强制要求 | Manifest uses-feature stepcounter/stepdetector required=true；logcat 加速度计 200ms 批处理 | `AntiEmulatorDetector`（SENSOR_STEP_MISSING）+ `metric_guard`（步数累计） |

## 5. 攻击方式对照（A/B）

- **攻击方式 A（本机虚拟环境，offense 方式一：无 root 真机 Gadget 注入 + 模拟定位）**
  - 定位伪造 → `AntiMockDetector` 四路信号（系统 API/高德 SDK/包名黑名单/多源交叉偏差）。
  - 重打包注入 Gadget → `IntegrityGuard` 签名 + DEX CRC 自校验 + maps 特征扫描。
  - Java 层改里程/步数 → `metric_guard.cpp` native 下沉 + HMAC 信封。
  - 伪造轨迹上报 → 服务端 `SPEED_MISMATCH`（上报速度与坐标矛盾）、`SUSPECT_VEHICLE`、重复轨迹检测。
- **攻击方式 B（虚拟机，offense 方式二：模拟器/redroid 容器）**
  - VM 硬件指纹 → `AntiEmulatorDetector`（Fingerprint/hardware/传感器/基带/存储多信号加权）。
  - VM 自带 root/frida-server → `IntegrityGuard`（root + 27042 + Xposed 探测）。
  - VM 时钟漂移/拼接轨迹 → 服务端 `CLOCK_SKEW`/`SAMPLE_GAP`/`DUP_SHAPE`。

## 6. 快速运行（服务端两个 Python 内置合成 demo）

```bash
python server-side/trajectory_plausibility.py   # 3 组 demo：正常/脚本轨迹/重放
python server-side/run_evidence_validator.py    # 3 组 demo：正常/骑车/虚拟环境
```

预期：正常跑步 pass；骑车作弊命中 SUSPECT_VEHICLE（reject）；虚拟环境作弊
命中 SPEED_MISMATCH + OFF_ROAD + DUP_SHAPE（reject）。

客户端 Kotlin/C++ 为工程集成骨架：Kotlin 放入 `cn.edu.hust.campusrun.shield.defense`
包，C++ 用 NDK 编译为 `libmetric_guard.so`（编译命令见文件头注释）。

## 7. 文档索引

- 项目蓝图：`docs/04-defense-blueprint.md`（由文档组编写，本目录实现其 §3 分层架构）
- 攻击侧测试床：`offense/`（方式一 Gadget 注入 / 方式二 VM 测试床）
- 逆向证据：`agent-team/sport/REPORT.md`（判定规则）、`agent-team/runtime/REPORT.md`（检测点）、
  `agent-team/unpack/REPORT.md`（加固与脱壳）

## 8. 免责与授权

本项目所有代码仅用于：授权安全评估、防护研究与教学演示。集成方需遵守
所在地法律法规与所在高校的锻炼管理办法，服务端判定阈值需按实际运营数据校准。
