# 03 · 攻击方式二：虚拟机 + 本地虚拟环境

> 一句话：用一台"虚拟 Android"承载 App，在虚拟环境内部叠加 root + 全套虚拟层，
> 可以同时伪造定位、传感器甚至摄像头——比方式一更深、但更容易被环境指纹识破。

## 1. 原理

方式一只改 App 进程内的定位回调；方式二把**整台设备虚拟化**：

```
宿主(Windows/WSL2)
└── 虚拟 Android（redroid 容器 / 模拟器 / Android-x86）
      ├── root / Magisk（完整控制权）
      ├── frida-server（进程注入，替代 Gadget）
      ├── LSPosed / 应用变量（Xposed 层：改 Build 字段、隐藏 root）
      ├── 模拟定位 App（系统 mock provider + 高德 SDK Hook 双保险）
      ├── 传感器注入（SensorManager Hook：步频/加速度/陀螺仪）
      └── 虚拟摄像头（人脸核验时的画面注入）
```

## 2. 两条落地路线

| 路线 | 组成 | 优点 | 缺点 |
|---|---|---|---|
| A. Windows 模拟器 | MuMu / 雷电 / Genymotion / Android-x86(QEMU) | 上手快、GPU 好、快照回滚 | 模拟器指纹明显、部分 App 拒跑 |
| B. WSL2 + Docker redroid | redroid 11/12 镜像 + adb connect + scrcpy | 接近 AOSP、可脚本化编排 | 需要 WSL2/Hyper-V、无 GPU 加速 |

## 3. 关键组件与作用

- **root/Magisk**：frida-server、传感器注入、修改系统属性
- **frida-server**：`frida -U -f <pkg> -l hook.js` 进程注入（与方式一 hook.js 思路一致）
- **LSPosed + 应用变量**：改 `Build.MODEL/FINGERPRINT` 等，弱化模拟器指纹
- **模拟定位**：系统 mock provider（可被 isMock 检测）+ SDK 层 Hook（本项目的定位虚拟层）
- **传感器伪造**：Hook `SensorManager` 事件回调，构造与轨迹速度匹配的步频/加速度序列
- **虚拟摄像头**：向相机预览流注入画面（人脸核验的对抗面，需活体对抗）

## 4. 能力对比（与方式一）

| 能力 | 方式一 本机注入 | 方式二 虚拟机 |
|---|---|---|
| 定位伪造 | ✅ SDK 层 | ✅ 多层 |
| 步频/加速度伪造 | ❌ 真实传感器 | ✅ SensorManager 注入 |
| 摄像头注入 | ❌ | ⚠️ 虚拟摄像头（活体难度高） |
| 环境指纹 | 真机（低风险） | 高（Build/传感器/基带/相机特征） |
| 部署成本 | 低 | 中 |
| 快照回滚 | 无 | ✅ |

## 5. 被检测点（本项目实测 + 通用）

- **模拟器指纹**：`Build.HARDWARE`（goldfish/ranchu）、传感器数量与型号异常、无基带/蓝牙、相机参数异常、ABI 组合异常
- **root/注入特征**：`su`、Magisk 目录、frida 端口（27042）、maps 特征、XposedBridge
- **环境一致性**：模拟器常见 IMEI/序列号空值、GPS 芯片无卫星数据、传感器采样率恒定
- 华中大体育实测的检测点（详见 `agent-team/runtime/REPORT.md`）：XposedBridge 检查、`su` 执行检查、
  fakelocation 包名检查（BaseActivity.onResume 全页面）、壳内 frida/xposed 字符串

## 6. 可行性边界

- 在**未适配检测**的目标上，方式二能通过 95% 以上校验（定位+传感器自洽）；
- 在**已做模拟器识别 + 设备认证**的目标上，方式二的暴露面远大于方式一；
- 人脸活体是两种方式共同的硬边界。

## 7. 复现入口

- 环境编排：[`offense/vm/docker-compose.yml`](../offense/vm/docker-compose.yml)
- frida-server 部署：[`offense/vm/setup-frida.sh`](../offense/vm/setup-frida.sh)
- 传感器/摄像头方案：[`offense/vm/sensor-spoof.md`](../offense/vm/sensor-spoof.md)
