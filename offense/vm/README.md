# campusrun-shield · offense/vm —— 方式二：虚拟机 + 本地虚拟环境测试床

> **用途声明**：本项目为「2026 华中科技大学优秀开源项目征集」参赛作品的一部分，全部内容仅用于**授权安全评估**与**自建隔离实验环境**中的逆向分析、防护研究教学。请在获得目标应用与设备所有者的明确授权后使用，且只对你自己搭建的虚拟 Android 环境操作。任何面向生产环境、他人账号或真实校园运动数据的应用均不属于本项目范围。

本目录提供「方式二」的完整测试床：**在完整虚拟 Android 环境（模拟器 / redroid 容器）中复现"本地虚拟环境类"攻击路径（攻击方式 B）**，用于对照验证本项目 `defense/` 中的防护参考实现。

---

## 1. 原理

方式二不依赖真机，而是在宿主 PC 上运行一个**完整的虚拟 Android 系统**（QEMU/KVM 模拟器，或 redroid 容器化 Android），并在其上叠加一层**虚拟环境控制面**：

```text
┌─────────────────────────────────────────────────────────┐
│                    宿主 PC（Windows / WSL2）              │
│                                                         │
│  ┌───────────────────────────────────────────────────┐ │
│  │ 虚拟 Android 系统（模拟器镜像 / redroid 容器）      │ │
│  │                                                   │ │
│  │  ┌─────────────────────────────────────────────┐  │ │
│  │  │ 目标应用 net.crigh.hzkjsport（原样安装）      │  │ │
│  │  │ 加固壳: DA2Lock + libjdi VMP + CSN（不受影响）│  │ │
│  │  └─────────────────────────────────────────────┘  │ │
│  │                                                   │ │
│  │  虚拟环境控制面（root 之下）                        │ │
│  │  ├─ Magisk / Superuser        → 系统级权限        │ │
│  │  ├─ frida-server              → 进程注入/Hook     │ │
│  │  ├─ LSPosed + 模块             → Java 层 Hook     │ │
│  │  ├─ 模拟定位（GPS 路由/虚拟基站）→ 轨迹伪造        │ │
│  │  ├─ 传感器伪造（加速度/计步）    → 步频伪造        │ │
│  │  └─ 虚拟摄像头                 → 人脸/拍照伪造     │ │
│  └───────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

与方式一（`offense/gadget/`，无 root 真机 Gadget 注入）的本质区别：**方式二拥有系统级控制权，可伪造一切设备输入（GPS/传感器/摄像头），但代价是运行环境本身就是最强的"环境指纹"**——模拟器与容器的硬件特征会暴露在目标应用的检测面之下。

### 1.1 目标应用的现实情况（证据引用）

本测试床针对华中科技大学校园跑 `net.crigh.hzkjsport` v1.1.0（青果 ChingoSport 多校平台华科定制版）。分析报告见 [`../../agent-team/runtime/REPORT.md`](../../agent-team/runtime/REPORT.md) 与 [`../../agent-team/sport/REPORT.md`](../../agent-team/sport/REPORT.md)（工作区共享证据，任务板 task-2/task-3 产出）。与你直接相关、且**已在真机 logcat 实证**的检测点：

| # | 检测点 | 触发位置（实证） | 对本方式的影响 |
|---|---|---|---|
| D1 | **Xposed 检测**：主动 `loadClass("de.robv.android.xposed.XposedBridge")` | `MyApplication.onCreate`，经 `EasyProtectorLib → SecurityCheckUtil` | **直接拦截 LSPosed/Xposed 框架**。VM 里装 LSPosed 等于自曝 |
| D2 | **Root 检测**：`ProcessBuilder("su")` 执行探测 | `AppUtils.isAppRoot()`，RxJava 重试多轮 | **直接拦截 Magisk**。VM 里 su 常驻，必须隐藏（Shamiko/Zygisk DenyList） |
| D3 | **模拟定位检测**：检查包 `com.lerist.fakelocation` 是否安装 | `BaseActivity.onResume`（每个页面 resume 都执行） | 只要不装 FakeLocation 这个包名即可绕过该条；但它说明 app 在查 mock 包 |
| D4 | **frida 检测字符串**：壳 CSN 数据 `assets/csn8783f650.data` 明文区含 10+ 处 `frida` / `xposed` 常量；高德定位 SDK `libAMapSDK_Location_v6_6_0.so` 内含 `Frida` 字符串 | 静态存在，调用点待脱壳定位 | frida-server 需改名 + 换默认端口（27042），并配合反检测脚本 |
| D5 | **加固壳反调试**：`DA2Lock v1.0.35r` + `libapk0000.so` + SELinux ptrace 拦截 | 每次进程启动（含 `:channel` 子进程） | 附着 frida 时机的选择比方式一更关键（见 3.4） |
| D6 | **定位融合**：高德定位 SDK 自带 `mock_location` 检查能力 | `AMapLocation`（SDK 6.6.0） | 模拟定位必须走"GPS 信号级"伪造而非 `setMockLocation` 直写 |

> 结论：在 VM 中，**环境类检测（D1–D3）是主要矛盾**，Hook 类检测（D4–D5）与真机方式一共享同样的对抗面。

### 1.2 暴露面收缩策略（VM 环境改造清单）

| 检测 | 收缩手段 | 备注 |
|---|---|---|
| D1 Xposed | 放弃 LSPosed，改用 frida 完成全部 Java/Native Hook；如确需 Xposed 生态，用 Zygisk 加载 + 隐藏 | 目标 app 的 XposedBridge 检查只跑一次（Application.onCreate），改 frida 最省事 |
| D2 su | Magisk + Shamiko / Zygisk DenyList 对目标包隐藏 root；移除 PATH 中的 su 可用性 | `isAppRoot()` 走 `ProcessBuilder("su")`，DenyList 可使其失败 |
| D3 fakelocation 包 | 不安装包名含 `fakelocation` 的任何应用；用 frida 直接 Hook LocationManager 更隐蔽 | 注意 app 可能扩展检查其它 mock 包名 |
| D4 frida | frida-server 改名 `frzs`、监听非默认端口、关闭默认命名管道；配合 gadget/自定义端口 | 高德 SDK 的 Frida 检测主要扫 maps 与 27042 端口 |
| 环境指纹 | 模拟器侧：改 Build 指纹、传感器列表、IMEI/基带字段（详见 sensor-spoof.md §4） | redroid 默认指纹极显眼，必须改 |

---

## 2. 与方式一（无 root 真机 Gadget）能力对比矩阵

| 维度 | 方式一：真机 + Gadget 注入（offense/gadget/） | 方式二：VM + 虚拟环境（本目录） |
|---|---|---|
| **定位伪造** | 中。Hook `LocationManager`/`AMapLocation` 返回伪造坐标；受高德 SDK 内部 mock 检测与 D3 类包检查约束 | **高**。GPS 信号级伪造（模拟器 GPS 路由 / redroid GPS HAL 注入）+ Hook 双通道；还可伪造基站/WiFi 定位上下文，多源一致 |
| **传感器（步频/加速度）伪造** | 低-中。真机传感器数据真实，Hook SensorManager 仅能改 Java 层读数，且被系统级注入限制（见 sensor-spoof.md §2） | **高**。模拟器支持传感器事件回放（Genymotion 内置、MuMu 脚本、redroid 通过 HAL）；可生成"物理自洽"的步频-速度-距离曲线 |
| **摄像头/人脸** | 高。真机摄像头真实，活体核验可真实通过 | 中-低。虚拟摄像头画面可注入，但**活体核验（TNN/OpenCV 人脸栈 + 转动指令）在 VM 下几乎不可行**——详见 sensor-spoof.md §4 边界结论 |
| **被检测风险** | **低**。真机环境指纹干净；主要暴露面是 Gadget 注入痕迹与 Hook 框架 | **高**。VM 本身是最大指纹（Build 字段、传感器缺失、无基带、渲染器差异）；需叠加全套环境伪装，且高德 SDK 的 `mock_location`/模拟器检测持续升级 |
| **部署成本** | 中。需要一台可控 Android 真机 + 改包/Gadget 注入流程 | **低**。一台 PC 即可；模拟器分钟级启动，redroid 一条 docker compose 命令；可批量克隆多实例并行验证 |
| **root 权限** | 无（限制多） | 有（限制少，代价是 D2 检测面） |
| **自动化/CI** | 难。物理设备不可伸缩 | **强**。容器化可进 CI：`docker compose up -d` → adb → 自动化脚本 |
| **适合场景** | 最终验证、真实行为观测、防护规则验收 | 快速迭代 Hook 脚本、传感器协议逆向、防护实现(defense/)的对抗性测试 |

---

## 3. 两条落地路线

### 路线 A：Windows 本机模拟器（推荐起步）

| 模拟器 | 特点 | root | 传感器/定位控制 | 备注 |
|---|---|---|---|---|
| **MuMu 12** | 性能好，Windows 友好，自带 adb | 内置开启开关 | 支持 GPS 路由、传感器模拟脚本 | 对 x86_64 Android 镜像，arm 应用靠转译 |
| **雷电 LDPlayer 9** | 国内生态，多开方便 | 内置 | 定位模拟、虚拟按键 | 目标 app 为 armeabi-v7a，注意 x86 转译兼容 |
| **Genymotion Desktop** | 面向开发者，传感器/定位/电池/网络可编程 | 默认有 adb root | **传感器事件回放（含加速度计）**、GPS 轨迹回放 | 对活体/传感器研究最合适；商业许可注意 |

快速验证流程（以 Genymotion 为例）：

```bash
# 1. 创建 Android 9 镜像（目标 app targetSdk=28 兼容）
gmtool admin create "hzkj" --sdk-version 9.0 --device "Google Pixel 3"

# 2. 安装目标
adb install evidence/apk/net.crigh.hzkjsport-1.1.0-base.apk

# 3. GPS 回放（轨迹伪造入口）
gmsaas instances list        # 拿实例 ID
gmtool device gps set        # 单点
gmtool device gps replay <gpx_file>   # GPX 轨迹回放

# 4. 传感器回放（步频伪造入口）
gmtool device sensors set acceleration x=0,y=0,z=9.8
```

### 路线 B：WSL2 + Docker + redroid（推荐进阶 / CI）

redroid 是 **容器化 Android**（基于 AOSP，无需 KVM 内的完整模拟器 UI），在 WSL2（需开启嵌套虚拟化）上一条 compose 命令起一个 Android 11 实例：

```bash
# WSL2 前置：确认 /dev/kvm 可用
ls -l /dev/kvm

# 启动（本目录 compose 文件）
cd offense/vm
docker compose up -d

# 连接
adb connect 127.0.0.1:5555
adb devices
# 屏幕投屏（可选）
scrcpy -s 127.0.0.1:5555
```

详见 [`docker-compose.yml`](./docker-compose.yml)。redroid 的优势：GPU 加速可选、多实例并发（不同端口）、可挂载宿主机目录作为 sdcard、无 UI 负担适合脚本化；劣势：传感器/GPS 需要额外的 HAL 注入方案（见 sensor-spoof.md §3）。

### 3.1 两条路线对比

| | Windows 模拟器 | WSL2 + redroid |
|---|---|---|
| Android 版本 | 5–13 可选 | 8.1/9/10/11/12/13 镜像可选 |
| GPU/渲染 | 完整（游戏级） | 宿主 GPU 直通可选 |
| 传感器控制 | 厂商 UI/API 成熟 | 需自建 HAL 注入 |
| 多开/CI | 一般 | **原生容器化，强** |
| 系统指纹修改 | 部分可改 | **完全可改（镜像/内核参数）** |
| 上手难度 | 低 | 中 |

---

## 4. 关键组件清单

| 组件 | 作用 | 安装方式（路线 A/B 通用） | 备注 |
|---|---|---|---|
| **Magisk** | 系统级 root 管理 | 模拟器内置 root 或 redroid 镜像内置；真刷入参考 Magisk 官方 | 必须配合 Shamiko/Zygisk DenyList 应对 D2 |
| **Zygisk + Shamiko** | 隐藏 root / 隐藏 Zygisk 注入痕迹 | Magisk 模块仓库 | 应对 `su` 执行探测（D2） |
| **frida-server** | 进程注入与 Hook 运行时 | 本目录 [`setup-frida.sh`](./setup-frida.sh)（自动匹配 ABI） | 改名为 `frzs`、端口 2742（应对 D4）；Android 9 x86_64 用 `frida-server-*-android-x86_64` |
| **frida CLI / frida-tools** | 客户端脚本下发 | `pip install frida-tools` | 配合本项目 `offense/` 下脚本库 |
| **Objection** | 免写脚本的快速 Hook（可选） | `pip install objection` | 适合快速验证类路径 |
| **LSPosed** | Xposed 框架（可选，慎用） | Magisk 模块 | 目标 app 有 XposedBridge 检查（D1），默认**不推荐**在 VM 中启用 |
| **应用变量（AppClone 类）** | 双开/多开环境 | 应用市场 | 主要用于方式一；VM 中可用作"容器内再隔离" |
| **模拟定位** | GPS 轨迹伪造 | 模拟器内置 GPS 路由 / redroid HAL 注入 / frida Hook | 不装 `fakelocation` 包名（D3）；优先 GPS 信号级伪造 |
| **传感器伪造** | 加速度/计步伪造 | Genymotion 回放 / MuMu 脚本 / redroid HAL 或 frida Hook | 详见 sensor-spoof.md §2 |
| **虚拟摄像头** | 画面注入 | 模拟器摄像头源切换 / redroid 无摄像头需外挂 | 活体核验边界见 sensor-spoof.md §4 |
| **scrcpy** | 屏幕投屏与操控 | `winget install scrcpy` / `apt install scrcpy` | redroid 无 UI，必配 |
| **adb** | 调试桥 | Android platform-tools | 所有路线的前置 |

---

## 5. 快速上手（5 分钟）

```bash
# ① 起环境（路线 B）
docker compose up -d && adb connect 127.0.0.1:5555

# ② 装 frida-server（参数化脚本，自动匹配 ABI 与版本）
bash setup-frida.sh \
  --host 127.0.0.1 --port 5555 \
  --frida-version 16.7.19 \
  --listen-port 2742 --alias frzs

# ③ 装目标 app（仓库内证据样本，需授权）
adb -s 127.0.0.1:5555 install ../../evidence/apk/net.crigh.hzkjsport-1.1.0-base.apk

# ④ 验证注入（frida 会话存活即成功）
frida -H 127.0.0.1:2742 -n net.crigh.hzkjsport -l ../../scripts/hello.js
```

常见问题：

- `adb connect` 后 `offline`：redroid 端口映射问题，见 docker-compose.yml 注释；重试 `adb reconnect offline`。
- x86_64 镜像运行 armeabi-v7a 应用：Android 11+ 镜像自带 arm 转译（`ro.product.cpu.abilist` 需含 arm），如闪退换 Android 9 镜像或路线 A。
- frida attach 即闪退：命中 D5（DA2Lock），用 `-f spawn` 模式配合 anti-anti 脚本，详见 sensor-spoof.md §2.3 与方式一共用的 `offense/` 脚本库。

---

## 6. 文件索引

| 文件 | 内容 |
|---|---|
| [`README.md`](./README.md) | 本文件：原理、对比矩阵、双路线、组件清单 |
| [`docker-compose.yml`](./docker-compose.yml) | redroid 容器编排（WSL2 路线） |
| [`setup-frida.sh`](./setup-frida.sh) | frida-server 参数化部署脚本 |
| [`sensor-spoof.md`](./sensor-spoof.md) | 步频/加速度伪造、虚拟摄像头、人脸活体 VM 可行性边界 |

关联文档：方式一 [`../gadget/README.md`](../gadget/README.md)；防护实现 [`../../defense/README.md`](../../defense/README.md)；蓝图 [`../../docs/04-defense-blueprint.md`](../../docs/04-defense-blueprint.md)；分析证据 [`../../agent-team/runtime/REPORT.md`](../../agent-team/runtime/REPORT.md)。
