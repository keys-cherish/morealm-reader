// edge_tts_auth.cpp —— Sec-MS-GEC token 生成。
//
// 算法（与 Kotlin 实现位级一致）：
//   unixSecs = nowMillis / 1000
//   winSecs  = unixSecs + WIN_EPOCH_OFFSET (11_644_473_600)
//   rounded  = winSecs - (winSecs % 300)            // 5 分钟窗口对齐
//   ticks    = rounded * 10_000_000                 // 100ns 单位
//   payload  = ascii(ticks) + TRUSTED_TOKEN
//   token    = sha256(payload) → 64 字符大写 hex
//
// Token 字面量在编译期用 xor 0x5A 编码，避免 `strings` 静态扫描命中。
//
// SHA-256 自实现（FIPS 180-4），不引入 BoringSSL / mbedTLS，避免 .so 里出现
// `EVP_*` `HMAC_*` 这类标志性导入符号触发外部分析者的关注。

#include <jni.h>

#include <cstdint>
#include <cstring>

namespace {

// ─────────────────────────────────────────────────────────────────────
// Token —— xor 编码字节存 .rodata，解码 key 通过 volatile 间接得到
// 让编译器无法 constant-fold 出明文常量到只读段。
//
// 原文长度 32 + null = 33 字节；下面 33 元素分别为 plain ^ 0x5A。
// ─────────────────────────────────────────────────────────────────────

// volatile 让编译器把每次取值视作可能变化 —— -O3 不再把 xor 表达式
// constant-fold 出 plain text 放到 .rodata。
volatile unsigned char gXorKey = 0x5A;

// 必须不是 constexpr / static —— 若 constexpr，LLVM 会把整段解码循环
// unroll + fold 成 plain text const，泄露到 .rodata。
const unsigned char kTokenEnc[33] = {
    0x6C, 0x1B, 0x6F, 0x1B, 0x1B, 0x6B, 0x1E, 0x6E,
    0x1F, 0x1B, 0x1C, 0x1C, 0x6E, 0x1F, 0x63, 0x1C,
    0x18, 0x69, 0x6D, 0x1F, 0x68, 0x69, 0x1E, 0x6C,
    0x62, 0x6E, 0x63, 0x6B, 0x1E, 0x6C, 0x1C, 0x6E,
    0x5A,
};

void decodeToken(char out[33]) {
    // 每次读 volatile gXorKey 让 -O3 无法证明它是常量 —— 整个解码过程
    // 被编译为真正运行时循环，没有 plain text 落到 .rodata。
    unsigned char k = gXorKey;
    for (size_t i = 0; i < 33; ++i) {
        out[i] = static_cast<char>(kTokenEnc[i] ^ k);
    }
}

// ─────────────────────────────────────────────────────────────────────
// SHA-256 (FIPS 180-4) —— 紧凑自实现，仅满足本文件需要的 one-shot 用法。
// ─────────────────────────────────────────────────────────────────────

inline uint32_t rotr(uint32_t x, uint32_t n) {
    return (x >> n) | (x << (32 - n));
}

const uint32_t K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
};

void sha256_compress(uint32_t state[8], const uint8_t block[64]) {
    uint32_t w[64];
    for (int i = 0; i < 16; ++i) {
        w[i] = (static_cast<uint32_t>(block[i * 4]) << 24) |
               (static_cast<uint32_t>(block[i * 4 + 1]) << 16) |
               (static_cast<uint32_t>(block[i * 4 + 2]) << 8) |
               (static_cast<uint32_t>(block[i * 4 + 3]));
    }
    for (int i = 16; i < 64; ++i) {
        uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }

    uint32_t a = state[0], b = state[1], c = state[2], d = state[3];
    uint32_t e = state[4], f = state[5], g = state[6], h = state[7];

    for (int i = 0; i < 64; ++i) {
        uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t temp1 = h + S1 + ch + K[i] + w[i];
        uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        uint32_t mj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t temp2 = S0 + mj;
        h = g; g = f; f = e;
        e = d + temp1;
        d = c; c = b; b = a;
        a = temp1 + temp2;
    }

    state[0] += a; state[1] += b; state[2] += c; state[3] += d;
    state[4] += e; state[5] += f; state[6] += g; state[7] += h;
}

void sha256(const uint8_t* data, size_t len, uint8_t out[32]) {
    uint32_t state[8] = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    };

    uint8_t block[64];
    size_t pos = 0;
    uint64_t total_bits = static_cast<uint64_t>(len) * 8ULL;

    while (len - pos >= 64) {
        sha256_compress(state, data + pos);
        pos += 64;
    }

    size_t tail = len - pos;
    memcpy(block, data + pos, tail);
    block[tail] = 0x80;
    if (tail + 1 > 56) {
        memset(block + tail + 1, 0, 64 - tail - 1);
        sha256_compress(state, block);
        memset(block, 0, 56);
    } else {
        memset(block + tail + 1, 0, 56 - tail - 1);
    }
    for (int i = 0; i < 8; ++i) {
        block[56 + i] = static_cast<uint8_t>((total_bits >> (56 - 8 * i)) & 0xFF);
    }
    sha256_compress(state, block);

    for (int i = 0; i < 8; ++i) {
        out[i * 4]     = static_cast<uint8_t>((state[i] >> 24) & 0xFF);
        out[i * 4 + 1] = static_cast<uint8_t>((state[i] >> 16) & 0xFF);
        out[i * 4 + 2] = static_cast<uint8_t>((state[i] >> 8) & 0xFF);
        out[i * 4 + 3] = static_cast<uint8_t>(state[i] & 0xFF);
    }
}

// ─────────────────────────────────────────────────────────────────────
// 时间窗口 → ticks 字符串 → SHA-256 → 大写 hex
// ─────────────────────────────────────────────────────────────────────

constexpr int64_t WIN_EPOCH_OFFSET_SECONDS = 11644473600LL;
constexpr int64_t ROUND_WINDOW_SECONDS = 300LL;
constexpr int64_t TICKS_PER_SECOND = 10000000LL;

// itoa for non-negative int64 → ascii。返回写入字符数（不含 null）。
// 调用方保证 buf 至少 24 字节（Int64 max 20 位 + 余量）。
size_t int64_to_ascii(int64_t v, char buf[24]) {
    if (v == 0) {
        buf[0] = '0';
        buf[1] = 0;
        return 1;
    }
    char tmp[24];
    size_t n = 0;
    while (v > 0) {
        tmp[n++] = static_cast<char>('0' + (v % 10));
        v /= 10;
    }
    for (size_t i = 0; i < n; ++i) {
        buf[i] = tmp[n - 1 - i];
    }
    buf[n] = 0;
    return n;
}

void bytes_to_upper_hex(const uint8_t bytes[32], char out[65]) {
    static const char HEX[] = "0123456789ABCDEF";
    for (int i = 0; i < 32; ++i) {
        out[i * 2]     = HEX[(bytes[i] >> 4) & 0x0F];
        out[i * 2 + 1] = HEX[bytes[i] & 0x0F];
    }
    out[64] = 0;
}

}  // namespace

// jni_onload.cpp 内 forward decl 同名引用 —— symbols.ver 限定为 .so 内 local，
// .so 二进制内仍看不到此函数名。
jstring morealm_op_generate_sec_ms_gec(JNIEnv* env, jclass /*clazz*/, jlong now_millis) {
    int64_t unix_secs = static_cast<int64_t>(now_millis) / 1000LL;
    int64_t win_secs = unix_secs + WIN_EPOCH_OFFSET_SECONDS;
    int64_t rounded = win_secs - (win_secs % ROUND_WINDOW_SECONDS);
    int64_t ticks = rounded * TICKS_PER_SECOND;

    // payload = ascii(ticks) + decoded_token。栈上拼接，避免堆分配。
    char ticks_buf[24];
    size_t ticks_len = int64_to_ascii(ticks, ticks_buf);

    char token_buf[33];
    decodeToken(token_buf);
    size_t token_len = 32;  // 编码原文 32 字符 + null

    // 最大长度 = 20 (ticks) + 32 (token) = 52；按 64 给余量。
    uint8_t payload[64];
    memcpy(payload, ticks_buf, ticks_len);
    memcpy(payload + ticks_len, token_buf, token_len);
    size_t payload_len = ticks_len + token_len;

    uint8_t digest[32];
    sha256(payload, payload_len, digest);

    // 立即清理栈上 token 副本，缩短运行时内存暴露窗口。
    memset(token_buf, 0, sizeof(token_buf));
    memset(payload, 0, sizeof(payload));

    char hex[65];
    bytes_to_upper_hex(digest, hex);

    return env->NewStringUTF(hex);
}

// Trusted client identifier accessor —— 返回 32 字符大写 hex。
// 每次调用都从 obfuscated 表解码一次,不在 .so 内持久存明文。
jstring morealm_op_trusted_client_identifier(JNIEnv* env, jclass /*clazz*/) {
    char token_buf[33];
    decodeToken(token_buf);
    jstring result = env->NewStringUTF(token_buf);
    memset(token_buf, 0, sizeof(token_buf));
    return result;
}
