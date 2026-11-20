# 13 · 定位伪造对抗（客户端与服务端联合）

> 覆盖从"系统 mock provider"到"SDK getter 覆写"的完整定位欺骗链，
> 每一环给出检测原理、代码要点与绕过成本。

## 1. 攻击链全景

```
第三方路线App(模拟位置应用, appops MOCK_LOCATION=allow)
  → LocationManager test provider 推送轨迹(1Hz)
    → 系统 gps provider 出现 mock 位置
      → 目标App定位SDK读取
        ├─[攻] hook isFromMockProvider → 恒 false
        ├─[攻] hook SDK getter(getLatitude/…) → 返回 mock 坐标
        ├─[攻] hook 监听器 onLocationChanged → setLatitude/setLongitude 改写
        └─[攻] hook 坐标转换(convertToGcj) 参数注入
          → 落库/上传伪轨迹
```

## 2. 逐环检测与防御

### 2.1 系统 mock provider（源头）
- 检测信号：`Location.isFromMockProvider()`；`dumpsys location` 中 `[mock]` 标记；设置项 `mock_location` 非空；appops `MOCK_LOCATION=allow`。
- 防御：
  - SDK 默认过滤 mock（AMap 6.x 融合引擎原生行为）；
  - 自实现兜底过滤：所有 provider 结果统一检查 mock 位；
  - 检测"模拟位置应用"设置：`Settings.Secure.getString("mock_location")` 非空 → 环境标记（注意 Android 12+ 该接口对普通应用已收紧，需要 fallback 检测 appops 状态或 behavior 证据）。

### 2.2 isFromMockProvider 恒 false（Java 层）
- 攻击：hook `android.location.Location.isFromMockProvider` 返回 false。
- 防御升级：**native 层直读对象字段**。`Location` 内部 `mIsFromMockProvider` 字段（可经反射取 fieldId 后 native 读取），绕开被 hook 的 Java 方法；再与 Java getter 返回值交叉比对，不一致即注入痕迹。
- 多 provider 交叉：gps / network / fused 三个来源的 mock 位应一致；不一致即异常。

### 2.3 SDK getter 覆写
- 攻击：覆写 AMapLocation 全部 getter（经纬度/速度/精度/时间/类型/卫星数/海拔/方位角）。
- 防御：
  - native 读字段 vs getter 返回值对账（2.2 同思路推广到 SDK 对象）；
  - 关键坐标消费点不调用 getter，直接经 native 通道取数；
  - getter 调用频率/耗时异常检测（hook 引入开销与缓存形态变化）。

### 2.4 监听器 onLocationChanged 替换
- 攻击：枚举实现 AMapLocationListener 的类（含业务 ExerciseActivity 等），替换回调，改写传入对象字段。
- 防御：
  - 回调内位置合理性检查：与上一回调的位移/时间差/速度三角校验；
  - 类名/方法名混淆（ProGuard 全开 + 自定义字典），抬高枚举匹配成本；
  - 回调入口关键段 native 化。

### 2.5 坐标转换函数劫持
- 攻击：hook native `convertToGcj`（WGS84→GCJ02）入口改参数，从源头注入虚拟坐标。
- 防御：函数首字节基线自检（docs/12 §2.1）；函数内做输入合法性校验（纬度范围、与系统定位一致性）；转换前后坐标交叉核对。

### 2.6 时间戳与新鲜度
- 攻击：回放过期 mock 位置（实测出现 elapsed 时间 3 天前的 mock 位置仍被读取）。
- 防御：消费侧强制新鲜度校验（>5 分钟丢弃）；用 `elapsedRealtime` 单调钟算时间差，防 wall clock 被同步伪造；上报时间戳由服务端再次校验。

### 2.7 getLastKnownLocation 伪造
- 攻击：覆写 SDK 的 getLastKnownLocation 直接返回构造对象。
- 防御：首定位不单信 SDK 缓存，另行 query 系统 provider 比对；返回对象字段与系统 lastLocation 不一致即异常。

## 3. 传感器交叉验证

- 步频（传感器）与 GPS 速度交叉：步频 × 步幅 ≈ 速度，偏差超阈值即疑；
- 加速度计噪声熵：真人步态有节律噪声，回放轨迹无对应传感器事件；
- 穿戴设备（如有）心率与配速相关性。

## 4. 客户端组件对应

| 需求 | 组件 |
|---|---|
| mock/系统级检测 | `defense/client-side/AntiMockDetector.kt` |
| 字段级交叉校验 | `defense/client-side/native/metric_guard.cpp` |
| 环境（设置/root/xposed） | `defense/client-side/AntiEmulatorDetector.kt` + IntegrityGuard.kt |

## 5. 结论

定位伪造是"源头可检测、中间层可拦截、终局靠服务端"的典型分层对抗。
客户端能做的：mock 位直读、字段对账、新鲜度、传感器交叉——全部为了**压缩伪造空间**；
服务端能做的：轨迹形态学与证据校验（docs/14）——判定真伪的终局。
