/*
 * campusrun-shield / defense / client-side / AntiEmulatorDetector.kt
 * ================================================================
 * 模拟器 / 虚拟机环境指纹检测 —— 客户端防护参考实现
 *
 * 沉淀来源（本项目逆向分析产出，见 defense/README.md 索引）：
 *   * agent-team/runtime/REPORT.md（task-2）实证检测点：
 *       - 目标 APP 的 strings.xml 含 simulator_detected
 *         （"不支持模拟器安装使用。请使用真实手机进行安装使用"）、
 *         app_open_more（"检测到应用被多开，请关闭后重试"）、
 *         being_traced（"检测到应用被调试，请关闭后重试"）。
 *       - 真机基线（device-getprop.txt）：ro.boot.hardware=qcom、
 *         ro.kernel.qemu.gles=0、verifiedbootstate=green、无 qemu/goldfish。
 *   * agent-team/sport/REPORT.md（task-3）：
 *       - R11 环境反作弊：root/frida/Xposed/模拟器/多开/调试/假定位检测。
 *
 * 对应攻击方式：
 *   * 攻击方式 B（虚拟机，offense/vm 测试床）：redroid/雷电/MuMu 等模拟器
 *     与 WSL2+Docker 容器安卓。本类检测对 B 直接生效——Build 指纹、传感器
 *     缺失、基带/蓝牙/相机异常是 VM 无法完全抹除的硬件特征。
 *   * 攻击方式 A（本机虚拟环境）：真机 + 虚拟空间/多开（应用双开容器）。
 *     多开检测（app_open_more 对标）在此实现。
 *
 * 说明：检测项按"信号强度"加权，返回 0-100 风险分；建议 ≥60 拦截运动
 *       开始、≥30 标记成绩 suspect 并附报告上报服务端。
 */

package cn.edu.hust.campusrun.shield.defense

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

data class EmulatorRisk(
    val score: Int,              // 0-100
    val hits: List<String>       // 命中项清单
)

object AntiEmulatorDetector {

    fun evaluate(context: Context): EmulatorRisk {
        val hits = mutableListOf<String>()
        var score = 0

        // ---------- 1. Build 字段指纹（VM 最常见且难以隐藏） ----------
        if (Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.FINGERPRINT.startsWith("google/sdk_gphone") ||
            Build.FINGERPRINT.contains("vbox", ignoreCase = true) ||
            Build.FINGERPRINT.contains("qemu", ignoreCase = true) ||
            Build.FINGERPRINT.contains("ranchu", ignoreCase = true)
        ) {
            hits += "FINGERPRINT_GENERIC:${Build.FINGERPRINT}"
            score += 35
        }
        if (Build.MODEL.contains("sdk", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.MODEL.contains("google_sdk", ignoreCase = true) ||
            Build.MODEL.contains("redroid", ignoreCase = true)
        ) {
            hits += "MODEL_SDK:${Build.MODEL}"
            score += 25
        }
        if (Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
            Build.MANUFACTURER.contains("unknown", ignoreCase = true) && Build.BRAND.contains("generic", ignoreCase = true)
        ) {
            hits += "MANUFACTURER_GENERIC:${Build.MANUFACTURER}"
            score += 15
        }
        if (Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.HARDWARE.contains("redroid", ignoreCase = true) ||
            Build.HARDWARE.contains("qemu", ignoreCase = true)
        ) {
            hits += "HARDWARE_QEMU:${Build.HARDWARE}"
            score += 30
        }
        // qemu 内核命令行 / 属性（真机基线：ro.boot.hardware=qcom）
        runCatching {
            val prop = Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
            val bootHardware = prop.invoke(null, "ro.boot.hardware", "") as String
            if (bootHardware.contains("qemu", ignoreCase = true) ||
                bootHardware.contains("goldfish", ignoreCase = true) ||
                bootHardware.contains("ranchu", ignoreCase = true)
            ) {
                hits += "PROP_BOOT_HARDWARE:$bootHardware"
                score += 25
            }
            val qemuGles = prop.invoke(null, "ro.kernel.qemu.gles", "0") as String
            if (qemuGles == "1") {
                hits += "PROP_QEMU_GLES"
                score += 20
            }
        }

        // ---------- 2. 传感器缺失/单一（VM 无真实加速度计阵列） ----------
        // task-3 证据：校园跑强依赖 stepcounter/stepdetector（Manifest
        // uses-feature required=true），VM 内这些传感器多为软件模拟或缺失。
        runCatching {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            if (sm != null) {
                val all = sm.getSensorList(Sensor.TYPE_ALL)
                if (all.size <= 8) {
                    hits += "SENSOR_COUNT_LOW:${all.size}"
                    score += 20
                }
                val hasStepCounter = all.any { it.type == Sensor.TYPE_STEP_COUNTER }
                val hasStepDetector = all.any { it.type == Sensor.TYPE_STEP_DETECTOR }
                if (!hasStepCounter || !hasStepDetector) {
                    hits += "SENSOR_STEP_MISSING"
                    score += 30
                }
                // VM 常见厂商字段为空/unknown
                all.forEach { s ->
                    if (s.vendor.isNullOrEmpty() || s.vendor.contains("unknown", true) ||
                        s.vendor.contains("goldfish", true) || s.vendor.contains("emu", true)
                    ) {
                        hits += "SENSOR_VENDOR_ANOMALY:${s.name}"
                        score += 5
                        return@runCatching
                    }
                }
            }
        }

        // ---------- 3. 基带 / IMEI / 蓝牙 / 相机特征 ----------
        runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val deviceId = tm?.deviceId
            if (deviceId.isNullOrEmpty() || deviceId == "000000000000000" ||
                deviceId.contains("000000", ignoreCase = true)
            ) {
                hits += "TELEPHONY_DEVICE_ID_EMPTY"
                score += 20
            }
        }
        runCatching {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                hits += "BT_ADAPTER_MISSING"
                score += 15
            }
        }
        val hasCamera = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        if (!hasCamera) {
            hits += "CAMERA_FEATURE_MISSING"
            score += 15
        }

        // ---------- 4. 存储容量 / 设备信息异常（VM 小磁盘/小内存） ----------
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.totalBytes
            if (totalBytes in 1..(4L shl 30)) {  // < 4GB 数据分区
                hits += "STORAGE_TINY:${totalBytes shr 20}MB"
                score += 10
            }
        }
        val totalMem = runCatching {
            val m = Class.forName("android.app.ActivityManager")
                .getMethod("getMemoryClass", Context::class.java).invoke(null, context) as Int
            m
        }.getOrDefault(0)
        if (totalMem in 1..96) {  // 96MB heap 以下几乎必为 VM
            hits += "MEMORY_CLASS_LOW:$totalMem"
            score += 15
        }

        // ---------- 5. 文件系统痕迹（VM/多开特征文件） ----------
        val traces = listOf(
            "/system/lib/libc_malloc_debug_qemu.so",
            "/sys/qemu_trace",
            "/system/bin/qemu-props",
            "/dev/socket/qemud",
            "/dev/qemu_pipe",
            "/dev/goldfish_pipe",
            "/proc/tty/drivers"            // 内容含 goldfish 时另判
        )
        traces.forEach { p ->
            if (File(p).exists()) {
                hits += "FS_TRACE:$p"
                score += 20
            }
        }

        // ---------- 6. 多开/虚拟空间痕迹（攻击 A 的容器化特征） ----------
        val dualApps = listOf(
            "com.lbe.parallel.intl",        // Parallel Space
            "com.excelliance.multiaccounts",// 双开助手
            "com.ludashi.dualspace",
            "com.bly.dkplat",
            "com.by.chaos",
            "io.va.exposed"                 // VirtualApp 变体
        )
        dualApps.forEach { pkg ->
            runCatching {
                context.packageManager.getPackageInfo(pkg, 0)
                hits += "DUAL_APP_INSTALLED:$pkg"
                score += 25
            }
        }

        return EmulatorRisk(score.coerceAtMost(100), hits.distinct())
    }

    /** 运动开始前硬拦截：分数过高直接拒绝（对标 simulator_detected 提示）。 */
    fun assertRealDevice(context: Context): Boolean = evaluate(context).score < 60
}
