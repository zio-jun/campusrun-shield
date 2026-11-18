/*
 * campusrun-shield / defense / client-side / AntiMockDetector.kt
 * ================================================================
 * 模拟定位（Mock Location）检测 —— 客户端防护参考实现
 *
 * 沉淀来源（本项目逆向分析产出，见 defense/README.md 索引）：
 *   * agent-team/runtime/REPORT.md（task-2）实证检测点：
 *       - 目标 APP 在 net.crigh.library.base.BaseActivity.onResume 中对
 *         每个页面检查模拟定位应用包 `com.lerist.fakelocation` 是否安装
 *         （logcat: NameNotFoundException: com.lerist.fakelocation）。
 *       - 高德定位 SDK（libAMapSDK_Location_v6_6_0.so）自带 mock_location
 *         与 Frida 检测字符串（string-scan: csn 数据 0x155c12d）。
 *       - 设备 getprop：ro.allow.mock.location=0（真机基线）。
 *   * agent-team/sport/REPORT.md（task-3）判定规则：
 *       - R7 电子围栏（离开锻炼区域）、R11 环境反作弊（root/frida/Xposed/
 *         模拟器/多开/调试/假定位检测，触发即拦截）。
 *
 * 对应攻击方式：
 *   * 攻击方式 A（本机虚拟环境）：无 root 真机 + 虚拟定位应用（Fake
 *     Location/Fake GPS）或开发者选项"选择模拟位置信息应用"。本类检测
 *     直接针对 A 的定位伪造层。
 *   * 攻击方式 B（虚拟机）：模拟器内 GPS 由宿主注入，isFromMockProvider
 *     常为 false（更隐蔽），需配合 AntiEmulatorDetector 与多源交叉校验
 *     （GPS vs 高德融合定位 vs 传感器推算）才能覆盖。
 *
 * 用法：在运动服务启动时调用 AntiMockDetector.evaluate(context, amapLocation)，
 *       返回 RiskReport；运动进行中周期性抽查（每 60s 一次），命中即
 *       标记成绩 suspect 并上报服务端（配合 server-side 交叉校验）。
 *
 * 说明：本文件为参考实现骨架，需在集成方工程中接入高德定位 SDK
 *       （com.amap.api.location.*）与具体埋点上报通道。
 */

package cn.edu.hust.campusrun.shield.defense

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

/**
 * 单次风险评估结果。
 * @param level  风险等级：0=干净 / 1=可疑 / 2=高风险（建议作废成绩段）
 * @param reasons 命中原因清单（埋点上报用）
 * @param mockProviderActive 系统 mock provider 是否激活
 * @param amapIsMock 高德 SDK mock 标记
 * @param crossDeviationM 多源定位偏差（米，-1 表示未评估）
 */
data class RiskReport(
    val level: Int,
    val reasons: List<String>,
    val mockProviderActive: Boolean,
    val amapIsMock: Boolean,
    val crossDeviationM: Double
)

object AntiMockDetector {

    /** 已知模拟定位应用包名黑名单（task-2 实证：目标 APP 检测 com.lerist.fakelocation）。
     *  集成方应按渠道持续维护；这里覆盖常见 Fake GPS / 定位修改器。 */
    private val KNOWN_MOCK_PACKAGES = setOf(
        "com.lerist.fakelocation",        // Fake Location（目标 APP 实证检测此包）
        "com.lexa.fakegps",               // Fake GPS Location
        "com.blogspot.newapphorizons.fakegpsroute",
        "com.gsmartstudio.fakegps",
        "com.incorporateapps.fakegps.fre",
        "com.fakegps.mock",
        "io.appium.settings",             // Appium 定位注入（自动化测试/攻击工具链）
        "com.theappninjas.fakegpsjoystick",
        "org.microg.nlp",                 // 自建网络定位提供者（需 root 后更危险）
        "com.anydesk.adcontrol.ad1"       // 部分改机工具的定位插件宿主
    )

    /**
     * 主入口：融合系统 API / 高德 SDK / 包名黑名单 / 多源交叉四路信号。
     *
     * @param context       应用上下文
     * @param amapLocation  高德定位回调结果（可为 null，跳过 SDK 信号）
     * @param fusedLocation Android 原生融合定位结果（可为 null，跳过交叉校验）
     */
    fun evaluate(
        context: Context,
        amapLocation: Any? = null,
        fusedLocation: Location? = null
    ): RiskReport {
        val reasons = mutableListOf<String>()

        // ---- 信号 1：系统 API（攻击 A：开发者选项"模拟位置信息应用"）----
        val mockProviderActive = isMockProviderEnabled(context)
        if (mockProviderActive) {
            reasons.add("SYSTEM_MOCK_PROVIDER_ACTIVE")
        }

        // ---- 信号 2：Location 对象标记 ----
        // isFromMockProvider 在 Android 6.0+ 由系统标记；Android 12+ 需在
        // onLocationChanged 同一进程校验，跨进程读取可能失效（攻击 A 绕过点）。
        val locIsMock = fusedLocation?.isFromMockProvider == true
        if (locIsMock) {
            reasons.add("LOCATION_FLAG_MOCK")
        }

        // ---- 信号 3：高德 SDK 融合结果 mock 标记（task-2 实证 SDK 自带）----
        val amapIsMock = detectAmapMock(amapLocation)
        if (amapIsMock) {
            reasons.add("AMAP_SDK_MOCK")
        }

        // ---- 信号 4：mock 应用包名黑名单（task-2 实证检测点）----
        val pm = context.packageManager
        val installedMock = KNOWN_MOCK_PACKAGES.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            } catch (_: Throwable) {
                false
            }
        }
        if (installedMock.isNotEmpty()) {
            reasons.add("MOCK_APP_INSTALLED:${installedMock.joinToString(",")}")
        }

        // ---- 信号 5：多源交叉偏差（攻击 A/B 的模拟定位难以同时伪造全部源）----
        // GPS(原生) 与高德融合定位在同一时刻的坐标偏差 > 50m 即视为可疑。
        // 高德融合 = GPS + 基站 + WiFi + 传感器；纯 mock 只注入其中一个源。
        val crossDeviationM = crossCheck(amapLocation, fusedLocation)
        if (crossDeviationM > CROSS_DEVIATION_THRESHOLD_M) {
            reasons.add("CROSS_SOURCE_DEVIATION:${"%.0f".format(crossDeviationM)}m")
        }

        // ---- 信号 6：Settings.Secure 全局模拟开关（部分改机工具写入）----
        if (readMockSetting(context) == "1") {
            reasons.add("SETTINGS_MOCK_FLAG")
        }

        val level = when {
            reasons.size >= 3 || amapIsMock || mockProviderActive -> 2
            reasons.isNotEmpty() -> 1
            else -> 0
        }
        return RiskReport(level, reasons, mockProviderActive, amapIsMock, crossDeviationM)
    }

    /** 检查系统"选择模拟位置信息应用"是否启用（攻击 A 最直接的伪装方式）。 */
    private fun isMockProviderEnabled(context: Context): Boolean {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return false
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                (lm.getProvider(LocationManager.GPS_PROVIDER) == null).not() &&
                hasMockLocationApps(context)
        } catch (_: Exception) {
            false
        }
    }

    /** 列出当前可充当 mock location provider 的应用（Android 6.0+ API）。 */
    private fun hasMockLocationApps(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return false
            @Suppress("DEPRECATION")
            val apps = lm.getMockLocationApps()
            apps.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /** 读取 Settings.Secure 的 mock location 标记（部分改机工具写入）。 */
    private fun readMockSetting(context: Context): String? = try {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ALLOW_MOCK_LOCATION  // "mock_location"
        )
    } catch (_: Exception) {
        null
    }

    /**
     * 高德 SDK mock 标记。高德 AMapLocation 提供 isMock()（私有实现），
     * 公开 API 无直接方法；实际接入可通过：
     *   1) 反射调用 location.isMock()（SDK 6.x 存在该方法）；
     *   2) 高德融合结果 getLocationType()==LOCATION_TYPE_MOCK 系列常量；
     *   3) 信任 SDK 自带反 mock 能力（task-2 实证 SO 内 mock_location 字符串）。
     */
    private fun detectAmapMock(amapLocation: Any?): Boolean {
        if (amapLocation == null) return false
        return try {
            val m = amapLocation.javaClass.getMethod("isMock")
            m.isAccessible = true
            m.invoke(amapLocation) as? Boolean ?: false
        } catch (_: NoSuchMethodException) {
            // 无 isMock 方法时退化为类型常量比对
            try {
                val t = amapLocation.javaClass.getMethod("getLocationType").invoke(amapLocation)
                // 高德 mock 类型常量（LOCATION_TYPE_MOCK 等），按集成版本调整
                t.toString().contains("MOCK", ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 多源交叉校验：高德融合坐标 vs Android 原生 Location 坐标。
     * 两者应来自同一物理 GPS；模拟定位（攻击 A）通常只伪造原生 provider，
     * 高德融合（含基站/WiFi/传感器辅助）会暴露真实位置 → 偏差巨大。
     */
    private fun crossCheck(amapLocation: Any?, fusedLocation: Location?): Double {
        if (amapLocation == null || fusedLocation == null) return -1.0
        return try {
            val getLat = amapLocation.javaClass.getMethod("getLatitude")
            val getLng = amapLocation.javaClass.getMethod("getLongitude")
            val aLat = getLat.invoke(amapLocation) as Double
            val aLng = getLng.invoke(amapLocation) as Double
            val r = floatArrayOf(0f)
            Location.distanceBetween(aLat, aLng, fusedLocation.latitude, fusedLocation.longitude, r)
            r[0].toDouble()
        } catch (_: Exception) {
            -1.0
        }
    }

    /** 运动服务启动前调用：拦截明显 mock 环境（对应 task-2 检测链前端拦截）。 */
    fun assertClean(context: Context): Boolean {
        val report = evaluate(context)
        return report.level == 0
    }

    private const val CROSS_DEVIATION_THRESHOLD_M = 50.0

    /** 供周期巡检的时间锚（可选：结合 elapseRealtime 防时钟回拨）。 */
    fun nowMs(): Long = SystemClock.elapsedRealtime()
}
