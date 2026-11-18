# 02 · 攻击方式一：本机虚拟环境改造（无 root · Gadget 注入 + 定位虚拟层）

> 一句话：不改系统、不 root，只把「App 看到的定位世界」换成一条可编排的虚拟轨迹。

## 1. 原理

高德定位 SDK 6.6.0（`libAMapSDK_Location_v6_6_0.so`）走**自研融合定位**：GNSS + 基站 + WiFi 融合后
通过 `AMapLocationListener.onLocationChanged(AMapLocation)` 回调给业务层。
系统级 `LocationManager` mock 对它无效（它不读系统 mock 源），因此必须在 **SDK 回调层**做替换。

```
真实世界                       App 进程内
GPS/基站/WiFi ──► 高德融合定位 ──► AMapLocationListener ──► SportLocationService ──► 服务端
                        ▲
                        └── 虚拟定位层（Frida Gadget 注入）
                            · 拦截所有 Listener 实现类
                            · 用轨迹播放器替换 lat/lng/speed/accuracy/time/type
                            · isMock/setMock 伪装
```

## 2. 实施步骤（已在本项目实测走通，详见 `work/hook-router/README.md`）

1. **工具链**：apktool 2.9.x、uber-apk-signer 1.3.0、frida-gadget 16.x（与设备 ABI 一致）、adb
2. **解包**：`apktool d base.apk` → 壳的 Application 为 `com.security.shell.AppStub1`
3. **注入 Gadget**：`lib/<abi>/libcampusrun.so` + `libcampusrun.config.so`（script 模式，脚本路径指向 `/data/local/tmp/`）
4. **smali 插桩**：`AppStub1.attachBaseContext` 首行 `System.loadLibrary("campusrun")`
5. **重建 + 签名**：`apktool b` → `uber-apk-signer`（zipalign + v1/v2/v3）
6. **部署**：推脚本与路线 JSON → `adb uninstall` 原版 → `adb install` 重打包版
7. **运行**：开跑后 App 地图显示的是配置的轨迹（操场跑道/圆形/自定义点列）

## 3. 能力与边界

| 能力 | 状态 | 说明 |
|---|---|---|
| 轨迹伪造 | ✅ | 任意路线、任意速度（建议 2~5 m/s 合理区间） |
| 定位类型/精度伪装 | ✅ | type=GPS、accuracy/satellites 可设 |
| mock 标志伪装 | ✅ | `isMock()` 恒 false、`setMockEnable` 无效化 |
| 步频伪造 | ❌ | 传感器仍是真实数据 → 速度需与真实步频自洽 |
| 人脸核验绕过 | ❌ | 仍需真人，起终点活体无法伪造 |
| 绕过壳完整性校验 | ⚠️ | 实测触发 `entry classes.dex is tamper`，需补丁 smali 校验调用 |

## 4. 被检测点（本项目实测）

- 壳（DA2Lock + libjdi）对 `classes.dex` 做 CRC/哈希校验 → 重打包即崩
- 运行时：XposedBridge 检查、`su` 执行检查、fakelocation 包名检查（每页 onResume）
- 壳内嵌 frida/xposed 特征串检测；`libantitrace.so` 做反调试
- 结论：方式一的**天花板 = 客户端完整性 + 注入检测**，服务端交叉校验才是本质防线

## 5. 复现入口

- 脚本模板：[`offense/ondevice/hook.js`](../offense/ondevice/hook.js)
- 路线生成：[`offense/ondevice/gen_route.py`](../offense/ondevice/gen_route.py)
- 完整实操与排错：[`offense/ondevice/README.md`](../offense/ondevice/README.md)
