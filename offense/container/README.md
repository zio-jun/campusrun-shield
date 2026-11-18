# 方式三 · 应用级虚拟环境（容器）测试床

> 用途：在**授权评估/自建测试环境**下验证「把目标 App 塞进虚拟空间」这条路径，
> 以及目标应用的环境检测是否真正有效（大多数只做了 dex 完整性，没做容器检测）。

## 1. 组件清单

| 类别 | 选项 | 说明 |
|---|---|---|
| 双开/分身 | 双开助手、平行空间、VirtualApp 派生（开源：VirtualApp、VirtualXposed） | 同进程加载目标 APK |
| 虚拟安卓 | VMOS、VPhone、光速虚拟机 | 完整虚拟 ROM，可注入传感器 |
| hook 层 | LSPosed / 太极（容器内）、容器内 frida-server | 加载 `hook.js` 定位虚拟层 |
| 定位 | 容器自带 mock location、或 SDK 层 hook | 双路径 |

## 2. 攻击流程

1. 安装容器 App（建议选择支持导入外部 APK 的）
2. 容器内导入目标 APK 并安装
3. 启用容器内 hook 框架，加载 `../ondevice/hook.js`
   （或容器内启动 frida-server：`frida -U -f <目标包名> -l hook.js`）
4. 把轨迹 JSON 推到容器内可见存储：
   - `/sdcard/Download/campusrun_route.json`
   - 或目标包专属目录 `/sdcard/Android/data/<包名>/files/campusrun_route.json`
5. 容器内启动目标 App → 开跑 → 观察轨迹是否被替换

## 3. 为什么这条路径有效（给防护方的提醒）

- 容器内运行**不需要改 APK**，因此壳的 `classes.dex is tamper` 校验完全不会被触发
- 目标 App 若未检测容器特征（包名/进程路径/SO/多开标志），就无法区分真实设备与容器
- 修复方向见 `docs/04-defense-blueprint.md` 第 0 层：虚拟空间特征 + 多开检测 + 环境完整性

## 4. 注意事项

- 仅限授权评估与自建环境
- 部分容器要求 root 或特定 ROM 版本
- 容器内的系统服务行为可能与真机不同（例如传感器采样率），这本身也是检测特征
