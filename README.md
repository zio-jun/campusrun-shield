# CampusRun Shield · 校园跑安全盾

**校园跑类运动打卡应用的红蓝对抗开源工具集：两种攻击方式的可复现测试床 + 分层防护参考实现**

> 案例目标：**华中大体育**（`net.crigh.hzkjsport` v1.1.0，青果体育平台 / 华中科技大学校园跑）
> 参赛定位：2026 华中科技大学优秀开源项目征集 · 原创开源软件项目赛道

---

## 为什么做这个项目

校园跑/运动打卡类 App 把跑步成绩与学分直接挂钩，因此天然是作弊对抗的前线：
定位可以用虚拟环境伪造、轨迹可以用脚本播放、步频可以伪造、人脸核验可以尝试绕过。
另一方面，厂商的防护长期是"黑盒"——学生只能靠零散教程试错，学校难以评估自家 App 的真实防护水位。

本项目把攻防两侧都做成**开源、可复现、可度量**的工程：

- **攻击端（软件）**：两种独立的攻击测试床，覆盖当前主流的作弊技术路线
- **防护端（研究）**：以华中大体育为真实案例，给出客户端 + 服务端分层防护蓝图与可运行参考实现
- **评估端**：攻击向量 × 检测手段的对抗矩阵，用实测数据说明"哪一层守得住、哪一层只是心理安慰"

## 两件交付物

| 交付物 | 形态 | 位置 |
|---|---|---|
| **攻击测试床（软件）** | 可执行：`campusrun-attacker.py` CLI + Frida 脚本 + 环境编排 | `offense/` |
| **防护参考实现（库）** | 供集成验证：服务端校验器 + 客户端检测模块 + 校验入口（不是独立 App） | `defense/` |

## 三种攻击方式（本项目核心创新点）

### 方式一：本机虚拟环境改造（无 root）
在不改系统、不 root 的真机上，用 Frida Gadget 注入重打包 APK，在**应用进程内构建一个虚拟定位层**：
Hook 高德定位 SDK 的 `AMapLocationListener` 回调，用轨迹播放器替换经纬度/速度/精度/时间戳/定位类型，
使 App 感知到的"世界"与真实世界解耦。系统级 mock location 对高德融合定位无效，必须在 SDK 层做。

→ 见 [`docs/02-offense-method-a.md`](docs/02-offense-method-a.md)、[`offense/ondevice/`](offense/ondevice/)

### 方式二：虚拟机 + 本地虚拟环境
用完整虚拟 Android（redroid 容器安卓 / Windows 模拟器 / Android-x86）承载 App，在**虚拟机内部**叠加
root + frida-server + LSPosed + 模拟定位 + 传感器伪造 + 虚拟摄像头，形成比方式一更深的环境虚拟化。

→ 见 [`docs/03-offense-method-b.md`](docs/03-offense-method-b.md)、[`offense/vm/`](offense/vm/)

### 方式三：应用级虚拟环境（双开 / VMOS / VirtualApp 容器）
把目标 APK 塞进容器 App（双开助手、VMOS/VPhone、VirtualApp 派生）运行，在容器内完成 hook 与定位伪造。
**不需要重打包 → 不触发壳的 dex 完整性校验**；这条路径能成立，根因是目标应用
**只做了安装包静态完整性校验，却没有做运行环境真实性校验**（未检测容器特征/多开）。

→ 见 [`docs/03b-offense-method-c.md`](docs/03b-offense-method-c.md)、[`offense/container/`](offense/container/)

| 能力 | 方式一（本机注入） | 方式二（虚拟机） | 方式三（容器） |
|---|---|---|---|
| 定位伪造 | ✅ SDK 层 Hook | ✅ 多层（系统/框架/SDK） | ✅ 容器系统层 + SDK Hook |
| 步频/加速度伪造 | ❌（传感器仍真实） | ✅ SensorManager 注入 | ⚠️ 视容器能力 |
| 人脸核验绕过 | ❌（仍需真人） | ⚠️ 虚拟摄像头/活体难度高 | ⚠️ 依赖容器摄像头注入 |
| 需要重打包 | ✅（触发 dex 校验） | ❌ | ❌ |
| 需要 root | ❌ | ✅ | ⚠️ 视容器 |
| 被检测风险 | 中（壳完整性、frida 特征） | 高（模拟器指纹） | 高（容器/多开特征——但目标当前未检测） |
| 部署成本 | 低（真机 + adb） | 中（虚拟化环境） | 低（装个容器 App） |


## 防护蓝图（研究产出）

服务端是真相裁决者，客户端只是第一道过滤器：

1. **轨迹物理合理性**：速度上限/加速度/急停抖动、地图匹配评分、分段平滑度
2. **传感器–GPS 交叉验证**：步频 × 步长 ≈ 速度、加速度能量谱与速度相关性
3. **环境一致性**：IP/基站/WiFi 与 GPS 城市、设备时间 vs 服务端时间、上传延迟分布
4. **群体异常**：轨迹哈希重复、DTW 相似度聚类、同设备多账号、成绩分布异常
5. **证据链**：分段心跳 + 随机抽查点 + 起终/编号点人脸活体 + 请求签名/nonce
6. **客户端检测**：mock 标志与多源偏差、注入特征（maps/TracerPid/端口）、模拟器指纹、签名/CRC 自校验
7. **阶梯处置**：可疑标记 → 复核 → 处罚，避免硬阻断泄露阈值

→ 见 [`docs/04-defense-blueprint.md`](docs/04-defense-blueprint.md)、[`defense/`](defense/)

## 真实案例实测（本项目的可信度来源）

本项目不是纸上推演。在华中大体育上的实测结论（详见 `docs/05-evaluation.md`）：

- 壳为 **DA2Lock + libjdi VMP**（爱加密系），对 `classes.dex` 做完整性校验：重打包即触发 `entry classes.dex is tamper` 崩溃 → 方式一被**客户端完整性校验**直接拦截
- 运行时检测：XposedBridge 检查、`su` 执行检查、fakelocation 包名检查（全页面）、壳内 frida/xposed 字符串
- 业务判定：瞬时超速段剔除（`speed_too_fast`）、步频异常=自行车作弊（`dlg_step_rate_invalid`）、距离过短拒提交、起终点人脸核验
- 因此**单靠客户端检测只能拦截低门槛作弊**；真正有效的是服务端交叉校验（本项目 defense/server-side 给出参考实现）

## 目录结构

```
campusrun-shield/
├── docs/                    # 威胁模型 / 三种攻击方式 / 防护蓝图 / 评估矩阵 / 路线图
├── offense/                 # 攻击端软件
│   ├── campusrun-attacker.py#   CLI：info / route / push / attach / repack
│   ├── ondevice/            #   方式一：Gadget 注入 + 定位虚拟层（hook.js / 路线生成 / 配置）
│   ├── vm/                  #   方式二：redroid / 模拟器 + frida-server + 传感器伪造
│   └── container/           #   方式三：双开 / VMOS / VirtualApp 容器内运行
├── defense/                 # 防护参考实现（库，不是独立 App）
│   ├── validate_cli.py      #   统一校验入口：demo / trajectory / evidence / all
│   ├── server-side/         #   轨迹合理性、成绩证据校验（Python）
│   └── client-side/         #   反 mock / 反模拟器 / 完整性自校验（Kotlin + C++）
├── evaluation/              # 对抗评估矩阵与指标
└── README.md
```

## 快速开始

```bash
# 攻击端：生成轨迹 → 推送脚本与轨迹 → 附加目标进程（frida-server 环境）
python offense/campusrun-attacker.py route --shape stadium --center 30.5142,114.4049 \
    --track-length 100 --radius 18 -o campusrun_route.json
python offense/campusrun-attacker.py push --route campusrun_route.json --debug
python offense/campusrun-attacker.py attach

# 防护端：统一校验入口（demo / 轨迹 / 证据）
python defense/validate_cli.py demo
python defense/validate_cli.py trajectory campusrun_route.json
python defense/validate_cli.py evidence evidence.json

# 方式二：起一套容器安卓（详见 offense/vm/README.md）
docker compose -f offense/vm/docker-compose.yml up -d
```

## 使用边界

本项目面向**授权安全评估、自建测试环境与教学研究**。攻击端代码用于在你自己控制的设备/虚拟环境上
验证防护有效性；请勿用于对他人账户或生产系统进行未授权操作。

## 许可

MIT License，欢迎 issue / PR。见 [CONTRIBUTING.md](CONTRIBUTING.md)。
