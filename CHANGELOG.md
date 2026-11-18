# Changelog

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

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
