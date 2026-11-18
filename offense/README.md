# offense — 攻击端软件（三方式测试床）

> 用途：在**你自己的设备/虚拟环境**上验证校园跑类 App 的定位与传感器防护水位。
> 本目录是软件源码，实测流水线与阶段结论见 `docs/` 与项目案例附录。

## 统一入口：`campusrun-attacker.py`

| 子命令 | 作用 |
|---|---|
| `info` | 设备与目标 App 状态（adb devices / ABI / 包是否存在） |
| `route` | 生成轨迹 JSON（透传 `gen_route.py`，支持 circle / stadium / points） |
| `push` | 推送 hook.js 与轨迹 JSON（`--debug` 推送带日志版本） |
| `attach` | frida 附加目标进程（需设备侧 frida-server，方式 B/C 的免重打包路径） |
| `repack` | 方式一重打包操作指引（含壳完整性校验注意事项） |

```bash
python offense/campusrun-attacker.py route --shape circle --radius 200 -o route.json
python offense/campusrun-attacker.py push --route route.json --debug
python offense/campusrun-attacker.py attach
```

## 方式一：本机虚拟环境改造（`ondevice/`）

无 root、不改系统，用 Frida Gadget 注入重打包 APK，在 App 进程内构建定位虚拟层。

| 文件 | 作用 |
|---|---|
| `hook.js` | 定位虚拟层模板：等类加载 → hook `AMapLocationListener` 全部实现类 → 轨迹播放器替换坐标/速度/精度/时间/类型；mock 伪装；可配置脱壳 exfil |
| `gen_route.py` | 路线生成器：圆形 / 跑道圆角矩形 / 自定义点列 → `campusrun_route.json` |
| `gadget-config.json` | Gadget 配置模板（script 模式，脚本路径 `/data/local/tmp/`） |
| `README.md` | 完整注入-重打包-部署-验证步骤与排错 |

## 方式二：虚拟机 + 本地虚拟环境（`vm/`）

完整虚拟 Android（redroid / 模拟器）+ root + frida-server + 传感器/摄像头虚拟层。

| 文件 | 作用 |
|---|---|
| `docker-compose.yml` | redroid 容器编排 |
| `setup-frida.sh` | 匹配 ABI 的 frida-server 部署脚本 |
| `sensor-spoof.md` | 步频/加速度/摄像头伪造方案与边界 |

## 方式三：应用级虚拟环境（`container/`）

把目标 APK 塞进容器（双开助手 / VMOS / VirtualApp 派生）运行，容器内完成 hook 与定位伪造。
**不需要重打包 → 不触发壳的 dex 完整性校验**；可行性根因是目标只做了安装包静态校验、未做运行环境检测。

| 文件 | 作用 |
|---|---|
| `README.md` | 组件清单、攻击流程、为何有效、检测与修复方向 |

## 使用流程总览

```bash
# 1) 生成路线（可通过统一 CLI）
python offense/campusrun-attacker.py route --shape stadium --center LAT,LNG --track-length 100 --radius 18 -o campusrun_route.json

# 2) 方式一：注入 Gadget + 重打包（见 ondevice/README.md）
#    方式二：docker compose + frida-server（见 vm/README.md）
#    方式三：容器内导入 APK + 容器内 hook（见 container/README.md）

# 3) 推送路线与脚本（CLI 一条命令）
python offense/campusrun-attacker.py push --route campusrun_route.json --debug

# 4) 附加目标进程（frida-server 环境，方式 B/C）
python offense/campusrun-attacker.py attach
```

## 边界

仅用于授权评估与自建测试环境；不随附任何针对真实生产账号的操作。
