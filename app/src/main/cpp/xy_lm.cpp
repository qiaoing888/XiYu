#include "xy_lm.h"
#include "darts.h"

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <unordered_map>
#include <unordered_set>
#include <vector>

extern "C" {
    extern const uint8_t _binary_xy_embedded_bin_start[];
    extern const uint8_t _binary_xy_embedded_bin_end[];
}

namespace xy {

namespace {

// 频次 >= 1000 视为用户手动添加的快捷短语，不参与时间衰减
constexpr int CUSTOM_PHRASE_BASE_FREQ = 1000;
constexpr size_t MAX_BUCKET_ENTRIES = 24;
constexpr size_t MAX_RECORD_BUCKET_ENTRIES = 16;
constexpr size_t MAX_MAP_KEYS = 5000;

struct UserMemoryEntry {
    std::string word;
    int frequency;
    int64_t last_used_timestamp;
};

static std::mutex g_lm_mutex;
static Darts::DoubleArray g_lm_trie;
static int g_mmap_fd = -1;
static void *g_mmap_addr = nullptr;
static size_t g_mmap_size = 0;
static bool g_lm_loaded = false;
static std::atomic<bool> g_lm_is_embedded{false};
static std::string g_lm_path;
static uint32_t g_lm_da_size = 0;

#pragma pack(push, 1)
struct EmbHeader {
    char magic[8];             // "XYEMB001"
    uint32_t version;          // 1
    uint32_t total_size;
    uint32_t lm_offset;
    uint32_t lm_units;
    uint32_t dict_py_offset;
    uint32_t dict_py_units;
    uint32_t dict_word_offset;
    uint32_t dict_word_units;
    uint32_t dict_data_offset;
    uint32_t dict_data_size;
    uint32_t reserved[2];
};
#pragma pack(pop)

struct DictHeader {
    char magic[8];             // "XYDICT01"
    uint32_t version;          // 1
    uint32_t word_count;
    uint32_t pinyin_count;
    uint32_t py_trie_offset;
    uint32_t py_trie_units;
    uint32_t word_trie_offset;
    uint32_t word_trie_units;
    uint32_t data_offset;
    uint32_t data_size;
    uint32_t reserved[6];
};

// 词典分成两层：内置 core（嵌入式 .so 的 .rodata）+ 可选 ext（filesDir/xy_dict.bin）
// 查询时先 core 后 ext，按权重合并；ext 未加载时保持旧的免锁读取路径。
struct DictLayer {
    Darts::DoubleArray py_trie;
    Darts::DoubleArray word_trie;
    int fd = -1;
    void *mmap_addr = nullptr;
    size_t mmap_size = 0;
    const uint8_t *data_blob = nullptr;
    size_t data_size = 0;
    bool loaded = false;
    bool embedded = false;
    std::string path;
    uint32_t word_count = 0;
    uint32_t py_count = 0;
};

static std::mutex g_dict_mutex;
static DictLayer g_dict_core;
static DictLayer g_dict_ext;
// true 表示没有外置层，查询可以免锁；加载 ext 后置 false，回到加锁路径
static std::atomic<bool> g_dict_is_embedded{true};

static inline bool dict_any_loaded() {
    return g_dict_core.loaded || g_dict_ext.loaded;
}

static void unload_dict_layer(DictLayer &layer) {
    if (layer.mmap_addr) {
        munmap(layer.mmap_addr, layer.mmap_size);
    }
    if (layer.fd >= 0) {
        close(layer.fd);
    }
    layer.fd = -1;
    layer.mmap_addr = nullptr;
    layer.mmap_size = 0;
    layer.data_blob = nullptr;
    layer.data_size = 0;
    layer.loaded = false;
    layer.embedded = false;
    layer.path.clear();
    layer.word_count = 0;
    layer.py_count = 0;
    // Darts::DoubleArray 不可拷贝赋值；loaded=false 后不会再解引用旧指针，
    // 下次加载必须重新 set_array（内置/外置加载路径都会这么做）。
}

static std::mutex g_user_mem_mutex;
static std::unordered_map<std::string, std::vector<UserMemoryEntry>> g_user_memory;
static std::unordered_map<std::string, std::vector<UserMemoryEntry>> g_user_pinyin_memory;
static std::unordered_map<std::string, int64_t> g_suppressed_words;
static std::string g_storage_path;

static std::atomic<bool> g_quanpin_mode{true};
static std::atomic<bool> g_aggressive_long_phrase{true};

inline std::string clean_pinyin_string(const char *pinyin) {
    std::string s;
    if (!pinyin) return s;
    for (const char *p = pinyin; *p; ++p) {
        unsigned char c = static_cast<unsigned char>(*p);
        if (c > 0x20 && c < 0x7F && c != '\'' && c != '-') {
            s.push_back(static_cast<char>(std::tolower(c)));
        }
    }
    return s;
}

inline std::string trim_whitespace(const std::string &str) {
    size_t first = str.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return "";
    size_t last = str.find_last_not_of(" \t\r\n");
    return str.substr(first, last - first + 1);
}

inline uint32_t next_utf8_codepoint(const char *&p, const char *end) {
    if (p >= end) return 0;
    uint8_t c = static_cast<uint8_t>(*p++);
    if (c < 0x80) return c;
    if ((c & 0xE0) == 0xC0) {
        if (p >= end) return c;
        uint8_t c2 = static_cast<uint8_t>(*p++);
        return ((c & 0x1F) << 6) | (c2 & 0x3F);
    }
    if ((c & 0xF0) == 0xE0) {
        if (p + 1 >= end) {
            p = end;
            return c;
        }
        uint8_t c2 = static_cast<uint8_t>(*p++);
        uint8_t c3 = static_cast<uint8_t>(*p++);
        return ((c & 0x0F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F);
    }
    if ((c & 0xF8) == 0xF0) {
        if (p + 2 >= end) {
            p = end;
            return c;
        }
        uint8_t c2 = static_cast<uint8_t>(*p++);
        uint8_t c3 = static_cast<uint8_t>(*p++);
        uint8_t c4 = static_cast<uint8_t>(*p++);
        return ((c & 0x07) << 18) | ((c2 & 0x3F) << 12) | ((c3 & 0x3F) << 6) | (c4 & 0x3F);
    }
    return c;
}

inline void append_codepoint_to_utf8(std::string &out, uint32_t cp) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

std::string encode_octagram(const std::string &utf8_str) {
    char buf[128];
    char *e = buf;
    char *buf_end = buf + sizeof(buf) - 8;
    std::string overflow;

    const char *p = utf8_str.data();
    const char *end = p + utf8_str.size();

    while (p < end) {
        if (e >= buf_end) {
            overflow.append(buf, e - buf);
            e = buf;
        }

        uint32_t u = next_utf8_codepoint(p, end);
        if (u < 0x80) {
            *e++ = (u == 0) ? static_cast<char>(0xE0) : static_cast<char>(u);
        } else if (u >= 0x4000 && u < 0xA000) {
            if ((u & 0xFF) == 0) {
                *e++ = static_cast<char>(0xE1);
                *e++ = static_cast<char>((u >> 8) + 0x40);
            } else {
                *e++ = static_cast<char>((u >> 8) + 0x40);
                *e++ = static_cast<char>(u & 0xFF);
            }
        } else {
            int bits = 32;
            uint32_t u_tmp = u;
            while (bits > 0 && (u_tmp & 0xFE000000) == 0) {
                bits -= 7;
                u_tmp <<= 7;
            }
            int bytes_to_encode = (bits + 6) / 7;
            *e++ = static_cast<char>(0xE0 | bytes_to_encode);
            while (bytes_to_encode > 0) {
                --bytes_to_encode;
                *e++ = static_cast<char>(((u_tmp >> 25) & 0x7F) | 0x80);
                u_tmp <<= 7;
            }
        }
    }

    if (!overflow.empty()) {
        overflow.append(buf, e - buf);
        return overflow;
    }
    return std::string(buf, e - buf);
}

std::string decode_octagram(const std::string &enc_bytes) {
    std::string out;
    size_t offset = 0;
    size_t len = enc_bytes.size();
    const uint8_t *data = reinterpret_cast<const uint8_t *>(enc_bytes.data());
    while (offset < len) {
        uint8_t b0 = data[offset];
        if (b0 < 0x80) {
            append_codepoint_to_utf8(out, b0);
            offset += 1;
        } else if ((b0 & 0xF0) == 0xE0) {
            uint8_t count = (b0 & 0x0F);
            if (count == 0) {
                append_codepoint_to_utf8(out, 0);
                offset += 1;
            } else if (count == 1 && offset + 1 < len) {
                uint32_t u = (static_cast<uint32_t>(data[offset + 1]) - 0x40) << 8;
                append_codepoint_to_utf8(out, u);
                offset += 2;
            } else if (offset + count < len) {
                uint32_t val = 0;
                for (size_t i = 1; i <= count; ++i) {
                    val = (val << 7) | (data[offset + i] & 0x7F);
                }
                append_codepoint_to_utf8(out, val >> 3);
                offset += count + 1;
            } else {
                break;
            }
        } else if (offset + 1 < len) {
            uint8_t b1 = data[offset + 1];
            uint32_t u = ((static_cast<uint32_t>(b0) - 0x40) << 8) | b1;
            append_codepoint_to_utf8(out, u);
            offset += 2;
        } else {
            break;
        }
    }
    return out;
}

int64_t current_time_millis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()
    ).count();
}

// 判定分句边界字符（保留 '_' 供测试键与标识符使用）
inline bool is_boundary_codepoint(uint32_t cp) {
    if (cp <= 0x20 || cp == 0x00A0 || cp == 0x3000) return true;
    if (cp >= 0x21 && cp <= 0x2F) return true;
    if (cp >= 0x3A && cp <= 0x40) return true;
    if (cp >= 0x5B && cp <= 0x60 && cp != '_') return true;
    if (cp >= 0x7B && cp <= 0x7E) return true;
    if (cp >= 0x2000 && cp <= 0x206F) return true;
    if (cp >= 0x3000 && cp <= 0x303F) return true;
    if (cp >= 0xFF00 && cp <= 0xFF0F) return true;
    if (cp >= 0xFF1A && cp <= 0xFF20) return true;
    if (cp >= 0xFF3B && cp <= 0xFF40) return true;
    if (cp >= 0xFF5B && cp <= 0xFF65) return true;
    return false;
}

inline size_t utf8_char_length(const char *s) {
    if (!s) return 0;
    size_t count = 0;
    while (*s) {
        if ((static_cast<unsigned char>(*s) & 0xC0) != 0x80) {
            count++;
        }
        s++;
    }
    return count;
}

inline bool has_non_ascii_chars(const std::string &s) {
    for (unsigned char c : s) {
        if (c >= 0x80) return true;
    }
    return false;
}

// 清理上下文触发键首尾的空白与分句标点
std::string sanitize_context_key(const std::string &raw) {
    const char *p = raw.data();
    const char *end = p + raw.size();
    size_t first_valid = std::string::npos;
    size_t last_valid_end = 0;

    while (p < end) {
        size_t pos = static_cast<size_t>(p - raw.data());
        uint32_t cp = next_utf8_codepoint(p, end);
        size_t next_pos = static_cast<size_t>(p - raw.data());
        if (!is_boundary_codepoint(cp)) {
            if (first_valid == std::string::npos) {
                first_valid = pos;
            }
            last_valid_end = next_pos;
        }
    }
    if (first_valid == std::string::npos || last_valid_end <= first_valid) {
        return "";
    }
    return raw.substr(first_valid, last_valid_end - first_valid);
}

// 提取当前分句末尾的字级后缀片段（从长到短排列）
std::vector<std::string> extract_context_tails(const std::string &ctx, size_t max_char_count = 4) {
    std::vector<std::string> tails;
    if (ctx.empty() || max_char_count == 0) return tails;

    size_t last_boundary_end = 0;
    const char *p = ctx.data();
    const char *end = p + ctx.size();
    while (p < end) {
        uint32_t cp = next_utf8_codepoint(p, end);
        if (is_boundary_codepoint(cp)) {
            last_boundary_end = static_cast<size_t>(p - ctx.data());
        }
    }

    if (last_boundary_end >= ctx.size()) {
        return tails;
    }

    std::string seg = ctx.substr(last_boundary_end);
    std::vector<size_t> char_indices;
    p = seg.data();
    end = p + seg.size();
    while (p < end) {
        char_indices.push_back(static_cast<size_t>(p - seg.data()));
        next_utf8_codepoint(p, end);
    }
    char_indices.push_back(seg.size());

    size_t total_chars = char_indices.size() - 1;
    size_t take = std::min(total_chars, max_char_count);

    for (size_t len = take; len >= 1; --len) {
        size_t start_byte = char_indices[total_chars - len];
        tails.push_back(seg.substr(start_byte));
    }

    return tails;
}

bool load_embedded_model_locked() {
    const uint8_t *ptr = _binary_xy_embedded_bin_start;
    const uint8_t *end = _binary_xy_embedded_bin_end;
    if (!ptr || !end || end <= ptr) return false;

    size_t emb_size = static_cast<size_t>(end - ptr);
    if (emb_size < sizeof(EmbHeader)) return false;
    if (std::memcmp(ptr, "XYEMB001", 8) != 0) return false;

    const EmbHeader *hdr = reinterpret_cast<const EmbHeader *>(ptr);
    if (hdr->version != 1) return false;

    if (hdr->lm_offset > 0 && hdr->lm_units > 0 &&
        static_cast<size_t>(hdr->lm_offset) + static_cast<size_t>(hdr->lm_units) * 4 <= emb_size) {
        g_lm_trie.set_array(ptr + hdr->lm_offset, hdr->lm_units);
        g_lm_loaded = true;
        g_lm_is_embedded.store(true, std::memory_order_relaxed);
        g_lm_path = "[内置核心引擎]";
        g_lm_da_size = hdr->lm_units;
    }

    if (hdr->dict_py_offset > 0 && hdr->dict_py_units > 0 &&
        hdr->dict_word_offset > 0 && hdr->dict_word_units > 0 &&
        hdr->dict_data_offset > 0 && hdr->dict_data_size > 0 &&
        static_cast<size_t>(hdr->dict_py_offset) + static_cast<size_t>(hdr->dict_py_units) * 4 <= emb_size &&
        static_cast<size_t>(hdr->dict_word_offset) + static_cast<size_t>(hdr->dict_word_units) * 4 <= emb_size &&
        static_cast<size_t>(hdr->dict_data_offset) + static_cast<size_t>(hdr->dict_data_size) <= emb_size) {
        // 内置层直接指向 .rodata，不拥有 mmap
        unload_dict_layer(g_dict_core);
        g_dict_core.py_trie.set_array(ptr + hdr->dict_py_offset, hdr->dict_py_units);
        g_dict_core.word_trie.set_array(ptr + hdr->dict_word_offset, hdr->dict_word_units);
        g_dict_core.data_blob = ptr + hdr->dict_data_offset;
        g_dict_core.data_size = hdr->dict_data_size;
        g_dict_core.loaded = true;
        g_dict_core.embedded = true;
        g_dict_core.path = "[内置只读词库]";
        // reserved[0]/[1] 为真实词条数与拼音键数，缺失时回退 trie 单元数
        g_dict_core.word_count = hdr->reserved[0] > 0 ? hdr->reserved[0] : hdr->dict_word_units;
        g_dict_core.py_count = hdr->reserved[1] > 0 ? hdr->reserved[1] : hdr->dict_py_units;
    }

    return true;
}

bool load_lm_file_locked(const std::string &file_path) {
    if (file_path.empty()) return false;
    if (g_lm_loaded && g_lm_path == file_path) {
        return true;
    }

    int fd = open(file_path.c_str(), O_RDONLY);
    if (fd < 0) return false;

    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < 44) {
        close(fd);
        return false;
    }

    size_t file_size = static_cast<size_t>(st.st_size);
    void *mapped = mmap(nullptr, file_size, PROT_READ, MAP_SHARED, fd, 0);
    if (mapped == MAP_FAILED) {
        close(fd);
        return false;
    }

    const char *ptr = static_cast<const char *>(mapped);
    if (std::strncmp(ptr, "Rime::Grammar/", 14) != 0) {
        munmap(mapped, file_size);
        close(fd);
        return false;
    }

    uint32_t da_size = *reinterpret_cast<const uint32_t *>(ptr + 36);
    int32_t offset_rel = *reinterpret_cast<const int32_t *>(ptr + 40);
    if (offset_rel < -40) {
        munmap(mapped, file_size);
        close(fd);
        return false;
    }
    size_t expected_offset = static_cast<size_t>(40 + offset_rel);

    // 拒绝零长度与未对齐的 trie 数据
    if (da_size == 0 || expected_offset < 44 || (expected_offset & 3) != 0 ||
        expected_offset + static_cast<size_t>(da_size) * 4 > file_size) {
        munmap(mapped, file_size);
        close(fd);
        return false;
    }

    if (g_mmap_addr) {
        munmap(g_mmap_addr, g_mmap_size);
        g_mmap_addr = nullptr;
    }
    if (g_mmap_fd >= 0) {
        close(g_mmap_fd);
        g_mmap_fd = -1;
    }

    g_mmap_fd = fd;
    g_mmap_addr = mapped;
    g_mmap_size = file_size;
    g_lm_path = file_path;
    g_lm_da_size = da_size;

    g_lm_trie.set_array(ptr + expected_offset, da_size);
    g_lm_loaded = true;
    g_lm_is_embedded.store(false, std::memory_order_relaxed);

    return true;
}

bool load_dict_file_locked(const std::string &file_path, DictLayer &layer) {
    if (file_path.empty()) return false;
    if (layer.loaded && !layer.embedded && layer.path == file_path) {
        return true;
    }

    int fd = open(file_path.c_str(), O_RDONLY);
    if (fd < 0) return false;

    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < static_cast<off_t>(sizeof(DictHeader))) {
        close(fd);
        return false;
    }

    size_t file_size = static_cast<size_t>(st.st_size);
    void *mapped = mmap(nullptr, file_size, PROT_READ, MAP_SHARED, fd, 0);
    if (mapped == MAP_FAILED) {
        close(fd);
        return false;
    }

    const char *ptr = static_cast<const char *>(mapped);
    if (std::memcmp(ptr, "XYDICT01", 8) != 0) {
        munmap(mapped, file_size);
        close(fd);
        return false;
    }

    if (file_size < sizeof(DictHeader)) {
        munmap(mapped, file_size);
        close(fd);
        return false;
    }
    const DictHeader *header = reinterpret_cast<const DictHeader *>(ptr);
    if (header->version != 1) {
        munmap(mapped, file_size);
        close(fd);
        return false;
    }
    size_t py_off = header->py_trie_offset;
    size_t py_units = header->py_trie_units;
    size_t word_off = header->word_trie_offset;
    size_t word_units = header->word_trie_units;
    size_t data_off = header->data_offset;
    size_t data_size = header->data_size;
    // 拒绝零长度、越界与未对齐的 section，避免损坏文件导致越界读
    if (py_units == 0 || word_units == 0 || data_size == 0 ||
        (py_off & 3) || (word_off & 3) || (data_off & 3) ||
        py_off < sizeof(DictHeader) || word_off < sizeof(DictHeader) || data_off < sizeof(DictHeader) ||
        py_off + py_units * 4 > file_size ||
        word_off + word_units * 4 > file_size ||
        data_off + data_size > file_size) {
        munmap(mapped, file_size);
        close(fd);
        return false;
    }

    // 校验通过后再替换旧层，避免损坏文件把可用词库清掉
    unload_dict_layer(layer);
    layer.fd = fd;
    layer.mmap_addr = mapped;
    layer.mmap_size = file_size;
    layer.path = file_path;
    layer.word_count = header->word_count;
    layer.py_count = header->pinyin_count;
    layer.py_trie.set_array(ptr + header->py_trie_offset, header->py_trie_units);
    layer.word_trie.set_array(ptr + header->word_trie_offset, header->word_trie_units);
    layer.data_blob = reinterpret_cast<const uint8_t *>(ptr + header->data_offset);
    layer.data_size = header->data_size;
    layer.loaded = true;
    layer.embedded = false;

    return true;
}

struct PrecomputedTail {
    size_t node_pos;
    float tail_weight;
};

std::vector<PrecomputedTail> prepare_lm_tails(const std::vector<std::string> &tails) {
    std::vector<PrecomputedTail> out;
    if (!g_lm_loaded || tails.empty()) return out;
    out.reserve(tails.size());

    for (size_t t = 0; t < tails.size(); ++t) {
        std::string enc_tail = encode_octagram(tails[t]);
        if (enc_tail.empty()) continue;

        size_t node_pos = 0;
        size_t key_pos = 0;
        g_lm_trie.traverse(enc_tail.c_str(), node_pos, key_pos, enc_tail.length());
        if (key_pos == enc_tail.length()) {
            float tail_weight = 1.0f + 0.15f * static_cast<float>(tails.size() - t);
            out.push_back({node_pos, tail_weight});
        }
    }
    return out;
}

float query_lm_ngram_prepared(const std::vector<PrecomputedTail> &prep_tails, const std::string &cand) {
    if (!g_lm_loaded || prep_tails.empty() || cand.empty()) return 0.0f;

    std::string enc_cand = encode_octagram(cand);
    if (enc_cand.empty()) return 0.0f;
    // 无匹配转移时只做轻度惩罚：过重的惩罚会让无历史上下文的生僻路径反超
    float best_score = -5.0f;

    size_t cand_len = utf8_char_length(cand.c_str());

    for (const auto &pt : prep_tails) {
        Darts::DoubleArray::result_pair_type results[8];
        size_t n = g_lm_trie.commonPrefixSearch(enc_cand.c_str(), results, 8, enc_cand.length(), pt.node_pos);

        for (size_t i = 0; i < n && i < 8; ++i) {
            float raw_scaled = static_cast<float>(results[i].value) / 10000.0f;
            if (results[i].length == enc_cand.length()) {
                raw_scaled += 2.5f;
                if (cand_len > 2) {
                    raw_scaled += static_cast<float>(cand_len - 2) * 0.4f;
                }
            }
            float score = raw_scaled * pt.tail_weight;
            if (score > best_score) {
                best_score = score;
            }
        }
    }
    return best_score;
}

float query_dict_word_prior(const std::string &word) {
    if (word.empty() || !dict_any_loaded()) return 0.0f;
    int wt = 0;
    if (g_dict_core.loaded) {
        wt = g_dict_core.word_trie.exactMatchSearch<int>(word.c_str(), word.length());
    }
    if (g_dict_ext.loaded) {
        int ext_wt = g_dict_ext.word_trie.exactMatchSearch<int>(word.c_str(), word.length());
        if (ext_wt > wt) wt = ext_wt;
    }
    if (wt > 0) {
        return std::log10(static_cast<float>(wt)) * 1.5f;
    }
    return 0.0f;
}

struct NextWordScore {
    std::string word;
    int value;
};

void dfs_next_words(
    const Darts::DoubleArray &trie,
    size_t node_pos,
    std::string &current_bytes,
    int depth,
    int max_depth,
    int &visited_count,
    int max_visited,
    std::vector<NextWordScore> &candidates
) {
    if (depth > max_depth || visited_count >= max_visited) return;
    visited_count++;

    const auto *array_units = static_cast<const Darts::Details::DoubleArrayUnit *>(trie.array());
    auto unit = array_units[node_pos];

    if (!current_bytes.empty() && unit.has_leaf()) {
        size_t leaf_pos = node_pos ^ unit.offset();
        if (leaf_pos < trie.size()) {
            auto leaf_unit = array_units[leaf_pos];
            int val = leaf_unit.value();
            std::string dec = decode_octagram(current_bytes);
            if (!dec.empty()) {
                candidates.push_back({dec, val});
            }
        }
    }

    if (depth == max_depth) return;

    for (int b = 0; b < 256; ++b) {
        size_t next_pos = node_pos ^ unit.offset() ^ static_cast<unsigned int>(b);
        if (next_pos < trie.size()) {
            auto next_unit = array_units[next_pos];
            if (next_unit.label() == static_cast<unsigned int>(b)) {
                current_bytes.push_back(static_cast<char>(b));
                dfs_next_words(trie, next_pos, current_bytes, depth + 1, max_depth, visited_count, max_visited, candidates);
                current_bytes.pop_back();
            }
        }
        if (visited_count >= max_visited) break;
    }
}

// 普通话合法拼音音节表（按声母顺序排列）
static const char * const STANDARD_PINYIN_SYLLABLES[] = {
    "a", "ai", "an", "ang", "ao",
    "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng", "bi", "bian", "biao", "bie", "bin", "bing", "bo", "bu",
    "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng", "cha", "chai", "chan", "chang", "chao", "che", "chen",
    "cheng", "chi", "chong", "chou", "chu", "chua", "chuai", "chuan", "chuang", "chui", "chun", "chuo", "ci", "cong", "cou", "cu", "cuan", "cui", "cun", "cuo",
    "da", "dai", "dan", "dang", "dao", "de", "dei", "den", "deng", "di", "dia", "dian", "diao", "die", "ding", "diu", "dong", "dou", "du", "duan", "dui", "dun", "duo",
    "e", "ei", "en", "eng", "er",
    "fa", "fan", "fang", "fei", "fen", "feng", "fiao", "fo", "fou", "fu",
    "ga", "gai", "gan", "gang", "gao", "ge", "gei", "gen", "geng", "gong", "gou", "gu", "gua", "guai", "guan", "guang", "gui", "gun", "guo",
    "ha", "hai", "han", "hang", "hao", "he", "hei", "hen", "heng", "hng", "hong", "hou", "hu", "hua", "huai", "huan", "huang", "hui", "hun", "huo",
    "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiong", "jiu", "ju", "juan", "jue", "jun",
    "ka", "kai", "kan", "kang", "kao", "ke", "kei", "ken", "keng", "kong", "kou", "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo",
    "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia", "lian", "liang", "liao", "lie", "lin", "ling", "liu", "lo", "long", "lou", "lu", "luan", "lue", "lun", "luo", "lv", "lve",
    "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi", "mian", "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu",
    "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni", "nian", "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu", "nuan", "nue", "nuo", "nv", "nve",
    "o", "ou",
    "pa", "pai", "pan", "pang", "pao", "pei", "pen", "peng", "pi", "pian", "piao", "pie", "pin", "ping", "po", "pou", "pu",
    "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu", "qu", "quan", "que", "qun",
    "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru", "rua", "ruan", "rui", "run", "ruo",
    "sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai", "shan", "shang", "shao", "she", "shei", "shen", "sheng", "shi", "shou", "shu", "shua", "shuai", "shuan", "shuang", "shui", "shun", "shuo", "si", "song", "sou", "su", "suan", "sui", "sun", "suo",
    "ta", "tai", "tan", "tang", "tao", "te", "tei", "teng", "ti", "tian", "tiao", "tie", "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo",
    "wa", "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu",
    "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiong", "xiu", "xu", "xuan", "xue", "xun",
    "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying", "yo", "yong", "you", "yu", "yuan", "yue", "yun",
    "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen", "zeng", "zha", "zhai", "zhan", "zhang", "zhao", "zhe", "zhei", "zhen", "zheng", "zhi", "zhong", "zhou", "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun", "zhuo", "zi", "zong", "zou", "zu", "zuan", "zui", "zun", "zuo"
};
static const size_t STANDARD_PINYIN_SYLLABLE_COUNT = sizeof(STANDARD_PINYIN_SYLLABLES) / sizeof(STANDARD_PINYIN_SYLLABLES[0]);

static std::unordered_set<std::string> g_intact_pinyin_set;
static std::unordered_set<std::string> g_prefix_pinyin_set;
static std::once_flag g_pinyin_set_init_flag;

static void ensure_pinyin_sets_initialized() {
    std::call_once(g_pinyin_set_init_flag, []() {
        g_intact_pinyin_set.reserve(STANDARD_PINYIN_SYLLABLE_COUNT + 32);
        for (size_t i = 0; i < STANDARD_PINYIN_SYLLABLE_COUNT; ++i) {
            std::string syl(STANDARD_PINYIN_SYLLABLES[i]);
            g_intact_pinyin_set.insert(syl);
            for (size_t len = 1; len <= syl.size(); ++len) {
                g_prefix_pinyin_set.insert(syl.substr(0, len));
            }
        }
        static const char * const V_VARIANTS[] = {
            "jv", "jvan", "jve", "jvn",
            "qv", "qvan", "qve", "qvn",
            "xv", "xvan", "xve", "xvn",
            "yv", "yvan", "yve", "yvn"
        };
        for (const char *v : V_VARIANTS) {
            std::string syl(v);
            g_intact_pinyin_set.insert(syl);
            for (size_t len = 1; len <= syl.size(); ++len) {
                g_prefix_pinyin_set.insert(syl.substr(0, len));
            }
        }
    });
}

static inline bool is_valid_pinyin_prefix(const char *str, size_t len) {
    if (!str || len == 0 || len > 7) return false;
    ensure_pinyin_sets_initialized();
    return g_prefix_pinyin_set.find(std::string(str, len)) != g_prefix_pinyin_set.end();
}

struct SyllableEdge {
    size_t end_pos;
    std::string syllable;
    bool is_fuzzy;
    bool is_delimiter;
};

struct SyllableGraph {
    std::vector<std::vector<SyllableEdge>> edges;
};

static SyllableGraph build_syllable_graph(const std::string &pinyin, size_t &out_best_end) {
    ensure_pinyin_sets_initialized();
    size_t N = pinyin.size();
    SyllableGraph graph;
    graph.edges.resize(N + 1);
    out_best_end = 0;
    if (N == 0) return graph;

    for (size_t start = 0; start < N; ++start) {
        if (pinyin[start] == '\'') {
            graph.edges[start].push_back({start + 1, "", false, true});
            continue;
        }
        size_t max_len = std::min(static_cast<size_t>(6), N - start);
        for (size_t l = max_len; l >= 1; --l) {
            std::string sub = pinyin.substr(start, l);
            if (sub.find('\'') != std::string::npos) continue;
            if (g_intact_pinyin_set.find(sub) != g_intact_pinyin_set.end()) {
                graph.edges[start].push_back({start + l, sub, false, false});
            }
        }
    }

    std::vector<bool> can_reach(N + 1, false);
    can_reach[0] = true;
    for (size_t i = 0; i < N; ++i) {
        if (!can_reach[i]) continue;
        for (const auto &edge : graph.edges[i]) {
            if (edge.end_pos <= N) {
                can_reach[edge.end_pos] = true;
            }
        }
    }

    size_t best_target = 0;
    for (size_t t = N; t > 0; --t) {
        if (can_reach[t]) {
            best_target = t;
            break;
        }
    }
    out_best_end = best_target;
    if (best_target == 0) return graph;

    std::vector<bool> reaches_end(best_target + 1, false);
    reaches_end[best_target] = true;

    for (size_t start = best_target; start-- > 0;) {
        auto &edge_list = graph.edges[start];
        edge_list.erase(
            std::remove_if(edge_list.begin(), edge_list.end(), [&](const SyllableEdge &e) {
                return e.end_pos > best_target || !reaches_end[e.end_pos];
            }),
            edge_list.end()
        );
        if (!edge_list.empty()) {
            reaches_end[start] = true;
        }
    }

    return graph;
}

struct LatticeSpanPath {
    size_t end_pos;
    std::string combined_py;
    size_t syl_count;
    size_t first_syl_len;
    int fuzzy_count;
};

static void collect_lattice_spans(
    const SyllableGraph &graph,
    size_t cur_pos,
    size_t target_end,
    std::string &cur_py,
    size_t cur_syls,
    size_t first_syl_len,
    int cur_fuzzy,
    size_t max_syls,
    std::vector<LatticeSpanPath> &out_paths
) {
    if (cur_syls >= max_syls || cur_pos >= target_end) return;

    for (const auto &edge : graph.edges[cur_pos]) {
        if (edge.end_pos > target_end) continue;
        if (edge.is_delimiter) {
            collect_lattice_spans(
                graph, edge.end_pos, target_end, cur_py,
                cur_syls, first_syl_len, cur_fuzzy, max_syls, out_paths
            );
        } else {
            size_t old_len = cur_py.size();
            cur_py += edge.syllable;
            size_t next_syls = cur_syls + 1;
            size_t next_first_len = (cur_syls == 0) ? edge.syllable.size() : first_syl_len;
            int next_fuzzy = cur_fuzzy + (edge.is_fuzzy ? 1 : 0);

            out_paths.push_back({edge.end_pos, cur_py, next_syls, next_first_len, next_fuzzy});

            if (next_syls < max_syls && edge.end_pos < target_end) {
                collect_lattice_spans(
                    graph, edge.end_pos, target_end, cur_py,
                    next_syls, next_first_len, next_fuzzy, max_syls, out_paths
                );
            }
            cur_py.resize(old_len);
        }
    }
}

struct ViterbiWordMatch {
    std::string word;
    size_t end_pos;
    size_t span_syls;
    size_t first_syl_len;
    float prior;
    float fuzzy_penalty;
};

struct ViterbiState {
    float score;
    std::string word;
    std::string context_window;
    int parent_idx;
    size_t parent_pos;
};

static std::string advance_context_window(const std::string &prev_ctx, const std::string &new_word) {
    std::string combined = prev_ctx + new_word;
    std::vector<size_t> char_indices;
    for (size_t i = 0; i < combined.size(); ++i) {
        if ((combined[i] & 0xC0) != 0x80) {
            char_indices.push_back(i);
        }
    }
    if (char_indices.size() <= 4) {
        return combined;
    }
    size_t start_byte = char_indices[char_indices.size() - 4];
    return combined.substr(start_byte);
}

struct DictCand {
    std::string word;
    uint32_t weight = 0;
};

// 读取某个拼音键对应的候选块；越界/截断数据一律停止，避免坏词典导致越界读
static void read_dict_block(const DictLayer &layer, int offset, int max_take,
                            std::vector<DictCand> &out) {
    if (!layer.loaded || !layer.data_blob || !layer.data_size) return;
    if (offset <= 0 || static_cast<size_t>(offset) >= layer.data_size) return;
    const uint8_t *p = layer.data_blob + offset;
    const uint8_t *end = layer.data_blob + layer.data_size;
    if (p >= end) return;
    uint8_t count = *p++;
    int take = std::min(static_cast<int>(count), max_take);
    for (int i = 0; i < take; ++i) {
        if (p + 5 > end) return;
        uint32_t wt = 0;
        std::memcpy(&wt, p, sizeof(wt));
        p += 4;
        uint8_t len = *p++;
        if (p + len > end) return;
        out.push_back({std::string(reinterpret_cast<const char *>(p), len), wt});
        p += len;
    }
}

static void collect_exact_from_layer(const DictLayer &layer, const std::string &key,
                                     int max_take, std::vector<DictCand> &out) {
    if (!layer.loaded) return;
    int offset = layer.py_trie.exactMatchSearch<int>(key.c_str(), key.length());
    read_dict_block(layer, offset, max_take, out);
}

// core 与前缀无关的高权重候选优先；同权重时 core 在前，各自保持块内顺序
static void merge_layer_exact(const std::string &key, int per_layer_take,
                              std::vector<DictCand> &out) {
    std::vector<DictCand> core_hits, ext_hits;
    collect_exact_from_layer(g_dict_core, key, per_layer_take, core_hits);
    collect_exact_from_layer(g_dict_ext, key, per_layer_take, ext_hits);
    size_t i = 0, j = 0;
    while (i < core_hits.size() || j < ext_hits.size()) {
        bool take_core;
        if (i >= core_hits.size()) take_core = false;
        else if (j >= ext_hits.size()) take_core = true;
        else take_core = core_hits[i].weight >= ext_hits[j].weight;
        if (take_core) out.push_back(std::move(core_hits[i++]));
        else out.push_back(std::move(ext_hits[j++]));
    }
}

static void lookup_dict_matches_for_span(
    const std::string &span_py,
    size_t expected_char_len,
    size_t cost_syls,
    size_t end_pos,
    size_t first_syl_len,
    float fuzzy_penalty,
    int max_take,
    std::vector<ViterbiWordMatch> &matches
) {
    if (!dict_any_loaded() || span_py.empty()) return;

    std::vector<DictCand> hits;
    merge_layer_exact(span_py, max_take, hits);
    for (const auto &cand : hits) {
        if (cand.word.empty()) continue;
        size_t c_len = utf8_char_length(cand.word.c_str());
        if (expected_char_len != 0 && c_len != expected_char_len) continue;
        bool exists = false;
        for (auto &m : matches) {
            if (m.word == cand.word && m.end_pos == end_pos) {
                exists = true;
                break;
            }
        }
        if (exists) continue;
        float prior = (cand.weight > 0)
            ? std::log10(static_cast<float>(cand.weight)) * 1.5f
            : 0.0f;
        size_t effective_syls = (cost_syls > 0) ? cost_syls : c_len;
        matches.push_back({cand.word, end_pos, effective_syls, first_syl_len, prior, fuzzy_penalty});
    }
}

static void save_user_memory_locked() {
    if (g_storage_path.empty()) return;
    std::string tmp_path = g_storage_path + ".tmp";
    {
        std::ofstream ofs(tmp_path, std::ios::binary | std::ios::trunc);
        if (!ofs.is_open()) return;

        uint32_t magic = 0x58595533u; // 小端写入，文件头字节为 "3UYX"
        ofs.write(reinterpret_cast<const char *>(&magic), sizeof(magic));

        auto write_map = [&ofs](const std::unordered_map<std::string, std::vector<UserMemoryEntry>> &m) {
            uint32_t valid_keys = 0;
            for (const auto &pair : m) {
                if (!pair.first.empty() && pair.first != "^" && pair.first.size() <= 512 && !pair.second.empty()) {
                    ++valid_keys;
                }
            }
            ofs.write(reinterpret_cast<const char *>(&valid_keys), sizeof(valid_keys));
            for (const auto &pair : m) {
                if (pair.first.empty() || pair.first == "^" || pair.first.size() > 512 || pair.second.empty()) {
                    continue;
                }
                uint16_t key_len = static_cast<uint16_t>(pair.first.size());
                ofs.write(reinterpret_cast<const char *>(&key_len), sizeof(key_len));
                ofs.write(pair.first.data(), key_len);

                uint16_t list_size = static_cast<uint16_t>(std::min<size_t>(pair.second.size(), 255));
                ofs.write(reinterpret_cast<const char *>(&list_size), sizeof(list_size));
                for (size_t i = 0; i < list_size; ++i) {
                    const auto &item = pair.second[i];
                    uint16_t w_len = static_cast<uint16_t>(std::min<size_t>(item.word.size(), 2048));
                    ofs.write(reinterpret_cast<const char *>(&w_len), sizeof(w_len));
                    ofs.write(item.word.data(), w_len);
                    ofs.write(reinterpret_cast<const char *>(&item.frequency), sizeof(item.frequency));
                    ofs.write(reinterpret_cast<const char *>(&item.last_used_timestamp), sizeof(item.last_used_timestamp));
                }
            }
        };

        write_map(g_user_pinyin_memory);
        write_map(g_user_memory);

        uint32_t sup_size = static_cast<uint32_t>(g_suppressed_words.size());
        ofs.write(reinterpret_cast<const char *>(&sup_size), sizeof(sup_size));
        for (const auto &kv : g_suppressed_words) {
            uint16_t w_len = static_cast<uint16_t>(std::min<size_t>(kv.first.size(), 2048));
            ofs.write(reinterpret_cast<const char *>(&w_len), sizeof(w_len));
            ofs.write(kv.first.data(), w_len);
            ofs.write(reinterpret_cast<const char *>(&kv.second), sizeof(kv.second));
        }

        ofs.flush();
        if (!ofs.good()) {
            ofs.close();
            std::remove(tmp_path.c_str());
            return;
        }
    }
    if (std::rename(tmp_path.c_str(), g_storage_path.c_str()) != 0) {
        std::remove(tmp_path.c_str());
    }
}

} // namespace

bool is_valid_pinyin_sequence(const char *pinyin) {
    std::string s = clean_pinyin_string(pinyin);
    if (s.empty()) return false;
    ensure_pinyin_sets_initialized();
    size_t i = 0;
    while (i < s.size()) {
        size_t max_len = std::min<size_t>(6, s.size() - i);
        size_t take = 0;
        for (size_t len = max_len; len >= 1; --len) {
            if (g_intact_pinyin_set.find(s.substr(i, len)) != g_intact_pinyin_set.end()) {
                take = len;
                break;
            }
        }
        if (take == 0) {
            // 只允许最后一段是不完整音节前缀（用户还没打完）
            return g_prefix_pinyin_set.find(s.substr(i)) != g_prefix_pinyin_set.end();
        }
        i += take;
    }
    return true;
}

void init_lm(const char *storage_dir, const char *custom_model_path, const char *custom_dict_path) {
    {
        std::lock_guard<std::mutex> lm_lock(g_lm_mutex);
        std::lock_guard<std::mutex> dict_lock(g_dict_mutex);
        load_embedded_model_locked();
    }

    {
        std::lock_guard<std::mutex> lm_lock(g_lm_mutex);
        if (custom_model_path && std::strlen(custom_model_path) > 0) {
            if (access(custom_model_path, R_OK) == 0) {
                load_lm_file_locked(custom_model_path);
            }
        } else if (!g_lm_loaded && storage_dir && std::strlen(storage_dir) > 0) {
            std::string priv_model = std::string(storage_dir) + "/xy_lm.bin";
            if (access(priv_model.c_str(), R_OK) == 0) {
                load_lm_file_locked(priv_model);
            }
        }
    }

    {
        std::lock_guard<std::mutex> dict_lock(g_dict_mutex);
        // 先关闭免锁快路径，再动 ext 层，避免查询线程看到半初始化的 trie
        g_dict_is_embedded.store(false, std::memory_order_release);
        bool ext_found = false;
        if (custom_dict_path && std::strlen(custom_dict_path) > 0) {
            if (access(custom_dict_path, R_OK) == 0) {
                ext_found = load_dict_file_locked(custom_dict_path, g_dict_ext) || ext_found;
            }
        }
        if (!ext_found && storage_dir && std::strlen(storage_dir) > 0) {
            std::string priv_dict = std::string(storage_dir) + "/xy_dict.bin";
            if (access(priv_dict.c_str(), R_OK) == 0) {
                ext_found = load_dict_file_locked(priv_dict, g_dict_ext) || ext_found;
            }
        }
        // 文件被删除/停用时真正卸载扩展层，避免旧数据在本次进程内继续生效
        if (!ext_found && g_dict_ext.loaded) {
            unload_dict_layer(g_dict_ext);
        }
        // 有外置层时查询必须加锁；纯内置保持旧的免锁路径
        g_dict_is_embedded.store(!g_dict_ext.loaded, std::memory_order_release);
    }

    {
        std::lock_guard<std::mutex> mem_lock(g_user_mem_mutex);
        g_user_memory.clear();
        g_user_pinyin_memory.clear();
        g_suppressed_words.clear();
        g_storage_path.clear();

        if (storage_dir && std::strlen(storage_dir) > 0) {
            g_storage_path = std::string(storage_dir) + "/xy_user_dict.dat";
        }

        std::string load_path = g_storage_path;
        if (!load_path.empty() && access(load_path.c_str(), R_OK) != 0) {
            std::string legacy_path = std::string(storage_dir) + "/xy_user_lm.dat";
            if (access(legacy_path.c_str(), R_OK) == 0) {
                load_path = legacy_path;
            }
        }

        if (!load_path.empty()) {
            std::ifstream ifs(load_path, std::ios::binary);
            if (ifs.is_open()) {
                uint32_t magic = 0;
                ifs.read(reinterpret_cast<char *>(&magic), sizeof(magic));
                if (ifs.good() && (magic == 0x58595533u || magic == 0x5859554Eu)) {
                    auto read_map = [&ifs](std::unordered_map<std::string, std::vector<UserMemoryEntry>> &target_map, bool is_ctx_map) {
                        uint32_t map_size = 0;
                        ifs.read(reinterpret_cast<char *>(&map_size), sizeof(map_size));
                        if (!ifs.good() || map_size > 20000) return;

                        for (uint32_t i = 0; i < map_size && ifs.good(); ++i) {
                            uint16_t key_len = 0;
                            ifs.read(reinterpret_cast<char *>(&key_len), sizeof(key_len));
                            if (!ifs.good() || key_len > 512) break;
                            std::string key(key_len, '\0');
                            if (key_len > 0) {
                                ifs.read(&key[0], key_len);
                            }

                            uint16_t list_size = 0;
                            ifs.read(reinterpret_cast<char *>(&list_size), sizeof(list_size));
                            if (!ifs.good() || list_size > 256) break;

                            std::vector<UserMemoryEntry> list;
                            list.reserve(list_size);
                            for (uint16_t j = 0; j < list_size && ifs.good(); ++j) {
                                uint16_t w_len = 0;
                                ifs.read(reinterpret_cast<char *>(&w_len), sizeof(w_len));
                                if (!ifs.good() || w_len > 2048) break;
                                std::string word(w_len, '\0');
                                if (w_len > 0) {
                                    ifs.read(&word[0], w_len);
                                }
                                int freq = 0;
                                int64_t ts = 0;
                                ifs.read(reinterpret_cast<char *>(&freq), sizeof(freq));
                                ifs.read(reinterpret_cast<char *>(&ts), sizeof(ts));
                                if (ifs.good() && !word.empty() && freq > 0) {
                                    list.push_back({word, freq, ts});
                                }
                            }
                            if (!key.empty() && (!is_ctx_map || key != "^") && !list.empty()) {
                                target_map[key] = std::move(list);
                            }
                        }
                    };

                    read_map(g_user_pinyin_memory, false);
                    read_map(g_user_memory, true);

                    if (magic == 0x58595533u && ifs.good()) {
                        uint32_t sup_size = 0;
                        ifs.read(reinterpret_cast<char *>(&sup_size), sizeof(sup_size));
                        if (ifs.good() && sup_size <= 20000) {
                            for (uint32_t i = 0; i < sup_size && ifs.good(); ++i) {
                                uint16_t w_len = 0;
                                ifs.read(reinterpret_cast<char *>(&w_len), sizeof(w_len));
                                if (!ifs.good() || w_len > 2048) break;
                                std::string word(w_len, '\0');
                                if (w_len > 0) {
                                    ifs.read(&word[0], w_len);
                                }
                                int64_t ts = 0;
                                ifs.read(reinterpret_cast<char *>(&ts), sizeof(ts));
                                if (ifs.good() && !word.empty() &&
                                    g_suppressed_words.size() < MAX_MAP_KEYS) {
                                    g_suppressed_words[word] = ts;
                                }
                            }
                        }
                    }
                } else if (ifs.good() && magic == 0x5859554Du) {
                    uint32_t map_size = 0;
                    ifs.read(reinterpret_cast<char *>(&map_size), sizeof(map_size));
                    if (ifs.good() && map_size <= 20000) {
                        for (uint32_t i = 0; i < map_size && ifs.good(); ++i) {
                            uint16_t key_len = 0;
                            ifs.read(reinterpret_cast<char *>(&key_len), sizeof(key_len));
                            if (!ifs.good() || key_len > 512) break;
                            std::string key(key_len, '\0');
                            if (key_len > 0) {
                                ifs.read(&key[0], key_len);
                            }

                            uint16_t list_size = 0;
                            ifs.read(reinterpret_cast<char *>(&list_size), sizeof(list_size));
                            if (!ifs.good() || list_size > 256) break;
                            std::vector<UserMemoryEntry> list;
                            list.reserve(list_size);
                            for (uint16_t j = 0; j < list_size && ifs.good(); ++j) {
                                uint16_t w_len = 0;
                                ifs.read(reinterpret_cast<char *>(&w_len), sizeof(w_len));
                                if (!ifs.good() || w_len > 2048) break;
                                std::string word(w_len, '\0');
                                if (w_len > 0) {
                                    ifs.read(&word[0], w_len);
                                }
                                int freq = 0;
                                int64_t ts = 0;
                                ifs.read(reinterpret_cast<char *>(&freq), sizeof(freq));
                                ifs.read(reinterpret_cast<char *>(&ts), sizeof(ts));
                                if (ifs.good() && !word.empty() && freq > 0) {
                                    list.push_back({word, freq, ts});
                                }
                            }
                            if (!key.empty() && key != "^" && !list.empty()) {
                                g_user_memory[key] = std::move(list);
                            }
                        }
                    }
                }
            }
        }
    }
}

std::string get_lm_info() {
    std::lock_guard<std::mutex> lock(g_lm_mutex);
    if (g_lm_loaded) {
        return "核心引擎就绪: " + g_lm_path + " (" + std::to_string(g_lm_da_size) + " 单元)";
    }
    return "核心引擎未载入";
}

bool is_lm_ready() {
    std::lock_guard<std::mutex> lock(g_lm_mutex);
    return g_lm_loaded;
}

static std::string format_dict_count(size_t count) {
    if (count >= 10000) {
        char buf[32];
        std::snprintf(buf, sizeof(buf), "%.1f 万", static_cast<double>(count) / 10000.0);
        std::string text(buf);
        size_t dot = text.find(".0 万");
        if (dot != std::string::npos) text.erase(dot, 2);
        return text;
    }
    return std::to_string(count) + " ";
}

std::string get_dict_info() {
    std::lock_guard<std::mutex> lock(g_dict_mutex);
    std::string info;
    if (g_dict_core.loaded) {
        info = "内置只读词库 · " + format_dict_count(g_dict_core.word_count) +
               "词条 · " + format_dict_count(g_dict_core.py_count) + "拼音键";
    }
    if (g_dict_ext.loaded) {
        if (!info.empty()) info += "\n";
        info += "外置词库 · " + format_dict_count(g_dict_ext.word_count) +
                "词条 · " + format_dict_count(g_dict_ext.py_count) + "拼音键";
    }
    return info.empty() ? "核心词库未载入" : info;
}

bool is_dict_ready() {
    std::lock_guard<std::mutex> lock(g_dict_mutex);
    return dict_any_loaded();
}

// ---------------------------------------------------------------------------
// 前缀检索：用户只敲了 beij 时，exactMatchSearch 一个候选都给不出。
// 用 traverse 定位前缀节点，再做有界 DFS 收集以该前缀开头的拼音键。
// ---------------------------------------------------------------------------
static void dfs_collect_prefix_offsets(
    const DictLayer &layer,
    size_t node_pos,
    std::string &cur_bytes,
    int &visited,
    int max_nodes,
    std::vector<int> &offsets,
    int max_keys
) {
    if (visited >= max_nodes || static_cast<int>(offsets.size()) >= max_keys) return;
    ++visited;

    const auto *units = static_cast<const Darts::Details::DoubleArrayUnit *>(layer.py_trie.array());
    auto unit = units[node_pos];
    if (unit.has_leaf()) {
        size_t leaf_pos = node_pos ^ unit.offset();
        if (leaf_pos < layer.py_trie.size()) {
            int val = units[leaf_pos].value();
            if (val > 0) offsets.push_back(val);
        }
    }
    if (cur_bytes.size() >= 48) return;  // 拼音键长度上限保护

    for (int b = 0; b < 256; ++b) {
        size_t next_pos = node_pos ^ unit.offset() ^ static_cast<unsigned int>(b);
        if (next_pos < layer.py_trie.size() &&
            units[next_pos].label() == static_cast<unsigned int>(b)) {
            cur_bytes.push_back(static_cast<char>(b));
            dfs_collect_prefix_offsets(layer, next_pos, cur_bytes, visited, max_nodes,
                                       offsets, max_keys);
            cur_bytes.pop_back();
            if (visited >= max_nodes || static_cast<int>(offsets.size()) >= max_keys) break;
        }
    }
}

static void collect_prefix_offsets(const DictLayer &layer, const std::string &prefix,
                                   std::vector<int> &offsets) {
    if (!layer.loaded) return;
    size_t node_pos = 0;
    size_t key_pos = 0;
    layer.py_trie.traverse(prefix.c_str(), node_pos, key_pos, prefix.size());
    if (key_pos != prefix.size()) return;
    std::string cur;
    int visited = 0;
    constexpr int MAX_PREFIX_NODES = 16384;
    constexpr int MAX_PREFIX_KEYS = 4096;
    dfs_collect_prefix_offsets(layer, node_pos, cur, visited, MAX_PREFIX_NODES,
                               offsets, MAX_PREFIX_KEYS);
}

// 前缀检索结果按权重排序；每个匹配键只取前 4 个候选，避免长前缀爆炸
static void append_prefix_candidates(const std::string &prefix, int max_results,
                                     const std::unordered_set<std::string> &suppressed,
                                     std::vector<std::string> &results) {
    if (prefix.size() < 2 || static_cast<int>(results.size()) >= max_results) return;

    std::vector<DictCand> hits;
    constexpr int PER_KEY_TAKE = 4;
    constexpr size_t MAX_HITS = 8192;
    for (const DictLayer *layer : {&g_dict_core, &g_dict_ext}) {
        if (!layer->loaded) continue;
        std::vector<int> offsets;
        collect_prefix_offsets(*layer, prefix, offsets);
        for (int off : offsets) {
            read_dict_block(*layer, off, PER_KEY_TAKE, hits);
            if (hits.size() >= MAX_HITS) break;
        }
        if (hits.size() >= MAX_HITS) break;
    }
    std::stable_sort(hits.begin(), hits.end(), [](const DictCand &a, const DictCand &b) {
        return a.weight > b.weight;
    });
    for (const auto &hit : hits) {
        if (static_cast<int>(results.size()) >= max_results) break;
        if (hit.word.empty()) continue;
        if (suppressed.find(hit.word) != suppressed.end()) continue;
        if (std::find(results.begin(), results.end(), hit.word) != results.end()) continue;
        results.push_back(hit.word);
    }
}

std::vector<std::string> query_dict_pinyin(const char *pinyin, int max_results) {
    std::vector<std::string> results;
    if (!pinyin || max_results <= 0) return results;

    std::string py_clean = clean_pinyin_string(pinyin);
    if (py_clean.empty()) return results;

    std::unordered_set<std::string> suppressed_snapshot;
    {
        std::lock_guard<std::mutex> mem_lock(g_user_mem_mutex);
        for (const auto &kv : g_suppressed_words) {
            suppressed_snapshot.insert(kv.first);
        }
    }

    std::unique_lock<std::mutex> lock(g_dict_mutex, std::defer_lock);
    if (!g_dict_is_embedded.load(std::memory_order_relaxed)) {
        lock.lock();
    }
    if (!dict_any_loaded()) return results;

    auto fetch_by_key = [&](const std::string &key) {
        std::vector<DictCand> hits;
        merge_layer_exact(key, max_results, hits);
        for (const auto &hit : hits) {
            if (static_cast<int>(results.size()) >= max_results) break;
            if (hit.word.empty()) continue;
            if (suppressed_snapshot.find(hit.word) != suppressed_snapshot.end()) continue;
            if (std::find(results.begin(), results.end(), hit.word) != results.end()) continue;
            results.push_back(hit.word);
        }
    };

    fetch_by_key(py_clean);

    // 精确候选不够时，用前缀检索补足（仅输入长度 >=2）
    if (static_cast<int>(results.size()) < max_results && py_clean.size() >= 2) {
        append_prefix_candidates(py_clean, max_results, suppressed_snapshot, results);
    }

    return results;
}

// 已上屏词 -> 多字联想词。word_trie 的 key 就是词条本身，桩节点之后收集后缀。
static void dfs_collect_word_associations(
    const DictLayer &layer,
    size_t node_pos,
    const std::string &prefix,
    std::string &suffix,
    int &visited,
    int max_nodes,
    std::vector<DictCand> &hits
) {
    if (visited >= max_nodes) return;
    ++visited;

    const auto *units = static_cast<const Darts::Details::DoubleArrayUnit *>(layer.word_trie.array());
    auto unit = units[node_pos];
    if (unit.has_leaf()) {
        size_t leaf_pos = node_pos ^ unit.offset();
        if (leaf_pos < layer.word_trie.size()) {
            int val = units[leaf_pos].value();
            if (val > 0 && !suffix.empty()) {
                hits.push_back({prefix + suffix, static_cast<uint32_t>(val)});
            }
        }
    }
    if (suffix.size() >= 24) return;  // 最多再跟 8 个汉字
    for (int b = 0; b < 256; ++b) {
        size_t next_pos = node_pos ^ unit.offset() ^ static_cast<unsigned int>(b);
        if (next_pos < layer.word_trie.size() &&
            units[next_pos].label() == static_cast<unsigned int>(b)) {
            suffix.push_back(static_cast<char>(b));
            dfs_collect_word_associations(layer, next_pos, prefix, suffix, visited, max_nodes, hits);
            suffix.pop_back();
            if (visited >= max_nodes) break;
        }
    }
}

std::vector<std::string> query_dict_word_prefix(const char *prefix, int max_results) {
    std::vector<std::string> results;
    if (!prefix || !*prefix || max_results <= 0) return results;
    std::string p(prefix);

    std::unordered_set<std::string> suppressed_snapshot;
    {
        std::lock_guard<std::mutex> mem_lock(g_user_mem_mutex);
        for (const auto &kv : g_suppressed_words) {
            suppressed_snapshot.insert(kv.first);
        }
    }

    std::unique_lock<std::mutex> lock(g_dict_mutex, std::defer_lock);
    if (!g_dict_is_embedded.load(std::memory_order_relaxed)) {
        lock.lock();
    }
    if (!dict_any_loaded()) return results;

    std::vector<DictCand> hits;
    for (const DictLayer *layer : {&g_dict_core, &g_dict_ext}) {
        if (!layer->loaded) continue;
        size_t node_pos = 0;
        size_t key_pos = 0;
        layer->word_trie.traverse(p.c_str(), node_pos, key_pos, p.size());
        if (key_pos != p.size()) continue;
        std::string suffix;
        int visited = 0;
        dfs_collect_word_associations(*layer, node_pos, p, suffix, visited, 8192, hits);
    }
    std::stable_sort(hits.begin(), hits.end(), [](const DictCand &a, const DictCand &b) {
        return a.weight > b.weight;
    });
    for (const auto &hit : hits) {
        if (static_cast<int>(results.size()) >= max_results) break;
        if (hit.word.empty() || hit.word == p) continue;
        if (suppressed_snapshot.find(hit.word) != suppressed_snapshot.end()) continue;
        if (std::find(results.begin(), results.end(), hit.word) != results.end()) continue;
        results.push_back(hit.word);
    }
    return results;
}

std::vector<std::string> query_user_candidates(const char *code, const char *context, int max_results) {
    std::vector<std::string> results;
    if (max_results <= 0) return results;

    std::string clean_code = clean_pinyin_string(code);
    std::string ctx_str(context ? context : "");
    std::vector<std::string> tails = ctx_str.empty() ? std::vector<std::string>{} : extract_context_tails(ctx_str, 8);

    std::lock_guard<std::mutex> lock(g_user_mem_mutex);
    int64_t now = current_time_millis();

    struct ScoredUserCand {
        std::string word;
        float score;
    };
    std::vector<ScoredUserCand> candidates;

    // 手动快捷短语（频率基准值以上）始终优先于自学习词条
    auto score_of = [](const UserMemoryEntry &e, float mult) -> float {
        if (e.frequency >= CUSTOM_PHRASE_BASE_FREQ) {
            return 1000000.0f + static_cast<float>(e.frequency) * 0.001f;
        }
        return static_cast<float>(e.frequency) * mult;
    };

    if (!clean_code.empty()) {
        // 1. 编码精确匹配（若同时命中前文语境记忆则叠加语境权重，但不混入非当前编码的词条）
        auto it = g_user_pinyin_memory.find(clean_code);
        if (it != g_user_pinyin_memory.end()) {
            for (const auto &entry : it->second) {
                if (g_suppressed_words.find(entry.word) != g_suppressed_words.end()) continue;
                int64_t ts = (entry.last_used_timestamp > 0) ? entry.last_used_timestamp : now;
                double days = std::max(0.0, static_cast<double>(now - ts) / (1000.0 * 86400.0));
                float half_life = (entry.frequency >= CUSTOM_PHRASE_BASE_FREQ)
                    ? 1.0f
                    : std::pow(0.5f, static_cast<float>(days / 30.0));
                float score = score_of(entry, 20.0f) * half_life;

                for (const auto &tail : tails) {
                    auto ctx_it = g_user_memory.find(tail);
                    if (ctx_it == g_user_memory.end()) continue;
                    for (const auto &ctx_entry : ctx_it->second) {
                        if (ctx_entry.word == entry.word) {
                            score += static_cast<float>(std::min(ctx_entry.frequency, 50)) * 15.0f;
                            break;
                        }
                    }
                }
                candidates.push_back({entry.word, score});
            }
        }

        // 2. 编码前缀匹配：当输入编码长度 >= 2 时，补充召回前缀匹配的自定义快捷短语
        if (clean_code.size() >= 2) {
            for (const auto &pair : g_user_pinyin_memory) {
                const std::string &k = pair.first;
                if (k.size() > clean_code.size() &&
                    k.compare(0, clean_code.size(), clean_code) == 0) {
                    for (const auto &entry : pair.second) {
                        if (entry.frequency < CUSTOM_PHRASE_BASE_FREQ) continue;
                        if (g_suppressed_words.find(entry.word) != g_suppressed_words.end()) continue;
                        candidates.push_back({entry.word, score_of(entry, 8.0f)});
                    }
                }
            }
        }
    } else if (!tails.empty()) {
        // 闲置态（无正在输入的编码）下：仅召回用户显式配置的 @上下文 快捷短语
        for (const auto &tail : tails) {
            auto it = g_user_memory.find(tail);
            if (it == g_user_memory.end()) continue;
            for (const auto &entry : it->second) {
                if (entry.frequency < CUSTOM_PHRASE_BASE_FREQ) continue;
                if (g_suppressed_words.find(entry.word) != g_suppressed_words.end()) continue;
                if (ctx_str.size() >= entry.word.size() &&
                    ctx_str.compare(ctx_str.size() - entry.word.size(), entry.word.size(), entry.word) == 0) {
                    continue;
                }
                candidates.push_back({entry.word, score_of(entry, 15.0f)});
            }
        }
    }

    std::stable_sort(candidates.begin(), candidates.end(), [](const ScoredUserCand &a, const ScoredUserCand &b) {
        return a.score > b.score;
    });

    for (const auto &c : candidates) {
        if (!c.word.empty() && std::find(results.begin(), results.end(), c.word) == results.end()) {
            results.push_back(c.word);
            if (static_cast<int>(results.size()) >= max_results) break;
        }
    }
    return results;
}

void set_quanpin_mode(bool enabled) {
    g_quanpin_mode.store(enabled, std::memory_order_relaxed);
}


void set_long_phrase_aggressive(bool aggressive) {
    g_aggressive_long_phrase.store(aggressive, std::memory_order_relaxed);
}




std::string decode_pinyin_sentence(const char *context, const char *pinyin) {
    if (!pinyin || !*pinyin) return "";

    std::string py_clean;
    py_clean.reserve(std::strlen(pinyin));
    for (const char *p = pinyin; *p; ++p) {
        if ((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') || *p == '\'') {
            py_clean.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(*p))));
        }
    }
    if (py_clean.empty()) return "";
    // 限制输入长度，避免大量分隔符导致格图递归过深
    if (py_clean.size() > 256) return "";

    std::string py_no_apos = clean_pinyin_string(py_clean.c_str());
    {
        std::lock_guard<std::mutex> mem_lock(g_user_mem_mutex);
        if (!py_no_apos.empty()) {
            auto it = g_user_pinyin_memory.find(py_no_apos);
            if (it != g_user_pinyin_memory.end()) {
                for (const auto &entry : it->second) {
                    if (entry.frequency >= CUSTOM_PHRASE_BASE_FREQ &&
                        g_suppressed_words.find(entry.word) == g_suppressed_words.end()) {
                        return entry.word;
                    }
                }
            }
        }
    }

    std::unique_lock<std::mutex> lm_lock(g_lm_mutex, std::defer_lock);
    std::unique_lock<std::mutex> dict_lock(g_dict_mutex, std::defer_lock);
    if (!g_lm_is_embedded.load(std::memory_order_relaxed)) lm_lock.lock();
    if (!g_dict_is_embedded.load(std::memory_order_relaxed)) dict_lock.lock();

    if (py_clean.find('\'') == std::string::npos && !py_no_apos.empty()) {
        std::vector<ViterbiWordMatch> exact_whole;
        lookup_dict_matches_for_span(py_no_apos, 0, 0, py_no_apos.size(), py_no_apos.size(), 0.0f, 4, exact_whole);
        size_t probe_end = 0;
        build_syllable_graph(py_clean, probe_end);
        if (probe_end < py_clean.size() && !exact_whole.empty()) {
            return exact_whole.front().word;
        }
    }

    size_t best_end = 0;
    SyllableGraph graph = build_syllable_graph(py_clean, best_end);
    if (best_end == 0) {
        return "";
    }

    std::string trailing_prefix;
    if (best_end < py_clean.size()) {
        std::string rem = py_clean.substr(best_end);
        while (!rem.empty() && rem.front() == '\'') rem.erase(rem.begin());
        if (!rem.empty() && is_valid_pinyin_prefix(rem.data(), rem.size())) {
            trailing_prefix = rem;
        }
    }

    std::string ctx_str(context ? context : "");
    std::vector<std::string> init_tails = extract_context_tails(ctx_str, 3);
    std::string init_ctx_window = init_tails.empty() ? "" : init_tails.front();

    std::vector<std::vector<ViterbiState>> dp(best_end + 1);
    dp[0].push_back({0.0f, init_ctx_window, init_ctx_window, -1, 0});

    constexpr size_t BEAM_WIDTH = 16;
    const bool aggressive = g_aggressive_long_phrase.load(std::memory_order_relaxed);
    const float WORD_PENALTY = aggressive ? -12.0f : -10.0f;
    const float SYLLABLE_PENALTY = -8.0f;

    std::string scratch_py;
    scratch_py.reserve(32);

    for (size_t pos = 0; pos < best_end; ++pos) {
        if (dp[pos].empty()) continue;

        if (py_clean[pos] == '\'') {
            for (size_t h_idx = 0; h_idx < dp[pos].size(); ++h_idx) {
                const auto &st = dp[pos][h_idx];
                dp[pos + 1].push_back({st.score, "", st.context_window, static_cast<int>(h_idx), pos});
            }
            continue;
        }

        std::vector<LatticeSpanPath> span_paths;
        scratch_py.clear();
        collect_lattice_spans(graph, pos, best_end, scratch_py, 0, 0, 0, 5, span_paths);
        if (span_paths.empty()) continue;

        std::vector<ViterbiWordMatch> matches;
        matches.reserve(24);

        for (const auto &sp : span_paths) {
            float fz_pen = static_cast<float>(sp.fuzzy_count) * (-0.8f);
            lookup_dict_matches_for_span(
                sp.combined_py, sp.syl_count, sp.syl_count, sp.end_pos, sp.first_syl_len, fz_pen, 8, matches
            );

            if (sp.end_pos == best_end && !trailing_prefix.empty()) {
                std::string mixed_py = sp.combined_py + trailing_prefix;
                lookup_dict_matches_for_span(
                    mixed_py, sp.syl_count + 1, sp.syl_count, sp.end_pos, sp.first_syl_len, fz_pen + 1.5f, 4, matches
                );
            }
        }

        if (matches.empty()) {
            const auto &first_sp = span_paths.front();
            matches.push_back({first_sp.combined_py, first_sp.end_pos, 1, first_sp.first_syl_len, -15.0f, 0.0f});
        }

        std::vector<std::vector<PrecomputedTail>> state_lm_tails(dp[pos].size());
        if (g_lm_loaded) {
            for (size_t h_idx = 0; h_idx < dp[pos].size(); ++h_idx) {
                if (!dp[pos][h_idx].context_window.empty()) {
                    auto tails = extract_context_tails(dp[pos][h_idx].context_window, 3);
                    state_lm_tails[h_idx] = prepare_lm_tails(tails);
                }
            }
        }

        for (const auto &match : matches) {
            size_t next_pos = match.end_pos;
            if (next_pos > best_end) continue;

            float structure_score = WORD_PENALTY +
                                    static_cast<float>(match.span_syls) * SYLLABLE_PENALTY -
                                    static_cast<float>(match.first_syl_len) * 0.05f +
                                    match.fuzzy_penalty;

            for (size_t h_idx = 0; h_idx < dp[pos].size(); ++h_idx) {
                const auto &parent = dp[pos][h_idx];
                float lm_score = 0.0f;
                if (g_lm_loaded && !state_lm_tails[h_idx].empty()) {
                    lm_score = query_lm_ngram_prepared(state_lm_tails[h_idx], match.word);
                }
                float total_score = parent.score + lm_score + match.prior + structure_score;
                std::string next_ctx = advance_context_window(parent.context_window, match.word);
                dp[next_pos].push_back({total_score, match.word, std::move(next_ctx), static_cast<int>(h_idx), pos});
            }

            if (dp[next_pos].size() > BEAM_WIDTH * 2) {
                std::partial_sort(
                    dp[next_pos].begin(),
                    dp[next_pos].begin() + BEAM_WIDTH,
                    dp[next_pos].end(),
                    [](const ViterbiState &a, const ViterbiState &b) { return a.score > b.score; }
                );
                dp[next_pos].resize(BEAM_WIDTH);
            }
        }
    }

    size_t target_pos = best_end;
    while (target_pos > 0 && dp[target_pos].empty()) {
        --target_pos;
    }
    if (target_pos == 0 || dp[target_pos].empty()) {
        return "";
    }

    size_t best_idx = 0;
    float max_s = dp[target_pos][0].score;
    for (size_t i = 1; i < dp[target_pos].size(); ++i) {
        if (dp[target_pos][i].score > max_s) {
            max_s = dp[target_pos][i].score;
            best_idx = i;
        }
    }

    std::vector<std::string> path_words;
    size_t curr_pos = target_pos;
    int curr_idx = static_cast<int>(best_idx);
    while (curr_pos > 0 && curr_idx >= 0 && curr_idx < static_cast<int>(dp[curr_pos].size())) {
        const auto &st = dp[curr_pos][curr_idx];
        path_words.push_back(st.word);
        curr_pos = st.parent_pos;
        curr_idx = st.parent_idx;
    }
    std::reverse(path_words.begin(), path_words.end());

    std::string sentence;
    for (const auto &w : path_words) {
        sentence += w;
    }

    for (char c : sentence) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
            return "";
        }
    }

    return sentence;
}

void sync_user_memory() {
    std::lock_guard<std::mutex> lock(g_user_mem_mutex);
    if (g_storage_path.empty()) return;

    int64_t now = current_time_millis();
    constexpr int64_t THIRTY_DAYS_MS = 30LL * 86400LL * 1000LL;

    auto decay_map = [now](std::unordered_map<std::string, std::vector<UserMemoryEntry>> &m, bool is_ctx_map) {
        for (auto it = m.begin(); it != m.end();) {
            if (it->first.empty() || (is_ctx_map && it->first == "^")) {
                it = m.erase(it);
                continue;
            }
            auto &list = it->second;
            for (auto entry_it = list.begin(); entry_it != list.end();) {
                if (entry_it->last_used_timestamp <= 0) {
                    entry_it->last_used_timestamp = now;
                } else if (entry_it->frequency < CUSTOM_PHRASE_BASE_FREQ) {
                    int64_t elapsed = now - entry_it->last_used_timestamp;
                    if (elapsed >= THIRTY_DAYS_MS) {
                        int64_t steps = elapsed / THIRTY_DAYS_MS;
                        if (steps >= 16) {
                            entry_it->frequency = 0;
                        } else {
                            entry_it->frequency >>= static_cast<int>(steps);
                            entry_it->last_used_timestamp += steps * THIRTY_DAYS_MS;
                        }
                    }
                }
                if (entry_it->frequency <= 0) {
                    entry_it = list.erase(entry_it);
                } else {
                    ++entry_it;
                }
            }
            if (list.empty()) {
                it = m.erase(it);
            } else {
                ++it;
            }
        }
    };

    decay_map(g_user_pinyin_memory, false);
    decay_map(g_user_memory, true);

    save_user_memory_locked();
}

void clear_user_memory() {
    std::lock_guard<std::mutex> lock(g_user_mem_mutex);
    g_user_memory.clear();
    g_user_pinyin_memory.clear();
    g_suppressed_words.clear();
    save_user_memory_locked();
}

std::string export_user_memory_tsv() {
    std::lock_guard<std::mutex> lock(g_user_mem_mutex);

    struct ExportRow {
        char type;
        std::string key;
        std::string word;
        int freq;
        int64_t ts;
    };
    std::vector<ExportRow> rows;

    for (const auto &pair : g_user_pinyin_memory) {
        if (pair.first.empty()) continue;
        for (const auto &entry : pair.second) {
            if (!entry.word.empty() && entry.frequency > 0) {
                rows.push_back({'P', pair.first, entry.word, entry.frequency, entry.last_used_timestamp});
            }
        }
    }

    for (const auto &pair : g_user_memory) {
        if (pair.first.empty() || pair.first == "^") continue;
        for (const auto &entry : pair.second) {
            if (!entry.word.empty() && entry.frequency > 0) {
                rows.push_back({'C', pair.first, entry.word, entry.frequency, entry.last_used_timestamp});
            }
        }
    }

    for (const auto &kv : g_suppressed_words) {
        if (!kv.first.empty()) {
            rows.push_back({'S', "*", kv.first, -1, kv.second});
        }
    }

    std::sort(rows.begin(), rows.end(), [](const ExportRow &a, const ExportRow &b) {
        bool a_custom = (a.freq >= CUSTOM_PHRASE_BASE_FREQ);
        bool b_custom = (b.freq >= CUSTOM_PHRASE_BASE_FREQ);
        if (a_custom != b_custom) return a_custom > b_custom;
        if (a.type != b.type) {
            if (a.type == 'P') return true;
            if (b.type == 'P') return false;
            return a.type < b.type;
        }
        if (a.freq != b.freq) return a.freq > b.freq;
        return a.ts > b.ts;
    });

    // 字段内不能出现分隔符/换行，否则导入会串列
    auto sanitize_field = [](const std::string &s) {
        std::string out(s);
        for (char &c : out) {
            if (c == '\t' || c == '\n' || c == '\r') c = ' ';
        }
        return out;
    };

    std::ostringstream oss;
    if (!rows.empty()) {
        oss << "# XY_USER_LEXICON_TSV_V1\n";
    }
    for (const auto &r : rows) {
        oss << r.type << '\t' << sanitize_field(r.key) << '\t' << sanitize_field(r.word)
            << '\t' << r.freq << '\t' << r.ts << '\n';
    }
    return oss.str();
}

static bool tsv_has_valid_entry(const char *tsv_data) {
    if (!tsv_data) return false;
    std::istringstream iss(tsv_data);
    std::string line;
    while (std::getline(iss, line)) {
        line = trim_whitespace(line);
        if (line.empty() || line[0] == '#') continue;

        std::vector<std::string> cols;
        std::stringstream ss(line);
        std::string item;
        while (std::getline(ss, item, '\t')) {
            cols.push_back(trim_whitespace(item));
        }

        char type_upper = 0;
        if (cols.size() >= 3 && cols[0].size() == 1) {
            char c = static_cast<char>(std::toupper(static_cast<unsigned char>(cols[0][0])));
            if (c == 'P' || c == 'C' || c == 'S') type_upper = c;
        }
        if (type_upper != 0) {
            if (cols[2].empty()) continue;
            if (type_upper == 'S') return true;
            if (type_upper == 'P' && !clean_pinyin_string(cols[1].c_str()).empty()) return true;
            if (type_upper == 'C' && !sanitize_context_key(cols[1]).empty()) return true;
            continue;
        }

        std::string k_part;
        std::string w_part;
        if (cols.size() == 2) {
            k_part = cols[0];
            w_part = cols[1];
        } else {
            size_t sp = line.find_first_of(" \t");
            if (sp != std::string::npos) {
                k_part = trim_whitespace(line.substr(0, sp));
                w_part = trim_whitespace(line.substr(sp + 1));
            }
        }
        if (!k_part.empty() && !w_part.empty()) {
            if (k_part[0] == '@' && k_part.size() > 1 &&
                !sanitize_context_key(k_part.substr(1)).empty()) {
                return true;
            }
            if (k_part == "!" || k_part == "-") return true;
            if (!clean_pinyin_string(k_part.c_str()).empty()) return true;
            if (has_non_ascii_chars(k_part) && !sanitize_context_key(k_part).empty()) return true;
        } else if ((line[0] == '!' || line[0] == '-') && line.size() > 1) {
            if (!trim_whitespace(line.substr(1)).empty()) return true;
        }
    }
    return false;
}

int import_user_memory_tsv(const char *tsv_data, bool merge) {
    if (!tsv_data) return 0;
    // 覆盖导入前先确认至少有一条有效记录，空/坏 TSV 不能把现有词库清空
    if (!merge && !tsv_has_valid_entry(tsv_data)) return 0;

    std::lock_guard<std::mutex> lock(g_user_mem_mutex);
    if (!merge) {
        g_user_pinyin_memory.clear();
        g_user_memory.clear();
        g_suppressed_words.clear();
    }

    int64_t now = current_time_millis();
    int imported_count = 0;

    auto upsert_entry = [](std::unordered_map<std::string, std::vector<UserMemoryEntry>> &map_ref,
                           const std::string &k, const std::string &w, int freq, int64_t ts) {
        if (k.empty() || k.size() > 512 || w.empty() || w.size() > 2048) return;
        if (map_ref.find(k) == map_ref.end() && map_ref.size() >= MAX_MAP_KEYS) return;
        auto &list = map_ref[k];
        for (auto &item : list) {
            if (item.word == w) {
                item.frequency = std::max(item.frequency, freq);
                item.last_used_timestamp = std::max(item.last_used_timestamp, ts);
                return;
            }
        }
        if (list.size() >= MAX_BUCKET_ENTRIES) {
            auto min_it = std::min_element(list.begin(), list.end(), [](const UserMemoryEntry &a, const UserMemoryEntry &b) {
                return a.frequency < b.frequency;
            });
            if (min_it != list.end() && min_it->frequency < freq) {
                *min_it = {w, freq, ts};
            }
        } else {
            list.push_back({w, freq, ts});
        }
    };

    std::istringstream iss(tsv_data);
    std::string line;
    while (std::getline(iss, line)) {
        line = trim_whitespace(line);
        if (line.empty() || line[0] == '#') continue;

        std::vector<std::string> cols;
        std::stringstream ss(line);
        std::string item;
        while (std::getline(ss, item, '\t')) {
            cols.push_back(trim_whitespace(item));
        }

        char type_upper = 0;
        if (cols.size() >= 3 && cols[0].size() == 1) {
            char c = static_cast<char>(std::toupper(static_cast<unsigned char>(cols[0][0])));
            if (c == 'P' || c == 'C' || c == 'S') {
                type_upper = c;
            }
        }

        if (type_upper != 0) {
            std::string key = cols[1];
            std::string word = cols[2];
            if (word.empty()) continue;

            int freq = 1;
            if (cols.size() >= 4 && !cols[3].empty()) {
                try { freq = std::stoi(cols[3]); } catch (...) { freq = 1; }
            }
            int64_t ts = now;
            if (cols.size() >= 5 && !cols[4].empty()) {
                try {
                    int64_t parsed_ts = std::stoll(cols[4]);
                    ts = (parsed_ts > 0) ? parsed_ts : now;
                } catch (...) {
                    ts = now;
                }
            }

            if (type_upper == 'S') {
                if (word.size() <= 2048 &&
                    (g_suppressed_words.count(word) || g_suppressed_words.size() < MAX_MAP_KEYS)) {
                    g_suppressed_words[word] = ts;
                    ++imported_count;
                }
            } else if (type_upper == 'P') {
                std::string clean_k = clean_pinyin_string(key.c_str());
                if (!clean_k.empty()) {
                    upsert_entry(g_user_pinyin_memory, clean_k, word, std::max(1, freq), ts);
                    ++imported_count;
                }
            } else if (type_upper == 'C') {
                std::string clean_ctx = sanitize_context_key(key);
                if (!clean_ctx.empty()) {
                    upsert_entry(g_user_memory, clean_ctx, word, std::max(1, freq), ts);
                    ++imported_count;
                }
            }
        } else {
            // 兼容简易两列格式: "编码\t词条"、"编码 词条"、"@上下文 词条"、"!坏词"
            std::string k_part;
            std::string w_part;
            if (cols.size() == 2) {
                k_part = cols[0];
                w_part = cols[1];
            } else {
                size_t sp = line.find_first_of(" \t");
                if (sp != std::string::npos) {
                    k_part = trim_whitespace(line.substr(0, sp));
                    w_part = trim_whitespace(line.substr(sp + 1));
                }
            }
            if (!k_part.empty() && !w_part.empty()) {
                if (k_part[0] == '@' && k_part.size() > 1) {
                    std::string clean_ctx = sanitize_context_key(k_part.substr(1));
                    if (!clean_ctx.empty()) {
                        upsert_entry(g_user_memory, clean_ctx, w_part, CUSTOM_PHRASE_BASE_FREQ, now);
                        ++imported_count;
                    }
                } else if (k_part == "!" || k_part == "-") {
                    if (w_part.size() <= 2048 &&
                        (g_suppressed_words.count(w_part) || g_suppressed_words.size() < MAX_MAP_KEYS)) {
                        g_suppressed_words[w_part] = now;
                        ++imported_count;
                    }
                } else {
                    std::string clean_k = clean_pinyin_string(k_part.c_str());
                    if (!clean_k.empty()) {
                        upsert_entry(g_user_pinyin_memory, clean_k, w_part, CUSTOM_PHRASE_BASE_FREQ, now);
                        ++imported_count;
                    } else if (has_non_ascii_chars(k_part)) {
                        std::string clean_ctx = sanitize_context_key(k_part);
                        if (!clean_ctx.empty()) {
                            upsert_entry(g_user_memory, clean_ctx, w_part, CUSTOM_PHRASE_BASE_FREQ, now);
                            ++imported_count;
                        }
                    }
                }
            } else if ((line[0] == '!' || line[0] == '-') && line.size() > 1) {
                std::string sup_w = trim_whitespace(line.substr(1));
                if (!sup_w.empty() && sup_w.size() <= 2048 &&
                    (g_suppressed_words.count(sup_w) || g_suppressed_words.size() < MAX_MAP_KEYS)) {
                    g_suppressed_words[sup_w] = now;
                    ++imported_count;
                }
            }
        }
    }

    if (imported_count > 0 || !merge) {
        save_user_memory_locked();
    }
    return imported_count;
}

bool add_user_custom_phrase(const char *trigger_key, const char *word, bool is_context_trigger) {
    if (!trigger_key || !word) return false;
    std::string w = trim_whitespace(word);
    std::string k = trim_whitespace(trigger_key);
    if (w.empty() || k.empty() || w.size() > 2048 || k.size() > 512) return false;

    std::lock_guard<std::mutex> lock(g_user_mem_mutex);
    int64_t now = current_time_millis();

    g_suppressed_words.erase(w);

    bool use_context = is_context_trigger || k[0] == '@' ||
                       (clean_pinyin_string(k.c_str()).empty() && has_non_ascii_chars(k));

    if (use_context) {
        if (!k.empty() && k[0] == '@') {
            k = k.substr(1);
        }
        std::string clean_ctx = sanitize_context_key(k);
        if (clean_ctx.empty()) return false;
        if (g_user_memory.find(clean_ctx) == g_user_memory.end() &&
            g_user_memory.size() >= MAX_MAP_KEYS) return false;

        auto &list = g_user_memory[clean_ctx];
        for (auto &entry : list) {
            if (entry.word == w) {
                entry.frequency = std::max(entry.frequency, CUSTOM_PHRASE_BASE_FREQ);
                entry.last_used_timestamp = now;
                save_user_memory_locked();
                return true;
            }
        }
        if (list.size() >= MAX_BUCKET_ENTRIES) return false;
        list.push_back({w, CUSTOM_PHRASE_BASE_FREQ, now});
        save_user_memory_locked();
        return true;
    } else {
        std::string clean_k = clean_pinyin_string(k.c_str());
        if (clean_k.empty()) return false;
        if (g_user_pinyin_memory.find(clean_k) == g_user_pinyin_memory.end() &&
            g_user_pinyin_memory.size() >= MAX_MAP_KEYS) return false;

        auto &list = g_user_pinyin_memory[clean_k];
        for (auto &entry : list) {
            if (entry.word == w) {
                entry.frequency = std::max(entry.frequency, CUSTOM_PHRASE_BASE_FREQ);
                entry.last_used_timestamp = now;
                save_user_memory_locked();
                return true;
            }
        }
        if (list.size() >= MAX_BUCKET_ENTRIES) return false;
        list.push_back({w, CUSTOM_PHRASE_BASE_FREQ, now});
        save_user_memory_locked();
        return true;
    }
}

bool remove_user_memory_entry(const char *type, const char *key, const char *word, bool suppress_bad_word) {
    if (!word || !*word) return false;
    std::string target_word(word);
    std::string target_key(key ? key : "");
    std::string type_str(type ? type : "*");

    std::lock_guard<std::mutex> lock(g_user_mem_mutex);
    bool changed = false;
    bool removed_custom = false;

    auto remove_from_map = [&](std::unordered_map<std::string, std::vector<UserMemoryEntry>> &m,
                               const std::string &k_filter,
                               bool only_learned) {
        auto predicate = [&](const UserMemoryEntry &e) {
            if (e.word != target_word) return false;
            if (only_learned && e.frequency >= CUSTOM_PHRASE_BASE_FREQ) return false;
            if (e.frequency >= CUSTOM_PHRASE_BASE_FREQ) removed_custom = true;
            return true;
        };

        if (!k_filter.empty() && k_filter != "*") {
            auto it = m.find(k_filter);
            if (it != m.end()) {
                auto &list = it->second;
                auto old_sz = list.size();
                list.erase(std::remove_if(list.begin(), list.end(), predicate), list.end());
                if (list.size() != old_sz) changed = true;
                if (list.empty()) m.erase(it);
            }
        } else {
            for (auto it = m.begin(); it != m.end();) {
                auto &list = it->second;
                auto old_sz = list.size();
                list.erase(std::remove_if(list.begin(), list.end(), predicate), list.end());
                if (list.size() != old_sz) changed = true;
                if (list.empty()) it = m.erase(it);
                else ++it;
            }
        }
    };

    if (type_str == "S" || type_str == "s") {
        if (g_suppressed_words.erase(target_word) > 0) {
            changed = true;
            save_user_memory_locked();
        }
        return changed;
    }

    if (type_str == "P" || type_str == "p") {
        std::string clean_k = (target_key.empty() || target_key == "*") ? "*" : clean_pinyin_string(target_key.c_str());
        remove_from_map(g_user_pinyin_memory, clean_k, false);
        if (!removed_custom) {
            remove_from_map(g_user_pinyin_memory, "*", true);
            remove_from_map(g_user_memory, "*", true);
        }
    } else if (type_str == "C" || type_str == "c") {
        std::string clean_ctx = (target_key.empty() || target_key == "*") ? "*" : sanitize_context_key(target_key);
        remove_from_map(g_user_memory, clean_ctx.empty() ? target_key : clean_ctx, false);
        if (!removed_custom) {
            remove_from_map(g_user_pinyin_memory, "*", true);
            remove_from_map(g_user_memory, "*", true);
        }
    } else if (type_str == "*" || type_str.empty()) {
        std::string clean_k = (target_key.empty() || target_key == "*") ? "*" : clean_pinyin_string(target_key.c_str());
        remove_from_map(g_user_pinyin_memory, clean_k, false);
        remove_from_map(g_user_memory, target_key.empty() ? "*" : target_key, false);
    } else {
        std::string clean_k = clean_pinyin_string(type_str.c_str());
        if (!clean_k.empty()) {
            remove_from_map(g_user_pinyin_memory, clean_k, false);
        }
        if (!target_key.empty()) {
            remove_from_map(g_user_memory, sanitize_context_key(target_key), false);
        } else if (!removed_custom) {
            remove_from_map(g_user_memory, "*", true);
        }
    }

    if (suppress_bad_word) {
        remove_from_map(g_user_pinyin_memory, "*", false);
        remove_from_map(g_user_memory, "*", false);
        if (target_word.size() <= 2048 &&
            (g_suppressed_words.count(target_word) || g_suppressed_words.size() < MAX_MAP_KEYS)) {
            g_suppressed_words[target_word] = current_time_millis();
            changed = true;
        }
    }

    if (changed) {
        save_user_memory_locked();
    }
    return changed;
}

void rerank_candidates(
    const char *pinyin,
    const char *context,
    const char **candidates,
    const bool *is_user_learned,
    int count,
    int *out_indices
) {
    if (!candidates || count <= 0 || !out_indices) return;

    for (int i = 0; i < count; ++i) {
        out_indices[i] = i;
    }

    std::string ctx_str(context ? context : "");
    std::vector<std::string> tails = extract_context_tails(ctx_str, 8);

    std::vector<LmCandidateScore> scores(count);
    std::vector<bool> boosted(count, false);
    std::vector<bool> user_prioritized(count, false);
    std::vector<bool> is_suppressed(count, false);
    std::vector<int> user_max_freq(count, 0);

    for (int i = 0; i < count; ++i) {
        scores[i].original_index = i;
        scores[i].char_length = candidates[i] ? utf8_char_length(candidates[i]) : 0;
        scores[i].score = -static_cast<float>(i) * 0.4f;
    }

    bool has_boost = false;
    bool first_is_suppressed = false;
    std::string clean_py = clean_pinyin_string(pinyin);

    {
        std::lock_guard<std::mutex> lock(g_user_mem_mutex);
        int64_t now = current_time_millis();

        if (!g_suppressed_words.empty()) {
            for (int i = 0; i < count; ++i) {
                if (!candidates[i]) continue;
                if (g_suppressed_words.find(candidates[i]) != g_suppressed_words.end()) {
                    scores[i].score -= 25.0f;
                    is_suppressed[i] = true;
                    has_boost = true;
                    if (i == 0) first_is_suppressed = true;
                }
            }
        }

        if (!clean_py.empty()) {
            auto it = g_user_pinyin_memory.find(clean_py);
            if (it != g_user_pinyin_memory.end()) {
                for (int i = 0; i < count; ++i) {
                    if (!candidates[i] || is_suppressed[i]) continue;
                    std::string cand(candidates[i]);
                    for (const auto &entry : it->second) {
                        if (entry.word == cand) {
                            int64_t ts = (entry.last_used_timestamp > 0) ? entry.last_used_timestamp : now;
                            double days = std::max(0.0, static_cast<double>(now - ts) / (1000.0 * 86400.0));
                            float half_life = (entry.frequency >= CUSTOM_PHRASE_BASE_FREQ)
                                ? 1.0f
                                : std::pow(0.5f, static_cast<float>(days / 30.0));
                            float s = static_cast<float>(std::min(entry.frequency, 50)) * 20.0f * half_life;
                            scores[i].score += s;
                            has_boost = true;
                            boosted[i] = true;
                            user_prioritized[i] = true;
                            user_max_freq[i] = std::max(user_max_freq[i], entry.frequency);
                            break;
                        }
                    }
                }
            }
        }

        if (!tails.empty()) {
            for (int i = 0; i < count; ++i) {
                if (!candidates[i] || is_suppressed[i]) continue;
                std::string cand(candidates[i]);
                float best_user_score = 0.0f;
                int best_freq = 0;
                for (const auto &tail : tails) {
                    auto it = g_user_memory.find(tail);
                    if (it != g_user_memory.end()) {
                        for (const auto &entry : it->second) {
                            if (entry.word == cand) {
                                int64_t ts = (entry.last_used_timestamp > 0) ? entry.last_used_timestamp : now;
                                double days = std::max(0.0, static_cast<double>(now - ts) / (1000.0 * 86400.0));
                                float half_life_factor = (entry.frequency >= CUSTOM_PHRASE_BASE_FREQ)
                                    ? 1.0f
                                    : std::pow(0.5f, static_cast<float>(days / 30.0));
                                float s = static_cast<float>(std::min(entry.frequency, 50)) * 15.0f * half_life_factor;
                                if (s > best_user_score) {
                                    best_user_score = s;
                                    best_freq = entry.frequency;
                                }
                            }
                        }
                    }
                }
                if (best_user_score > 0.0f) {
                    scores[i].score += best_user_score;
                    has_boost = true;
                    boosted[i] = true;
                    user_prioritized[i] = true;
                    user_max_freq[i] = std::max(user_max_freq[i], best_freq);
                }
            }
        }
    }

    if (is_user_learned) {
        for (int i = 0; i < count; ++i) {
            if (is_user_learned[i] && !is_suppressed[i]) {
                scores[i].score += 15.0f;
                has_boost = true;
                boosted[i] = true;
                user_prioritized[i] = true;
                user_max_freq[i] = std::max(user_max_freq[i], 2);
            }
        }
    }

    std::vector<float> lm_scores(count, 0.0f);
    if (!tails.empty()) {
        std::unique_lock<std::mutex> lock(g_lm_mutex, std::defer_lock);
        if (!g_lm_is_embedded.load(std::memory_order_relaxed)) lock.lock();
        if (g_lm_loaded) {
            auto prep_tails = prepare_lm_tails(tails);
            if (!prep_tails.empty()) {
                for (int i = 0; i < count; ++i) {
                    if (candidates[i] && !is_suppressed[i]) {
                        float s = query_lm_ngram_prepared(prep_tails, candidates[i]);
                        if (s > 0.0f) {
                            lm_scores[i] = s;
                            scores[i].score += s;
                            has_boost = true;
                            boosted[i] = true;
                        }
                    }
                }
            }
        }
    }

    {
        std::unique_lock<std::mutex> lock(g_dict_mutex, std::defer_lock);
        if (!g_dict_is_embedded.load(std::memory_order_relaxed)) lock.lock();
        if (dict_any_loaded()) {
            for (int i = 0; i < count; ++i) {
                if (candidates[i] && !is_suppressed[i]) {
                    float s = query_dict_word_prior(candidates[i]);
                    if (s > 0.0f) {
                        scores[i].score += s;
                        has_boost = true;
                        boosted[i] = true;
                    }
                }
            }
        }
    }

    if (count > 0 && candidates[0] && scores[0].char_length >= 4 && !first_is_suppressed) {
        boosted[0] = true;
        has_boost = true;
    }

    float w_len = g_aggressive_long_phrase.load(std::memory_order_relaxed) ? 1.35f : 0.65f;
    for (int i = 0; i < count; ++i) {
        if (boosted[i] && candidates[i] && !is_suppressed[i]) {
            size_t l = scores[i].char_length;
            if (l > 2) {
                scores[i].score += static_cast<float>(l - 2) * w_len;
            }
        }
    }

    if (has_boost && count > 1) {
        int best_idx = 0;
        float max_score = scores[0].score;
        float orig_0_score = scores[0].score;

        for (int i = 1; i < count; ++i) {
            if (scores[i].score > max_score) {
                max_score = scores[i].score;
                best_idx = i;
            }
        }

        const bool quanpin_mode = g_quanpin_mode.load(std::memory_order_relaxed);
        float flip_gate_delta = 1.20f;

        if (quanpin_mode && !clean_py.empty()) {
            if (clean_py.length() <= 3) {
                flip_gate_delta = 2.50f;
            } else if (clean_py.length() >= 6 && scores[best_idx].char_length >= 3 &&
                       scores[best_idx].char_length > scores[0].char_length) {
                flip_gate_delta = 0.60f;
            } else if (scores[0].char_length >= 2 && scores[best_idx].char_length == scores[0].char_length &&
                       lm_scores[best_idx] == 0.0f && !user_prioritized[best_idx]) {
                flip_gate_delta = 2.20f;
            }
        } else {
            if (scores[0].char_length >= 2 && scores[best_idx].char_length == scores[0].char_length &&
                lm_scores[best_idx] == 0.0f && !user_prioritized[best_idx]) {
                flip_gate_delta = 2.20f;
            }
        }

        bool is_user_chosen = (best_idx != 0 && user_prioritized[best_idx]);
        bool allow_short_override = (is_user_chosen && user_max_freq[best_idx] >= 2);

        bool is_short_truncation = false;
        if (!first_is_suppressed && !allow_short_override && best_idx != 0 &&
            scores[best_idx].char_length < scores[0].char_length) {
            if (scores[0].char_length >= 4) {
                is_short_truncation = true;
            } else if (quanpin_mode && !clean_py.empty() && clean_py.length() >= 6 && scores[0].char_length >= 2) {
                is_short_truncation = true;
            } else if (!quanpin_mode && scores[0].char_length >= 2 && scores[best_idx].char_length == 1) {
                is_short_truncation = true;
            }
        }

        if (!first_is_suppressed &&
            (is_short_truncation || (!is_user_chosen && best_idx != 0 && (max_score - orig_0_score) < flip_gate_delta))) {
            std::stable_sort(scores.begin() + 1, scores.end(), [](const LmCandidateScore &a, const LmCandidateScore &b) {
                return a.score > b.score;
            });
        } else {
            std::stable_sort(scores.begin(), scores.end(), [](const LmCandidateScore &a, const LmCandidateScore &b) {
                return a.score > b.score;
            });
        }

        for (int i = 0; i < count; ++i) {
            out_indices[i] = scores[i].original_index;
        }
    }
}

std::vector<std::string> predict_next_words(const char *context, int max_results) {
    std::vector<std::string> results;
    if (!context || max_results <= 0) return results;

    std::string ctx_str(context);
    std::vector<std::string> tails = extract_context_tails(ctx_str, 8);
    if (tails.empty()) return results;

    std::unordered_set<std::string> suppressed_snapshot;

    {
        std::lock_guard<std::mutex> lock(g_user_mem_mutex);
        for (const auto &kv : g_suppressed_words) {
            suppressed_snapshot.insert(kv.first);
        }
        int64_t now = current_time_millis();
        for (const auto &tail : tails) {
            auto it = g_user_memory.find(tail);
            if (it != g_user_memory.end()) {
                std::vector<std::pair<std::string, float>> ranked_user;
                ranked_user.reserve(it->second.size());
                for (const auto &entry : it->second) {
                    if (suppressed_snapshot.find(entry.word) != suppressed_snapshot.end()) continue;
                    if (ctx_str.size() >= entry.word.size() &&
                        ctx_str.compare(ctx_str.size() - entry.word.size(), entry.word.size(), entry.word) == 0) {
                        continue;
                    }
                    int64_t ts = (entry.last_used_timestamp > 0) ? entry.last_used_timestamp : now;
                    double days = std::max(0.0, static_cast<double>(now - ts) / (1000.0 * 86400.0));
                    float half_life = (entry.frequency >= CUSTOM_PHRASE_BASE_FREQ)
                        ? 1.0f
                        : std::pow(0.5f, static_cast<float>(days / 30.0));
                    ranked_user.push_back({entry.word, static_cast<float>(entry.frequency) * half_life});
                }
                std::sort(ranked_user.begin(), ranked_user.end(), [](const auto &a, const auto &b) {
                    return a.second > b.second;
                });
                for (const auto &item : ranked_user) {
                    if (std::find(results.begin(), results.end(), item.first) == results.end()) {
                        results.push_back(item.first);
                        if (static_cast<int>(results.size()) >= max_results) {
                            return results;
                        }
                    }
                }
            }
        }
    }

    {
        std::unique_lock<std::mutex> lock(g_lm_mutex, std::defer_lock);
        if (!g_lm_is_embedded.load(std::memory_order_relaxed)) lock.lock();
        if (g_lm_loaded) {
            std::string scratch_bytes;
            scratch_bytes.reserve(16);
            for (const auto &tail : tails) {
                std::string enc_tail = encode_octagram(tail);
                size_t node_pos = 0;
                size_t key_pos = 0;
                g_lm_trie.traverse(enc_tail.c_str(), node_pos, key_pos, enc_tail.length());

                if (key_pos == enc_tail.length()) {
                    std::vector<NextWordScore> cand_list;
                    int visited = 0;
                    scratch_bytes.clear();
                    dfs_next_words(g_lm_trie, node_pos, scratch_bytes, 0, 8, visited, 512, cand_list);

                    std::sort(cand_list.begin(), cand_list.end(), [](const NextWordScore &a, const NextWordScore &b) {
                        return a.value > b.value;
                    });

                    for (const auto &item : cand_list) {
                        if (item.word.size() >= 3 &&
                            suppressed_snapshot.find(item.word) == suppressed_snapshot.end() &&
                            std::find(results.begin(), results.end(), item.word) == results.end()) {
                            results.push_back(item.word);
                            if (static_cast<int>(results.size()) >= max_results) {
                                return results;
                            }
                        }
                    }
                }
            }
        }
    }

    return results;
}

void record_user_commit(const char *pinyin, const char *prev_context, const char *committed_text) {
    if (!committed_text) return;
    std::string word = trim_whitespace(committed_text);
    if (word.empty()) return;

    std::lock_guard<std::mutex> lock(g_user_mem_mutex);
    if (g_suppressed_words.find(word) != g_suppressed_words.end()) {
        return;
    }

    int64_t now = current_time_millis();
    bool is_custom_commit = false;

    std::string clean_py = clean_pinyin_string(pinyin);
    if (!clean_py.empty()) {
        auto &py_list = g_user_pinyin_memory[clean_py];
        bool py_found = false;
        for (auto &entry : py_list) {
            if (entry.word == word) {
                if (entry.frequency >= CUSTOM_PHRASE_BASE_FREQ) {
                    is_custom_commit = true;
                } else {
                    entry.frequency++;
                }
                entry.last_used_timestamp = now;
                py_found = true;
                break;
            }
        }
        if (!py_found) {
            if (py_list.size() >= MAX_RECORD_BUCKET_ENTRIES) {
                auto min_it = std::min_element(py_list.begin(), py_list.end(), [](const UserMemoryEntry &a, const UserMemoryEntry &b) {
                    return a.frequency < b.frequency;
                });
                if (min_it != py_list.end() && min_it->frequency < CUSTOM_PHRASE_BASE_FREQ) {
                    *min_it = {word, 1, now};
                }
            } else {
                py_list.push_back({word, 1, now});
            }
        }
    }

    // 若本次上屏的是自定义快捷短语，不额外生成碎片的自学习语境记录
    if (!is_custom_commit && prev_context && std::strlen(prev_context) > 0) {
        std::string ctx_str(prev_context);
        std::vector<std::string> tails = extract_context_tails(ctx_str, 3);
        for (const auto &tail : tails) {
            auto it = g_user_memory.find(tail);
            if (it != g_user_memory.end()) {
                for (auto &entry : it->second) {
                    if (entry.word == word && entry.frequency >= CUSTOM_PHRASE_BASE_FREQ) {
                        entry.last_used_timestamp = now;
                        is_custom_commit = true;
                        break;
                    }
                }
            }
            if (is_custom_commit) break;
        }

        if (!is_custom_commit && !tails.empty()) {
            const std::string &primary_tail = tails.front();
            if (!primary_tail.empty() && primary_tail != "^" && primary_tail != word) {
                auto &list = g_user_memory[primary_tail];
                bool found = false;
                for (auto &entry : list) {
                    if (entry.word == word) {
                        if (entry.frequency < CUSTOM_PHRASE_BASE_FREQ) {
                            entry.frequency++;
                        }
                        entry.last_used_timestamp = now;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    if (list.size() >= MAX_RECORD_BUCKET_ENTRIES) {
                        auto min_it = std::min_element(list.begin(), list.end(), [](const UserMemoryEntry &a, const UserMemoryEntry &b) {
                            return a.frequency < b.frequency;
                        });
                        if (min_it != list.end() && min_it->frequency < CUSTOM_PHRASE_BASE_FREQ) {
                            *min_it = {word, 1, now};
                        }
                    } else {
                        list.push_back({word, 1, now});
                    }
                }
            }
        }
    }

    if (g_user_pinyin_memory.size() > MAX_MAP_KEYS) {
        for (auto it = g_user_pinyin_memory.begin(); it != g_user_pinyin_memory.end();) {
            if (it->second.empty() || (it->second.size() == 1 && it->second[0].frequency <= 1)) {
                it = g_user_pinyin_memory.erase(it);
            } else {
                ++it;
            }
        }
    }

    if (g_user_memory.size() > MAX_MAP_KEYS) {
        for (auto it = g_user_memory.begin(); it != g_user_memory.end();) {
            if (it->second.empty() || (it->second.size() == 1 && it->second[0].frequency <= 1)) {
                it = g_user_memory.erase(it);
            } else {
                ++it;
            }
        }
    }
}

void rollback_user_commit(const char *pinyin, const char *prev_context, const char *committed_text) {
    if (!committed_text) return;
    std::string word = trim_whitespace(committed_text);
    if (word.empty()) return;

    std::lock_guard<std::mutex> lock(g_user_mem_mutex);

    std::string clean_py = clean_pinyin_string(pinyin);
    if (!clean_py.empty()) {
        auto it = g_user_pinyin_memory.find(clean_py);
        if (it != g_user_pinyin_memory.end()) {
            auto &list = it->second;
            for (auto entry_it = list.begin(); entry_it != list.end();) {
                if (entry_it->word == word) {
                    if (entry_it->frequency < CUSTOM_PHRASE_BASE_FREQ) {
                        entry_it->frequency--;
                    }
                    if (entry_it->frequency <= 0) {
                        entry_it = list.erase(entry_it);
                    } else {
                        ++entry_it;
                    }
                    break;
                } else {
                    ++entry_it;
                }
            }
            if (list.empty()) {
                g_user_pinyin_memory.erase(it);
            }
        }
    }

    if (prev_context && std::strlen(prev_context) > 0) {
        std::string ctx_str(prev_context);
        std::vector<std::string> tails = extract_context_tails(ctx_str, 3);
        for (const auto &tail : tails) {
            if (tail.empty()) continue;
            auto it = g_user_memory.find(tail);
            if (it != g_user_memory.end()) {
                auto &list = it->second;
                for (auto entry_it = list.begin(); entry_it != list.end();) {
                    if (entry_it->word == word) {
                        if (entry_it->frequency < CUSTOM_PHRASE_BASE_FREQ) {
                            entry_it->frequency--;
                        }
                        if (entry_it->frequency <= 0) {
                            entry_it = list.erase(entry_it);
                        } else {
                            ++entry_it;
                        }
                        break;
                    } else {
                        ++entry_it;
                    }
                }
                if (list.empty()) {
                    g_user_memory.erase(it);
                }
            }
        }
    }
}

std::string truncate_sentence_context(const std::string &ctx) {
    if (ctx.empty()) return "";
    const char *p = ctx.data();
    const char *end = p + ctx.size();
    size_t last_boundary_end = std::string::npos;
    while (p < end) {
        uint32_t cp = next_utf8_codepoint(p, end);
        if (is_boundary_codepoint(cp)) {
            last_boundary_end = static_cast<size_t>(p - ctx.data());
        }
    }
    if (last_boundary_end == std::string::npos) return ctx;
    if (last_boundary_end >= ctx.size()) return "";
    return ctx.substr(last_boundary_end);
}

bool text_contains_chinese(const std::string &text) {
    const char *p = text.data();
    const char *end = p + text.size();
    while (p < end) {
        uint32_t cp = next_utf8_codepoint(p, end);
        if ((cp >= 0x4E00 && cp <= 0x9FFF) ||
            (cp >= 0x3400 && cp <= 0x4DBF) ||
            (cp >= 0x20000 && cp <= 0x2A6DF)) {
            return true;
        }
    }
    return false;
}

} // namespace xy
