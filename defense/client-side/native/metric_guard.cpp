/*
 * campusrun-shield / defense / client-side / native / metric_guard.cpp
 * =====================================================================
 * 关键运动指标 native 计算 + HMAC 结果签名（JNI 参考实现）
 *
 * 沉淀来源（本项目逆向分析产出，见 defense/README.md 索引）：
 *   * agent-team/sport/REPORT.md（task-3）判定规则：
 *       - R1  speed_too_fast：瞬时速度超上限 -> 该段距离无效
 *           （对应 APP txt_tips_speed_err + speed_too_fast.wav）。
 *       - R5  步频异常 = 疑似自行车/交通工具（dlg_step_rate_invalid
 *           复用 suspect_use_bicycle 文案）；室内运动 UI 单独展示
 *           步频（步/分）tv_cadence。
 *       - R12 有效里程 = GPS 有效段累计（超速段剔除）。
 *   * agent-team/runtime/REPORT.md（task-2）：
 *       - 客户端指标计算原本全在 Java 层（被注入/Hook 的重灾区：
 *         Frida Hook 点清单 H2/H4 即里程累计与 onSensorChanged）。
 *
 * 防御目标（对应攻击方式）：
 *   * 攻击方式 A（本机虚拟环境）：无 root 真机 Gadget 注入，Java 层
 *     里程/步数方法可被 Frida 直接改写。把累计逻辑下沉到 native 并
 *     对结果做 HMAC，攻击者必须逆向 .so 并重放密钥流才能伪造。
 *   * 攻击方式 B（虚拟机）：VM 内 root + frida-server，Java 与 native
 *     均可 Hook，但 HMAC 密钥不再明文存放于 Java 侧，提高了伪造成本；
 *     最终裁决仍需服务端交叉校验（server-side 两个 Python）。
 *
 * 编译（NDK，armeabi-v7a/arm64-v8a 均可）：
 *   $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/<host>/bin/clang++ \
 *       -shared -fPIC -O2 -fvisibility=hidden \
 *       -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
 *       metric_guard.cpp -o libmetric_guard.so
 *
 * JNI 注册方式：静态注册（Java_ 前缀），包名
 *   cn.edu.hust.campusrun.shield.defense.native.MetricGuard
 */

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <vector>
#include <string>

// ---------------------------------------------------------------------------
// 阈值（与 server-side/trajectory_plausibility.py、run_evidence_validator.py
// 保持一致；修改需三处同步）
// ---------------------------------------------------------------------------
static constexpr double kMaxSpeedMps  = 6.0;    // R1 超速上限
static constexpr double kMinSpeedMps  = 0.5;    // R2 低速下限
static constexpr double kMaxAccelMps2 = 4.0;    // 加速度上限
static constexpr double kMaxStrideM   = 1.8;    // R5 步长上限（骑车判定物理依据）
static constexpr double kMinStrideM   = 0.3;    // R5 步长下限
static constexpr int    kCadenceRunMin = 130;   // 步频下限（步/分）
static constexpr int    kCadenceRunMax = 210;   // 步频上限（步/分）

// ---------------------------------------------------------------------------
// HMAC-SHA256（自包含实现，不依赖 OpenSSL，方便编译与移植）
// ---------------------------------------------------------------------------
namespace {

struct Sha256Ctx {
    uint32_t h[8];
    uint64_t len;
    uint8_t  buf[64];
    size_t   buf_len;
};

constexpr uint32_t kSha256K[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

inline uint32_t rotr(uint32_t x, uint32_t n) { return (x >> n) | (x << (32 - n)); }

void sha256_init(Sha256Ctx* c) {
    c->h[0]=0x6a09e667; c->h[1]=0xbb67ae85; c->h[2]=0x3c6ef372; c->h[3]=0xa54ff53a;
    c->h[4]=0x510e527f; c->h[5]=0x9b05688c; c->h[6]=0x1f83d9ab; c->h[7]=0x5be0cd19;
    c->len = 0; c->buf_len = 0;
}

void sha256_block(Sha256Ctx* c, const uint8_t* p) {
    uint32_t w[64];
    for (int i = 0; i < 16; i++) {
        w[i] = (uint32_t(p[i*4]) << 24) | (uint32_t(p[i*4+1]) << 16) |
               (uint32_t(p[i*4+2]) << 8) | uint32_t(p[i*4+3]);
    }
    for (int i = 16; i < 64; i++) {
        uint32_t s0 = rotr(w[i-15],7) ^ rotr(w[i-15],18) ^ (w[i-15] >> 3);
        uint32_t s1 = rotr(w[i-2],17) ^ rotr(w[i-2],19) ^ (w[i-2] >> 10);
        w[i] = w[i-16] + s0 + w[i-7] + s1;
    }
    uint32_t a=c->h[0],b=c->h[1],cc=c->h[2],d=c->h[3],
             e=c->h[4],f=c->h[5],g=c->h[6],h=c->h[7];
    for (int i = 0; i < 64; i++) {
        uint32_t S1 = rotr(e,6) ^ rotr(e,11) ^ rotr(e,25);
        uint32_t ch = (e & f) ^ (~e & g);
        uint32_t t1 = h + S1 + ch + kSha256K[i] + w[i];
        uint32_t S0 = rotr(a,2) ^ rotr(a,13) ^ rotr(a,22);
        uint32_t mj = (a & b) ^ (a & cc) ^ (b & cc);
        uint32_t t2 = S0 + mj;
        h=g; g=f; f=e; e=d+t1; d=cc; cc=b; b=a; a=t1+t2;
    }
    c->h[0]+=a; c->h[1]+=b; c->h[2]+=cc; c->h[3]+=d;
    c->h[4]+=e; c->h[5]+=f; c->h[6]+=g; c->h[7]+=h;
}

void sha256_update(Sha256Ctx* c, const uint8_t* data, size_t n) {
    c->len += n;
    while (n > 0) {
        size_t take = 64 - c->buf_len;
        if (take > n) take = n;
        memcpy(c->buf + c->buf_len, data, take);
        c->buf_len += take; data += take; n -= take;
        if (c->buf_len == 64) { sha256_block(c, c->buf); c->buf_len = 0; }
    }
}

void sha256_final(Sha256Ctx* c, uint8_t out[32]) {
    uint64_t bits = c->len * 8;
    uint8_t pad = 0x80;
    sha256_update(c, &pad, 1);
    uint8_t zero = 0;
    while (c->buf_len != 56) sha256_update(c, &zero, 1);
    uint8_t lenb[8];
    for (int i = 0; i < 8; i++) lenb[i] = uint8_t(bits >> (56 - i*8));
    sha256_update(c, lenb, 8);
    for (int i = 0; i < 8; i++) {
        out[i*4]   = uint8_t(c->h[i] >> 24);
        out[i*4+1] = uint8_t(c->h[i] >> 16);
        out[i*4+2] = uint8_t(c->h[i] >> 8);
        out[i*4+3] = uint8_t(c->h[i]);
    }
}

// HMAC-SHA256(key, message) -> hex string（小写）
std::string hmac_sha256_hex(const std::string& key, const std::string& msg) {
    uint8_t k[64]; memset(k, 0, sizeof(k));
    if (key.size() > 64) {
        Sha256Ctx c; sha256_init(&c);
        sha256_update(&c, reinterpret_cast<const uint8_t*>(key.data()), key.size());
        uint8_t h[32]; sha256_final(&c, h);
        memcpy(k, h, 32);
    } else {
        memcpy(k, key.data(), key.size());
    }
    uint8_t ipad[64], opad[64];
    for (int i = 0; i < 64; i++) { ipad[i] = k[i] ^ 0x36; opad[i] = k[i] ^ 0x5c; }

    Sha256Ctx inner; sha256_init(&inner);
    sha256_update(&inner, ipad, 64);
    sha256_update(&inner, reinterpret_cast<const uint8_t*>(msg.data()), msg.size());
    uint8_t ih[32]; sha256_final(&inner, ih);

    Sha256Ctx outer; sha256_init(&outer);
    sha256_update(&outer, opad, 64);
    sha256_update(&outer, ih, 32);
    uint8_t oh[32]; sha256_final(&outer, oh);

    static const char* hexd = "0123456789abcdef";
    std::string out; out.reserve(64);
    for (uint8_t b : oh) { out += hexd[b >> 4]; out += hexd[b & 0xf]; }
    return out;
}

} // namespace

// ---------------------------------------------------------------------------
// 内部累计状态（单实例；由运动服务生命周期管理）
// ---------------------------------------------------------------------------
struct MetricState {
    double total_dist_m   = 0.0;   // 累计总距离（米）
    double valid_dist_m   = 0.0;   // 有效距离（剔除超速段，R12）
    double last_lat       = 0.0;
    double last_lng       = 0.0;
    double last_ts_s      = 0.0;
    double last_speed     = 0.0;
    uint64_t steps        = 0;     // 步数（传感器计步，R5 交叉用）
    int      over_speed_segments = 0;  // 超速段计数（speed_too_fast 命中）
    bool     has_last      = false;
};

// 密钥由构建脚本生成并混淆存放（示例占位，正式接入务必替换 + 做常量混淆）
static const char* kHmacKey = "campusrun-shield-demo-key-2026-hust";

// 球面距离（米），Haversine 简化
static double haversine_m(double lat1, double lng1, double lat2, double lng2) {
    const double R = 6371000.0;
    double p1 = lat1 * 0.017453292519943295;
    double p2 = lat2 * 0.017453292519943295;
    double dp = (lat2 - lat1) * 0.017453292519943295;
    double dl = (lng2 - lng1) * 0.017453292519943295;
    double h = std::sin(dp/2)*std::sin(dp/2) +
               std::cos(p1)*std::cos(p2)*std::sin(dl/2)*std::sin(dl/2);
    return 2 * R * std::asin(std::sqrt(h < 0 ? 0 : (h > 1 ? 1 : h)));
}

// ---------------------------------------------------------------------------
// JNI 接口
//   包名 cn.edu.hust.campusrun.shield.defense.native.MetricGuard
//   静态注册：Java_cn_edu_hust_campusrun_shield_defense_native_MetricGuard_xxx
// ---------------------------------------------------------------------------

extern "C" {

/**
 * nativeReset：运动开始时复位累计器（对应 Java MetricGuard.reset()）。
 * 攻击 A 会 Hook 此方法伪造初始状态 —— 故同时返回 HMAC 锚点，
 * 服务端可用锚点验证状态机未被重置/篡改。
 */
JNIEXPORT jstring JNICALL
Java_cn_edu_hust_campusrun_shield_defense_native_MetricGuard_nativeReset(
    JNIEnv* env, jobject /*thiz*/, jlong session_id) {
    static MetricState s;
    s = MetricState();
    std::string msg = "reset:" + std::to_string(static_cast<uint64_t>(session_id));
    std::string mac = hmac_sha256_hex(kHmacKey, msg);
    return env->NewStringUTF(mac.c_str());
}

/**
 * nativeAccumulate：喂入一个 GPS 采样点（lat/lng/epoch_s）。
 * 在 native 完成：距离累计、速度判定（R1/R2）、超速段剔除（R12）。
 * 攻击 A 的 Frida Hook 点（Java 层 updateDistance）被此下沉抵消。
 */
JNIEXPORT void JNICALL
Java_cn_edu_hust_campusrun_shield_defense_native_MetricGuard_nativeAccumulate(
    JNIEnv* /*env*/, jobject /*thiz*/, jdouble lat, jdouble lng, jdouble ts_s) {
    static MetricState s;
    if (!s.has_last) {
        s.last_lat = lat; s.last_lng = lng; s.last_ts_s = ts_s;
        s.has_last = true;
        return;
    }
    double dt = ts_s - s.last_ts_s;
    if (dt <= 0) return;  // 时间戳非单调（服务端另有 TS_NONMONOTONIC 判定）
    double d = haversine_m(s.last_lat, s.last_lng, lat, lng);
    double v = d / dt;
    s.total_dist_m += d;

    // R1 speed_too_fast：超上限段不计入有效里程（对应 txt_tips_speed_err）
    if (v > kMaxSpeedMps) {
        s.over_speed_segments++;
        // 超速段距离不计入有效里程（R12）
    } else if (v >= kMinSpeedMps) {
        s.valid_dist_m += d;  // R2：低于下限的漂移段也不计入
    }
    s.last_lat = lat; s.last_lng = lng; s.last_ts_s = ts_s; s.last_speed = v;
}

/**
 * nativeAddSteps：传感器步数累加（R5 步频交叉校验数据源）。
 * 攻击 A 会伪造 onSensorChanged 步数 —— 服务端以步长合理性（
 * stride = v*60/cadence ∈ [0.3,1.8]m）交叉校验，见 run_evidence_validator.py。
 */
JNIEXPORT void JNICALL
Java_cn_edu_hust_campusrun_shield_defense_native_MetricGuard_nativeAddSteps(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong steps) {
    static MetricState s;
    s.steps += static_cast<uint64_t>(steps);
}

/**
 * nativeSeal：结束运动时对全部指标做 HMAC 签名（防篡改信封）。
 * 返回 "total|valid|steps|over_segments|mac" 字符串；
 * 服务端以相同密钥验签并与 trajectory_plausibility 重新计算比对。
 */
JNIEXPORT jstring JNICALL
Java_cn_edu_hust_campusrun_shield_defense_native_MetricGuard_nativeSeal(
    JNIEnv* env, jobject /*thiz*/, jlong session_id) {
    static MetricState s;
    char buf[256];
    snprintf(buf, sizeof(buf), "%.2f:%.2f:%llu:%d:%lld",
             s.total_dist_m, s.valid_dist_m,
             static_cast<unsigned long long>(s.steps),
             s.over_speed_segments, static_cast<long long>(session_id));
    std::string msg(buf);
    std::string mac = hmac_sha256_hex(kHmacKey, msg);
    std::string out = buf + std::string(":") + mac;
    return env->NewStringUTF(out.c_str());
}

/**
 * nativeStrideCheck：单次步频/速度点步长校验（R5 客户端前置判定）。
 * stride = speed*60/cadence；超 [0.3,1.8]m 返回 1（可疑），
 * 与 run_evidence_validator.py 的 SUSPECT_VEHICLE 规则同源。
 */
JNIEXPORT jint JNICALL
Java_cn_edu_hust_campusrun_shield_defense_native_MetricGuard_nativeStrideCheck(
    JNIEnv* /*env*/, jobject /*thiz*/, jdouble speed_mps, jint cadence_per_min) {
    if (cadence_per_min <= 0 || speed_mps < kMinSpeedMps) return 0;
    double stride = speed_mps * 60.0 / cadence_per_min;
    if (stride > kMaxStrideM || stride < kMinStrideM) return 1;
    if (cadence_per_min > kCadenceRunMax || cadence_per_min < kCadenceRunMin) return 1;
    return 0;
}

} // extern "C"
