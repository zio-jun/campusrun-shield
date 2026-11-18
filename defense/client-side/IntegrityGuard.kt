/*
 * campusrun-shield / defense / client-side / IntegrityGuard.kt
 * ================================================================
 * 完整性守卫：签名/DEX 自校验 + 注入检测 + root 检测
 *
 * 沉淀来源（本项目逆向分析产出，见 defense/README.md 索引）：
 *   * agent-team/runtime/REPORT.md（task-2）实证检测点：
 *       - root 检测：AppUtils.isAppRoot() 执行 "su"（logcat: Cannot run
 *         program "su"），RxJava 多轮重试。
 *       - Xposed 检测：MyApplication.onCreate 中 loadClass
 *         "de.robv.android.xposed.XposedBridge"（ClassNotFoundException 被打印）。
 *       - 调试检测：being_traced 字符串；壳反调试 ptrace 被 SELinux 拒绝
 *         （avc: denied { ptrace } zygote→untrusted_app）。
 *       - 壳内 frida 字符串（csn 资产 0x154877b 等 10+ 处）与高德 SO 内
 *         Frida 字符串 —— frida 检测以字符串特征 + 端口 27042 + maps 为主。
 *       - 多开检测：app_open_more 字符串。
 *   * agent-team/sport/REPORT.md（task-3）：
 *       - R11 环境反作弊：root/frida/Xposed/模拟器/多开/调试检测，触发即拦截。
 *
 * 对应攻击方式：
 *   * 攻击方式 A（本机虚拟环境）：无 root 真机 Gadget 注入（frida-gadget
 *     重打包）→ 签名/DEX CRC 自校验可发现重打包；注入后 /proc/self/maps
 *     出现 gadget 特征段；frida-server 监听 27042。
 *   * 攻击方式 B（虚拟机）：VM 内常带 root（redroid/MuMu 自带 su），
 *     root 检测 + magisk 痕迹检测对 B 直接生效。
 *
 * 说明：所有检测只做"发现 + 上报"，不建议客户端直接退出（避免被反指纹）；
 *       拦截动作应交由服务端按报告数据裁决（配合 server-side 评分）。
 */

package cn.edu.hust.campusrun.shield.defense

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

data class IntegrityReport(
    val tampered: Boolean,      // 签名/DEX 被篡改
    val debugged: Boolean,      // 正在被调试
    val injected: Boolean,      // 检测到注入痕迹
    val rooted: Boolean,        // root 环境
    val details: List<String>
)

object IntegrityGuard {

    // ------------------------------------------------------------------
    // 1. APK 签名与 classes.dex CRC 自校验（攻击 A：Gadget 重打包检测）
    // ------------------------------------------------------------------

    /**
     * 校验当前安装 APK 的签名指纹与预置指纹一致。
     * 预置指纹应在发版时写入 BuildConfig 或 so；此处允许外部传入比对。
     */
    fun checkSignature(context: Context, expectedSha256: String): Boolean {
        return runCatching {
            val pi: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.signingInfo?.apkContentsSigners?.map { it.toByteArray() } ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                pi.signatures?.map { it.toByteArray() } ?: emptyList()
            }
            sigs.any { sig ->
                sha256Hex(sig).equals(expectedSha256, ignoreCase = true)
            }
        }.getOrDefault(false)
    }

    /** 校验 APK 内 classes.dex 的 CRC 是否与预置值一致（重打包第一道关卡）。 */
    fun checkDexCrc(context: Context, expectedCrc: Long): Boolean {
        return runCatching {
            val apkPath = context.packageManager
                .getApplicationInfo(context.packageName, 0).sourceDir
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry("classes.dex") ?: return false
                val crc = CRC32()
                zip.getInputStream(entry).use { ins ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        crc.update(buf, 0, n)
                    }
                }
                crc.value == expectedCrc
            }
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // 2. 注入检测（攻击 A：frida-gadget / 模块注入 / ptrace）
    // ------------------------------------------------------------------

    /** /proc/self/maps 特征扫描：frida、xposed、magisk、可疑匿名可执行段。 */
    fun checkMapsInjection(): List<String> {
        val hits = mutableListOf<String>()
        runCatching {
            BufferedReader(FileReader(File("/proc/self/maps"))).use { br ->
                br.lineSequence().forEach { line ->
                    when {
                        line.contains("frida", ignoreCase = true) ->
                            hits += "MAPS_FRIDA:$line"
                        line.contains("gadget", ignoreCase = true) &&
                            (line.contains("r-xp") || line.contains("lib")) ->
                            hits += "MAPS_GADGET:$line"
                        line.contains("xposed", ignoreCase = true) ->
                            hits += "MAPS_XPOSED:$line"
                        line.contains("magisk", ignoreCase = true) ->
                            hits += "MAPS_MAGISK:$line"
                        line.contains("substrate", ignoreCase = true) ->
                            hits += "MAPS_SUBSTRATE:$line"
                    }
                }
            }
        }
        return hits
    }

    /** TracerPid 检测（/proc/self/status）。 */
    fun isDebugged(): Boolean {
        return Debug.isDebuggerConnected() || runCatching {
            BufferedReader(FileReader(File("/proc/self/status"))).use { br ->
                br.lineSequence().any {
                    it.startsWith("TracerPid:") &&
                        it.substringAfter(":").trim() != "0"
                }
            }
        }.getOrDefault(false)
    }

    /** frida-server 默认端口 27042 探测（task-2 实证 frida 字符串检测点）。 */
    fun isFridaPortOpen(timeoutMs: Int = 400): Boolean {
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", 27042), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    /** Xposed 框架类探测（对标 task-2 实证 loadClass("de.robv.android.xposed.XposedBridge")）。 */
    fun isXposedActive(): Boolean {
        return runCatching {
            Class.forName("de.robv.android.xposed.XposedBridge")
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // 3. root 检测（攻击 B：VM 自带 root；对标 AppUtils.isAppRoot 的 su 探测）
    // ------------------------------------------------------------------

    fun isRooted(): Boolean {
        // su 二进制路径（task-2 实证检测方式为执行 "su"，此处升级为
        // 文件存在性 + PATH 探测双通道，避免每次 spawn 进程）
        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/sbin/su", "/vendor/bin/su", "/su/bin/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
            "/cache/magisk", "/data/adb/magisk",           // Magisk 痕迹
            "/data/adb/modules"                             // LSPosed/模块目录
        )
        if (suPaths.any { File(it).exists() }) return true
        // PATH 环境变量探测（不 spawn，仅检查 PATH 内是否有 su 目录）
        val path = System.getenv("PATH") ?: return false
        return path.split(":")
            .any { dir -> suPaths.any { p -> p.startsWith(dir.trimEnd('/')) } }
    }

    // ------------------------------------------------------------------
    // 汇总入口
    // ------------------------------------------------------------------

    fun evaluate(
        context: Context,
        expectedSignSha256: String? = null,
        expectedDexCrc: Long? = null
    ): IntegrityReport {
        val details = mutableListOf<String>()
        var tampered = false

        expectedSignSha256?.let {
            if (!checkSignature(context, it)) {
                tampered = true
                details += "SIGNATURE_MISMATCH"
            }
        }
        expectedDexCrc?.let {
            if (!checkDexCrc(context, it)) {
                tampered = true
                details += "DEX_CRC_MISMATCH"
            }
        }

        val mapHits = checkMapsInjection()
        if (mapHits.isNotEmpty()) details += mapHits

        val debugged = isDebugged()
        if (debugged) details += "DEBUGGER_ATTACHED"

        if (isFridaPortOpen()) details += "FRIDA_PORT_27042_OPEN"

        if (isXposedActive()) details += "XPOSED_FRAMEWORK"

        val rooted = isRooted()
        if (rooted) details += "ROOT_DETECTED"

        return IntegrityReport(
            tampered = tampered,
            debugged = debugged,
            injected = mapHits.isNotEmpty(),
            rooted = rooted,
            details = details.distinct()
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
