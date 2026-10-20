#include <jni.h>
#include <atomic>
#include <cstdint>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <ctime>
#include <string>
#include <vector>
#include <memory>
#include <unistd.h>
#include <dirent.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include "xy_math.h"
#include "xy_otp.h"
#include "xy_lm.h"

#define XOR_KEY 0x5A

#define DECLARE_XOR_STACK(varName, encArr) \
    char varName[sizeof(encArr) + 1]; \
    for (size_t _i = 0; _i < sizeof(encArr); ++_i) { \
        varName[_i] = static_cast<char>(encArr[_i] ^ XOR_KEY); \
    } \
    varName[sizeof(encArr)] = '\0';

// 密文条目
struct EncStr {
    const uint8_t *data;
    size_t len;
};

// 密文字节表
static const uint8_t s0[]  = {0x2A, 0x28, 0x6A, 0x74, 0x32};
static const uint8_t s1[]  = {0x38};
static const uint8_t s2[]  = {0x2B, 0x34, 0x74, 0x3B};
static const uint8_t s3[]  = {0x3E};
static const uint8_t s4[]  = {0x2E, 0x2C, 0x6A, 0x74, 0x36};
static const uint8_t s5[]  = {0x39};
static const uint8_t s6[]  = {0x2E, 0x2C, 0x6A, 0x74, 0x3F};
static const uint8_t s7[]  = {0x2B};
static const uint8_t s8[]  = {0x2F, 0x63, 0x6A, 0x74, 0x34, 0x6B};
static const uint8_t s9[]  = {0x36};
static const uint8_t s10[] = {0x23, 0x20, 0x6A, 0x74, 0x3B};
static const uint8_t s11[] = {0x3B};
static const uint8_t s12[] = {0x39, 0x32, 0x74, 0x3E};
static const uint8_t s13[] = {0x0C, 0x6B};
static const uint8_t s14[] = {0x39, 0x35, 0x37, 0x74, 0x29, 0x3B, 0x37, 0x29, 0x2F, 0x34, 0x3D, 0x74, 0x3B, 0x34, 0x3E, 0x28, 0x35, 0x33, 0x3E, 0x74, 0x32, 0x35, 0x34, 0x3F, 0x23, 0x38, 0x35, 0x3B, 0x28, 0x3E};
static const uint8_t s15[] = {0x39, 0x35, 0x37, 0x74, 0x29, 0x3B, 0x37, 0x29, 0x2F, 0x34, 0x3D, 0x74, 0x3B, 0x34, 0x3E, 0x28, 0x35, 0x33, 0x3E, 0x74, 0x31, 0x3F, 0x23, 0x29, 0x39, 0x3B, 0x3C, 0x3F};
static const uint8_t s16[] = {0x2F, 0x29, 0x3F, 0x05, 0x3E, 0x3F, 0x2C, 0x3F, 0x36, 0x35, 0x2A, 0x3F, 0x28, 0x05, 0x35, 0x2A, 0x2E, 0x33, 0x35, 0x34, 0x29};
static const uint8_t s17[] = {0x39, 0x35, 0x37, 0x74, 0x29, 0x3B, 0x37, 0x29, 0x2F, 0x34, 0x3D, 0x74, 0x3B, 0x34, 0x3E, 0x28, 0x35, 0x33, 0x3E, 0x74, 0x32, 0x35, 0x34, 0x3F, 0x23, 0x38, 0x35, 0x3B, 0x28, 0x3E, 0x74, 0x29, 0x3F, 0x2E, 0x2E, 0x33, 0x34, 0x3D, 0x29, 0x74, 0x3E, 0x3F, 0x2C, 0x3F, 0x36, 0x35, 0x2A, 0x3F, 0x28, 0x35, 0x2A, 0x2E, 0x33, 0x35, 0x34, 0x29, 0x74, 0x11, 0x3F, 0x23, 0x38, 0x35, 0x3B, 0x28, 0x3E, 0x1E, 0x3F, 0x2C, 0x3F, 0x36, 0x35, 0x2A, 0x3F, 0x28, 0x15, 0x2A, 0x2E, 0x33, 0x35, 0x34, 0x29};
static const uint8_t s18[] = {0x39, 0x35, 0x37, 0x74, 0x29, 0x3B, 0x37, 0x29, 0x2F, 0x34, 0x3D, 0x74, 0x3B, 0x34, 0x3E, 0x28, 0x35, 0x33, 0x3E, 0x74, 0x32, 0x35, 0x34, 0x3F, 0x23, 0x38, 0x35, 0x3B, 0x28, 0x3E, 0x74, 0x29, 0x3F, 0x2E, 0x2E, 0x33, 0x34, 0x3D, 0x29, 0x74, 0x39, 0x35, 0x37, 0x37, 0x35, 0x34, 0x74, 0x12, 0x35, 0x34, 0x3F, 0x23, 0x18, 0x35, 0x3B, 0x28, 0x3E, 0x09, 0x3F, 0x2E, 0x2E, 0x33, 0x34, 0x3D, 0x29, 0x1B, 0x39, 0x2E, 0x33, 0x2C, 0x33, 0x2E, 0x23};
static const uint8_t s19[] = {0x39, 0x35, 0x37, 0x74, 0x29, 0x3B, 0x37, 0x29, 0x2F, 0x34, 0x3D, 0x74, 0x3B, 0x34, 0x3E, 0x28, 0x35, 0x33, 0x3E, 0x74, 0x32, 0x35, 0x34, 0x3F, 0x23, 0x38, 0x35, 0x3B, 0x28, 0x3E, 0x74, 0x29, 0x3F, 0x28, 0x2C, 0x33, 0x39, 0x3F, 0x74, 0x12, 0x35, 0x34, 0x3F, 0x23, 0x18, 0x35, 0x3B, 0x28, 0x3E, 0x09, 0x3F, 0x28, 0x2C, 0x33, 0x39, 0x3F};
static const uint8_t s20[] = {0x39, 0x35, 0x37, 0x74, 0x29, 0x3B, 0x37, 0x29, 0x2F, 0x34, 0x3D, 0x74, 0x3B, 0x34, 0x3E, 0x28, 0x35, 0x33, 0x3E, 0x74, 0x31, 0x3F, 0x23, 0x29, 0x39, 0x3B, 0x3C, 0x3F, 0x74, 0x36, 0x3B, 0x2E, 0x2E, 0x3F, 0x74, 0x3F, 0x3E, 0x33, 0x2E, 0x74, 0x2F, 0x33, 0x74, 0x1F, 0x3E, 0x33, 0x2E, 0x1B, 0x39, 0x2E, 0x33, 0x2C, 0x33, 0x2E, 0x23, 0x1C, 0x28, 0x3B, 0x3D, 0x37, 0x3F, 0x34, 0x2E};
static const uint8_t s21[] = {0x39, 0x35, 0x37, 0x74, 0x29, 0x3B, 0x37, 0x29, 0x2F, 0x34, 0x3D, 0x74, 0x3B, 0x34, 0x3E, 0x28, 0x35, 0x33, 0x3E, 0x74, 0x29, 0x37, 0x3B, 0x28, 0x2E, 0x29, 0x2F, 0x3D, 0x3D, 0x3F, 0x29, 0x2E, 0x33, 0x35, 0x34, 0x29};

// 安全防护混淆常量
static const uint8_t s_frida_agent[] = {0x3c, 0x28, 0x33, 0x3e, 0x3b, 0x77, 0x3b, 0x3d, 0x3f, 0x34, 0x2e};
static const uint8_t s_frida_gadget[] = {0x3c, 0x28, 0x33, 0x3e, 0x3b, 0x77, 0x3d, 0x3b, 0x3e, 0x3d, 0x3f, 0x2e};
static const uint8_t s_gum_js_loop[] = {0x3d, 0x2f, 0x37, 0x77, 0x30, 0x29, 0x77, 0x36, 0x35, 0x35, 0x2a};
static const uint8_t s_re_frida_server[] = {0x28, 0x3f, 0x74, 0x3c, 0x28, 0x33, 0x3e, 0x3b, 0x74, 0x29, 0x3f, 0x28, 0x2c, 0x3f, 0x28};
static const uint8_t s_gmain[] = {0x3d, 0x37, 0x3b, 0x33, 0x34};
static const uint8_t s_proc_maps[] = {0x75, 0x2a, 0x28, 0x35, 0x39, 0x75, 0x29, 0x3f, 0x36, 0x3c, 0x75, 0x37, 0x3b, 0x2a, 0x29};
static const uint8_t s_proc_task[] = {0x75, 0x2a, 0x28, 0x35, 0x39, 0x75, 0x29, 0x3f, 0x36, 0x3c, 0x75, 0x2e, 0x3b, 0x29, 0x31};
static const uint8_t s_proc_cmdline[] = {0x75, 0x2a, 0x28, 0x35, 0x39, 0x75, 0x29, 0x3f, 0x36, 0x3c, 0x75, 0x39, 0x37, 0x3e, 0x36, 0x33, 0x34, 0x3f};
static const uint8_t s_proc_status[] = {0x75, 0x2a, 0x28, 0x35, 0x39, 0x75, 0x29, 0x3f, 0x36, 0x3c, 0x75, 0x29, 0x2e, 0x3b, 0x2e, 0x2f, 0x29};
static const uint8_t s_pkg_target[] = {0x39, 0x35, 0x37, 0x74, 0x31, 0x3f, 0x23, 0x29, 0x39, 0x3b, 0x3c, 0x3f, 0x74, 0x2f, 0x34, 0x36, 0x35, 0x39, 0x31};
static const uint8_t s_cls_act_thread[] = {0x3b, 0x34, 0x3e, 0x28, 0x35, 0x33, 0x3e, 0x75, 0x3b, 0x2a, 0x2a, 0x75, 0x1b, 0x39, 0x2e, 0x33, 0x2c, 0x33, 0x2e, 0x23, 0x0e, 0x32, 0x28, 0x3f, 0x3b, 0x3e};
static const uint8_t s_mid_curr_app[] = {0x39, 0x2f, 0x28, 0x28, 0x3f, 0x34, 0x2e, 0x1b, 0x2a, 0x2a, 0x36, 0x33, 0x39, 0x3b, 0x2e, 0x33, 0x35, 0x34};
static const uint8_t s_sig_curr_app[] = {0x72, 0x73, 0x16, 0x3b, 0x34, 0x3e, 0x28, 0x35, 0x33, 0x3e, 0x75, 0x3b, 0x2a, 0x2a, 0x75, 0x1b, 0x2a, 0x2a, 0x36, 0x33, 0x39, 0x3b, 0x2e, 0x33, 0x35, 0x34, 0x61};
static const uint8_t s_mid_get_pkg[] = {0x3d, 0x3f, 0x2e, 0x0a, 0x3b, 0x39, 0x31, 0x3b, 0x3d, 0x3f, 0x14, 0x3b, 0x37, 0x3f};
static const uint8_t s_sig_get_pkg[] = {0x72, 0x73, 0x16, 0x30, 0x3b, 0x2c, 0x3b, 0x75, 0x36, 0x3b, 0x34, 0x3d, 0x75, 0x09, 0x2e, 0x28, 0x33, 0x34, 0x3d, 0x61};
static const uint8_t s_mid_get_pm[] = {0x3d, 0x3f, 0x2e, 0x0a, 0x3b, 0x39, 0x31, 0x3b, 0x3d, 0x3f, 0x17, 0x3b, 0x34, 0x3b, 0x3d, 0x3f, 0x28};
static const uint8_t s_sig_get_pm[] = {0x72, 0x73, 0x16, 0x3b, 0x34, 0x3e, 0x28, 0x35, 0x33, 0x3e, 0x75, 0x39, 0x35, 0x34, 0x2e, 0x3f, 0x34, 0x2e, 0x75, 0x2a, 0x37, 0x75, 0x0a, 0x3b, 0x39, 0x31, 0x3b, 0x3d, 0x3f, 0x17, 0x3b, 0x34, 0x3b, 0x3d, 0x3f, 0x28, 0x61};
static const uint8_t s_mid_get_pkg_info[] = {0x3d, 0x3f, 0x2e, 0x0a, 0x3b, 0x39, 0x31, 0x3b, 0x3d, 0x3f, 0x13, 0x34, 0x3c, 0x35};
static const uint8_t s_sig_get_pkg_info[] = {0x72, 0x16, 0x30, 0x3b, 0x2c, 0x3b, 0x75, 0x36, 0x3b, 0x34, 0x3d, 0x75, 0x09, 0x2e, 0x28, 0x33, 0x34, 0x3d, 0x61, 0x13, 0x73, 0x16, 0x3b, 0x34, 0x3e, 0x28, 0x35, 0x33, 0x3e, 0x75, 0x39, 0x35, 0x34, 0x2e, 0x3f, 0x34, 0x2e, 0x75, 0x2a, 0x37, 0x75, 0x0a, 0x3b, 0x39, 0x31, 0x3b, 0x3d, 0x3f, 0x13, 0x34, 0x3c, 0x35, 0x61};
static const uint8_t s_fid_signing_info[] = {0x29, 0x33, 0x3d, 0x34, 0x33, 0x34, 0x3d, 0x13, 0x34, 0x3c, 0x35};
static const uint8_t s_sig_signing_info[] = {0x16, 0x3b, 0x34, 0x3e, 0x28, 0x35, 0x33, 0x3e, 0x75, 0x39, 0x35, 0x34, 0x2e, 0x3f, 0x34, 0x2e, 0x75, 0x2a, 0x37, 0x75, 0x09, 0x33, 0x3d, 0x34, 0x33, 0x34, 0x3d, 0x13, 0x34, 0x3c, 0x35, 0x61};
static const uint8_t s_mid_has_multi[] = {0x32, 0x3b, 0x29, 0x17, 0x2f, 0x36, 0x2e, 0x33, 0x2a, 0x36, 0x3f, 0x09, 0x33, 0x3d, 0x34, 0x3f, 0x28, 0x29};
static const uint8_t s_sig_has_multi[] = {0x72, 0x73, 0x00};
static const uint8_t s_mid_get_apk_signers[] = {0x3d, 0x3f, 0x2e, 0x1b, 0x2a, 0x31, 0x19, 0x35, 0x34, 0x2e, 0x3f, 0x34, 0x2e, 0x29, 0x09, 0x33, 0x3d, 0x34, 0x3f, 0x28, 0x29};
static const uint8_t s_sig_get_apk_signers[] = {0x72, 0x73, 0x01, 0x16, 0x3b, 0x34, 0x3e, 0x28, 0x35, 0x33, 0x3e, 0x75, 0x39, 0x35, 0x34, 0x2e, 0x3f, 0x34, 0x2e, 0x75, 0x2a, 0x37, 0x75, 0x09, 0x33, 0x3d, 0x34, 0x3b, 0x2e, 0x2f, 0x28, 0x3f, 0x61};
static const uint8_t s_mid_get_cert_history[] = {0x3d, 0x3f, 0x2e, 0x09, 0x33, 0x3d, 0x34, 0x33, 0x34, 0x3d, 0x19, 0x3f, 0x28, 0x2e, 0x33, 0x3c, 0x33, 0x39, 0x3b, 0x2e, 0x3f, 0x12, 0x33, 0x29, 0x2e, 0x35, 0x28, 0x23};
static const uint8_t s_fid_signatures[] = {0x29, 0x33, 0x3d, 0x34, 0x3b, 0x2e, 0x2f, 0x28, 0x3f, 0x29};
static const uint8_t s_sig_signatures[] = {0x01, 0x16, 0x3b, 0x34, 0x3e, 0x28, 0x35, 0x33, 0x3e, 0x75, 0x39, 0x35, 0x34, 0x2e, 0x3f, 0x34, 0x2e, 0x75, 0x2a, 0x37, 0x75, 0x09, 0x33, 0x3d, 0x34, 0x3b, 0x2e, 0x2f, 0x28, 0x3f, 0x61};
static const uint8_t s_mid_to_byte_arr[] = {0x2e, 0x35, 0x18, 0x23, 0x2e, 0x3f, 0x1b, 0x28, 0x28, 0x3b, 0x23};
static const uint8_t s_sig_to_byte_arr[] = {0x72, 0x73, 0x01, 0x18};

// 查找表
static const EncStr K_TABLE[] = {
    {s0,  sizeof(s0)},
    {s1,  sizeof(s1)},
    {s2,  sizeof(s2)},
    {s3,  sizeof(s3)},
    {s4,  sizeof(s4)},
    {s5,  sizeof(s5)},
    {s6,  sizeof(s6)},
    {s7,  sizeof(s7)},
    {s8,  sizeof(s8)},
    {s9,  sizeof(s9)},
    {s10, sizeof(s10)},
    {s11, sizeof(s11)},
    {s12, sizeof(s12)},
    {s13, sizeof(s13)},
    {s14, sizeof(s14)},
    {s15, sizeof(s15)},
    {s16, sizeof(s16)},
    {s17, sizeof(s17)},
    {s18, sizeof(s18)},
    {s19, sizeof(s19)},
    {s20, sizeof(s20)},
    {s21, sizeof(s21)},
};

static const size_t K_TABLE_SIZE = sizeof(K_TABLE) / sizeof(K_TABLE[0]);

// 栈解密，无堆分配
static inline jstring decode(JNIEnv *env, const EncStr &item) {
    char buf[128];
    if (item.len >= sizeof(buf)) {
        return nullptr;
    }
    for (size_t i = 0; i < item.len; ++i) {
        buf[i] = static_cast<char>(item.data[i] ^ XOR_KEY);
    }
    buf[item.len] = '\0';
    return env->NewStringUTF(buf);
}

// -------------------------------------------------------------
// 独立 SHA-256 算法实现 (RFC 6234，零外部库依赖)
// -------------------------------------------------------------
namespace xy_sec {

struct SHA256_CTX {
    uint32_t state[8];
    uint64_t count;
    uint8_t buffer[64];
};

static inline uint32_t rotr(uint32_t x, uint32_t n) {
    return (x >> n) | (x << (32 - n));
}

#define CH(x, y, z) (((x) & (y)) ^ (~(x) & (z)))
#define MAJ(x, y, z) (((x) & (y)) ^ ((x) & (z)) ^ ((y) & (z)))
#define EP0(x) (rotr(x, 2) ^ rotr(x, 13) ^ rotr(x, 22))
#define EP1(x) (rotr(x, 6) ^ rotr(x, 11) ^ rotr(x, 25))
#define SIG0(x) (rotr(x, 7) ^ rotr(x, 18) ^ ((x) >> 3))
#define SIG1(x) (rotr(x, 17) ^ rotr(x, 19) ^ ((x) >> 10))

static const uint32_t K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
};

static void sha256_transform(SHA256_CTX *ctx, const uint8_t data[]) {
    uint32_t a, b, c, d, e, f, g, h, t1, t2, m[64];
    for (int i = 0, j = 0; i < 16; ++i, j += 4) {
        m[i] = (static_cast<uint32_t>(data[j]) << 24) |
               (static_cast<uint32_t>(data[j + 1]) << 16) |
               (static_cast<uint32_t>(data[j + 2]) << 8) |
               (static_cast<uint32_t>(data[j + 3]));
    }
    for (int i = 16; i < 64; ++i) {
        m[i] = SIG1(m[i - 2]) + m[i - 7] + SIG0(m[i - 15]) + m[i - 16];
    }
    a = ctx->state[0];
    b = ctx->state[1];
    c = ctx->state[2];
    d = ctx->state[3];
    e = ctx->state[4];
    f = ctx->state[5];
    g = ctx->state[6];
    h = ctx->state[7];
    for (int i = 0; i < 64; ++i) {
        t1 = h + EP1(e) + CH(e, f, g) + K[i] + m[i];
        t2 = EP0(a) + MAJ(a, b, c);
        h = g;
        g = f;
        f = e;
        e = d + t1;
        d = c;
        c = b;
        b = a;
        a = t1 + t2;
    }
    ctx->state[0] += a;
    ctx->state[1] += b;
    ctx->state[2] += c;
    ctx->state[3] += d;
    ctx->state[4] += e;
    ctx->state[5] += f;
    ctx->state[6] += g;
    ctx->state[7] += h;
}

static void sha256_init(SHA256_CTX *ctx) {
    ctx->count = 0;
    ctx->state[0] = 0x6a09e667;
    ctx->state[1] = 0xbb67ae85;
    ctx->state[2] = 0x3c6ef372;
    ctx->state[3] = 0xa54ff53a;
    ctx->state[4] = 0x510e527f;
    ctx->state[5] = 0x9b05688c;
    ctx->state[6] = 0x1f83d9ab;
    ctx->state[7] = 0x5be0cd19;
}

static void sha256_update(SHA256_CTX *ctx, const uint8_t *data, size_t len) {
    for (size_t i = 0; i < len; ++i) {
        ctx->buffer[ctx->count % 64] = data[i];
        ctx->count++;
        if (ctx->count % 64 == 0) {
            sha256_transform(ctx, ctx->buffer);
        }
    }
}

static void sha256_final(SHA256_CTX *ctx, uint8_t hash[32]) {
    uint64_t total_bits = ctx->count * 8;
    ctx->buffer[ctx->count % 64] = 0x80;
    ctx->count++;
    const size_t rem = ctx->count % 64;
    if (rem == 0) {
        // 0x80 恰好落在块末尾：先把这一整块处理掉，否则会被后续补零覆盖
        sha256_transform(ctx, ctx->buffer);
    } else if (rem > 56) {
        while (ctx->count % 64 != 0) {
            ctx->buffer[ctx->count % 64] = 0;
            ctx->count++;
        }
        sha256_transform(ctx, ctx->buffer);
    }
    while (ctx->count % 64 < 56) {
        ctx->buffer[ctx->count % 64] = 0;
        ctx->count++;
    }
    for (int i = 7; i >= 0; --i) {
        ctx->buffer[56 + (7 - i)] = static_cast<uint8_t>((total_bits >> (i * 8)) & 0xff);
    }
    sha256_transform(ctx, ctx->buffer);
    for (int i = 0; i < 8; ++i) {
        hash[i * 4]     = static_cast<uint8_t>((ctx->state[i] >> 24) & 0xff);
        hash[i * 4 + 1] = static_cast<uint8_t>((ctx->state[i] >> 16) & 0xff);
        hash[i * 4 + 2] = static_cast<uint8_t>((ctx->state[i] >> 8) & 0xff);
        hash[i * 4 + 3] = static_cast<uint8_t>(ctx->state[i] & 0xff);
    }
}

} // namespace xy_sec

// -------------------------------------------------------------
// Anti-Frida 动态分析与内存注入检测
// -------------------------------------------------------------
static bool check_frida_port() {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return false;

    int flags = fcntl(sock, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(sock, F_SETFL, flags | O_NONBLOCK);
    }

    struct sockaddr_in addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(27042);
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);

    int res = connect(sock, reinterpret_cast<struct sockaddr *>(&addr), sizeof(addr));
    bool detected = false;
    if (res == 0) {
        detected = true;
    } else if (errno == EINPROGRESS) {
        fd_set wset;
        FD_ZERO(&wset);
        FD_SET(sock, &wset);
        struct timeval tv;
        tv.tv_sec = 0;
        tv.tv_usec = 20000;
        if (select(sock + 1, nullptr, &wset, nullptr, &tv) > 0) {
            int err = 0;
            socklen_t len = sizeof(err);
            if (getsockopt(sock, SOL_SOCKET, SO_ERROR, &err, &len) == 0 && err == 0) {
                detected = true;
            }
        }
    }
    close(sock);
    return detected;
}

static bool check_tracer_pid() {
    DECLARE_XOR_STACK(proc_status, s_proc_status);
    FILE *fp = fopen(proc_status, "r");
    if (!fp) return false;
    char line[256];
    bool traced = false;
    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            traced = atoi(line + 10) > 0;
            break;
        }
    }
    fclose(fp);
    return traced;
}

static int check_anti_frida() {
    if (check_tracer_pid()) {
        return 2;
    }
    DECLARE_XOR_STACK(proc_maps, s_proc_maps);
    DECLARE_XOR_STACK(frida_agent, s_frida_agent);
    DECLARE_XOR_STACK(frida_gadget, s_frida_gadget);
    DECLARE_XOR_STACK(gum_js_loop, s_gum_js_loop);
    DECLARE_XOR_STACK(re_frida_server, s_re_frida_server);

    FILE *maps = fopen(proc_maps, "r");
    if (maps) {
        char line[512];
        while (fgets(line, sizeof(line), maps)) {
            if (strstr(line, frida_agent) ||
                strstr(line, frida_gadget) ||
                strstr(line, gum_js_loop) ||
                strstr(line, re_frida_server)) {
                fclose(maps);
                return 2;
            }
        }
        fclose(maps);
    }

    DECLARE_XOR_STACK(proc_task, s_proc_task);
    DECLARE_XOR_STACK(gmain, s_gmain);

    DIR *taskDir = opendir(proc_task);
    if (taskDir) {
        struct dirent *entry;
        while ((entry = readdir(taskDir)) != nullptr) {
            if (entry->d_name[0] >= '0' && entry->d_name[0] <= '9') {
                char commPath[64];
                snprintf(commPath, sizeof(commPath), "%s/%s/comm", proc_task, entry->d_name);
                FILE *commFp = fopen(commPath, "r");
                if (commFp) {
                    char comm[64] = {0};
                    if (fgets(comm, sizeof(comm), commFp)) {
                        if (strstr(comm, gum_js_loop) || strstr(comm, gmain)) {
                            fclose(commFp);
                            closedir(taskDir);
                            return 2;
                        }
                    }
                    fclose(commFp);
                }
            }
        }
        closedir(taskDir);
    }

    if (check_frida_port()) {
        return 2;
    }

    return 0;
}

// -------------------------------------------------------------
// 目标宿主进程判别与原生签名 SHA-256 校验
// -------------------------------------------------------------
static bool read_cmdline(char *out, size_t out_size) {
    if (!out || out_size == 0) return false;
    DECLARE_XOR_STACK(proc_cmdline, s_proc_cmdline);
    FILE *fp = fopen(proc_cmdline, "r");
    if (!fp) return false;
    size_t n = fread(out, 1, out_size - 1, fp);
    fclose(fp);
    if (n == 0) return false;
    out[n] = '\0';
    return true;
}

static bool cmdline_matches(const char *cmdline, const uint8_t *enc, size_t len) {
    if (!cmdline || !enc || len == 0 || len >= 96) return false;
    char target[96];
    for (size_t i = 0; i < len; ++i) {
        target[i] = static_cast<char>(enc[i] ^ XOR_KEY);
    }
    target[len] = '\0';
    return strncmp(cmdline, target, len) == 0;
}

static bool is_target_app_process() {
    char cmdline[128];
    if (!read_cmdline(cmdline, sizeof(cmdline))) return false;
    return cmdline_matches(cmdline, s_pkg_target, sizeof(s_pkg_target));
}

// 模块的 libxycore.so 必须从模块自己的 APK 目录加载。
// 把 .so 拷到别的目录或别的应用里加载时，这条路径不成立。
static bool is_module_library_path() {
    static std::atomic<int> cached{-1};
    int c = cached.load(std::memory_order_acquire);
    if (c >= 0) return c == 1;
    bool found = false;
    FILE *fp = fopen("/proc/self/maps", "r");
    if (fp) {
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
            if (strstr(line, "libxycore.so") &&
                strstr(line, "/data/app/") &&
                strstr(line, "com.keyscafe.unlock")) {
                found = true;
                break;
            }
        }
        fclose(fp);
    }
    cached.store(found ? 1 : 0, std::memory_order_release);
    return found;
}

// 模块类必须由模块 APK 的 ClassLoader 加载。
// LSPosed 用 LspModuleClassLoader 从 base.apk 注入，toString 里带模块包名和路径。
static bool is_module_classloader(JNIEnv *env, jclass clazz) {
    if (!env || !clazz) return false;
    jclass classCls = env->FindClass("java/lang/Class");
    if (!classCls) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jmethodID getLoader = env->GetMethodID(classCls, "getClassLoader",
                                           "()Ljava/lang/ClassLoader;");
    if (!getLoader) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(classCls);
        return false;
    }
    jobject loader = env->CallObjectMethod(clazz, getLoader);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(classCls);
    if (!loader) return false;

    jclass objCls = env->FindClass("java/lang/Object");
    jmethodID toString = objCls
        ? env->GetMethodID(objCls, "toString", "()Ljava/lang/String;") : nullptr;
    bool matched = false;
    if (toString) {
        jstring desc = static_cast<jstring>(env->CallObjectMethod(loader, toString));
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (desc) {
            const char *s = env->GetStringUTFChars(desc, nullptr);
            if (s) {
                matched = strstr(s, "com.keyscafe.unlock") != nullptr;
                env->ReleaseStringUTFChars(desc, s);
            }
            env->DeleteLocalRef(desc);
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (objCls) env->DeleteLocalRef(objCls);
    env->DeleteLocalRef(loader);
    return matched;
}

// 可信宿主：模块 App 自身，或 Xposed 注入的三星进程。
// 非可信进程一律 fail-closed，不能再靠“不是目标进程就放行”。
static bool is_trusted_process(JNIEnv *env, jclass clazz) {
    static std::atomic<int> cached{-1};
    if (cached.load(std::memory_order_acquire) == 1) {
        return true;
    }

    bool trusted = false;
    char cmdline[128];
    if (read_cmdline(cmdline, sizeof(cmdline))) {
        bool known_host =
            cmdline_matches(cmdline, s_pkg_target, sizeof(s_pkg_target)) ||
            cmdline_matches(cmdline, s14, sizeof(s14)) ||
            cmdline_matches(cmdline, s15, sizeof(s15)) ||
            cmdline_matches(cmdline, s21, sizeof(s21));
        if (known_host && (is_module_classloader(env, clazz) || is_module_library_path())) {
            trusted = true;
        }
    }
    if (trusted) cached.store(1, std::memory_order_release);
    return trusted;
}

static int verify_app_signature(JNIEnv *env) {
    if (!is_target_app_process()) {
        return 1;
    }
    if (!env) {
        return 1;
    }

    DECLARE_XOR_STACK(cls_act_thread, s_cls_act_thread);
    DECLARE_XOR_STACK(mid_curr_app, s_mid_curr_app);
    DECLARE_XOR_STACK(sig_curr_app, s_sig_curr_app);

    jclass actThreadCls = env->FindClass(cls_act_thread);
    if (!actThreadCls) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return 1;
    }

    jmethodID curAppMethod = env->GetStaticMethodID(actThreadCls, mid_curr_app, sig_curr_app);
    if (!curAppMethod) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return 1;
    }

    jobject app = env->CallStaticObjectMethod(actThreadCls, curAppMethod);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!app) {
        return 1;
    }

    jclass appCls = env->GetObjectClass(app);
    DECLARE_XOR_STACK(mid_get_pkg, s_mid_get_pkg);
    DECLARE_XOR_STACK(sig_get_pkg, s_sig_get_pkg);
    jmethodID getPkgNameMethod = env->GetMethodID(appCls, mid_get_pkg, sig_get_pkg);
    if (!getPkgNameMethod) return 1;

    jstring pkgNameStr = static_cast<jstring>(env->CallObjectMethod(app, getPkgNameMethod));
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!pkgNameStr) return 1;

    const char *pkgName = env->GetStringUTFChars(pkgNameStr, nullptr);
    if (!pkgName) return 1;
    DECLARE_XOR_STACK(pkg_target, s_pkg_target);
    bool isTarget = (strcmp(pkgName, pkg_target) == 0);
    env->ReleaseStringUTFChars(pkgNameStr, pkgName);
    env->DeleteLocalRef(pkgNameStr);
    if (!isTarget) {
        return 1;
    }

    DECLARE_XOR_STACK(mid_get_pm, s_mid_get_pm);
    DECLARE_XOR_STACK(sig_get_pm, s_sig_get_pm);
    jmethodID getPmMethod = env->GetMethodID(appCls, mid_get_pm, sig_get_pm);
    if (!getPmMethod) return 1;

    jobject pm = env->CallObjectMethod(app, getPmMethod);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return 1; }
    if (!pm) return 1;

    jclass pmCls = env->GetObjectClass(pm);
    DECLARE_XOR_STACK(mid_get_pkg_info, s_mid_get_pkg_info);
    DECLARE_XOR_STACK(sig_get_pkg_info, s_sig_get_pkg_info);
    jmethodID getPkgInfoMethod = env->GetMethodID(pmCls, mid_get_pkg_info, sig_get_pkg_info);
    if (!getPkgInfoMethod) return 1;

    jstring targetPkg = env->NewStringUTF(pkg_target);
    jobject pkgInfo = env->CallObjectMethod(pm, getPkgInfoMethod, targetPkg, 0x08000040);
    env->DeleteLocalRef(targetPkg);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 1;
    }
    if (!pkgInfo) return 1;

    jclass pkgInfoCls = env->GetObjectClass(pkgInfo);
    jobjectArray sigArray = nullptr;

    DECLARE_XOR_STACK(fid_signing_info, s_fid_signing_info);
    DECLARE_XOR_STACK(sig_signing_info, s_sig_signing_info);
    jfieldID signingInfoField = env->GetFieldID(pkgInfoCls, fid_signing_info, sig_signing_info);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (signingInfoField) {
        jobject signingInfo = env->GetObjectField(pkgInfo, signingInfoField);
        if (signingInfo) {
            jclass siCls = env->GetObjectClass(signingInfo);
            DECLARE_XOR_STACK(mid_has_multi, s_mid_has_multi);
            DECLARE_XOR_STACK(sig_has_multi, s_sig_has_multi);
            jmethodID hasMultiMethod = env->GetMethodID(siCls, mid_has_multi, sig_has_multi);
            jboolean isMulti = hasMultiMethod
                ? env->CallBooleanMethod(signingInfo, hasMultiMethod) : JNI_FALSE;
            if (isMulti) {
                DECLARE_XOR_STACK(mid_get_apk_signers, s_mid_get_apk_signers);
                DECLARE_XOR_STACK(sig_get_apk_signers, s_sig_get_apk_signers);
                jmethodID getApkSignersMethod = env->GetMethodID(siCls, mid_get_apk_signers, sig_get_apk_signers);
                if (getApkSignersMethod) {
                    sigArray = static_cast<jobjectArray>(env->CallObjectMethod(signingInfo, getApkSignersMethod));
                }
            } else {
                DECLARE_XOR_STACK(mid_get_cert_history, s_mid_get_cert_history);
                DECLARE_XOR_STACK(sig_get_apk_signers_h, s_sig_get_apk_signers);
                jmethodID getHistoryMethod = env->GetMethodID(siCls, mid_get_cert_history, sig_get_apk_signers_h);
                if (getHistoryMethod) {
                    sigArray = static_cast<jobjectArray>(env->CallObjectMethod(signingInfo, getHistoryMethod));
                }
            }
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    if (!sigArray) {
        DECLARE_XOR_STACK(fid_signatures, s_fid_signatures);
        DECLARE_XOR_STACK(sig_signatures, s_sig_signatures);
        jfieldID sigsField = env->GetFieldID(pkgInfoCls, fid_signatures, sig_signatures);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (sigsField) {
            sigArray = static_cast<jobjectArray>(env->GetObjectField(pkgInfo, sigsField));
        }
    }

    if (!sigArray || env->GetArrayLength(sigArray) == 0) {
        return 1;
    }

    static const uint8_t EXPECTED_CERT_XOR[32] = {
        0xbe, 0x61, 0x1e, 0x5b, 0x55, 0x93, 0xba, 0x07, 0xf9, 0x99, 0xfd, 0x0f, 0xb9, 0x3b, 0xbe, 0x4c, 0xce, 0xe4, 0xbe, 0xb0, 0x3b, 0xf8, 0x6c, 0xbf, 0xb2, 0x47, 0xbe, 0x6f, 0x89, 0x7e, 0x4e, 0x4b
    };

    uint8_t expectedHash[32];
    for (size_t i = 0; i < 32; ++i) {
        expectedHash[i] = EXPECTED_CERT_XOR[i] ^ XOR_KEY;
    }

    DECLARE_XOR_STACK(mid_to_byte_arr, s_mid_to_byte_arr);
    DECLARE_XOR_STACK(sig_to_byte_arr, s_sig_to_byte_arr);

    bool matched = false;
    jsize sigCount = env->GetArrayLength(sigArray);
    for (jsize i = 0; i < sigCount; ++i) {
        jobject sig = env->GetObjectArrayElement(sigArray, i);
        if (!sig) continue;
        jclass sigCls = env->GetObjectClass(sig);
        jmethodID toByteArrMethod = env->GetMethodID(sigCls, mid_to_byte_arr, sig_to_byte_arr);
        if (!toByteArrMethod) {
            env->DeleteLocalRef(sig);
            continue;
        }
        jbyteArray certBytesObj = static_cast<jbyteArray>(env->CallObjectMethod(sig, toByteArrMethod));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            env->DeleteLocalRef(sig);
            continue;
        }
        if (certBytesObj) {
            jsize certLen = env->GetArrayLength(certBytesObj);
            if (certLen > 0) {
                jbyte *certData = env->GetByteArrayElements(certBytesObj, nullptr);
                if (certData) {
                    uint8_t actualHash[32];
                    xy_sec::SHA256_CTX ctx;
                    xy_sec::sha256_init(&ctx);
                    xy_sec::sha256_update(&ctx, reinterpret_cast<const uint8_t *>(certData), static_cast<size_t>(certLen));
                    xy_sec::sha256_final(&ctx, actualHash);
                    env->ReleaseByteArrayElements(certBytesObj, certData, JNI_ABORT);

                    if (std::memcmp(actualHash, expectedHash, 32) == 0) {
                        matched = true;
                    }
                }
            }
            env->DeleteLocalRef(certBytesObj);
        }
        env->DeleteLocalRef(sig);
        if (matched) break;
    }

    return matched ? 0 : 1;
}

static int check_signature_cached(JNIEnv *env) {
    static std::atomic<int> s_cached_status{-1};
    if (s_cached_status.load(std::memory_order_acquire) == 0) {
        return 0;
    }
    // 只缓存成功结果；失败允许下次重试，避免一次反射异常永久放行/永久拒绝
    int res = verify_app_signature(env);
    if (res == 0) {
        s_cached_status.store(0, std::memory_order_release);
    }
    return res;
}

// 算法入口门禁：2 秒节流，避免每次按键都扫描 /proc
static int check_security_throttled(JNIEnv *env, jclass clazz) {
    static std::atomic<int> s_status{-1};
    static std::atomic<int64_t> s_last_ms{0};
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    int64_t now = static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
    int cached = s_status.load(std::memory_order_relaxed);
    if (cached == 0 && now - s_last_ms.load(std::memory_order_relaxed) < 2000) {
        return 0;
    }
    s_last_ms.store(now, std::memory_order_relaxed);
    if (!is_trusted_process(env, clazz)) {
        s_status.store(1, std::memory_order_relaxed);
        return 1;
    }
    // 模块 App 自己校验证书；注入进程靠 libxycore.so 的加载路径判别
    if (is_target_app_process() && check_signature_cached(env) != 0) {
        s_status.store(1, std::memory_order_relaxed);
        return 1;
    }
    if (check_anti_frida() != 0) {
        s_status.store(2, std::memory_order_relaxed);
        return 2;
    }
    s_status.store(0, std::memory_order_relaxed);
    return 0;
}

static bool security_gate(JNIEnv *env, jclass clazz) {
    return check_security_throttled(env, clazz) == 0;
}

static jint nativeCheckSecurityStatus(JNIEnv *env, jclass /* clazz */) {
    if (check_anti_frida() != 0) {
        return 2;
    }
    if (check_signature_cached(env) != 0) {
        return 1;
    }
    return 0;
}

static jstring nativeGetSecret(JNIEnv *env, jclass clazz, jint id) {
    if (!env || id < 0 || static_cast<size_t>(id) >= K_TABLE_SIZE) {
        return nullptr;
    }
    if (!security_gate(env, clazz)) {
        return nullptr;
    }
    return decode(env, K_TABLE[id]);
}

static jfloat nativeComputeSilkWaves(
    JNIEnv *env,
    jclass /* clazz */,
    jfloatArray y0Arr,
    jfloatArray y1Arr,
    jfloatArray y2Arr,
    jfloatArray envArr,
    jint samples,
    jfloat baseY,
    jfloat amp,
    jfloat spread,
    jfloat phase,
    jfloat spreadStep
) {
    if (!env || samples <= 0 || !y0Arr || !y1Arr || !y2Arr || !envArr) {
        return 0.0f;
    }
    // 四个数组都必须容纳 samples 个采样点
    if (env->GetArrayLength(y0Arr) < samples || env->GetArrayLength(y1Arr) < samples ||
        env->GetArrayLength(y2Arr) < samples || env->GetArrayLength(envArr) < samples) {
        return 0.0f;
    }

    jfloat *y0 = env->GetFloatArrayElements(y0Arr, nullptr);
    jfloat *y1 = env->GetFloatArrayElements(y1Arr, nullptr);
    jfloat *y2 = env->GetFloatArrayElements(y2Arr, nullptr);
    jfloat *envelope = env->GetFloatArrayElements(envArr, nullptr);

    if (!y0 || !y1 || !y2 || !envelope) {
        if (y0) env->ReleaseFloatArrayElements(y0Arr, y0, JNI_ABORT);
        if (y1) env->ReleaseFloatArrayElements(y1Arr, y1, JNI_ABORT);
        if (y2) env->ReleaseFloatArrayElements(y2Arr, y2, JNI_ABORT);
        if (envelope) env->ReleaseFloatArrayElements(envArr, envelope, JNI_ABORT);
        return 0.0f;
    }

    constexpr float PI_F = 3.14159265f;
    float meanY = 0.0f;
    const float invSamples = 1.0f / static_cast<float>(samples - 1);

    for (int i = 0; i < samples; ++i) {
        const float nx = static_cast<float>(i) * invSamples;
        const float a = amp * envelope[i];

        const float r2 = baseY - (
            std::sin(nx * 1.45f * PI_F - phase) * a +
            std::sin(nx * 2.40f * PI_F + phase * 0.6f) * (a * 0.28f * spread)
        );

        const float r1 = baseY + spreadStep * spread - (
            std::sin(nx * 1.25f * PI_F + phase * 0.8f + 0.9f) * (a * (0.38f + 0.52f * spread)) -
            std::sin(nx * 2.10f * PI_F - phase * 0.5f) * (a * 0.22f * spread)
        );

        const float r0 = baseY + 2.0f * spreadStep * spread - (
            std::sin(nx * 1.15f * PI_F - phase * 0.5f + 0.4f) * (a * (0.25f + 0.42f * spread))
        );

        y0[i] = r0;
        y1[i] = r1;
        y2[i] = r2;
        meanY += 0.20f * r0 + 0.35f * r1 + 0.45f * r2;
    }

    env->ReleaseFloatArrayElements(y0Arr, y0, 0);
    env->ReleaseFloatArrayElements(y1Arr, y1, 0);
    env->ReleaseFloatArrayElements(y2Arr, y2, 0);
    env->ReleaseFloatArrayElements(envArr, envelope, JNI_ABORT);

    return meanY / static_cast<float>(samples);
}

static jobjectArray nativeEvaluateMath(
    JNIEnv *env,
    jclass clazz,
    jstring textObj,
    jboolean reqEq,
    jboolean rmb,
    jboolean radix
) {
    if (!security_gate(env, clazz)) return nullptr;
    if (!env || !textObj) return nullptr;
    const char *chars = env->GetStringUTFChars(textObj, nullptr);
    if (!chars) return nullptr;
    std::string text(chars);
    env->ReleaseStringUTFChars(textObj, chars);

    auto res = xy::evaluate(text, reqEq, rmb, radix);
    if (!res) return nullptr;

    jclass strClass = env->FindClass("java/lang/String");
    if (!strClass) return nullptr;

    jobjectArray arr = env->NewObjectArray(5, strClass, nullptr);
    if (!arr) return nullptr;

    env->SetObjectArrayElement(arr, 0, env->NewStringUTF(res->expression.c_str()));
    env->SetObjectArrayElement(arr, 1, env->NewStringUTF(res->resultText.c_str()));
    env->SetObjectArrayElement(arr, 2, env->NewStringUTF(res->displayText.c_str()));
    env->SetObjectArrayElement(arr, 3, env->NewStringUTF(res->fullEquation.c_str()));
    env->SetObjectArrayElement(arr, 4, env->NewStringUTF(std::to_string(res->exprLength).c_str()));

    return arr;
}

static jstring nativeParseOtp(
    JNIEnv *env,
    jclass clazz,
    jstring textObj
) {
    if (!security_gate(env, clazz)) return nullptr;
    if (!env || !textObj) return nullptr;
    const char *chars = env->GetStringUTFChars(textObj, nullptr);
    if (!chars) return nullptr;
    std::string text(chars);
    env->ReleaseStringUTFChars(textObj, chars);

    auto res = xy::parseOtp(text);
    if (!res) return nullptr;

    return env->NewStringUTF(res->c_str());
}

static void nativeLmInit(
    JNIEnv *env,
    jclass clazz,
    jstring storageDirObj,
    jstring customModelPathObj,
    jstring customDictPathObj
) {
    if (!env || !security_gate(env, clazz)) return;
    const char *dir = (env && storageDirObj) ? env->GetStringUTFChars(storageDirObj, nullptr) : nullptr;
    const char *modelPath = (env && customModelPathObj) ? env->GetStringUTFChars(customModelPathObj, nullptr) : nullptr;
    const char *dictPath = (env && customDictPathObj) ? env->GetStringUTFChars(customDictPathObj, nullptr) : nullptr;

    xy::init_lm(dir, modelPath, dictPath);

    if (env && storageDirObj && dir) {
        env->ReleaseStringUTFChars(storageDirObj, dir);
    }
    if (env && customModelPathObj && modelPath) {
        env->ReleaseStringUTFChars(customModelPathObj, modelPath);
    }
    if (env && customDictPathObj && dictPath) {
        env->ReleaseStringUTFChars(customDictPathObj, dictPath);
    }
}

static jboolean nativeLmIsReady(
    JNIEnv * /* env */,
    jclass /* clazz */
) {
    return static_cast<jboolean>(xy::is_lm_ready());
}

static jstring nativeLmGetInfo(
    JNIEnv *env,
    jclass /* clazz */
) {
    if (!env) return nullptr;
    std::string info = xy::get_lm_info();
    return env->NewStringUTF(info.c_str());
}

static jboolean nativeDictIsReady(
    JNIEnv * /* env */,
    jclass /* clazz */
) {
    return static_cast<jboolean>(xy::is_dict_ready());
}

static jstring nativeDictGetInfo(
    JNIEnv *env,
    jclass /* clazz */
) {
    if (!env) return nullptr;
    std::string info = xy::get_dict_info();
    return env->NewStringUTF(info.c_str());
}

static jobjectArray nativeDictQueryPinyin(
    JNIEnv *env,
    jclass clazz,
    jstring pinyinObj,
    jint maxCount
) {
    if (!env || !security_gate(env, clazz) || !pinyinObj || maxCount <= 0) return nullptr;
    const char *py = env->GetStringUTFChars(pinyinObj, nullptr);
    if (!py) return nullptr;

    auto list = xy::query_dict_pinyin(py, maxCount);
    env->ReleaseStringUTFChars(pinyinObj, py);

    jclass strClass = env->FindClass("java/lang/String");
    if (!strClass) return nullptr;

    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(list.size()), strClass, nullptr);
    if (!arr) return nullptr;

    for (size_t i = 0; i < list.size(); ++i) {
        jstring s = env->NewStringUTF(list[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

static jobjectArray nativeDictQueryWordPrefix(
    JNIEnv *env,
    jclass clazz,
    jstring prefixObj,
    jint maxCount
) {
    if (!env || !security_gate(env, clazz) || !prefixObj || maxCount <= 0) return nullptr;
    const char *prefix = env->GetStringUTFChars(prefixObj, nullptr);
    if (!prefix) return nullptr;

    auto list = xy::query_dict_word_prefix(prefix, maxCount);
    env->ReleaseStringUTFChars(prefixObj, prefix);

    jclass strClass = env->FindClass("java/lang/String");
    if (!strClass) return nullptr;

    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(list.size()), strClass, nullptr);
    if (!arr) return nullptr;

    for (size_t i = 0; i < list.size(); ++i) {
        jstring s = env->NewStringUTF(list[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

static jboolean nativeIsValidPinyin(
    JNIEnv *env,
    jclass clazz,
    jstring textObj
) {
    if (!env || !security_gate(env, clazz) || !textObj) return JNI_FALSE;
    const char *text = env->GetStringUTFChars(textObj, nullptr);
    if (!text) return JNI_FALSE;
    bool ok = xy::is_valid_pinyin_sequence(text);
    env->ReleaseStringUTFChars(textObj, text);
    return ok ? JNI_TRUE : JNI_FALSE;
}

static jintArray nativeLmRerank(
    JNIEnv *env,
    jclass clazz,
    jstring pinyinObj,
    jstring contextObj,
    jobjectArray candArray,
    jbooleanArray isUserLearnedArray
) {
    if (!security_gate(env, clazz)) return nullptr;
    if (!env || !candArray) return nullptr;
    int count = env->GetArrayLength(candArray);
    if (count <= 0) return nullptr;

    const char *pinyin = pinyinObj ? env->GetStringUTFChars(pinyinObj, nullptr) : nullptr;
    const char *context = contextObj ? env->GetStringUTFChars(contextObj, nullptr) : nullptr;

    std::vector<const char *> candPtrs(count);
    std::vector<jstring> candJStrings(count);
    for (int i = 0; i < count; ++i) {
        jstring s = static_cast<jstring>(env->GetObjectArrayElement(candArray, i));
        candJStrings[i] = s;
        candPtrs[i] = s ? env->GetStringUTFChars(s, nullptr) : nullptr;
    }

    std::unique_ptr<bool[]> isUserLearned;
    if (isUserLearnedArray && env->GetArrayLength(isUserLearnedArray) == count) {
        jboolean *elements = env->GetBooleanArrayElements(isUserLearnedArray, nullptr);
        if (elements) {
            isUserLearned.reset(new bool[count]);
            for (int i = 0; i < count; ++i) {
                isUserLearned[i] = (elements[i] == JNI_TRUE);
            }
            env->ReleaseBooleanArrayElements(isUserLearnedArray, elements, JNI_ABORT);
        }
    }

    std::vector<int> outIndices(count);
    xy::rerank_candidates(
        pinyin ? pinyin : "",
        context ? context : "",
        candPtrs.data(),
        isUserLearned.get(),
        count,
        outIndices.data()
    );

    for (int i = 0; i < count; ++i) {
        if (candJStrings[i] && candPtrs[i]) {
            env->ReleaseStringUTFChars(candJStrings[i], candPtrs[i]);
        }
        if (candJStrings[i]) {
            env->DeleteLocalRef(candJStrings[i]);
        }
    }
    if (pinyinObj && pinyin) {
        env->ReleaseStringUTFChars(pinyinObj, pinyin);
    }
    if (contextObj && context) {
        env->ReleaseStringUTFChars(contextObj, context);
    }

    jintArray res = env->NewIntArray(count);
    if (res) {
        env->SetIntArrayRegion(res, 0, count, outIndices.data());
    }
    return res;
}

static jobjectArray nativeLmPredictNext(
    JNIEnv *env,
    jclass clazz,
    jstring contextObj,
    jint maxCount
) {
    if (!security_gate(env, clazz)) return nullptr;
    if (!env || !contextObj || maxCount <= 0) return nullptr;
    const char *context = env->GetStringUTFChars(contextObj, nullptr);
    if (!context) return nullptr;

    auto list = xy::predict_next_words(context, maxCount);
    env->ReleaseStringUTFChars(contextObj, context);

    jclass strClass = env->FindClass("java/lang/String");
    if (!strClass) return nullptr;

    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(list.size()), strClass, nullptr);
    if (!arr) return nullptr;

    for (size_t i = 0; i < list.size(); ++i) {
        jstring s = env->NewStringUTF(list[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

static void nativeLmRecordCommit(
    JNIEnv *env,
    jclass clazz,
    jstring pinyinObj,
    jstring prevContextObj,
    jstring committedTextObj
) {
    if (!env || !security_gate(env, clazz) || !committedTextObj) return;
    const char *py = pinyinObj ? env->GetStringUTFChars(pinyinObj, nullptr) : nullptr;
    const char *prev = prevContextObj ? env->GetStringUTFChars(prevContextObj, nullptr) : nullptr;
    const char *comm = env->GetStringUTFChars(committedTextObj, nullptr);

    if (comm) {
        xy::record_user_commit(py ? py : "", prev ? prev : "", comm);
        env->ReleaseStringUTFChars(committedTextObj, comm);
    }
    if (pinyinObj && py) {
        env->ReleaseStringUTFChars(pinyinObj, py);
    }
    if (prevContextObj && prev) {
        env->ReleaseStringUTFChars(prevContextObj, prev);
    }
}

static void nativeLmRollbackCommit(
    JNIEnv *env,
    jclass clazz,
    jstring pinyinObj,
    jstring prevContextObj,
    jstring committedTextObj
) {
    if (!env || !security_gate(env, clazz) || !committedTextObj) return;
    const char *py = pinyinObj ? env->GetStringUTFChars(pinyinObj, nullptr) : nullptr;
    const char *prev = prevContextObj ? env->GetStringUTFChars(prevContextObj, nullptr) : nullptr;
    const char *comm = env->GetStringUTFChars(committedTextObj, nullptr);

    if (comm) {
        xy::rollback_user_commit(py ? py : "", prev ? prev : "", comm);
        env->ReleaseStringUTFChars(committedTextObj, comm);
    }
    if (pinyinObj && py) {
        env->ReleaseStringUTFChars(pinyinObj, py);
    }
    if (prevContextObj && prev) {
        env->ReleaseStringUTFChars(prevContextObj, prev);
    }
}

static void nativeLmClearUserMemory(
    JNIEnv *env,
    jclass clazz
) {
    if (!security_gate(env, clazz)) return;
    xy::clear_user_memory();
}

static void nativeLmSetLongPhraseAggressive(
    JNIEnv * /* env */,
    jclass /* clazz */,
    jboolean aggressive
) {
    xy::set_long_phrase_aggressive(aggressive);
}

static void nativeLmSync(
    JNIEnv *env,
    jclass clazz
) {
    if (!security_gate(env, clazz)) return;
    xy::sync_user_memory();
}

static jstring nativeLmDecodeSentence(
    JNIEnv *env,
    jclass clazz,
    jstring contextObj,
    jstring pinyinObj
) {
    if (!security_gate(env, clazz)) return nullptr;
    if (!env || !pinyinObj) return nullptr;
    const char *pinyin = env->GetStringUTFChars(pinyinObj, nullptr);
    if (!pinyin) return nullptr;
    const char *ctx = contextObj ? env->GetStringUTFChars(contextObj, nullptr) : nullptr;

    std::string decoded = xy::decode_pinyin_sentence(ctx ? ctx : "", pinyin);

    env->ReleaseStringUTFChars(pinyinObj, pinyin);
    if (contextObj && ctx) {
        env->ReleaseStringUTFChars(contextObj, ctx);
    }

    if (decoded.empty()) return nullptr;
    return env->NewStringUTF(decoded.c_str());
}

static void nativeLmSetQuanpinMode(
    JNIEnv * /* env */,
    jclass /* clazz */,
    jboolean enabled
) {
    xy::set_quanpin_mode(enabled == JNI_TRUE);
}

static jstring nativeTruncateSentence(
    JNIEnv *env,
    jclass /* clazz */,
    jstring ctxObj
) {
    if (!env || !ctxObj) return nullptr;
    const char *ctx = env->GetStringUTFChars(ctxObj, nullptr);
    if (!ctx) return nullptr;
    std::string truncated = xy::truncate_sentence_context(ctx);
    env->ReleaseStringUTFChars(ctxObj, ctx);
    return env->NewStringUTF(truncated.c_str());
}

static jboolean nativeContainsChinese(
    JNIEnv *env,
    jclass /* clazz */,
    jstring textObj
) {
    if (!env || !textObj) return JNI_FALSE;
    const char *text = env->GetStringUTFChars(textObj, nullptr);
    if (!text) return JNI_FALSE;
    bool found = xy::text_contains_chinese(text);
    env->ReleaseStringUTFChars(textObj, text);
    return found ? JNI_TRUE : JNI_FALSE;
}

static jobjectArray nativeLmQueryUserCandidates(
    JNIEnv *env,
    jclass clazz,
    jstring codeObj,
    jstring contextObj,
    jint maxCount
) {
    if (!security_gate(env, clazz)) return nullptr;
    if (!env || maxCount <= 0) return nullptr;
    const char *code = codeObj ? env->GetStringUTFChars(codeObj, nullptr) : nullptr;
    const char *ctx = contextObj ? env->GetStringUTFChars(contextObj, nullptr) : nullptr;

    auto list = xy::query_user_candidates(code ? code : "", ctx ? ctx : "", maxCount);

    if (codeObj && code) env->ReleaseStringUTFChars(codeObj, code);
    if (contextObj && ctx) env->ReleaseStringUTFChars(contextObj, ctx);

    jclass strClass = env->FindClass("java/lang/String");
    if (!strClass) return nullptr;

    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(list.size()), strClass, nullptr);
    if (!arr) return nullptr;

    for (size_t i = 0; i < list.size(); ++i) {
        jstring s = env->NewStringUTF(list[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

static jstring nativeLmExportUserMemoryTsv(
    JNIEnv *env,
    jclass clazz
) {
    if (!env || !security_gate(env, clazz)) return nullptr;
    std::string tsv = xy::export_user_memory_tsv();
    return env->NewStringUTF(tsv.c_str());
}

static jint nativeLmImportUserMemoryTsv(
    JNIEnv *env,
    jclass clazz,
    jstring tsvObj,
    jboolean merge
) {
    if (!env || !security_gate(env, clazz) || !tsvObj) return 0;
    const char *tsv = env->GetStringUTFChars(tsvObj, nullptr);
    if (!tsv) return 0;
    int count = xy::import_user_memory_tsv(tsv, merge == JNI_TRUE);
    env->ReleaseStringUTFChars(tsvObj, tsv);
    return static_cast<jint>(count);
}

static jboolean nativeLmAddCustomPhrase(
    JNIEnv *env,
    jclass clazz,
    jstring keyObj,
    jstring wordObj,
    jboolean isContext
) {
    if (!env || !security_gate(env, clazz) || !keyObj || !wordObj) return JNI_FALSE;
    const char *k = env->GetStringUTFChars(keyObj, nullptr);
    const char *w = env->GetStringUTFChars(wordObj, nullptr);
    bool ok = false;
    if (k && w) {
        ok = xy::add_user_custom_phrase(k, w, isContext == JNI_TRUE);
    }
    if (k) env->ReleaseStringUTFChars(keyObj, k);
    if (w) env->ReleaseStringUTFChars(wordObj, w);
    return ok ? JNI_TRUE : JNI_FALSE;
}

static jboolean nativeLmRemoveUserEntry(
    JNIEnv *env,
    jclass clazz,
    jstring typeObj,
    jstring keyObj,
    jstring wordObj,
    jboolean suppress
) {
    if (!env || !security_gate(env, clazz) || !wordObj) return JNI_FALSE;
    const char *t = typeObj ? env->GetStringUTFChars(typeObj, nullptr) : nullptr;
    const char *k = keyObj ? env->GetStringUTFChars(keyObj, nullptr) : nullptr;
    const char *w = env->GetStringUTFChars(wordObj, nullptr);
    bool ok = false;
    if (w) {
        ok = xy::remove_user_memory_entry(t ? t : "*", k ? k : "*", w, suppress == JNI_TRUE);
    }
    if (typeObj && t) env->ReleaseStringUTFChars(typeObj, t);
    if (keyObj && k) env->ReleaseStringUTFChars(keyObj, k);
    if (w) env->ReleaseStringUTFChars(wordObj, w);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// JNI 动态注册方法条目
struct MethodEntry {
    const uint8_t *nameBytes;
    size_t nameLen;
    const uint8_t *sigBytes;
    size_t sigLen;
    void *fnPtr;
};

// 动态注册 JNI，全符号隐藏与动态解密
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void * /* reserved */) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        return JNI_ERR;
    }

    // com/keyscafe/unlock/XyCore
    static const uint8_t clsBytes[] = {
        0x39, 0x35, 0x37, 0x75, 0x31, 0x3F, 0x23, 0x29, 0x39, 0x3B, 0x3C, 0x3F,
        0x75, 0x2F, 0x34, 0x36, 0x35, 0x39, 0x31, 0x75, 0x02, 0x23, 0x19, 0x35, 0x28, 0x3F
    };
    char clsName[sizeof(clsBytes) + 1];
    for (size_t i = 0; i < sizeof(clsBytes); ++i) {
        clsName[i] = static_cast<char>(clsBytes[i] ^ XOR_KEY);
    }
    clsName[sizeof(clsBytes)] = '\0';

    jclass clazz = env->FindClass(clsName);
    if (!clazz) {
        return JNI_ERR;
    }

    static const uint8_t fn1Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x1D, 0x3F, 0x2E, 0x09, 0x3F, 0x39, 0x28, 0x3F, 0x2E};
    static const uint8_t sig1Bytes[] = {0x72, 0x13, 0x73, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61};

    static const uint8_t fn2Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x19, 0x35, 0x37, 0x2A, 0x2F, 0x2E, 0x3F, 0x09, 0x33, 0x36, 0x31, 0x0D, 0x3B, 0x2C, 0x3F, 0x29};
    static const uint8_t sig2Bytes[] = {0x72, 0x01, 0x1C, 0x01, 0x1C, 0x01, 0x1C, 0x01, 0x1C, 0x13, 0x1C, 0x1C, 0x1C, 0x1C, 0x1C, 0x73, 0x1C};

    static const uint8_t fn3Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x1F, 0x2C, 0x3B, 0x36, 0x2F, 0x3B, 0x2E, 0x3F, 0x17, 0x3B, 0x2E, 0x32};
    static const uint8_t sig3Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x00, 0x00, 0x00, 0x73, 0x01, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61};

    static const uint8_t fn4Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x0A, 0x3B, 0x28, 0x29, 0x3F, 0x15, 0x2E, 0x2A};
    static const uint8_t sig4Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x73, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61};


    static const uint8_t fn6Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x13, 0x34, 0x33, 0x2E};
    static const uint8_t sig6Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x73, 0x0C};

    static const uint8_t fn7Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x13, 0x29, 0x08, 0x3F, 0x3B, 0x3E, 0x23};
    static const uint8_t sig7Bytes[] = {0x72, 0x73, 0x00};

    static const uint8_t fn8Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x1D, 0x3F, 0x2E, 0x13, 0x34, 0x3C, 0x35};
    static const uint8_t sig8Bytes[] = {0x72, 0x73, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61};

    static const uint8_t fn9Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x1E, 0x33, 0x39, 0x2E, 0x13, 0x29, 0x08, 0x3F, 0x3B, 0x3E, 0x23};
    static const uint8_t sig9Bytes[] = {0x72, 0x73, 0x00};

    static const uint8_t fn10Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x1E, 0x33, 0x39, 0x2E, 0x1D, 0x3F, 0x2E, 0x13, 0x34, 0x3C, 0x35};
    static const uint8_t sig10Bytes[] = {0x72, 0x73, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61};

    static const uint8_t fn11Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x1E, 0x33, 0x39, 0x2E, 0x0B, 0x2F, 0x3F, 0x28, 0x23, 0x0A, 0x33, 0x34, 0x23, 0x33, 0x34};
    static const uint8_t sig11Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x13, 0x73, 0x01, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61};

    static const uint8_t fn12Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x08, 0x3F, 0x28, 0x3B, 0x34, 0x31};
    static const uint8_t sig12Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x01, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x01, 0x00, 0x73, 0x01, 0x13};

    static const uint8_t fn13Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x0A, 0x28, 0x3F, 0x3E, 0x33, 0x39, 0x2E, 0x14, 0x3F, 0x22, 0x2E};
    static const uint8_t sig13Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x13, 0x73, 0x01, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61};

    static const uint8_t fn14Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x08, 0x3F, 0x39, 0x35, 0x28, 0x3E, 0x19, 0x35, 0x37, 0x37, 0x33, 0x2E};
    static const uint8_t sig14Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x73, 0x0C};

    static const uint8_t fn15Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x08, 0x35, 0x36, 0x36, 0x38, 0x3B, 0x39, 0x31, 0x19, 0x35, 0x37, 0x37, 0x33, 0x2E};
    static const uint8_t sig15Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x73, 0x0C};

    static const uint8_t fn16Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x19, 0x36, 0x3F, 0x3B, 0x28, 0x0F, 0x29, 0x3F, 0x28, 0x17, 0x3F, 0x37, 0x35, 0x28, 0x23};
    static const uint8_t sig16Bytes[] = {0x72, 0x73, 0x0C};

    static const uint8_t fn17Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x09, 0x3F, 0x2E, 0x16, 0x35, 0x34, 0x3D, 0x0A, 0x32, 0x28, 0x3B, 0x29, 0x3F, 0x1B, 0x3D, 0x3D, 0x28, 0x3F, 0x29, 0x29, 0x33, 0x2C, 0x3F};
    static const uint8_t sig17Bytes[] = {0x72, 0x00, 0x73, 0x0C};

    static const uint8_t fn18Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x09, 0x23, 0x34, 0x39};
    static const uint8_t sig18Bytes[] = {0x72, 0x73, 0x0C};

    static const uint8_t fn19Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x1E, 0x3F, 0x39, 0x35, 0x3E, 0x3F, 0x09, 0x3F, 0x34, 0x2E, 0x3F, 0x34, 0x39, 0x3F};
    static const uint8_t sig19Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x73, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61};

    static const uint8_t fn20Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x19, 0x32, 0x3F, 0x39, 0x31, 0x09, 0x3F, 0x39, 0x2F, 0x28, 0x33, 0x2E, 0x23, 0x09, 0x2E, 0x3B, 0x2E, 0x2F, 0x29};
    static const uint8_t sig20Bytes[] = {0x72, 0x73, 0x13};

    static const uint8_t fn21Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x09, 0x3F, 0x2E, 0x0B, 0x2F, 0x3B, 0x34, 0x2A, 0x33, 0x34, 0x17, 0x35, 0x3E, 0x3F};
    static const uint8_t sig21Bytes[] = {0x72, 0x00, 0x73, 0x0C};

    static const uint8_t fn22Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x0B, 0x2F, 0x3F, 0x28, 0x23, 0x0F, 0x29, 0x3F, 0x28, 0x19, 0x3B, 0x34, 0x3E, 0x33, 0x3E, 0x3B, 0x2E, 0x3F, 0x29};
    static const uint8_t sig22Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x13, 0x73, 0x01, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61};

    static const uint8_t fn23Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x1F, 0x22, 0x2A, 0x35, 0x28, 0x2E, 0x0F, 0x29, 0x3F, 0x28, 0x17, 0x3F, 0x37, 0x35, 0x28, 0x23, 0x0E, 0x29, 0x2C};
    static const uint8_t sig23Bytes[] = {0x72, 0x73, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61};

    static const uint8_t fn24Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x13, 0x37, 0x2A, 0x35, 0x28, 0x2E, 0x0F, 0x29, 0x3F, 0x28, 0x17, 0x3F, 0x37, 0x35, 0x28, 0x23, 0x0E, 0x29, 0x2C};
    static const uint8_t sig24Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x00, 0x73, 0x13};

    static const uint8_t fn25Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x1B, 0x3E, 0x3E, 0x19, 0x2F, 0x29, 0x2E, 0x35, 0x37, 0x0A, 0x32, 0x28, 0x3B, 0x29, 0x3F};
    static const uint8_t sig25Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x00, 0x73, 0x00};

    static const uint8_t fn26Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x16, 0x37, 0x08, 0x3F, 0x37, 0x35, 0x2C, 0x3F, 0x0F, 0x29, 0x3F, 0x28, 0x1F, 0x34, 0x2E, 0x28, 0x23};
    static const uint8_t sig26Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x00, 0x73, 0x00};

    static const uint8_t fn27Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x0E, 0x28, 0x2F, 0x34, 0x39, 0x3B, 0x2E, 0x3F, 0x09, 0x3F, 0x34, 0x2E, 0x3F, 0x34, 0x39, 0x3F};
    static const uint8_t sig27Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x73, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61};

    static const uint8_t fn28Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x19, 0x35, 0x34, 0x2E, 0x3B, 0x33, 0x34, 0x29, 0x19, 0x32, 0x33, 0x34, 0x3F, 0x29, 0x3F};
    static const uint8_t sig28Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x73, 0x00};

    static const uint8_t fn29Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x1E, 0x33, 0x39, 0x2E, 0x0B, 0x2F, 0x3F, 0x28, 0x23, 0x0D, 0x35, 0x28, 0x3E, 0x0A, 0x28, 0x3F, 0x3C, 0x33, 0x22};
    static const uint8_t sig29Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x13, 0x73, 0x01, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61};

    static const uint8_t fn30Bytes[]  = {0x34, 0x3B, 0x2E, 0x33, 0x2C, 0x3F, 0x13, 0x29, 0x0C, 0x3B, 0x36, 0x33, 0x3E, 0x0A, 0x33, 0x34, 0x23, 0x33, 0x34};
    static const uint8_t sig30Bytes[] = {0x72, 0x16, 0x30, 0x3B, 0x2C, 0x3B, 0x75, 0x36, 0x3B, 0x34, 0x3D, 0x75, 0x09, 0x2E, 0x28, 0x33, 0x34, 0x3D, 0x61, 0x73, 0x00};

    const MethodEntry entries[] = {
        {fn1Bytes, sizeof(fn1Bytes), sig1Bytes, sizeof(sig1Bytes), reinterpret_cast<void *>(nativeGetSecret)},
        {fn2Bytes, sizeof(fn2Bytes), sig2Bytes, sizeof(sig2Bytes), reinterpret_cast<void *>(nativeComputeSilkWaves)},
        {fn3Bytes, sizeof(fn3Bytes), sig3Bytes, sizeof(sig3Bytes), reinterpret_cast<void *>(nativeEvaluateMath)},
        {fn4Bytes, sizeof(fn4Bytes), sig4Bytes, sizeof(sig4Bytes), reinterpret_cast<void *>(nativeParseOtp)},
        {fn6Bytes, sizeof(fn6Bytes), sig6Bytes, sizeof(sig6Bytes), reinterpret_cast<void *>(nativeLmInit)},
        {fn7Bytes, sizeof(fn7Bytes), sig7Bytes, sizeof(sig7Bytes), reinterpret_cast<void *>(nativeLmIsReady)},
        {fn8Bytes, sizeof(fn8Bytes), sig8Bytes, sizeof(sig8Bytes), reinterpret_cast<void *>(nativeLmGetInfo)},
        {fn9Bytes, sizeof(fn9Bytes), sig9Bytes, sizeof(sig9Bytes), reinterpret_cast<void *>(nativeDictIsReady)},
        {fn10Bytes, sizeof(fn10Bytes), sig10Bytes, sizeof(sig10Bytes), reinterpret_cast<void *>(nativeDictGetInfo)},
        {fn11Bytes, sizeof(fn11Bytes), sig11Bytes, sizeof(sig11Bytes), reinterpret_cast<void *>(nativeDictQueryPinyin)},
        {fn12Bytes, sizeof(fn12Bytes), sig12Bytes, sizeof(sig12Bytes), reinterpret_cast<void *>(nativeLmRerank)},
        {fn13Bytes, sizeof(fn13Bytes), sig13Bytes, sizeof(sig13Bytes), reinterpret_cast<void *>(nativeLmPredictNext)},
        {fn14Bytes, sizeof(fn14Bytes), sig14Bytes, sizeof(sig14Bytes), reinterpret_cast<void *>(nativeLmRecordCommit)},
        {fn15Bytes, sizeof(fn15Bytes), sig15Bytes, sizeof(sig15Bytes), reinterpret_cast<void *>(nativeLmRollbackCommit)},
        {fn16Bytes, sizeof(fn16Bytes), sig16Bytes, sizeof(sig16Bytes), reinterpret_cast<void *>(nativeLmClearUserMemory)},
        {fn17Bytes, sizeof(fn17Bytes), sig17Bytes, sizeof(sig17Bytes), reinterpret_cast<void *>(nativeLmSetLongPhraseAggressive)},
        {fn18Bytes, sizeof(fn18Bytes), sig18Bytes, sizeof(sig18Bytes), reinterpret_cast<void *>(nativeLmSync)},
        {fn19Bytes, sizeof(fn19Bytes), sig19Bytes, sizeof(sig19Bytes), reinterpret_cast<void *>(nativeLmDecodeSentence)},
        {fn20Bytes, sizeof(fn20Bytes), sig20Bytes, sizeof(sig20Bytes), reinterpret_cast<void *>(nativeCheckSecurityStatus)},
        {fn21Bytes, sizeof(fn21Bytes), sig21Bytes, sizeof(sig21Bytes), reinterpret_cast<void *>(nativeLmSetQuanpinMode)},
        {fn22Bytes, sizeof(fn22Bytes), sig22Bytes, sizeof(sig22Bytes), reinterpret_cast<void *>(nativeLmQueryUserCandidates)},
        {fn23Bytes, sizeof(fn23Bytes), sig23Bytes, sizeof(sig23Bytes), reinterpret_cast<void *>(nativeLmExportUserMemoryTsv)},
        {fn24Bytes, sizeof(fn24Bytes), sig24Bytes, sizeof(sig24Bytes), reinterpret_cast<void *>(nativeLmImportUserMemoryTsv)},
        {fn25Bytes, sizeof(fn25Bytes), sig25Bytes, sizeof(sig25Bytes), reinterpret_cast<void *>(nativeLmAddCustomPhrase)},
        {fn26Bytes, sizeof(fn26Bytes), sig26Bytes, sizeof(sig26Bytes), reinterpret_cast<void *>(nativeLmRemoveUserEntry)},
        {fn27Bytes, sizeof(fn27Bytes), sig27Bytes, sizeof(sig27Bytes), reinterpret_cast<void *>(nativeTruncateSentence)},
        {fn28Bytes, sizeof(fn28Bytes), sig28Bytes, sizeof(sig28Bytes), reinterpret_cast<void *>(nativeContainsChinese)},
        {fn29Bytes, sizeof(fn29Bytes), sig29Bytes, sizeof(sig29Bytes), reinterpret_cast<void *>(nativeDictQueryWordPrefix)},
        {fn30Bytes, sizeof(fn30Bytes), sig30Bytes, sizeof(sig30Bytes), reinterpret_cast<void *>(nativeIsValidPinyin)},
    };

    constexpr size_t count = sizeof(entries) / sizeof(entries[0]);
    char fnBuffers[count][64];
    char sigBuffers[count][128];
    JNINativeMethod methods[count];

    for (size_t i = 0; i < count; ++i) {
        for (size_t j = 0; j < entries[i].nameLen; ++j) {
            fnBuffers[i][j] = static_cast<char>(entries[i].nameBytes[j] ^ XOR_KEY);
        }
        fnBuffers[i][entries[i].nameLen] = '\0';

        for (size_t j = 0; j < entries[i].sigLen; ++j) {
            sigBuffers[i][j] = static_cast<char>(entries[i].sigBytes[j] ^ XOR_KEY);
        }
        sigBuffers[i][entries[i].sigLen] = '\0';

        methods[i].name = fnBuffers[i];
        methods[i].signature = sigBuffers[i];
        methods[i].fnPtr = entries[i].fnPtr;
    }

    if (env->RegisterNatives(clazz, methods, static_cast<jint>(count)) < 0) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}