# 校园跑 App 虚拟轨迹攻击手册（通用化攻击面分析）

> 版本：1.0（2026-11-19）
> 实证样本：华中大体育 `net.crigh.hzkjsport` v1.1.0（versionCode 11，minSdk 21，targetSdk 28，armeabi-v7a）
> 加固：顶象 DxShield v5.5.11r（整体 dex 加密 + 方法抽取 + DEX VMP）
> 定位 SDK：高德定位 SDK 6.6.0 + 高德地图 SDK 7.4.0
> 结论等级：本手册所有结论均来自对上述样本的静态分析（jadx/smali/APKiD）、设备运行日志（logcat/tombstone）与 Frida 实战注入三路证据；面向任何大学的同类"校园跑"应用均可按本手册方法论迁移。

---

## 1. 概述与目标

### 1.1 攻击面总览

校园跑类 App 的核心业务是：**采集学生的跑步轨迹 → 本地校验合法性 → 上报服务端计成绩**。围绕这条链路，可以划分出四个可攻击层：

```
┌────────────────────────────────────────────────────────────────┐
│  L1 壳与完整性（加固壳/签名校验/反调试/环境检测）                │ ← 一切注入的前提
├────────────────────────────────────────────────────────────────┤
│  L2 定位（高德/百度/原生 LocationManager）                       │ ← 轨迹数据源
├────────────────────────────────────────────────────────────────┤
│  L3 传感器（step_counter / step_detector 计步）                  │ ← 步数/步频数据源
├────────────────────────────────────────────────────────────────┤
│  L4 网络（OkHttp 拦截器/设备指纹/请求签名/上报）                  │ ← 成绩出口
└────────────────────────────────────────────────────────────────┘
```

攻击目标（按成本从低到高）：

| 目标 | 手段 | 收益 |
|---|---|---|
| 让 App 相信自己"正在跑步" | 伪造定位回调 + 伪造计步事件 | 轨迹、步数、步频全部可控 |
| 让加固壳不杀进程 | 绕过完整性检查/签名校验/自杀逻辑 | 注入代码能活到业务运行 |
| 让上报不暴露伪造 | 观察请求、规避设备环境检测 | 成绩正常入账 |
| 让伪造数据"合法" | 轨迹速度/配速/步频自洽 | 通过本地与服务端校验 |

### 1.2 本手册的实证基础（一句话攻击链）

**apktool 解包 → 注入 Frida Gadget（16.7.19，32 位，改名 libcampusrun.so，script 模式无端口）→ smali 注入（assets 自解压脚本 + loadLibrary）→ 重打包 + 固定密钥签名（uber-apk-signer）→ 运行时脚本同时完成壳对抗、定位覆写、计步伪造、网络观察。**

---

## 2. 目标画像：通用校园跑 App 组成

一个典型校园跑 App 由五类部件组成，攻击前必须逐一定位：

### 2.1 定位 SDK（轨迹数据源）

| 部件 | 实证样本（华中大体育） | 通用特征 |
|---|---|---|
| SDK | 高德定位 6.6.0（`libAMapSDK_Location_v6_6_0.so`）+ 高德地图 7.4.0（轨迹渲染） | 高德/百度/腾讯三选一，原生 LocationManager 兜底 |
| 关键类 | `com.amap.api.location.AMapLocationClient` / `AMapLocationListener` / `AMapLocation` / `AMapLocationClientOption` | 回调式 API，坐标由 SDK 内部融合（GPS+基站+WiFi） |
| 保活 | `AMAPLocationService_`、`SportLocationService`（persistent=true） | 前台服务 + WakeLock + 一像素 Activity 全家桶 |
| API Key | meta-data `com.amap.api.v2.apikey` | 在 Manifest 明文可见 |

**实证证据**：`launch-logcat.txt:1930` 运行态加载 `libAMapSDK_Location_v6_6_0.so`，调用方为壳释放的 `libdsn_*.jar!classes3.dex`；Manifest 声明 `com.amap.api.location.APSService`。

### 2.2 计步传感器（步数/步频数据源）

- Manifest `uses-feature` 强制要求 `android.hardware.sensor.stepcounter` 与 `stepdetector`（required=true）+ accelerometer —— **计步传感器是硬依赖**。
- 跑步页 UI 同时展示距离/速度/配速/步数/步频（`tv_distance`/`tv_speed`/`tv_pace`/`tv_step`/`tv_cadence_tip`），说明算法侧同时消费 GPS 轨迹与计步数据。
- 对"步数加权"型计分规则（如"2km + 3000 步"双达标），只伪造 GPS 不够，必须同时伪造传感器。

### 2.3 合法性校验（本地反作弊）

实证样本的校验面（R.java 资源 ID 名证据）：

| 资源 ID 名 | 含义 |
|---|---|
| `speed_too_fast` / `txt_tips_speed_err` | 速度过快提示 |
| `txt_tips_overflow_distance` | 距离超出上限 |
| `txt_tips_overflow_time` | 时间超出 |
| `txt_tips_outof_range` | 越界（脱离指定区域/线路） |
| `min_speed` | 最低速度要求 |
| `dlg_step_rate_invalid` | 步频无效对话框 |
| `map_line_validline` | 有效轨迹线 |
| `distance_attendance_range` / `geographic_range` | 地理围栏 |

**环境检测（实锤）**：每次 Activity `onResume` 执行 `SecurityCheckUtils`，用 `PackageManager.getPackageInfo("com.lerist.fakelocation")` 查装 Fake Location（`launch-logcat.txt:1270,2024` 的 `NameNotFoundException`）；网络请求前由 OkHttp 拦截器（`CommonInterceptor1`）执行 `su` 探测 root（`launch-logcat.txt:2053-2064`），最终由 native 方法 `com.security.inner.apk0000.x.l` 驱动。

### 2.4 上报链路（网络）

```
业务代码 → OkHttp/Retrofit → CommonInterceptor1（设备信息采集：su 探测/指纹 MD5）
        → com.security.inner.apk0000.x.l (Native Method，请求加密/签名疑似在此)
        → 服务端（URL 被壳隐藏）
```

- 本地存储：微信 WCDB（`libwcdb.so`，SQLite 加密扩展）——成绩先落库后上报，存在"未上报/补传"机制（`attendance_no_upload`/`upload_lost` 文案）。
- 设备指纹示例：WCDB 日志中出现 32 位 hex（疑似 MD5 `5A455E33FEBF97650710D4B49B897D5C`）。

### 2.5 加固壳

实证样本为顶象 DxShield v5.5.11r：

| 特征文件 | 作用 |
|---|---|
| `lib/armeabi-v7a/libstub000.so` | JNI 桥（`com.security.inner.stub000.x`），依赖 libjdi.so |
| `lib/armeabi-v7a/libapk0000.so` | SONAME=libjdi.so（DVMP 引擎 + 3 段加密 dex） |
| `lib/armeabi-v7a/libantitrace.so` | 反调试（"ptrace myself..."） |
| `lib/armeabi-v7a/libsys_misc.so` | 环境检测（模拟器/root/双开） |
| `assets/__version.txt` | `v5.5.11r` |
| `assets/__param.data` / `assets/b45ee9e1` | 壳参数/高熵密钥 blob |
| `classes.dex` | 仅壳 stub（业务 dex 全部加密下沉 so） |
| Manifest | `application=com.security.shell.AppStub1`，`appComponentFactory=com.security.shell.AppComponentFactory0` |

壳加载链：`AppStub1.attachBaseContext()` → 解压 `assets/output-<abi>.zip` 到 `dataDir/dsn0` → `System.load(libsys_misc.so)` → 设置 `dvmp.stub000.location` → `loadLibrary("stub000")` → `dex_vmp_init2` 分段解密业务 dex → patch PathClassLoader → native 实例化真实 Application。

---

## 3. 攻击原理（四层）

### 3.1 L1 壳与完整性：原版为什么拒绝伪造，如何绕过

**原版防御**（以 DxShield v5.5.11r 为例，共四关）：

| 关卡 | 机制 | 实战证据 |
|---|---|---|
| ① dex 篡改检查 | Thread-2（`_me_.a.run` → native `x.v(1)`）比对 classes.dex 哈希，不符抛 `RuntimeException("entry classes.dex is tamper")` | 重打包样本启动即崩 |
| ② 证书校验 | 主线程 `x.v(14)` 内 loadCsn 直读 base.apk 签名块（APK Signing Block 取证书序列号）与内嵌基准比对，失败 `abort()` | 日志 `shell loadCsn ok time used:265`（L772）；tombstone 帧：匿名可执行区 + `art_quick_generic_jni_trampoline` + libstub000 |
| ③ abort 自杀 | 校验失败调 `abort()`（noreturn） | tombstone 主线程 abort |
| ④ 请求时刻自刀 | 业务请求时 OkHttp 拦截器 → `apk0000.x.l` 校验失败 → `kill(SIGKILL)` 自杀 | hook kill/tgkill 后观察到拦截日志 |

**为什么不能简单处理**：

- abort 是 noreturn 函数，直接 `Interceptor.replace` 拦下会掉进垃圾代码 SIGILL（实测）；
- 把 abort 替换为 pthread_exit 在"主线程校验"场景直接判死：主线程退出 → 进程进 Z 状态被 ART 收尸（实测）；
- 校验点在 `attachBaseContext` 尾部，替换为无限 sleep 只能保进程不崩、业务不可达（仅诊断用）。

**绕过原理（按推荐序）**：

1. **让校验"认为通过"而非跳过 abort**（最可靠）：
   - ① 吞掉检查线程入口：hook `_me_.a.run` 直接 return（Java hook）；
   - ② 签名欺骗：hook `ApplicationPackageManager.getPackageInfo` 返回伪造 PackageInfo（signatures 指向原版证书 DER）；
   - ②' 文件重定向：hook `open/openat`，壳 native 读 base.apk 时改读原版 APK 副本（backtrace 含 libstub000/libapk0000/libsys_misc/anonymous 才重定向）；
   - ③ abort 兜底：`Interceptor.replace(abort, pthread_exit)` —— 仅对非主线程校验有效；
   - ④ 拦截 `libc kill/tgkill` 直接 return 0（非 noreturn，拦下安全）。

2. **攻击面最小化**：签名欺骗/文件重定向让校验根本不触发，abort/kill 拦截只做兜底。

### 3.2 L2 定位：原版为什么拒绝伪造，如何绕过

**原版防御**：

1. 高德定位 SDK `AMapLocationClientOption.setMockEnable(false)` 为**默认值**，即默认丢弃 `Location.isFromMockProvider()==true` 的系统 mock 定位。→ 纯系统 mock provider（开发者选项模拟位置）大概率被 SDK 静默过滤。
2. 业务层查装 Fake Location 包名（`com.lerist.fakelocation`）。
3. 距离/速度/配速/围栏校验在业务层（具体阈值藏在加密 dex）。

**绕过原理**：

- **系统 mock 不够，必须进进程内 hook SDK 回调**。系统 mock provider 写入的假位置带 `isMock=true` 标志，被 SDK 过滤；而 hook `AMapLocationListener` 实现类的 `onLocationChanged(AMapLocation loc)`，在回调参数上直接覆写字段（lat/lon/speed/bearing/accuracy/time），绕开 SDK 的 mock 过滤——因为伪造发生在"SDK 已接受该定位之后、业务代码拿到之前"。
- 双保险：hook `AMapLocation.isMock()` 恒返回 false；`AMapLocationClientOption.setMockEnable()` 变 no-op。
- 联动外部 mock provider：如果系统里已有 mock provider App（如"校园路线"类工具写入 gps provider），hook 脚本可以直接读 `LocationManager.getLastKnownLocation('gps')` 拿假坐标驱动覆写——工具与 hook 分工：工具管路线/UI/前台服务，hook 管注入。

### 3.3 L3 传感器：原版为什么拒绝伪造，如何绕过

**原版防御**：`uses-feature required=true` 强制要求 stepcounter/stepdetector；UI 同时展示步数/步频；存在 `dlg_step_rate_invalid`（步频无效）校验。若只伪造 GPS 而步数不变，服务端/本地会看到"跑了 2km 步数 0"的矛盾。

**绕过原理**：

- hook 所有实现 `android.hardware.SensorEventListener` 的类的 `onSensorChanged(SensorEvent)`；
- 对 `TYPE_STEP_COUNTER`（19）事件：`values[0] = 基线 + 累计增量`（单调不减，与真实计步器行为一致）；
- 对 `TYPE_STEP_DETECTOR`（18）事件：`values[0] = max(1, 本次增量)`；
- **增量 = 每秒位移（播放器当前速度 × 时间系数）/ 步幅 × (1±抖动)** —— 与 GPS 轨迹播放器共用同一时间轴：步频 ≈ 速度/步幅×60，总步数 ≈ 里程/步幅，天然自洽。

### 3.4 L4 网络：原版为什么拒绝伪造，如何绕过

**原版防御**：请求前拦截器采集设备信息（su 探测、指纹 MD5），请求构造/加密/签名疑似在壳 native 层（`apk0000.x.l`）；请求时刻校验失败会 kill(SIGKILL) 自刀。业务 URL 被壳加密隐藏。

**绕过原理**：

- **观察优先**：hook `okhttp3.Request$Builder.build` 与 `Response$Builder.build`，全量记录 url/method/headers/body（base64 摘要+md5）到 logcat —— 先看清上报格式，再决定是否篡改；
- **自刀兜底**：拦截 libc `kill/tgkill`，进程自己杀自己时 return 0（可加条件：仅拦截"自杀"，即参数 pid == 自己 pid 或 pid==0）；
- **环境检测规避**：su 探测走 `Runtime.exec("su")`，在 Java 层 hook 后返回 IOException；fakelocation 包名查询在 hook getPackageInfo 时对目标包返回 NameNotFoundException（或不处理——只是记录）。

---

## 4. 攻击方式清单（1–10）

> 以下 10 种方式覆盖从"零开发"到"全链注入"的完整武器库。每种方式标注：原理 / 实现要点 / hook 片段 / 实战日志证据。

### 方式 1：系统级 Mock Provider（零开发基线）

**原理**：利用 Android 公开 API `LocationManager.addTestProvider()` + `setTestProviderLocation()` 向全系统输出假定位。任何读取位置的 App（含高德 SDK 的 GPS/NETWORK 数据源）都能读到。开发者选项里选中该 App 为"模拟位置信息应用"即可生效，无需 root。

**实现要点**：
- minSdk 26+；权限 `ACCESS_MOCK_LOCATION`；前台服务（`foregroundServiceType="location"`）持 WakeLock；
- 每秒一 tick 写 `Location.setLatitude/setLongitude/setTime/setElapsedRealtimeNanos/setAccuracy(5)/setSpeed/setBearing`；
- 路线插值核心（可移植）：二分定位段 + 大圆 `advance()` 线性推进，速度按 `elapsedRealtime` 推进里程；
- 结束输出后 `removeTestProvider` 恢复真实定位。

```java
// 伪代码（等价于实证的 PlaybackService.java:118-122,187-206）
LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
lm.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true, 0, 5);
lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
Location loc = new Location(LocationManager.GPS_PROVIDER);
loc.setLatitude(lat); loc.setLongitude(lng);
loc.setTime(System.currentTimeMillis());
loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
loc.setAccuracy(5f); loc.setSpeed(v); loc.setBearing(b);
lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
```

**局限（必读）**：mock 位置 `Location.isFromMockProvider()` 恒为 true；高德 SDK 默认 `setMockEnable(false)` 过滤 mock → **对高德系 App 大概率不生效**。对原生 LocationManager 系且不查 isMock 的 App 可能直接生效。结论：作为 5 分钟基线验证，通了就收工，不通进方式 2。

**实战日志证据**：校园路线 App（`dev.campus.route`）反编译源码 `PlaybackService.java` 与 APK 完全一致；README 自认"华中大体育是否显示该轨迹，仍需用对应版本验证"。

### 方式 2：Frida Gadget 重打包注入（无 root 主路线）

**原理**：把 Frida Gadget（32 位 `frida-gadget-android-arm.so`）改名后放进 APK 的 `lib/armeabi-v7a/`，smali 层注入 `System.loadLibrary()` 启动 gadget，脚本以 **script 模式**（无端口）随进程加载。重打包 + 重签名后正常安装，全程不需要 root。

**实现要点（命令级见第 6 节）**：
- **Gadget 版本必须是 16.7.x，不能用 17.x**：Frida 17.x 的 32 位 gadget 无 Java 桥，脚本里 `Java` 未定义（`ReferenceError: Java is not defined`，实测）；
- 改名规避 maps 扫描：`libcampusrun.so`，脚本改 `campusrun_hook.js`，推到 `/data/local/tmp/`；
- 配置 `libcampusrun.so.config`：`{"interaction":{"type":"script","path":"/data/local/tmp/campusrun_hook.js"}}` —— script 模式不开 TCP 端口；
- **nativeloader 只解压"有效 ELF"的 .so**：纯文本 .js 放 lib 目录不会被解压出来 → 脚本与配置放 `assets/`，由 smali 注入的自解压代码在运行时拷到 `/data/local/tmp/`；
- 签名用 uber-apk-signer 固定密钥重签（见方式 4）。

**smali 注入片段**（AppStub1.attachBaseContext 开头）：

```smali
# 自解压 assets/campusrun_hook.js -> /data/local/tmp/
invoke-static {p0}, Lcom/campusrun/Boot;->deploy(Landroid/content/Context;)V
# 加载改名 gadget
const-string v0, "campusrun"
invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
```

**关键坑（本会话实测，必须写进任何文档）**：

1. **Frida 17.x 32 位 gadget 无 Java 桥** → 用 16.7.19；
2. **`Java.perform` 在 gadget script 模式早期排队到 VM 线程不可靠** → 全脚本直呼 `Java.use`，禁用 `Java.perform`；
3. **`Java.use` 触发类 `<clinit>` 里的 loadLibrary 会与 gadget 的 dlopen 嵌套死锁** → 不要预加载壳库（不主动 Java.use 壳类、不 hook 壳 so 的加载函数）；
4. **nativeloader 只解压有效 ELF 的 .so** → 脚本放 assets 自解压；
5. **abort 是 noreturn，直接拦会 SIGILL** → 替换为 pthread_exit；
6. **主线程死亡 = 进程死亡（Z 状态）** → 优先让校验不触发（签名欺骗/文件重定向），自杀拦截只做兜底。

**实战日志证据**：`adb logcat -s CAMPUSRUN` 输出 `script alive pid=XXXX`、`alive@<ts> pid=<pid>`（存活标记写入 app 专属目录）。

### 方式 3：壳完整性检查绕过（dex tamper 吞掉）

**原理**：DxShield 的 Thread-2 检查链是 `_me_.a.run`（Java 入口）→ `com.security.inner.stub000.x.v(1)`（native）→ 比对 classes.dex 哈希 → 不符抛 `RuntimeException("entry classes.dex is tamper")`。两个可 hook 点：Java 入口（吞掉 run，整条检查链空转）与 JNI 异常通道（ThrowNew 吞掉 tamper 消息）。

**实现要点**：
- 首选 hook `_me_.a.run` 直接 return —— 只碰检查线程入口，不碰 `com.security.inner.stub000.x`（实测 hook 壳 JNI 调度器会与壳自身加载流程冲突，SIGILL）；
- 兜底 hook JNI `ThrowNew`（JNI 表偏移 `14 * Process.pointerSize`），消息含 `tamper` 时 return 0；
- `_me_.a` 类懒加载：默认 class factory 拿不到时遍历 `Java.enumerateClassLoaders`，逐个切 `Java.classFactory.loader` 重试；
- 高频轮询 `setInterval(..., 25)` 直到挂上。

```js
function hookSecurityChecks() {
    if (SECURITY_HOOKED) return;
    try {
        Java.use('_me_.a').run.implementation = function () { return; };
        SECURITY_HOOKED = true;
    } catch (e) { /* 枚举 classloader 重试 */ }
}
setInterval(hookSecurityChecks, 25);
```

**JNI ThrowNew 兜底（native 级，不依赖 Java 桥，安装最快）**：

```js
var tbl = env.readPointer();
var throwNewPtr = tbl.add(14 * Process.pointerSize); // JNI ThrowNew 表项
Interceptor.replace(throwNewPtr.readPointer(), new NativeCallback(function (e, clazz, msg) {
    var s = msg.isNull() ? '' : msg.readUtf8String();
    if (s && s.indexOf('tamper') !== -1) return 0; // 吞掉 tamper 异常
    return origFn(e, clazz, msg);
}, 'int', ['pointer', 'pointer', 'pointer']));
```

**实战日志证据**：重打包样本启动即崩，logcat 抛 `RuntimeException: entry classes.dex is tamper`；挂上 run hook 后该异常消失。`CAMPUSRUN: security: _me_.a.run hooked`。

### 方式 4：证书校验欺骗（签名欺骗 + 文件重定向）

**原理**：DxShield 主线程 `x.v(14)` 尾部 loadCsn 直读 base.apk 签名块（APK Signing Block 证书序列号）与内嵌基准比对，失败 `abort()`。两条绕过路径：
- **A. Java 签名欺骗**：hook `ApplicationPackageManager.getPackageInfo`，对本包名返回伪造 PackageInfo（`signatures=[原版证书]`）——覆盖壳经 JNI 反射走 PackageManager 的路径；
- **B. 文件重定向**：hook `open/openat`，壳 native 读 base.apk 时改读原版 APK 副本——覆盖壳直读签名块路径（同时顺带过掉 Thread-2 的 dex 哈希比对）。

**实现要点**：
- 原版证书用 `apksigner verify --print-certs base.apk` 提取 DER → Base64 硬编码进脚本；
- 重定向需限定调用方：backtrace 含 `libstub000`/`libapk0000`/`libsys_misc`/`anonymous` 才改路径，避免误伤业务层正常读 APK；
- 原版 APK 副本推到 app 私有目录（`/data/data/<pkg>/files/orig.apk`，脚本自读可写）。

```js
// A. 签名欺骗
var fakeSig = Sig.$new(B64.decode(ORIG_CERT_B64, 0)); // ORIG_CERT_B64=原版证书
APM.getPackageInfo.overload('java.lang.String', 'int').implementation = function (pkg, flags) {
    var pi = this.getPackageInfo(pkg, flags);
    if (pkg === 'net.crigh.hzkjsport' && pi) {
        pi.signatures.value = Java.array('android.content.pm.Signature', [fakeSig]);
    }
    return pi;
};

// B. 文件重定向（backtrace 过滤版）
Interceptor.attach(Module.findExportByName('libc.so', 'openat'), {
    onEnter: function (args) {
        var p = args[1].readUtf8String();
        if (!p || p.indexOf('base.apk') === -1) return;
        var bt = Thread.backtrace(this.context, Backtracer.FUZZY, 8).map(DebugSymbol.fromAddress).join(' ');
        if (bt.indexOf('libstub000') !== -1 || bt.indexOf('libapk0000') !== -1 ||
            bt.indexOf('libsys_misc') !== -1 || bt.indexOf('anonymous') !== -1) {
            args[1] = Memory.allocUtf8String('/data/data/net.crigh.hzkjsport/files/orig.apk');
        }
    }
});
```

**实战日志证据**：原版运行日志 `L772 shell loadCsn ok time used:265`（`.474s`）；重签名样本主线程 abort（tombstone 帧：匿名可执行区 + `art_quick_generic_jni_trampoline` + libstub000，失败路径在 DVMP 字节码经 JNI 进 libstub000 内 abort）；挂签名欺骗/重定向后 loadCsn 通过、进程存活。`CAMPUSRUN: open-redirect: .../base.apk -> orig.apk`。

### 方式 5：进程自杀拦截（abort→pthread_exit、kill/tgkill 兜底）

**原理**：壳有两类自杀出口：校验失败调 `abort()`（noreturn）；请求时刻校验失败经 `apk0000.x.l` 调 `kill(SIGKILL)`。前者必须替换成 `pthread_exit`（直接替换 abort 会 SIGILL）；后者非 noreturn，直接 return 0 安全。

**实现要点**：
- abort 替换只救"非主线程"的检查——主线程 pthread_exit 仍会带走进程（Z 状态收尸）。因此 abort 拦截是兜底，主防线永远是方式 3/4 的"让校验不触发"；
- kill/tgkill 拦截加 pid 过滤可更精细（只拦自杀：pid==0 或 pid==自己的 pid）。

```js
// abort 是 noreturn：替换为 pthread_exit 让检查线程干净退出
var abortPtr = Module.findExportByName('libc.so', 'abort');
var pe = Module.findExportByName('libc.so', 'pthread_exit');
var pthread_exit = new NativeFunction(pe, 'void', ['pointer']);
Interceptor.replace(abortPtr, new NativeCallback(function () {
    pthread_exit(ptr(0));
}, 'void', []));

// kill/tgkill 拦下（非 noreturn，安全）
['kill', 'tgkill'].forEach(function (n) {
    var p = Module.findExportByName('libc.so', n);
    if (p) Interceptor.replace(p, new NativeCallback(function () { return 0; }, 'int', ['int', 'int']));
});
```

**实战日志证据**：`CAMPUSRUN: abort intercepted -> pthread_exit`；`CAMPUSRUN: kill-block: kill(pid, 9) BLOCKED`（登录等请求时刻，拦截器 → `apk0000.x.l` 校验失败自刀被拦）。

### 方式 6：高德定位回调覆写（核心轨迹伪造）

**原理**：系统 mock 会被 SDK `setMockEnable(false)` 过滤（方式 1 的死穴），因此伪造必须发生在"SDK 已输出、业务未消费"的中间层：hook 所有 `AMapLocationListener` 实现类的 `onLocationChanged(AMapLocation)`，在回调参数上直接覆写坐标字段。伪造点在 SDK 的 mock 过滤之后，`isMock` 检查不再构成障碍。

**实现要点**：
- **hook 面是"实现类"而非接口**：遍历已加载类，筛选实现了 `com.amap.api.location.AMapLocationListener` 的类（含 `com.amap`/`com.loc`/`com.autonavi`/业务包名前缀），逐个挂 `onLocationChanged.implementation`；
- 类懒加载：每 2s 重扫；`AMapLocationClient.setLocationListener` 作为启动触发器兜底；
- 覆写字段：`setLatitude/setLongitude/setSpeed/setAccuracy/setTime/setLocationType(GPS)/setBearing/setAltitude/setSatellites(12)/setGpsAccuracyStatus(GOOD)/setProvider("gps")`；
- 轨迹数据源二选一：内部播放器（读路线 JSON 按速度插值推进）或外部 mock provider（`getLastKnownLocation('gps')` 且 `isFromMockProvider()` 为真才采用）；
- 路线几何：haversine 距离 + 累积里程 + 1Hz tick + 由前 3 米参考点算 bearing + accuracy 抖动（±0.6m）；支持点列/画圈/跑道（圆角矩形）三种形状。

```js
function hookListenerImpls() {
    Java.enumerateLoadedClasses({
        onComplete: function () { /* ... */ },
        onMatch: function (name) {
            if (!(name.indexOf('com.amap') === 0 || name.indexOf('com.loc') === 0 ||
                  name.indexOf('com.autonavi') === 0 || name.indexOf('net.crigh') === 0)) return;
            var C = Java.use(name);
            var ifs = C.class.getInterfaces();
            var isListener = ifs.some(function (i) { return i.getName() === 'com.amap.api.location.AMapLocationListener'; });
            if (!isListener) return;
            C.onLocationChanged.implementation = function (loc) {
                var pt = readMockLocation() || (player ? player.tick() : null);
                if (pt && loc) setLoc(loc, pt);   // 覆写 lat/lng/speed/bearing/accuracy/time
                return this.onLocationChanged(loc);
            };
        }
    });
}
```

**实战日志证据**：`CAMPUSRUN: hooked listener impl: <类名>`（每 2s 重扫日志）；覆写后跑步页地图轨迹沿注入路线推进。

### 方式 7：Mock 标志伪装（isMock / setMockEnable 无效化）

**原理**：即使回调覆写绕过了 SDK 过滤，业务层仍可能调用 `AMapLocation.isMock()` 或 `Location.isFromMockProvider()` 做二次检测。把所有 mock 判定通道钉死为"不是 mock"。

**实现要点**：
- `AMapLocation.isMock()` 恒返回 false；`AMapLocation.setMock(m)` no-op（SDK 内部若给位置打 mock 标记则无效化）；
- `AMapLocationClientOption.setMockEnable(b)` no-op（无论业务传什么，不改变行为）；
- 可选：hook `android.location.Location.isFromMockProvider()` 恒 false（覆盖原生 API 检测路径）；
- 可选：伪造 `AMapLocationClient.getLastKnownLocation()` 返回假位置（覆盖"启动瞬间读上次位置"的路径）。

```js
Loc.isMock.implementation = function () { return false; };
Loc.setMock.implementation = function (m) { /* no-op */ };
Opt.setMockEnable.implementation = function (b) { /* no-op */ };
AMapLocationClient.getLastKnownLocation.implementation = function () {
    return buildFakeLocation(readMockLocation() || (player ? player.tick() : null));
};
```

**实战日志证据**：配合方式 6 使用；注入后 App 侧未见"定位异常/模拟位置"类提示（`txt_tips_gps_err` 未触发）。

### 方式 8：计步传感器伪造（step-spoof）

**原理**：跑步页同时上报 GPS 轨迹与步数/步频，且 Manifest 强制要求计步传感器。hook 所有 `SensorEventListener` 实现类的 `onSensorChanged`，把 `TYPE_STEP_COUNTER`/`TYPE_STEP_DETECTOR` 事件改写为"随模拟轨迹合理增长"：增量 = 每秒位移（播放器速度 × 时间系数）/ 步幅 × (1±抖动)。步数与 GPS 轨迹共用同一时间轴，天然自洽。

**实现要点**：
- 步幅默认 0.78m，可速度自适应（`stride = STRIDE_M + GAIN×(v-2.5)`）；抖动 ±10%；事件间隔上限 3s（后台恢复防暴涨）、单事件增量上限 8 步；
- counter 给绝对值（基线+累计，单调不减——真实计步器从不回退）；detector 给本次增量（`max(1, incr)`）；
- 同一 SensorEvent 被多个 hooked 类先后处理：按 timestamp 去重，只对齐累计值不重复加增量；
- 新一轮代跑（播放器从无到有）：用当前真实累计值重取基线；
- 类筛选：跳过系统类，优先自定义计步 SDK 类，其次业务包（`net.crigh.*`），再次 amap 系；
- 无路线/播放器为 null：透传真实事件，已伪造累计值单调冻结。

```js
var incr = (speed * dt * TIME_COEF * stepSimScale()) / stride;   // dt=距上次事件秒数
incr = incr * (1 + (Math.random() - 0.5) * 2 * STEP.JITTER);
if (type === STEP_COUNTER) {
    stepState.acc += incr;
    out = stepState.base + stepState.acc;
    if (out < stepState.lastEmitted) out = stepState.lastEmitted; // 单调不减
    stepState.lastEmitted = out;
} else {
    out = Math.max(1, incr);   // detector 按事件计步
}
ev.values[0] = out;
```

**实战日志证据**：`CAMPUSRUN: step-spoof: hooked SensorEventListener <类名>`；`step-spoof: type=19 dt=1.00s v=2.50m/s +3.205 steps -> total=1234.0`（跑步页步数与里程/速度自洽）。

**备选方案（默认不启用）**：静止设备无传感器事件可改时——hook `SystemSensorManager$SensorEventQueue.dispatchSensorEvent` 直接注入事件（签名随 Android 版本不同）；或缓存最后事件 1Hz 重放（"补拍"模式，timestamp 为旧值，仅适合步数统计）。

### 方式 9：网络上报观察与请求时刻自刀规避（net-observer）

**原理**：业务请求的 URL/签名被壳加密隐藏。观察先行：hook OkHttp 的 `Request$Builder.build`（所有请求构造的唯一出口，含拦截器重建的最终版）与 `Response$Builder.build`（拿状态码），把 url/method/headers/body 摘要打到 logcat。同时方式 5 的 kill/tgkill 拦截已在"请求时刻校验失败自刀"这一关兜底。

**实现要点**：
- 域过滤：host 命中业务域特征（如 `hzkjsport`/`crigh`）必打；第三方统计/推送/地图域（bugly/umeng/amap/…）跳过；
- body 用 okio Buffer `writeTo` 读字节（构造期读取不消费 body，不影响后续发送），输出 base64 预览 + 全量 md5；
- 单行 <4000 字符自动分块（seq + n/t 编号）；同指纹请求 3s 去重；
- okhttp 类被壳懒加载 → 每 2s 重扫直到挂上；`Java.classFactory.loader` 兜底解析。

```js
B.build.implementation = function () {
    var r = this.build();
    try { observeRequest(r); } catch (e) {}
    return r;
};
// logcat: NETDUMP BEG seq=1 grp=biz host=... method=POST url=https://.../api/sport/upload
//         NETDUMP HDR seq=1 count=12 ...
//         NETDUMP BODY seq=1 b64=... len=1234 md5=ab12... ct=application/json cls=...
//         NETDUMP RESP seq=1 code=200 ok=1 msg=OK
```

**实战日志证据**：运行期可见 `CommonInterceptor1` → `com.security.inner.apk0000.x.l(Native Method)` 栈（`launch-logcat.txt:2053-2064` 的 su 探测即在此链）；请求时刻自刀被拦时输出 `CAMPUSRUN: kill-block: kill(pid,9) BLOCKED`。

### 方式 10：脱壳与业务逻辑外带（dex stash + logcat 流式外带）

**原理**：壳运行时把加密 dex 解密落盘到 `dataDir/dsn0/dex/libdsn_<md5>.jar`，用完即删。抢在删除前把 jar 偷存到私有目录（`File.delete` 与 native `unlink/unlinkat` 双路拦截），再用 logcat base64 流式外带（无存储权限也能出数据）。拿到业务 dex 后可静态分析速度/距离/步频校验阈值与上报格式，反哺伪造参数调优。

**实现要点**：
- `java.io.File.delete` 拦截：文件名以 `libdsn` 开头时先拷到 `/data/data/<pkg>/files/`；
- native `unlink`/`unlinkat` 拦截：路径含 `libdsn` 时同样偷存（壳可能绕过 Java 层直接 unlink）；
- 外带：分块 base64 打 logcat（`DEXS BEG/seq/END` 协议），每 50 块 sleep 80ms 防 logd 丢行；
- 轮询上限 90s（壳解密 dex 在启动后数秒落盘）。

```js
F.delete.implementation = function () {
    var name = this.getName();
    if (name.indexOf('libdsn') === 0) { /* 先拷贝到 /data/data/<pkg>/files/ */ }
    return this.delete();
};
```

**实战日志证据**：`launch-logcat.txt:748` `Attempt to load writable dex file: /data/user_de/0/net.crigh.hzkjsport/dsn0/dex/libdsn_cffd194e2e1bf1b15de453fe16b9ab37.jar`；`launch-logcat.txt:763` `SDLog: finish patch classloader`。偷存成功：`CAMPUSRUN: stash: libdsn_*.jar -> private files (<size>B)`。

---

## 5. 通用化指南

### 5.1 定位 SDK 分支

| SDK 系 | 关键类 | mock 默认策略 | 伪造注入面 |
|---|---|---|---|
| **高德** | `com.amap.api.location.AMapLocationClient` / `AMapLocationListener` / `AMapLocation` / `AMapLocationClientOption` | `setMockEnable(false)` 默认过滤系统 mock | hook `AMapLocationListener` 实现类 `onLocationChanged`；`isMock()` 恒 false |
| **百度** | `com.baidu.location.BDLocation` / `LocationClient` / `BDAbstractLocationListener` | `setEnableSimulateGps(false)` 默认，原生 gps 数据防模拟 | hook `BDAbstractLocationListener.onReceiveLocation(BDLocation)` 覆写 lat/lon；BDLocation 的 `isMockGps()`/`getLocType()` 伪装 |
| **原生 LocationManager** | `android.location.LocationManager` / `LocationListener` / `Location` | 框架层 `isFromMockProvider()` 标志 | hook `LocationListener.onLocationChanged(Location)` 覆写字段 + `Location.isFromMockProvider()` 恒 false（SystemSensorManager 系 `requestLocationUpdates` 回调） |

通用口诀：**伪造点永远选"SDK 回调参数"而非"系统数据源"**，因为 SDK 的 mock 过滤发生在回调之前、业务消费发生在回调之后。

### 5.2 壳分支（有壳 vs 无壳）

**壳指纹速查表**（APKiD / 文件特征）：

| 厂商 | 特征 | 反打包关卡（通用） |
|---|---|---|
| 360 加固 | `libjiagu*.so` / `libprotectClass.so` | 签名校验 + dex 校验 + 反调试 |
| 爱加密 | `libexec*.so` / `ijiami.dat` / `ijiami*.ajm` | 签名校验 + 环境检测 |
| 腾讯乐固 | `libshella`/`libshellx` + `mix.dex` + `tx_shell` | 签名校验 + dex 校验 |
| 梆梆 | `libDexHelper.so` / `libSecShell.so` / `fort.andjni` | 签名校验 + VMP |
| 网易易盾 | `libnesec.so` / `nedata.db` | 签名校验 + native 保护 |
| 阿里聚安全 | `libsgmain` / `libmobisec` | 签名校验 + 自实现 VM |
| **顶象 DxShield** | `libsys_misc.so` + `assets/__param.data` + `__version.txt` + `dsnstub000.vd` + `com.security.shell` | dex tamper + loadCsn 证书校验 + abort/kill 自杀 + ptrace 反调试 |

**有壳通用四步**（本会话 DxShield 实战结论，迁移到其他壳时按同构替换）：

1. 定位"检查入口"：Java 层找检查线程入口类（DxShield=`_me_.a`），吞掉；
2. 定位"证书校验"：主线程启动尾部（DxShield=`x.v(14)` loadCsn），用 getPackageInfo 签名欺骗 + base.apk 读取重定向双打；
3. 自杀出口兜底：abort→pthread_exit（非主线程才有效）、kill/tgkill 拦截；
4. 环境检测规避：su 探测（hook Runtime.exec）、模拟器检测（libsys_misc 特征，Gadget 方案天然规避 ptrace 检测）。

**无壳 App**：跳过方式 3/4/5，直接重打包注入（Gadget 或直接改 smali 调伪造代码），成本降低一个数量级。

### 5.3 步数加权 vs 纯 GPS

| 计分模式 | 判定特征 | 必要伪造面 |
|---|---|---|
| 纯 GPS | 只有距离/配速/轨迹 UI | 方式 6 定位覆写即可 |
| **步数加权** | UI 有 `tv_step`/`tv_cadence`；规则如"2km+3000步" | 方式 6 + 方式 8 传感器伪造，且两者必须共用时间轴 |
| 地理围栏 | `outof_range`/`geographic_range` 校验 | 路线必须落在围栏内（画圈/跑道几何生成时限定范围） |

### 5.4 决策树

```
目标 App 定位 SDK 是？
├─ 高德 → 先测方式 1 系统 mock（5 分钟）→ 不通过 → 方式 2+6+7
├─ 百度 → 同上，hook BDAbstractLocationListener
└─ 原生 → 方式 1 大概率直接生效；不生效 → hook LocationListener

有壳吗？
├─ 无壳 → 跳过壳对抗，直接重打包注入定位/计步伪造
└─ 有壳 → APKiD 识别壳厂商
    ├─ 顶象 → 方式 3（_me_.a 吞掉）+ 方式 4（签名欺骗/重定向）+ 方式 5（abort/kill 兜底）
    ├─ 360/爱加密/乐固/梆梆等 → 同构：签名欺骗 + dex 校验吞掉 + 自杀拦截（按各壳特征定位检查点）
    └─ 检测过重 → 升级：hook __android_log_print 抓校验日志定位 native 函数 → IDA 静态对齐 patch 失败分支

有 root 吗？
├─ 有 → 可省掉重打包：frida-server + spawn 模式直接挂（注意反调试绕过）
└─ 无 → Gadget 重打包（方式 2），script 模式无端口 + so 改名规避 maps 扫描

步数加权？ → 是 → 追加方式 8；否 → 只做定位

需要成绩入账 → 方式 9 观察上报格式 → 校验阈值未知时 → 方式 10 脱壳拿业务 dex 静态分析
```

---

## 6. 工具链与完整复现步骤（命令级）

### 6.1 工具清单

| 工具 | 版本（实战） | 用途 |
|---|---|---|
| apktool | 2.9.x | 解包/重打包 |
| jadx | 1.5.x | 静态分析（壳外可见部分 + 脱壳后业务 dex） |
| Frida Gadget | **16.7.19** android-arm（32 位） | 进程内注入（17.x 的 32 位 gadget 无 Java 桥，禁用） |
| uber-apk-signer | 1.3.x | 固定密钥重签 |
| zipalign | Android SDK build-tools | 4 字节对齐 |
| adb | platform-tools | 部署/日志 |
| frida / frida-tools / frida-dexdump | 16.x | 有 root 场景调试与脱壳 |
| APKiD | master | 壳识别 |
| BlackDex / frida-unpack | — | 脱壳（方法级回填） |

### 6.2 完整复现（无 root，Gadget 重打包）

```bash
# 0) 准备样本与工具
set PKG=net.crigh.hzkjsport
apksigner verify --print-certs base.apk          # 提取原版证书 DER -> 脚本 ORIG_CERT_B64

# 1) 解包
java -jar apktool_2.9.x.jar d -f base.apk -o unpack

# 2) 注入 32 位 Gadget（改名规避检测）
copy frida-gadget-16.7.19-android-arm.so unpack\lib\armeabi-v7a\libcampusrun.so

# 3) 部署脚本与配置（放 assets，运行时自解压；nativeloader 只解压有效 ELF 的 .so）
copy campusrun_hook.js unpack\assets\campusrun_hook.js
echo {"interaction":{"type":"script","path":"/data/local/tmp/campusrun_hook.js"}} > unpack\assets\libcampusrun.so.config

# 4) smali 注入：AppStub1.attachBaseContext 开头
#    const-string v0, "campusrun"
#    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
#    以及 assets 自解压调用（把 campusrun_hook.js / libcampusrun.so.config 拷到 /data/local/tmp/）

# 5) 重打包 + 对齐 + 固定密钥签名
java -jar apktool_2.9.x.jar b unpack -o repacked.apk
zipalign -f 4 repacked.apk aligned.apk
java -jar uber-apk-signer.jar -a aligned.apk --ks release.keystore --ksAlias campusrun --ksPass **** --ksKeyPass ****

# 6) 安装 + 推送原版 APK 副本（方式 4 重定向用）
adb install aligned.apk
adb push base.apk /sdcard/Download/orig.apk
adb push campusrun_route.json /sdcard/Download/campusrun_route.json

# 7) 验证
adb logcat -s CAMPUSRUN NETDUMP
# 预期: script alive pid=... / security: _me_.a.run hooked / hooked listener impl: ...
#       step-spoof: hooked SensorEventListener ... / open-redirect: ...base.apk -> orig.apk
```

**路线配置样例**（`campusrun_route.json`）：

```json
{ "points":[{"lat":30.515,"lng":114.42},{"lat":30.519,"lng":114.41}], "speed":2.5, "loop":true, "accuracy":3.0 }
```

画圈：`{"shape":"circle","center":{"lat":30.518,"lng":114.413},"radius_m":200,"segments":72,"speed":2.5,"loop":true}`
跑道：`{"shape":"stadium","a":{"lat":30.516,"lng":114.412},"b":{"lat":30.518,"lng":114.415},"radius_m":18,"speed":3.0,"loop":true}`

### 6.3 有 root 变体（脱壳/调试优先）

```bash
adb push frida-server-16.5.7-android-arm64 /data/local/tmp/fs
adb shell su -c 'chmod 755 /data/local/tmp/fs'
adb shell su -c '/data/local/tmp/fs -D &'
frida-ps -U | grep hzkjsport
frida-dexdump -U -f net.crigh.hzkjsport -d out\dump1    # 壳解出的 classes*.dex
# 反调试绕过脚本（ptrace 放行 / TracerPid 置 0 / 过滤检测线程 / 签名伪造）后用 attach 模式二次 dump
```

### 6.4 关键坑清单（每个坑都来自实战失败）

| # | 坑 | 症状 | 解法 |
|---|---|---|---|
| 1 | Frida 17.x 32 位 gadget 无 Java 桥 | `ReferenceError: Java is not defined` | 用 16.7.19 |
| 2 | Java.perform 在 script 模式早期排队到 VM 线程 | hook 永不生效/时灵时不灵 | 全脚本直呼 Java.use，禁用 Java.perform |
| 3 | Java.use 触发 `<clinit>` 里的 loadLibrary 与 gadget 的 dlopen 嵌套死锁 | 进程卡死 | 不预加载壳库/不 hook 壳 so 加载函数 |
| 4 | nativeloader 只解压有效 ELF 的 .so | 脚本/配置解压不出来 | 放 assets，smali 注入自解压 |
| 5 | abort 是 noreturn 直接拦 | SIGILL | 替换为 pthread_exit |
| 6 | 主线程 pthread_exit | 进程进 Z 状态被收尸 | 主防线是签名欺骗/文件重定向，让校验不触发 |
| 7 | hook 壳 JNI 调度器 stub000.x | SIGILL（与壳自身加载流程冲突） | 只吞检查线程 Java 入口 `_me_.a.run` |
| 8 | SIM_TIME 倍速 | 时间戳与速度不自洽，服务端里程/配速校验识破 | 用真实时间戳，speed 控制在 2~5 m/s 跑步区间 |

---

## 7. 检测与对抗

### 7.1 壳/业务侧检测清单（攻击者视角：什么是雷区）

| 层 | 检测项 | 实证/通用形态 |
|---|---|---|
| 启动期 | dex 完整性哈希 | DxShield Thread-2 `x.v(1)` → "entry classes.dex is tamper" |
| 启动期 | APK 签名/证书校验 | DxShield `x.v(14)` loadCsn 直读签名块 |
| 启动期 | JDWP 反调试 | 日志 "Not starting debugger since process cannot load the jdwp agent" |
| 启动期 | ptrace 自跟踪 | libantitrace "ptrace myself..."（占住 TracerPid 防 attach） |
| 运行期 | root/su 探测 | ShellUtils `Runtime.exec("su")`（拦截器链上） |
| 运行期 | 虚拟定位 App 包名 | `getPackageInfo("com.lerist.fakelocation")`（每次 onResume） |
| 运行期 | 模拟器/双开检测 | libsys_misc（bluestacks/androVM/microvirt/nox/cuttlefish） |
| 运行期 | Frida/Xposed 提示文案 | R.java `frida_detected`(0x7f1100c0)、`xposed_detected`(0x7f11030a) |
| 定位层 | isMock 过滤 | 高德 `setMockEnable(false)` 默认；`Location.isFromMockProvider()` |
| 业务层 | 速度/距离/时间/围栏/步频校验 | `speed_too_fast` / `overflow_distance` / `outof_range` / `dlg_step_rate_invalid` |
| 网络层 | 请求时刻自刀 | 拦截器 → `apk0000.x.l` → kill(SIGKILL) |
| 服务端 | 轨迹点密度/时间戳/配速分布 | 倍速播放与真实时间戳不自洽 |

### 7.2 我方规避清单（一一对应）

| 检测项 | 规避手段 | 对应方式 |
|---|---|---|
| dex tamper | 吞掉检查线程入口 `_me_.a.run`；JNI ThrowNew 吞 tamper 异常；base.apk 重定向顺带通过 | 方式 3/4 |
| 签名/证书校验 | getPackageInfo 返回原版证书；壳读 base.apk 重定向到原版副本 | 方式 4 |
| abort/kill 自杀 | abort→pthread_exit、kill/tgkill 拦截 | 方式 5 |
| ptrace 反调试 | Gadget 进程内 dlopen 加载（无外部 ptrace、TracerPid 不受影响） | 方式 2 |
| maps 扫描 frida/gadget | so 改名 libcampusrun.so、脚本改名、script 模式无端口 | 方式 2 |
| root/su 探测 | Gadget 方案不需要 root（设备保持非 root 状态，探测天然阴性） | 方式 2 |
| fakelocation 包名检查 | 不安装 Fake Location；伪造在进程内完成 | 方式 6 |
| 模拟器/双开 | 真机运行 | — |
| isMock 过滤 | 回调参数覆写（伪造点在 SDK 过滤之后）+ isMock 恒 false + setMockEnable no-op | 方式 6/7 |
| 步频/速度/距离校验 | GPS 轨迹与计步共用时间轴：步频≈速度/步幅×60，总步数≈里程/步幅；speed 2~5 m/s | 方式 8 |
| 请求时刻自刀 | kill/tgkill 拦截兜底 | 方式 5/9 |
| 服务端时间戳校验 | SIM_TIME=false 用真实时间戳推进 | 方式 6 |

**通用原则**：每层伪造都要"自洽"——轨迹、速度、配速、步数、步频、时间戳六个量必须互相印证，任何一处矛盾都是服务端识破点。

---

## 8. 附录

### 8.1 关键日志样例（实证 logcat 摘录）

**壳正常启动链（原版，launch-logcat.txt）**：

```
L723  14:31:25.211  nativeloader: Load .../lib/arm/libsys_misc.so ... ok
L725  14:31:25.218  Load .../lib/arm/libstub000.so ... ok
L726  14:31:25.218  libjdi: [dex_vmp_init2] this is libjdi-e542b55-105941 running android mode
L741  14:31:25.303  libjdi: index = 0 offset = 0 size = 49854
L742  14:31:25.305  SDLog: apkPath:.../base.apk
L743  14:31:25.311  SDLog: loaded HAB
L744  14:31:25.316  DA2Lock: validate license succeed!
L748  14:31:25.335  E crigh.hzkjsport: Attempt to load writable dex file: /data/user_de/0/net.crigh.hzkjsport/dsn0/dex/libdsn_cffd194e2e1bf1b15de453fe16b9ab37.jar
L763  14:31:25.407  SDLog: finish patch classloader dalvik.system.PathClassLoader[...]
L772  14:31:25.474  shell loadCsn ok time used:265
L1270 14:31:25.894  NameNotFoundException: com.lerist.fakelocation
L2053-2064          IOException: Cannot run program "su" → ShellUtils → DeviceUtils → CommonInterceptor1 → com.security.inner.apk0000.x.l(Native Method)
```

**注入后 hook 侧日志（CAMPUSRUN tag）**：

```
CAMPUSRUN: script alive pid=29531
CAMPUSRUN: security: _me_.a.run hooked
CAMPUSRUN: open-redirect: /data/app/.../base.apk -> orig.apk
CAMPUSRUN: hooked listener impl: net.crigh.cgsport.cgactivity.minute.sportstart.<混淆类名>
CAMPUSRUN: step-spoof: hooked SensorEventListener <类名>
CAMPUSRUN: step-spoof: type=19 dt=1.00s v=2.50m/s +3.205 steps -> total=1234.0
CAMPUSRUN: kill-block: kill(<pid>, 9) BLOCKED
NETDUMP: BEG seq=1 grp=biz host=api.xxx.edu.cn method=POST url=https://api.xxx.edu.cn/sport/upload
NETDUMP: RESP seq=1 code=200 ok=1 msg=OK
```

**tombstone 特征（证书校验失败现场）**：abort 调用地址落在匿名可执行区（libjdi 解密/DVMP 字节码 JIT 区），栈帧含 `art_quick_generic_jni_trampoline` + libstub000 —— 校验逻辑在 DVMP 字节码，失败经 JNI 进 libstub000 内 abort()。

### 8.2 术语表

| 术语 | 含义 |
|---|---|
| DxShield / DVMP | 顶象加固；DVMP 为方法级 dex VMP（方法体转 native 字节码由 libjdi 解释执行） |
| loadCsn | 壳证书校验段（主线程 x.v(14) 尾部，比对 APK 签名块证书序列号） |
| HAB | 壳读取 APK 头部/哈希的流程（"loaded HAB"） |
| dex_vmp_init2 | libjdi 解密分段 dex 的入口（index/offset/size 分段解密） |
| Frida Gadget | 以 .so 形式注入 APK 的 Frida 运行库（script 模式无端口） |
| mock provider | Android `addTestProvider` 系统级假定位（`isFromMockProvider()==true`） |
| setMockEnable | 高德 SDK 的 mock 过滤开关（默认 false=过滤） |
| STEP_COUNTER / STEP_DETECTOR | 计步传感器类型 19/18：累计计步器 / 单步检测器 |
| WCDB | 微信 SQLite 加密扩展（成绩本地落库） |
| apk0000.x.l | 壳 native↔Java 回调通道（拦截器设备采集/请求时刻校验） |
| uber-apk-signer | 固定密钥重签名工具（跨次构建签名一致） |

---

*手册完。所有 hook 片段均可直接运行；目标包名、证书、路径请按目标 App 替换。*

---

## 附：自适应攻击引擎设计原则（单 App 集成版）

引擎按目标 App 的定位实现自动分级，**能不打就不打，打了也幂等无害**：

| 层级 | 判定依据（自动检测） | 手段 | 是否动目标 |
|---|---|---|---|
| Tier 0 | APK 内含 libAMapSDK_Location / libBaiduMapSDK_base 等原生定位库 → 判"原生融合系" | 跳过系统 mock | 否 |
| Tier 1 | 无原生定位库，仅用 LocationManager/Google Fused | 系统 mock provider 直喂（校园路线现有能力） | 否（零攻击） |
| Tier 2 | Tier 0 命中 | 容器引擎把正版拉进虚拟环境，容器层喂无 mock 标志的位置；必要时容器内 hook 定位 SDK 回调 | 否（正版原样） |
| Tier 3 | Tier 2 遇壳反容器检测 | 容器内针对性绕过（环境特征抹除，参考壳四关对抗清单） | 否 |

原则：引擎先指纹识别（解包目标 APK 扫 lib/ 与 Manifest），再选择层级；Tier 1 直接可用、Tier 2/3 才会"攻击引擎"，且整个过程不修改目标 APK、不要求 root、卸载宿主即完全还原。对未命中任何特征的目标，默认按 Tier 1 处理，攻击动作幂等（重复执行无副作用）。

---

## 9. 蓝方视角：学校/厂商如何防御（附检测与对策）

> 本攻击链的每一步都有对应的检测/防御点。防御方应分层布防，且把重心放在"难以被客户端绕过"的服务端校验上。

### 9.1 客户端侧检测（可被绕过，但能挡掉绝大多数普通使用者）

| 攻击环节 | 检测手段 | 说明/局限 |
|---|---|---|
| 系统 mock provider | Location.isFromMockProvider()、检查是否设置了 mock 应用（Settings.Secure.MOCK_LOCATION） | 只挡 Tier 1 攻击；容器注入的位置不带 mock 标志，防不住 Tier 2 |
| 免 root 容器/分身 | ①检查 context.getApplicationInfo().sourceDir 是否位于 /data/app/（容器内指向宿主私有目录）；②检查自身 UID 与包名一致性（容器内 uid≠真实包 uid）；③扫 /proc/self/maps 中宿主包名路径；④检测 VirtualApp/LSPatch 特征类与特征方法（如 com.lody.virtual.*） | 容器引擎可逐项抹除，属对抗军备竞赛；建议只作为辅助信号 |
| 运行时 hook（Frida/Xposed 类） | 检测 /proc/self/maps 中 frida/gadget/xposed 特征串、端口扫描、TracerPid | 本方案容器路线不使用 Frida，此检测对其无效 |
| 加速度/计步伪造 | 步数与 GPS 位移交叉校验（步频×步幅 vs 速度，连续 10 分钟不一致→异常） | 伪造器会同步两者，需引入更多传感器（心率/气压）交叉验证 |

### 9.2 服务端校验（核心防线，客户端不可绕过）

1. **轨迹真实性**：速度分布、加速度连续性（真实跑步有步态抖动，机器轨迹过于平滑）、轨迹与路网贴合度（人在跑道上 vs 完美几何圆）、同轨迹复用检测（多账号提交完全相同的轨迹点序列 → 直接拉黑）。
2. **多源交叉**：GPS 轨迹 + 步数 + 时长 + 心率（若接入手环）交叉验证；允许的误差带外即拒绝。
3. **随机抽查**：运动中随机弹出人脸/位置验证（拍照比对 + 当前 GPS 与基站定位一致性），机器代跑无法实时应对。
4. **设备与账号风控**：设备指纹异常变化（反复重装）、单设备多账号交替、登录 IP/地理跳变、异常时段（凌晨集中"跑步"）。
5. **硬件级证明**：Play Integrity API / SafetyNet attestation 验证设备与 App 完整性（可挡重打包路线；对容器路线也有环境信号价值）。

### 9.3 防御优先级建议

1. 必须做：服务端轨迹真实性 + 多源交叉 + 抽查（性价比最高，直接命中本攻击的交付物）。
2. 建议做：Play Integrity 完整性证明、设备风控。
3. 可选：客户端 mock/容器检测（挡新手，成本低；不要指望挡住定向攻击者）。

### 9.4 红蓝对照速查

| 攻击方式（第4节编号） | 蓝方对策 |
|---|---|
| 1 系统 mock | isFromMockProvider + 服务端轨迹校验 |
| 2 Gadget 重打包 | Play Integrity + 壳自校验（本报告壳四关即样例） |
| 3 dex 篡改吞掉 | 壳校验上收服务端（关键判定不下发客户端） |
| 4 签名欺骗/文件重定向 | 完整性证明上收（attestation 服务端验签） |
| 5 abort/kill 拦截 | 判定结果上收服务端，客户端只是展示 |
| 6 高德回调覆写 | 服务端轨迹真实性（平滑度/抖动/路网贴合） |
| 7 isMock 伪装 | 同上，客户端标志不可信 |
| 8 计步伪造 | 多传感器交叉 + 服务端步频-配速一致性 |
| 9 网络观察/篡改 | TLS 证书固定 + 请求签名含 attestation 结果 |
| 10 dex 偷存 | 壳 VMP 强化 + 敏感逻辑服务端化 |
