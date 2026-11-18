# sensor-spoof.md —— 步频/加速度伪造、虚拟摄像头与人脸活体在 VM 下的可行性边界

> **用途声明**：本文属于 campusrun-shield 开源项目的授权评估与自建隔离实验环境研究资料。所有技术描述用于**验证本项目 defense/ 防护实现的有效性**，请勿用于生产环境、他人设备或真实校园运动数据的伪造。

目标应用：`net.crigh.hzkjsport`（华科校园跑，青果 ChingoSport 多校平台）。证据索引：[`../../agent-team/runtime/REPORT.md`](../../agent-team/runtime/REPORT.md)（检测点 D1–D6）、[`../../agent-team/sport/REPORT.md`](../../agent-team/sport/REPORT.md)（计步/GPS/里程/speed_too_fast 判定）。

---

## 1. 问题域

校园跑的防作弊判定（task-3 归纳）依赖四类信号：

1. **GPS 轨迹**（高德定位 SDK 6.6.0）——里程、配速、轨迹贴合度、速度上限（speed_too_fast）
2. **步频/加速度**（Android SensorManager 步数计数器/加速度计）——步频是否与配速物理自洽
3. **时间**——设备时间/服务端时间/上传延迟
4. **生物核验**（人脸：libchingo_face.so + OpenCV4 + TNN）——随机抽查/关键操作前的人证比对

本文解决第 2、3 类信号在**虚拟 Android 环境**下的伪造方案，并给出第 4 类（人脸活体）在 VM 下的边界结论。

---

## 2. 步频/加速度传感器伪造

### 2.1 信号路径与可 Hook 面

```text
物理层（VM 中不存在）→ 传感器 HAL（redroid 默认缺失 / 模拟器可回放）
        → SensorService（系统服务，不可直接 Hook，受 SELinux 保护）
        → SensorManager / SensorEventListener（Java 层，frida 可 Hook）
        → 业务层（计步累加、步频计算）
```

两个伪造层次：

| 层次 | 手段 | 真实度 | 被检测风险 |
|---|---|---|---|
| **HAL/信号级**（最真实） | 模拟器传感器回放（Genymotion/MuMu 脚本）；redroid 自写 HAL 数据源注入 | 高——业务层读到的是"系统真实传感器数据" | 低（无 Hook 痕迹），但 VM 环境指纹仍在 |
| **Java 层 Hook**（最灵活） | frida Hook `SensorManager.registerListener`/`SensorEvent.values`，或 Hook 业务层的计步回调 | 中——只能改读到数据的类；若业务走 native 计步（如 AMap/自研 SO）则失效 | 中——frida 痕迹（D4）；且需要逐点生成自洽序列 |

### 2.2 关键：生成"物理自洽"的加速度序列

伪造加速度必须满足**步频 ↔ 配速 ↔ 距离**的自洽性，否则命中 speed_too_fast 类交叉校验：

```text
步频 f（步/分钟）∈ [120, 190] 正常跑步区间
配速 v（m/s）≈ 步长 L × f / 60，步长 L ≈ 0.8–1.2 m
  → f=170, L=1.0m  →  v ≈ 2.83 m/s（≈ 5'53"/km）
加速度垂直分量近似：a_z(t) = g + A·sin(2π f t)，A ≈ 1.5–2.5 m/s²（跑步落地冲击）
距离积分约束：∫ v dt = 里程，与 GPS 轨迹里程差 < 5%
```

frida 脚本思路（Java 层，示意，完整脚本见 offense/ 脚本库）：

```javascript
// 示意：Hook SensorEvent 返回合成加速度
// 注意：真机/VM 通用；VM 下更建议用 HAL 级回放（见 2.3）
Java.perform(function () {
  var SensorEvent = Java.use('android.hardware.SensorEvent');
  var target = null; // 业务注册的加速度监听器实例，需先 Hook registerListener 捕获
  var T0 = Date.now();
  var F = 172;          // 步频
  var A = 1.8;          // 振幅 m/s²
  setInterval(function () {
    if (target === null) return;
    var t = (Date.now() - T0) / 1000;
    // 垂直加速度 + 随机抖动（噪声避免"太完美"）
    var noise = (Math.random() - 0.5) * 0.2;
    target.values.value[2] = 9.8 + A * Math.sin(2 * Math.PI * F * t) + noise;
  }, 20); // 50Hz 采样
});
```

**步数计数器（TYPE_STEP_COUNTER）**：其为累积值，Hook 时按 `steps = f * t / 60` 单调递增，且步数与加速度序列时间对齐。

### 2.3 系统级注入限制说明（为什么 VM 不一定能"为所欲为"）

1. **SELinux**：`SensorService` 是系统服务，普通 frida 注入的应用进程无法跨进程改它（除非 root + sepolicy 补丁；Magisk 环境可用 `supolicy` 放宽，但**这会进一步放大环境指纹**）。
2. **native 计步**：若业务侧使用自研 SO 或厂商计步（高德步频 SDK 等）在 native 读 `/dev/` 或 HAL，Java 层 Hook 失效，必须：
   - HAL 级伪造（模拟器回放优先）；
   - 或 native Hook（`frida -f` + `Module.findExportByName`，成本高）。
3. **多源交叉**：目标 app 已接入高德定位，SDK 内自带 `mock_location` 检查；传感器伪造若不与 GPS 伪造协同，会因"GPS 显示移动 1km、步数只加 300"这类矛盾被防护侧交叉校验（对应 defense/run_evidence_validator.py 的 GPS vs 步频×步长校验）。
4. **redroid 的传感器现状**：默认镜像**不提供传感器 HAL 数据源**（无加速度计/计步器）。伪造需二选一：
   - 在容器内注入自定义 sensor HAL 实现（工程量大，本项目 `offense/vm/` 预留扩展点）；
   - 退回 Java 层 frida Hook（最通用）；
   - **或改用路线 A 模拟器**（Genymotion 原生支持加速度计事件回放——推荐）。

### 2.4 推荐组合（按目标防护强度递增）

| 场景 | 推荐方案 |
|---|---|
| 仅验证计步累加逻辑（无交叉校验） | frida Hook Java 计步回调，线性步数 |
| 验证步频↔配速校验 | Genymotion 加速度回放 + GPS 轨迹回放（同源脚本生成） |
| 对抗完整 defense/（含 native metric_guard） | HAL 级伪造 + 轨迹规划器（GPX 生成器与加速度序列生成器共享同一"运动模型"） |

---

## 3. 虚拟摄像头方案

| 路线 | 实现 | 真实度 | 适用 |
|---|---|---|---|
| **模拟器摄像头源切换** | MuMu/雷电/Genymotion 均支持"摄像头→本地视频文件/图片"映射 | 中 | 拍照打卡、人脸比对（非活体） |
| **redroid** | 默认**无摄像头设备**；通过 `androidboot.redroid_*` 无原生支持，需外挂 USB 摄像头映射或注入 fake camera HAL | 低-中 | 工程量大，不推荐首期 |
| **frida Hook 相机 API** | Hook `Camera.open`/Camera2 回传预置帧 | 低（活体动作无法响应） | 仅静态拍照场景 |
| **OBS Virtual Camera + 模拟器** | Windows 下虚拟摄像头作为模拟器摄像头源 | 高（可实时注入任意画面，含视频流） | 人脸核验的**非活体**部分 |

### 3.1 目标 app 的摄像头使用面（证据）

- 布局/图标：`activity_attendance_take_photo`、`dialog_take_photo_tip`、`activity_face`、`anim_face_rotate`（人脸采集带转头动画指引）、`item_take_photo`
- 人脸 SDK：`libchingo_face.so` + `libopencv_java4.so` + `libTNN.so`（腾讯 TNN 推理）
- 权限：CAMERA granted（runtime 表确认）

**"anim_face_rotate"（转头动画）是活体动作指令的强信号**：核验时会要求用户按提示转动头部，画面必须实时响应。

---

## 4. 人脸活体核验在 VM 下的可行性边界与结论

### 4.1 判定链

```text
人脸采集（相机帧）→ 人脸检测/关键点（TNN 推理）
  → 活体动作（转头/眨眼，响应"anim_face_rotate"类指令）
  → 与底库比对（学校侧照片）→ 通过/拒绝
```

### 4.2 逐环节可行性

| 环节 | VM 可行性 | 说明 |
|---|---|---|
| 静态人脸画面注入 | **可行** | OBS 虚拟摄像头/模拟器摄像头源切文件即可 |
| 底库比对 | **可行** | 有授权底片（本人照片）即可通过相似度阈值 |
| **活体动作响应** | **基本不可行** | 要求画面随指令实时转动/眨眼：静态照片或预录视频难以与随机指令时序对齐；预录视频需覆盖所有指令序列且动作幅度/时序被判"机械重复"的风险高 |
| 3D 人脸重建/驱动 | 边界外 | 需要完整 3D 模型与实时驱动管线，属另一个问题域 |
| 服务器侧抽查策略 | 不可控 | 若服务端对核验场景随机化（时机/动作/次数），VM 侧无法预知 |

### 4.3 结论（写入项目文档的正式结论）

1. **在 VM 测试床中，人脸活体核验应被视为"不可绕过的环节"**：可以完成画面注入与静态比对，但无法稳定通过活体动作环节。
2. 因此，方式二（VM）的价值定位是：**验证除生物核验外的全部攻击面**（GPS/步频/时间/设备指纹），以及**验证防护侧把"关键操作绑定活体核验"这一策略的有效性**——即：VM 攻击者能伪造一切传感器数据，却仍然被活体环节挡住，这恰恰证明了 defense/ 蓝图中"生物核验作为最终闸门"设计的合理性。
3. 需要真实通过人脸核验的实验（如授权真机全流程评估），应切换到方式一（真机 + Gadget）或直接真人操作——这是两个 offense 方向的**能力边界互补点**，详见 README §2 对比矩阵。
4. 附带结论：`anim_face_rotate` 类**随机动作指令 + 时序校验**是性价比最高的活体增强手段，建议 defense/ 侧将其列为客户端参考实现的建议项。

---

## 5. 与 defense/ 的对应关系（对抗性验证入口）

| 本文件方案 | 被验证的 defense 模块 |
|---|---|
| 2.2 加速度序列生成器 | `defense/server-side/run_evidence_validator.py`（GPS vs 步频×步长交叉校验） |
| 2.3 多源一致性策略 | 同上 + `client-side/AntiMockDetector.kt`（多源偏差） |
| 3 虚拟摄像头 | `client-side/AntiEmulatorDetector.kt`（摄像头特征指纹） |
| 4 活体边界 | `client-side/AntiEmulatorDetector.kt` + 服务端抽查策略 |
| 环境指纹收缩（README §1.2） | `client-side/AntiEmulatorDetector.kt`（Build/传感器/基带指纹） |
