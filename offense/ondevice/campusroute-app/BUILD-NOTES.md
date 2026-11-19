# CampusRoute 自定义地图功能改动说明

## 产物

- 修改后完整工程：`C:\校园跑\analysis\campusroute-src-mod\`（含 local.properties、gradle.properties）
- APK：`C:\校园跑\build\campusroute-new.apk`（348,001 字节，debug 签名，versionCode 2 / versionName 1.1）
- 本次改动文件：`app/src/main/java/dev/campus/route/{CampusMap,CampusMapView,MainActivity}.java`、`app/build.gradle`、`README.md`、新增 `local.properties`、`gradle.properties`

## 功能改动

### 1. 空白地图模式（无地图也能画线/画圈/播放）

- **确认结论**：PlaybackService / Geo 完全不依赖 campus-map.json。路线在 MainActivity 里算成 `Geo.Route`（点列表），经 Intent extra `"coordinates"` 传给服务；`alongRoads=false` 或找不到路网时直接点间直连。地图 JSON 只影响视觉渲染与"沿道路"规划（`CampusMap.walkingLeg`）。
- `CampusMapView`：新增 `setBlank(boolean)` 与 `blank` 字段。空白模式下背景改为浅色 `0xFFEEF2ED`，网格线 `0xFFD3DCD4`，比例尺文字改为深色；不再绘制地图图形、标签与 OSM 版权行（MainActivity 同步隐藏 attribution）。
- `MainActivity`：`applyMap()` 里 `map == null` 即空白模式；`recomputeRoute()` 提示文案区分"空白地图：未加载道路，按选点顺序直连。"。
- **投影原点动态化**（CampusMap）：原版 `LAT/LON/LON_METERS` 为写死华科常量。改为可变的 `originLat/originLon/lonMeters` + `setOrigin()`，`x()/y()/point()` 全部走动态原点。空白模式下首个选点（或已有路线时）自动设为原点，保证任意坐标系的本地平面投影不失真（`MainActivity.ensureBlankOrigin/setBlankOriginFromRoute` + `CampusMapView.projectionChanged()` 重建轨迹并适配视野）。

### 2. 地图导入

- `MainActivity`：地图卡片左下新增「导入地图」按钮 → `ACTION_OPEN_DOCUMENT`（`*/*`，`CATEGORY_OPENABLE`，request 20）→ 后台线程读 Uri 内容 → `CampusMap.parse()` 校验 → 写入 `getFilesDir()/custom-map.json` → 保存 `mapMode=auto`、`mapName` → 重新加载；格式错误 Toast「地图格式错误，无法导入。支持 GeoJSON 或 campus-map.json 格式。」；显示名取自 `OpenableColumns.DISPLAY_NAME`。
- `CampusMap.parse(String)`：兼容两种格式
  - campus-map.json 格式（含可选 `origin` 字段，`fromLegacy`）；
  - GeoJSON（FeatureCollection / Feature / 几何对象，`fromGeoJson`）：Polygon→填充面(kind 0)、LineString/MultiLineString→线(kind 5) 并自动建步行路网（顶点按 0.1m 去重、连边），Point/MultiPoint→标签；`properties.name`→地名标签；原点取几何包围盒中心；带顶点/图形数量上限防超大文件。无可用图形或路网则抛异常（触发 Toast）。

### 3. 加载顺序：用户导入 > 内置华科 > 空白

- `MainActivity.loadMap()`：pref `mapMode` 非 `blank` 时先读 `custom-map.json`，失败/缺失再读 assets 内置地图，仍失败或强制 blank 则空白模式。地图卡右上新增「空白地图/自动地图」切换按钮（写 `mapMode` pref 后重载）。副标题动态显示「自定义地图 · 文件名 / 华科主校区 · 离线地图 / 空白地图 · 自由选点」。

## 构建步骤（本机已执行成功）

1. SDK：`sdkmanager --sdk_root=C:\校园跑\tools\android-sdk "platforms;android-33" "build-tools;33.0.0"`（按任务要求），另补装 `platforms;android-35`、`build-tools;34.0.0`（compileSdk 35 与 AGP 8.7 默认需要）；先 `--licenses` 全部接受。注意：**必须清空 HTTP_PROXY/HTTPS_PROXY 环境变量**（本机指向的 127.0.0.1:7897 代理已死，Java 的 sdkmanager 会读它导致 manifest 下载 IO 异常）。
2. JDK：解压 `tools\dl\jdk21.zip` → `C:\校园跑\tools\jdk21\jdk-21.0.13.0.2+1`（含 javac 的完整 JDK）。
3. 构建：`JAVA_HOME=C:\校园跑\tools\jdk21\jdk-21.0.13.0.2+1`，`cd campusroute-src-mod`，`gradlew.bat assembleDebug --no-daemon --console=plain`（wrapper 自动下载 gradle-8.9 + AGP 8.7.3 依赖）。

## 失败原因与解决（3 次）

1. **non-ASCII 路径检查**：工程在 `C:\校园跑\...`，AGP 报 "non-ASCII characters"。→ 加 `gradle.properties: android.overridePathCheck=true`（AGP 8.x 官方开关）。
2. **sdk.dir 不解析**：local.properties 里 unicode 转义 `\u6821...` 写成了双反斜杠且 AGP 按 Latin-1 读 properties，中文路径不可靠。→ `mklink /J C:\android-sdk "C:\校园跑\tools\android-sdk"`，`local.properties` 写纯 ASCII `sdk.dir=C\:\\android-sdk`，构建时另设 `ANDROID_HOME=C:\android-sdk`。
3. **编译错误**：`CampusMap.java` LineString 分支 `new JSONArray[]{coordinates}` 与 `addRings(JSONArray)` 参数类型不符。→ 改 `new JSONArray().put(coordinates)`。修复后 `BUILD SUCCESSFUL in 15s`。

## 使用说明

- 安装 APK 后（与 v1 同包名 `dev.campus.route`，debug 签名与旧版不同需先卸载 v1）：地图卡「空白地图」切换无底图模式；「导入地图」选 GeoJSON（任意学校，经纬度 WGS84）或同格式 campus-map.json；GeoJSON 的线要素自动成为"沿道路"路网。模拟定位输出流程不变（开发者选项选模拟位置应用 → 准备系统定位 → 通知栏开始移动）。
- 注意：原 v1 保存的路线坐标（华科）在空白/自定义地图下仍按坐标显示，无影响。
