# 08 · 相关工作（Related Work）

> 本文档把项目放在学术与工程文献的坐标系里：每条给出"与我们哪一部分相关"，便于评审核对，
> 也便于后续贡献者判断是否需要新增/替换引用。引用均附可访问链接，未标注卷期的以官方页面为准。

## 1. 位置欺骗与 GNSS 攻击

| 文献 | 与本项目的关系 |
|---|---|
| Tippenhauer et al., *On the Requirements for Successful GPS Spoofing Attacks*, ACM CCS 2011 | 经典 GPS 欺骗可行性分析：说明"位置来源不可信"是系统性问题，对应本项目方式一/二的威胁前提 |
| *A Contemporary Survey on GNSS Spoofing Attacks and Effective Countermeasures*（[Zenodo](https://zenodo.org/records/20632922)） | 综述类：GNSS 欺骗检测手段全景（信号层 / 融合层 / 应用层），对应本项目"服务端交叉校验"的定位 |

## 2. 轨迹伪造检测与异常检测

| 文献 | 与本项目的关系 |
|---|---|
| *A Comprehensive Study of Trajectory Forgery and Detection in Location-Based Services*（[IEEE](https://ieeexplore.ieee.org/document/10119175)） | 直接对标：轨迹伪造的生成方式与检测思路，是本项目 `trajectory_plausibility.py`（物理约束 + 地图匹配 + 重放检测）的方法论来源之一 |
| *An Experimental Study of Existing Tools for Outlier Detection and Cleaning in Trajectories*（[arXiv](https://arxiv.org/abs/2511.20139)） | 轨迹离群点清洗的工程评估：对应本项目"超速尖峰剔除 + 分段有效里程"的实现取舍 |

## 3. 传感器可信与设备指纹

| 文献 | 与本项目的关系 |
|---|---|
| Zhang et al., *SensorID: Sensor Calibration Fingerprinting for Smartphones*, IEEE S&P 2019（[IEEE](https://ieeexplore.ieee.org/abstract/document/8835276)） | 传感器校准指纹：说明传感器数据具有可辨识性，为"传感器×GPS 交叉校验"和"设备一致性"提供理论支撑 |
| 本项目实测（case study） | 目标 App 的步频异常判定（`dlg_step_rate_invalid` 复用自行车提示）是"传感器×速度"交叉的工业实践样本 |

## 4. 移动端完整性、反注入与认证

| 文献 / 标准 | 与本项目的关系 |
|---|---|
| *CLOADER: Evading Security Mobile Defenses via Runtime Obfuscation and Adaptive Hooking Tactics*（[arXiv](https://arxiv.org/abs/2609.23396)） | 运行时混淆与自适应 Hook 规避：对应本项目方式一（Frida 注入）与方式三（容器内 hook）的对抗面，以及防护端"注入特征检测"的必要性 |
| [OWASP MASVS](https://github.com/OWASP/owasp-masvs)（移动应用安全验证标准） | 客户端加固的工程基线：对应第 0 层（反调试/反注入/完整性）与第 1 层（关键逻辑 native 化）的设计依据 |
| [Android Play Integrity API](https://developer.android.com/google/play/integrity) | 设备与应用的完整性证明：对应"设备认证作为信号而非唯一判据"的设计取向 |
| [Android Location 开发文档](https://developer.android.com/training/location) | mock location 机制与限制：说明为何系统级 mock 对融合定位 SDK 无效，需要在 SDK 层验证 |

## 5. 校园跑制度与体育政策（中文）

| 文献 / 政策 | 与本项目的关系 |
|---|---|
| 《校园跑APP促进大学生健康的价值、挑战与实践路径研究》，体育世界（[PDF](https://www.sxsportsworld.com/rc-pub/front/front-article/download/117538837/lowqualitypdf/校园跑APP促进大学生健康的价值、挑战与实践路径研究.pdf)） | 制度侧研究：说明校园跑的价值与执行挑战，支撑本项目"记录更自由、审核更自动"的价值主张 |
| 《特色校园跑对学生身体素质的影响研究》，体育世界（[PDF](https://www.sxsportsworld.com/rc-pub/front/front-article/download/97770305/lowqualitypdf/特色校园跑对学生身体素质的影响研究.pdf)） | 效果侧研究：为"校园跑与体质提升的关系"提供经验证据 |
| 教育部《高等学校体育工作基本标准》（教体艺〔2014〕5 号） | 政策依据：校园跑类要求的制度来源，解释为何成绩与学分绑定、作弊动机强 |

## 6. 本项目与文献的关系图

```
        位置欺骗（CCS 2011 / GNSS 综述）
                    │
                    ▼
   方式一 注入重打包 ──┐        ┌── 完整性自校验 / 反注入（MASVS, CLOADER）
   方式二 虚拟机      ──┼──► 攻击 ──► 防护 ◄── 传感器指纹（SensorID）
   方式三 容器        ──┘        └── 轨迹伪造检测（IEEE 轨迹伪造研究）
                    │
                    ▼
        服务端裁决（GNSS 综述 + 轨迹检测 + 传感器交叉）
                    │
                    ▼
        制度层（校园跑中文研究 + 体育工作基本标准）
```

## 7. 引用维护约定

- 新增引用必须是**可访问、可核对**的来源（DOI / 官方页面 / 预印本）
- 不引用无法核实的"内部资料"充当学术支撑
- 与本项目结论冲突的文献同样收录，并在 `docs/05-evaluation.md` 中说明差异

---

## 补充：真实文献引用（2026-11-20 增补）

### 加固与重打包（攻击侧基础）
1. What did you pack in my app? A systematic analysis of commercial Android packers — 商业加固壳系统性分析（DexGuard/Bangcle/顶象等壳家族的脱壳难度与抗分析特性）
2. The terminator to Android hardening services (Zhang et al.) — 加固服务的系统性绕过方法
3. Android app repackaging detection: A comprehensive survey — 重打包检测综述（ScienceDirect）
4. Leveraging Visible Widget Sizes for Detecting Repackaged Android Apps (ACM Internetware) — UI 层重打包检测
5. Study on Structure for Robust App Protection through Commercial Android App Hardening Service (KCI) — 商业加固服务的防护结构研究

### 动态插桩与 Hook
6. Scaling Android Hooking: Usability and Performance of Frida and Xposed for Mass API Interception (Athene/ScitePress) — Frida/Xposed 大规模 API 拦截的可用性与性能（本文动态钩子路线的学术依据）

### 定位欺骗与检测（红蓝对抗核心）
7. GNSS Spoofing in Mobile Devices: A Survey on Impact and Countermeasures — 移动设备 GNSS 欺骗影响与对策综述
8. Guardian Positioning System (GPS) for Location Based Services (arXiv 2505.09743) — LBS 场景下的位置防欺骗机制
9. GPS spoofing detection (2026) — 高德/百度 SDK mock 过滤与传感器融合防伪的产业实现对照

### 本文档的定位
上述文献共同印证本项目的三条技术路线（注入改写/虚拟环境/容器）与防护分层（客户端检测+服务端交叉校验）的合理性；本项目以真实商业壳（顶象 DxShield v5.5.11r/v5.5.72r）与真实校园跑应用为对象，补充了工程级实测数据。

---

## 补充2：GitHub 开源工具链参考（2026-11-20 增补）

| 项目 | 用途 | 与本项目关系 |
|---|---|---|
| [undetected-frida / strongR-frida-android](https://github.com/Furtif/undetected-frida) | Frida 反检测补丁（gadget/server 特征抹除, Magisk/KSU 模块） | 本项目注入载体的免杀升级路线（对抗壳的 libantitrace） |
| [FridaBypassKit](https://github.com/okankurtuluss/FridaBypassKit) | Root/SSL Pinning/模拟器/调试检测通用绕过 | 环境检测绕过的脚本模式参考 |
| [JJKLab/android-mockloc](https://github.com/JJKLab/android-mockloc) | 系统级 mock 位置提供者 | 虚拟环境前端的参考实现 |
| [AuroraNest/Modify_Positioning](https://github.com/AuroraNest/Modify_Positioning) | 安卓/iOS 定位修改 | mock 服务实现参考 |
| [Sriharan-S/elsewhere](https://github.com/Sriharan-S/elsewhere) | Flutter GPS mock（地图选点全局生效） | 与"校园路线"画圈/手绘交互对照 |
| [ceigt/GeoLocation](https://github.com/ceigt/GeoLocation) | LSPosed 定位模块（LocusMimic 后继） | 有 root 环境下的标准工具路线 |

**工程映射**：本项目的"校园路线"= mockloc/elsewhere 的路线 + 无 root 注入；"改造版"= FridaBypassKit/undetected-frida 的注入+反检测结合体。上述项目为免 root 路线的可替代/可组合组件提供了公开实现依据。
