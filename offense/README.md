# offense — 攻击端软件（双方式测试床）

> 用途：在**你自己的设备/虚拟环境**上验证校园跑类 App 的定位与传感器防护水位。
> 本目录是软件源码，实测流水线与阶段结论见 `docs/` 与项目案例附录。

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

## 使用流程总览

```bash
# 1) 生成路线
python ondevice/gen_route.py --shape stadium --center LAT,LNG --track-length 100 --radius 18 -o campusrun_route.json

# 2) 方式一：注入 Gadget + 重打包（见 ondevice/README.md）
#    方式二：docker compose + frida-server（见 vm/README.md）

# 3) 推送路线与脚本，启动 App 验证轨迹
adb push campusrun_route.json /sdcard/Download/campusrun_route.json
adb push hook.js /data/local/tmp/campusrun_hook.js
```

## 边界

仅用于授权评估与自建测试环境；不随附任何针对真实生产账号的操作。
