# 方式一 · 本机虚拟环境改造（Gadget 注入）使用指南

> 目标：无 root 真机上让 App 看到一条虚拟轨迹。适用于校园跑类 App（本文以高德定位 SDK 为例）。

## 1. 前置

- `apktool` 2.9.x、`uber-apk-signer` 1.3.0、`frida-gadget` 16.x（与设备 ABI 一致）、`adb`、JDK
- 目标 APK（从合法渠道获取，仅限授权评估）

## 2. 步骤

```bash
# 2.1 解包
apktool d -f -o apk-out target.apk

# 2.2 放 Gadget（按设备 ABI：arm64-v8a / armeabi-v7a）
cp frida-gadget-android-arm64.so apk-out/lib/arm64-v8a/libcampusrun.so
cp gadget-config.json           apk-out/lib/arm64-v8a/libcampusrun.config.so

# 2.3 smali 插桩：找到壳/应用 Application 的 attachBaseContext，首行插入
#     const-string v0, "campusrun"
#     invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V

# 2.4 重建 + 对齐 + 签名
apktool b apk-out -o repacked.apk
zipalign -p -f 4 repacked.apk aligned.apk
apksigner sign --ks my.keystore --ks-key-alias mykey --out final.apk aligned.apk
# （或用 uber-apk-signer：java -jar uber-apk-signer.jar -a repacked.apk --overwrite）

# 2.5 部署
adb push hook.js /data/local/tmp/campusrun_hook.js
adb push campusrun_route.json /sdcard/Download/campusrun_route.json
adb uninstall <包名>
adb install final.apk
```

## 3. 验证

1. 启动 App，进锻炼页开跑 → 地图轨迹应为配置路线
2. 排错：`hook.js` 顶部 `CONFIG.DEBUG = true`，`adb logcat | grep -i campusrun`

## 4. 关键配置（`hook.js` CONFIG）

| 参数 | 说明 |
|---|---|
| `JSON_PATHS` | 路线 JSON 查找顺序（Android 11+ 建议 app 专属外部目录优先） |
| `TICK_MS` | 定位节拍（按真实流逝时间推进，默认 1000ms） |
| `FALLBACK_SPEED/ACCURACY` | 缺省配速与精度 |
| `FAKE_LAST_KNOWN` | 是否同时伪造 `getLastKnownLocation` |
| `SIM_TIME/TIME_SCALE` | 时间轴策略（默认真实时间，服务端时间校验安全） |
| `DEBUG` | 日志开关（生产 false） |
| `EXFIL_DEX*` | 可选：壳解密 dex 落盘拷贝（默认关闭，见案例附录） |

## 5. 常见问题

| 现象 | 处理 |
|---|---|
| 启动即崩，`...is tamper` | 壳完整性校验：定位校验调用点（如 `_me_.a.b → x.v`）并补丁 smali，重建验证 |
| 无日志 | DEBUG=true 重新 push；确认 config 路径与脚本文件名一致 |
| 轨迹不动 | 确认 JSON 已推送且格式正确；确认 hook 到 Listener 实现类 |
| 安装失败 INCOMPATIBLE | 先 `adb uninstall` 原版（注意数据丢失） |

## 6. 与防护的对应关系

方式一能绕过客户端 mock 检测，但：
- 触发壳完整性校验（若未补丁）
- 无法伪造步频 → 服务端「步频×速度」交叉校验会识破
- 无法绕过人脸活体

→ 详见 `docs/04-defense-blueprint.md`、`docs/05-evaluation.md`
