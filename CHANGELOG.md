# Changelog

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## v0.3.1 — 2026-11-20

### 工程化完善

- 新增 `pyproject.toml`：`pip install -e .` 后可直接使用 `sentinel` 命令
- 新增 `examples/`：真人/回放轨迹、证据包、带加固特征的 APK 样本与四份示例报告
- 新增 [docs/16-sentinel-cli.md](docs/16-sentinel-cli.md)：工具使用手册
- README 目录结构同步 sentinel / tests / examples
- docs 索引补充第 16 篇

## v0.3.0 — 2026-11-20

### 评估工具合入

- 新增 `sentinel/` CLI 工具包：`apk`（加固识别）、`trace`（轨迹可信度）、
  `evidence`（成绩证据校验）、`bundle`（合并报告）、`demo`（样例全流程）
- 新增 `tests/` 单元测试（10 用例，unittest，零依赖）
- CI 增加 sentinel 测试与 demo smoke 步骤
- README 增加「评估工具 · sentinel」章节

## v0.2.1 — 2026-11-20

### 安全通告

- 新增 [SECURITY.md](SECURITY.md)：披露政策与时间线承诺
- 新增 [docs/advisories/](docs/advisories/)：通告索引与首条通告
  [CRSH-ADV-2026-001](docs/advisories/2026-11-20-campus-run-score-integrity.md)
  （校园跑成绩提交接口数据完整性校验缺失，中危，已通过 EDUSRC 报送）
- README 新增「安全通告」入口

## v0.2.0 — 2026-11-20

### 文档

- 新增 ⭐ [docs/10-defense-matrix.md](docs/10-defense-matrix.md)：攻防全景矩阵——8 层 50 种攻击模式 × 检测信号 × 客户端/服务端防御 × 绕过后下一步 × 残留风险
- 新增 [docs/11-shell-and-injection.md](docs/11-shell-and-injection.md)：壳与注入对抗实录（62 个改造版本实测沉淀）
- 新增 [docs/12-anti-frida-anti-hook.md](docs/12-anti-frida-anti-hook.md)：Frida/Hook 六类检测技术与反绕过
- 新增 [docs/13-location-anti-spoof.md](docs/13-location-anti-spoof.md)：定位伪造对抗全链路
- 新增 [docs/14-server-side-validation.md](docs/14-server-side-validation.md)：服务端判定算法（证据校验/形态学/配速模型/图谱风控）
- 新增 [docs/15-defense-in-depth.md](docs/15-defense-in-depth.md)：纵深防御成本模型、验收清单与运营手册
- 新增 [docs/README.md](docs/README.md)：文档导航索引
- 更新 README 文档索引，标注矩阵为总索引入口

## v0.1.0 — 2026-11-18

首个公开版本。

### 攻击测试床（软件）

- 统一 CLI：`offense/campusrun-attacker.py`（info / route / push / attach / repack）
- 方式 A：Gadget 注入重打包 + 定位虚拟层（`offense/ondevice/`，含 hook.js 与路线生成器）
- 方式 B：redroid / 模拟器 + frida-server + 传感器与摄像头伪造方案（`offense/vm/`）
- 方式 C：双开 / VMOS / VirtualApp 容器内运行（`offense/container/`）

### 防护参考实现（库）

- 服务端：轨迹物理合理性、地图匹配、重放检测、成绩证据交叉校验（`defense/server-side/`）
- 客户端：反 mock、反模拟器、完整性自校验（`defense/client-side/`）
- native：关键指标 native 计算 + HMAC-SHA256 签名示例
- 统一校验入口：`defense/validate_cli.py`

### 文档

- 威胁模型、三种攻击方式、防护蓝图、对抗评估矩阵、路线图、开源组织结构
- 架构图：`docs/assets/architecture.svg`

### 案例结论

- 壳（DA2Lock + libjdi VMP）对 `classes.dex` 做完整性校验，重打包触发 `is tamper`
- 目标运行时具备 Xposed / su / fakelocation 检测，且无容器与多开检测
- 服务端侧有超速段剔除与步频异常判定雏形
