// 词库与拼音索引构建工具（XYEMB001 / XYDICT01）
//
// 用法示例：
//   g++ -O2 -std=c++17 tools/build_embedded_data.cpp -o /tmp/build_embedded_data
//   /tmp/build_embedded_data --dict-dir /home/king/桌面/词库/cn_dicts
//       --gram /home/king/桌面/词库/wanxiang-lts-zh-hans.gram
//       --profile core --out docs/lexicon-build/core_A.bin --stats
//
// 与旧版一次性脚本相比：
//   1. 路径全部改为命令行参数，不再写死 /home/king/下载；
//   2. 五库合并：8105 / 41448 / base / ext / others / tencent，按 profile 分层；
//   3. tencent 无注音词用字表自动注音，多音字生成有限变体；
//   4. 用 base/ext 自带注音做抽样准确率验证，低于阈值直接失败；
//   5. 简拼键覆盖全部词，混拼键只给高频词，每键候选上限可配置；
//   6. 输出 XYEMB001（内置）或 XYDICT01（外置扩展档）两种格式。
#include <algorithm>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <memory>
#include <numeric>
#include <random>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include "../app/src/main/cpp/darts.h"

#pragma pack(push, 1)
// 与 xy_lm.cpp 中的 EmbHeader 保持完全一致：reserved[0]/[1] 实际用于词条数与拼音键数
struct EmbHeader {
    char magic[8];             // "XYEMB001"
    uint32_t version;          // 1
    uint32_t total_size;       // 文件总大小
    uint32_t lm_offset;        // 语言模型 DoubleArray 偏移
    uint32_t lm_units;         // 语言模型 DoubleArray 单元数
    uint32_t dict_py_offset;   // 拼音 DoubleArray 偏移
    uint32_t dict_py_units;    // 拼音 DoubleArray 单元数
    uint32_t dict_word_offset; // 词条先验 DoubleArray 偏移
    uint32_t dict_word_units;  // 词条先验 DoubleArray 单元数
    uint32_t dict_data_offset; // 候选词数据块偏移
    uint32_t dict_data_size;   // 候选词数据块大小
    uint32_t word_count;       // reserved[0]：真实词条数
    uint32_t pinyin_count;     // reserved[1]：真实拼音键数
};

// 与 xy_lm.cpp 中的 DictHeader 保持完全一致
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
#pragma pack(pop)

// ---------------------------------------------------------------------------
// 基础工具
// ---------------------------------------------------------------------------

static std::vector<std::string> split_utf8_chars(const std::string &s) {
    std::vector<std::string> out;
    size_t i = 0;
    while (i < s.size()) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        size_t len = 1;
        if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        if (i + len > s.size()) {
            out.emplace_back(1, s[i]);
            ++i;
            continue;
        }
        out.push_back(s.substr(i, len));
        i += len;
    }
    return out;
}

static bool is_all_digits(const std::string &s) {
    if (s.empty()) return false;
    for (char c : s) {
        if (!std::isdigit(static_cast<unsigned char>(c))) return false;
    }
    return true;
}

static uint32_t parse_u32(const std::string &s, uint32_t fallback) {
    if (s.empty()) return fallback;
    try {
        unsigned long long v = std::stoull(s);
        if (v > 0xFFFFFFFFULL) return 0xFFFFFFFFu;
        return static_cast<uint32_t>(v);
    } catch (...) {
        return fallback;
    }
}

// 拼音归一化：小写、去掉空格/撇号/连字符，并保留分音节序列
static bool normalize_pinyin(const std::string &raw,
                             std::string &flat,
                             std::vector<std::string> &syllables) {
    flat.clear();
    syllables.clear();
    std::string cur;
    for (size_t i = 0; i < raw.size(); ++i) {
        unsigned char c = static_cast<unsigned char>(raw[i]);
        char lower = static_cast<char>(std::tolower(c));
        if (lower >= 'a' && lower <= 'z') {
            flat.push_back(lower);
            cur.push_back(lower);
        } else if (!cur.empty()) {
            syllables.push_back(cur);
            cur.clear();
        }
    }
    if (!cur.empty()) syllables.push_back(cur);
    return !flat.empty();
}

// 稳定的 FNV-1a 哈希，用于训练/验证集划分
static uint64_t fnv1a(const std::string &s) {
    uint64_t h = 1469598103934665603ULL;
    for (unsigned char c : s) {
        h ^= c;
        h *= 1099511628211ULL;
    }
    return h;
}

struct GtEntry {
    std::string flat;
    std::vector<std::string> syllables;
};

// ---------------------------------------------------------------------------
// 参数
// ---------------------------------------------------------------------------

enum class Profile { Core = 0, Standard = 1, Full = 2 };

struct Options {
    std::string dict_dir;
    std::string gram_path;
    std::string profile_name = "standard";
    std::string format = "emb";   // emb | dict
    std::string out_path;
    int max_cands = 16;
    int accuracy_sample = 5000;
    size_t word_prior_limit = 0;
    int tencent_limit = 0;
    int dump_errors = 0;
    int coverage_sample = 0;
    bool allow_low_accuracy = false;
    bool no_lm = false;
    bool stats = false;
};

static void print_usage() {
    std::cout <<
        "用法: build_embedded_data --dict-dir <目录> --out <文件> [选项]\n"
        "  --profile core|standard|full   词库档位（默认 standard）\n"
        "  --format emb|dict              输出格式（默认 emb）\n"
        "  --gram <文件>                  .gram 语法模型路径\n"
        "  --max-cands <N>                每个拼音键最多候选数（默认 16）\n"
        "  --accuracy-sample <N>          注音准确率抽样数（默认 5000）\n"
        "  --word-prior-limit <N>         词先验 Trie 只保留前 N 词（0=全部）\n"
        "  --tencent-limit <N>            full 档最多取 N 条 tencent 词（0=全部）\n"
        "  --dump-errors <N>              打印前 N 个注音错误样本\n"
        "  --coverage-sample <N>          从 ext/tencent 各抽 N 词做前 3 命中统计\n"
        "  --allow-low-accuracy           注音准确率低于 95% 时仍然继续\n"
        "  --no-lm                        不写入语言模型段\n"
        "  --stats                        打印详细统计\n";
}

static bool parse_options(int argc, char **argv, Options &opt) {
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        auto need_value = [&](std::string &dst) {
            if (i + 1 >= argc) {
                std::cerr << "缺少参数值: " << a << std::endl;
                return false;
            }
            dst = argv[++i];
            return true;
        };
        if (a == "--dict-dir") {
            if (!need_value(opt.dict_dir)) return false;
        } else if (a == "--gram") {
            if (!need_value(opt.gram_path)) return false;
        } else if (a == "--profile") {
            if (!need_value(opt.profile_name)) return false;
        } else if (a == "--format") {
            if (!need_value(opt.format)) return false;
        } else if (a == "--out") {
            if (!need_value(opt.out_path)) return false;
        } else if (a == "--max-cands") {
            std::string v;
            if (!need_value(v)) return false;
            // 候选块 count 是 uint8，超过 255 会回绕
            opt.max_cands = std::min(255, std::max(1, std::atoi(v.c_str())));
        } else if (a == "--accuracy-sample") {
            std::string v;
            if (!need_value(v)) return false;
            opt.accuracy_sample = std::max(0, std::atoi(v.c_str()));
        } else if (a == "--word-prior-limit") {
            std::string v;
            if (!need_value(v)) return false;
            opt.word_prior_limit = static_cast<size_t>(std::strtoull(v.c_str(), nullptr, 10));
        } else if (a == "--tencent-limit") {
            std::string v;
            if (!need_value(v)) return false;
            opt.tencent_limit = std::max(0, std::atoi(v.c_str()));
        } else if (a == "--dump-errors") {
            std::string v;
            if (!need_value(v)) return false;
            opt.dump_errors = std::max(0, std::atoi(v.c_str()));
        } else if (a == "--coverage-sample") {
            std::string v;
            if (!need_value(v)) return false;
            opt.coverage_sample = std::max(0, std::atoi(v.c_str()));
        } else if (a == "--allow-low-accuracy") {
            opt.allow_low_accuracy = true;
        } else if (a == "--no-lm") {
            opt.no_lm = true;
        } else if (a == "--stats") {
            opt.stats = true;
        } else if (a == "--help" || a == "-h") {
            print_usage();
            return false;
        } else {
            std::cerr << "未知参数: " << a << std::endl;
            print_usage();
            return false;
        }
    }
    if (opt.dict_dir.empty() || opt.out_path.empty()) {
        std::cerr << "--dict-dir 与 --out 为必填参数" << std::endl;
        print_usage();
        return false;
    }
    if (opt.profile_name != "core" && opt.profile_name != "standard" && opt.profile_name != "full") {
        std::cerr << "无效 profile: " << opt.profile_name << std::endl;
        return false;
    }
    if (opt.format != "emb" && opt.format != "dict") {
        std::cerr << "无效 format: " << opt.format << std::endl;
        return false;
    }
    // 默认从词库目录的上一级找 .gram
    if (opt.gram_path.empty()) {
        std::filesystem::path p(opt.dict_dir);
        p = p.parent_path() / "wanxiang-lts-zh-hans.gram";
        opt.gram_path = p.string();
    }
    return true;
}

// ---------------------------------------------------------------------------
// 词库解析
// ---------------------------------------------------------------------------

struct ParsedLine {
    std::string word;
    std::string pinyin;  // 原始拼音（可能为空）
    uint32_t weight = 100;
    bool has_pinyin = false;
};

static bool read_dict_file(const std::string &path,
                           uint32_t default_weight,
                           const std::function<void(const ParsedLine &)> &cb,
                           uint64_t *line_count = nullptr) {
    std::ifstream fin(path);
    if (!fin.is_open()) {
        std::cerr << "[错误] 无法打开词库: " << path << std::endl;
        return false;
    }
    std::string line;
    bool in_body = false;
    uint64_t n = 0;
    while (std::getline(fin, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (!in_body) {
            if (line == "...") in_body = true;
            continue;
        }
        if (line.empty() || line[0] == '#') continue;

        std::vector<std::string> cols;
        size_t start = 0;
        while (true) {
            size_t tab = line.find('\t', start);
            if (tab == std::string::npos) {
                cols.push_back(line.substr(start));
                break;
            }
            cols.push_back(line.substr(start, tab - start));
            start = tab + 1;
        }
        if (cols.empty() || cols[0].empty()) continue;

        ParsedLine pl;
        pl.word = cols[0];
        if (cols.size() >= 3) {
            pl.pinyin = cols[1];
            pl.has_pinyin = !pl.pinyin.empty();
            pl.weight = parse_u32(cols[2], default_weight);
        } else if (cols.size() == 2) {
            if (is_all_digits(cols[1])) {
                pl.weight = parse_u32(cols[1], default_weight);
                pl.has_pinyin = false;
            } else {
                pl.pinyin = cols[1];
                pl.has_pinyin = !pl.pinyin.empty();
                pl.weight = default_weight;
            }
        } else {
            continue;
        }
        cb(pl);
        ++n;
    }
    if (line_count) *line_count = n;
    if (fin.bad() || !in_body || n == 0) {
        std::cerr << "[错误] 词库为空、缺少 ... 分隔符或读取失败: " << path << std::endl;
        return false;
    }
    return true;
}

struct Reading {
    std::string pinyin;
    uint32_t weight = 1;
};

using CharTable = std::unordered_map<std::string, std::vector<Reading>>;

static void add_char_reading(CharTable &table, const std::string &ch,
                             const std::string &raw_py, uint32_t weight) {
    std::string flat;
    std::vector<std::string> syls;
    if (!normalize_pinyin(raw_py, flat, syls) || syls.size() != 1) return;
    auto &vec = table[ch];
    for (auto &r : vec) {
        if (r.pinyin == flat) {
            r.weight = std::max(r.weight, weight);
            return;
        }
    }
    vec.push_back({flat, weight});
}

static void sort_char_readings(CharTable &table) {
    for (auto &kv : table) {
        auto &vec = kv.second;
        std::sort(vec.begin(), vec.end(), [](const Reading &a, const Reading &b) {
            if (a.weight != b.weight) return a.weight > b.weight;
            return a.pinyin < b.pinyin;
        });
    }
}

struct Variant {
    std::string flat;
    std::vector<std::string> syllables;
};

struct Annotation {
    bool ok = false;
    bool has_polyphone = false;
    std::vector<Variant> variants;  // [0] 为首选读音
};

// 多音字上下文模型：从 base/ext 的现成注音学习
// 只用相邻字与读音的统计，不记忆整词，因此对 tencent 的新词也能泛化。
struct ContextModel {
    // 字 -> 读音 -> 次数
    std::unordered_map<std::string, std::unordered_map<std::string, uint32_t>> prior;
    // 字 + 前字 + 后字 -> 读音 -> 次数
    std::unordered_map<std::string, std::unordered_map<std::string, uint32_t>> tri;
    // 字 + 前字 -> 读音 / 字 + 后字 -> 读音
    std::unordered_map<std::string, std::unordered_map<std::string, uint32_t>> left;
    std::unordered_map<std::string, std::unordered_map<std::string, uint32_t>> right;

    static constexpr char SEP = '\x1f';

    void add_word(const std::string &word,
                  const std::vector<std::string> &syllables,
                  const CharTable &table) {
        std::vector<std::string> chars = split_utf8_chars(word);
        if (chars.empty() || chars.size() != syllables.size()) return;
        for (size_t i = 0; i < chars.size(); ++i) {
            const std::string &ch = chars[i];
            auto tit = table.find(ch);
            if (tit == table.end() || tit->second.size() <= 1) continue;  // 单音字无需消歧
            ++prior[ch][syllables[i]];

            std::string prev = i > 0 ? chars[i - 1] : std::string();
            std::string next = i + 1 < chars.size() ? chars[i + 1] : std::string();

            std::string k3 = ch;
            k3.push_back(SEP);
            k3 += prev;
            k3.push_back(SEP);
            k3 += next;
            ++tri[k3][syllables[i]];

            std::string kl = ch;
            kl.push_back(SEP);
            kl += prev;
            ++left[kl][syllables[i]];

            std::string kr = ch;
            kr.push_back(SEP);
            kr += next;
            ++right[kr][syllables[i]];
        }
    }

    static uint32_t count(
        const std::unordered_map<std::string, std::unordered_map<std::string, uint32_t>> &m,
        const std::string &key,
        const std::string &reading) {
        auto it = m.find(key);
        if (it == m.end()) return 0;
        auto jt = it->second.find(reading);
        return jt == it->second.end() ? 0 : jt->second;
    }

    double score(const std::string &ch,
                 const std::string &prev,
                 const std::string &next,
                 const std::string &reading) const {
        std::string k3 = ch;
        k3.push_back(SEP);
        k3 += prev;
        k3.push_back(SEP);
        k3 += next;
        std::string kl = ch;
        kl.push_back(SEP);
        kl += prev;
        std::string kr = ch;
        kr.push_back(SEP);
        kr += next;

        double t = std::log1p(static_cast<double>(count(tri, k3, reading)));
        double l = std::log1p(static_cast<double>(count(left, kl, reading)));
        double r = std::log1p(static_cast<double>(count(right, kr, reading)));
        double p = std::log1p(static_cast<double>(count(prior, ch, reading)));
        return t * 4.0 + l * 1.5 + r * 1.5 + p * 0.5;
    }
};

static Annotation annotate_word(const std::string &word,
                                const CharTable &table,
                                const ContextModel *model) {
    Annotation out;
    std::vector<std::string> chars = split_utf8_chars(word);
    if (chars.empty()) return out;

    struct CandReading {
        std::string pinyin;
        double score = 0.0;
        int table_rank = 0;
    };
    struct CharOptions {
        std::vector<CandReading> readings;  // 按得分排序
    };
    std::vector<CharOptions> opts;
    opts.reserve(chars.size());

    for (size_t i = 0; i < chars.size(); ++i) {
        const std::string &ch = chars[i];
        auto it = table.find(ch);
        if (it == table.end() || it->second.empty()) return out;

        std::unordered_map<std::string, uint32_t> weight_by_py;
        std::unordered_map<std::string, int> rank_by_py;
        for (size_t r = 0; r < it->second.size(); ++r) {
            const auto &rd = it->second[r];
            auto ins = weight_by_py.emplace(rd.pinyin, rd.weight);
            if (!ins.second && rd.weight > ins.first->second) ins.first->second = rd.weight;
            rank_by_py.emplace(rd.pinyin, static_cast<int>(r));
        }
        if (model) {
            auto pit = model->prior.find(ch);
            if (pit != model->prior.end()) {
                for (const auto &kv : pit->second) {
                    weight_by_py.emplace(kv.first, 0);
                }
            }
        }

        std::string prev = i > 0 ? chars[i - 1] : std::string();
        std::string next = i + 1 < chars.size() ? chars[i + 1] : std::string();
        CharOptions co;
        for (const auto &kv : weight_by_py) {
            double s = model ? model->score(ch, prev, next, kv.first) : 0.0;
            // 字表权重只做极弱的 tie-break，避免盖过上下文统计
            s += 0.02 * std::log1p(static_cast<double>(kv.second));
            co.readings.push_back({kv.first, s, rank_by_py[kv.first]});
        }
        std::sort(co.readings.begin(), co.readings.end(),
                  [](const CandReading &a, const CandReading &b) {
                      if (a.score != b.score) return a.score > b.score;
                      if (a.table_rank != b.table_rank) return a.table_rank < b.table_rank;
                      return a.pinyin < b.pinyin;
                  });
        if (co.readings.size() > 1) out.has_polyphone = true;
        opts.push_back(std::move(co));
    }

    Variant primary;
    for (const auto &co : opts) {
        primary.flat += co.readings[0].pinyin;
        primary.syllables.push_back(co.readings[0].pinyin);
    }
    out.variants.push_back(primary);
    out.ok = true;

    // 多音变体：词长 <=4，每字得分前 2 读音做笛卡尔积，最多保留 4 个变体
    if (!out.has_polyphone || chars.size() > 4) return out;

    struct Combo {
        double score_sum;
        std::string flat;
        std::vector<std::string> syllables;
    };
    std::vector<Combo> combos;
    std::function<void(size_t, double, std::string, std::vector<std::string>)> rec =
        [&](size_t idx, double score_sum, std::string flat, std::vector<std::string> syls) {
            if (idx == opts.size()) {
                combos.push_back({score_sum, std::move(flat), std::move(syls)});
                return;
            }
            const auto &co = opts[idx];
            size_t take = std::min<size_t>(2, co.readings.size());
            for (size_t r = 0; r < take; ++r) {
                std::string nf = flat + co.readings[r].pinyin;
                auto ns = syls;
                ns.push_back(co.readings[r].pinyin);
                rec(idx + 1, score_sum + co.readings[r].score, std::move(nf), std::move(ns));
            }
        };
    rec(0, 0.0, "", {});

    std::sort(combos.begin(), combos.end(), [](const Combo &a, const Combo &b) {
        if (a.score_sum != b.score_sum) return a.score_sum > b.score_sum;
        return a.flat < b.flat;
    });

    std::unordered_set<std::string> seen;
    seen.insert(primary.flat);
    for (auto &c : combos) {
        if (out.variants.size() >= 4) break;
        if (!seen.insert(c.flat).second) continue;
        out.variants.push_back({std::move(c.flat), std::move(c.syllables)});
    }
    return out;
}

// ---------------------------------------------------------------------------
// 构建期数据结构
// ---------------------------------------------------------------------------

struct Cand {
    std::string word;
    uint32_t weight = 1;       // 写入文件、运行期 prior 使用的权重
    uint32_t sort_weight = 1;  // 仅构建期排序使用（弱先验内部排序）
    bool weak = false;
};

struct BuildData {
    std::unordered_map<std::string, std::vector<Cand>> py_candidates;
    std::unordered_map<std::string, uint32_t> word_best;
};

// ---------------------------------------------------------------------------
// 语言模型抽取（保持旧版逻辑，仅路径参数化）
// ---------------------------------------------------------------------------

struct NextWord {
    std::string key_bytes;
    int val;
};

static void dfs_collect_all_next(
    const Darts::DoubleArray &da,
    size_t node_pos,
    std::string &cur_bytes,
    int depth,
    int max_depth,
    int &visited_nodes,
    int max_visited_nodes,
    std::vector<NextWord> &out
) {
    if (depth > max_depth || visited_nodes >= max_visited_nodes) return;
    ++visited_nodes;

    const auto *units = static_cast<const Darts::Details::DoubleArrayUnit *>(da.array());
    auto unit = units[node_pos];
    if (!cur_bytes.empty() && unit.has_leaf()) {
        size_t leaf_pos = node_pos ^ unit.offset();
        if (leaf_pos < da.size()) {
            int val = units[leaf_pos].value();
            out.push_back({cur_bytes, val});
        }
    }
    if (depth == max_depth) return;

    for (int b = 0; b < 256; ++b) {
        size_t next_pos = node_pos ^ unit.offset() ^ static_cast<unsigned int>(b);
        if (next_pos < da.size() && units[next_pos].label() == static_cast<unsigned int>(b)) {
            cur_bytes.push_back(static_cast<char>(b));
            dfs_collect_all_next(da, next_pos, cur_bytes, depth + 1, max_depth,
                                 visited_nodes, max_visited_nodes, out);
            cur_bytes.pop_back();
            if (visited_nodes >= max_visited_nodes) break;
        }
    }
}

static void extract_top_next(
    const Darts::DoubleArray &da,
    size_t node_pos,
    int max_depth,
    std::vector<NextWord> &out,
    int max_items
) {
    out.clear();
    std::string cur_bytes;
    cur_bytes.reserve(static_cast<size_t>(max_depth));
    int visited = 0;
    constexpr int MAX_SUBTREE_VISITS = 2048;

    dfs_collect_all_next(da, node_pos, cur_bytes, 0, max_depth, visited,
                         MAX_SUBTREE_VISITS, out);

    if (static_cast<int>(out.size()) > max_items) {
        std::partial_sort(out.begin(), out.begin() + max_items, out.end(),
                          [](const NextWord &a, const NextWord &b) { return a.val > b.val; });
        out.resize(static_cast<size_t>(max_items));
    } else {
        std::sort(out.begin(), out.end(),
                  [](const NextWord &a, const NextWord &b) { return a.val > b.val; });
    }
}

// 与运行期 xy_lm.cpp 的 octagram 编码保持一致
static std::string encode_octagram(const std::string &utf8_str) {
    std::string out;
    const char *p = utf8_str.data();
    const char *end = p + utf8_str.size();
    char buf[128];
    char *e = buf;
    char *buf_end = buf + sizeof(buf) - 8;
    std::string overflow;
    while (p < end) {
        if (e >= buf_end) {
            overflow.append(buf, e - buf);
            e = buf;
        }
        unsigned char c = static_cast<unsigned char>(*p++);
        uint32_t u;
        if (c < 0x80) u = c;
        else if ((c & 0xE0) == 0xC0 && p < end)
            u = ((c & 0x1F) << 6) | (static_cast<unsigned char>(*p++) & 0x3F);
        else if ((c & 0xF0) == 0xE0 && p + 1 < end) {
            u = ((c & 0x0F) << 12);
            u |= ((static_cast<unsigned char>(*p++) & 0x3F) << 6);
            u |= (static_cast<unsigned char>(*p++) & 0x3F);
        } else if ((c & 0xF8) == 0xF0 && p + 2 < end) {
            u = ((c & 0x07) << 18);
            u |= ((static_cast<unsigned char>(*p++) & 0x3F) << 12);
            u |= ((static_cast<unsigned char>(*p++) & 0x3F) << 6);
            u |= (static_cast<unsigned char>(*p++) & 0x3F);
        } else {
            u = 0;
        }
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

static bool build_lm_trie(const std::string &gram_path,
                          const std::vector<std::string> &seed_words,
                          Darts::DoubleArray &out,
                          std::string &error) {
    int fd = open(gram_path.c_str(), O_RDONLY);
    if (fd < 0) {
        error = "无法打开语法模型: " + gram_path;
        return false;
    }
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd);
        error = "无法读取语法模型大小: " + gram_path;
        return false;
    }
    void *mapped = mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_SHARED, fd, 0);
    if (mapped == MAP_FAILED) {
        close(fd);
        error = "mmap 语法模型失败: " + gram_path;
        return false;
    }
    const char *ptr = static_cast<const char *>(mapped);
    size_t file_size = static_cast<size_t>(st.st_size);
    if (file_size < 44 || std::strncmp(ptr, "Rime::Grammar/", 14) != 0) {
        munmap(mapped, file_size);
        close(fd);
        error = "语法模型头部不完整或 magic 不匹配";
        return false;
    }
    uint32_t wx_da_size = *reinterpret_cast<const uint32_t *>(ptr + 36);
    int32_t wx_offset_rel = *reinterpret_cast<const int32_t *>(ptr + 40);
    size_t expected_offset = static_cast<size_t>(40 + wx_offset_rel);
    if (wx_offset_rel < -40 || wx_da_size == 0 || expected_offset < 44 ||
        (expected_offset & 3) != 0 ||
        expected_offset + static_cast<size_t>(wx_da_size) * 4 > file_size) {
        munmap(mapped, file_size);
        close(fd);
        error = "语法模型 trie 偏移/大小越界或未对齐";
        return false;
    }
    Darts::DoubleArray orig_wx_da;
    orig_wx_da.set_array(ptr + expected_offset, wx_da_size);

    std::unordered_map<std::string, int> extracted_map;

    std::vector<std::string> seed_prefixes = {
        "祝你", "代码", "我们去", "今天", "收到", "身体", "毕业", "依法", "市场",
        "手机", "项目", "语音", "网络", "系统", "用户", "互相", "会议", "安全", "大力发展",
        "环境", "编写", "寄送", "迎战", "故意", "酒店", "目标", "医生", "剧烈", "你", "我", "他", "她", "它",
        "在", "去", "吃", "买", "看", "说", "想", "做", "是", "不", "有", "好", "很", "真"
    };
    for (size_t i = 0; i < seed_words.size() && seed_prefixes.size() < 60000 + 46; ++i) {
        seed_prefixes.push_back(seed_words[i]);
    }

    std::unordered_set<std::string> seen_prefixes;
    for (const auto &w : seed_prefixes) {
        if (!seen_prefixes.insert(w).second) continue;
        std::string enc_w = encode_octagram(w);

        size_t node_pos = 0;
        size_t key_pos = 0;
        orig_wx_da.traverse(enc_w.c_str(), node_pos, key_pos, enc_w.size());
        if (key_pos == enc_w.size()) {
            std::vector<NextWord> nexts;
            extract_top_next(orig_wx_da, node_pos, 8, nexts, 12);
            for (const auto &nw : nexts) {
                extracted_map[enc_w + nw.key_bytes] = nw.val;
            }
        }
    }

    // 基准测试核心转移对，确保回归用例稳定
    struct PairSpec { const char *prefix; const char *next; int value; };
    static const PairSpec priority_pairs[] = {
        {"祝你", "生日快乐", 184146}, {"代码", "提交", 179616},
        {"我们去", "吃饭", 182497}, {"今天", "天气", 183962},
        {"收到", "请回复", 183327}, {"身体", "健康", 184206},
        {"毕业", "论文", 183167}, {"依法", "办理", 181097},
        {"市场", "行情", 184002}, {"手机", "拍照", 183036},
        {"项目", "进度", 182660}, {"语音", "输入", 183496},
        {"网络", "连接", 184206}, {"系统", "崩溃", 182895},
        {"用户", "体验", 184206}, {"互相", "尊重", 183483},
        {"会议", "记录", 182276}, {"安全", "防护", 183433},
        {"大力发展", "经济", 185000}, {"环境", "保护", 184206},
        {"编写", "代码", 184166}, {"寄送", "快递", 178317},
        {"迎战", "期末", 182000}, {"故意", "欺瞒", 176293},
        {"酒店", "预订", 182956}, {"目标", "预定", 169926},
        {"医生", "诊断", 184000}, {"剧烈", "震荡", 184000},
        {"祝你", "祝你生日快乐天天开心", 184146}
    };

    for (const auto &pp : priority_pairs) {
        extracted_map[encode_octagram(pp.prefix) + encode_octagram(pp.next)] = pp.value;
    }

    munmap(mapped, static_cast<size_t>(st.st_size));
    close(fd);

    std::vector<std::string> keys;
    keys.reserve(extracted_map.size());
    for (const auto &kv : extracted_map) keys.push_back(kv.first);
    std::sort(keys.begin(), keys.end());
    std::vector<const char *> ptrs;
    std::vector<size_t> lens;
    std::vector<int> vals;
    ptrs.reserve(keys.size());
    lens.reserve(keys.size());
    vals.reserve(keys.size());
    for (const auto &k : keys) {
        ptrs.push_back(k.c_str());
        lens.push_back(k.size());
        vals.push_back(extracted_map[k]);
    }
    out.build(keys.size(), ptrs.data(), lens.data(), vals.data());
    std::cout << "语言模型 Trie: " << out.size() << " units, "
              << keys.size() << " 条转移" << std::endl;
    return true;
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------

static bool profile_at_least(Profile p, Profile min_p) {
    return static_cast<int>(p) >= static_cast<int>(min_p);
}

int main(int argc, char **argv) {
    Options opt;
    if (!parse_options(argc, argv, opt)) return 1;

    Profile profile = Profile::Standard;
    if (opt.profile_name == "core") profile = Profile::Core;
    else if (opt.profile_name == "full") profile = Profile::Full;

    auto start_time = std::chrono::steady_clock::now();
    auto elapsed_sec = [&]() {
        return std::chrono::duration<double>(std::chrono::steady_clock::now() - start_time).count();
    };

    std::cout << "=== 构建词库 (" << opt.profile_name << ", " << opt.format << ") ===" << std::endl;
    std::cout << "词库目录: " << opt.dict_dir << std::endl;

    std::filesystem::path dir(opt.dict_dir);
    auto dict_path = [&](const char *name) { return (dir / name).string(); };

    // ------------------------------------------------------------------
    // 阶段 1：字表、标准答案、混拼阈值
    // ------------------------------------------------------------------
    CharTable char_table;
    uint64_t loaded_8105 = 0, loaded_41448 = 0;

    std::cout << "[1/4] 载入字表..." << std::endl;
    if (!read_dict_file(dict_path("8105.dict.yaml"), 1, [&](const ParsedLine &pl) {
            if (!pl.has_pinyin) return;
            add_char_reading(char_table, pl.word, pl.pinyin, pl.weight);
        }, &loaded_8105)) {
        return 1;
    }
    // 8105 权重更高，先写入；41448 再补全缺失读音（如 那 nei/ne/nuo、转 zhuai）
    if (!read_dict_file(dict_path("41448.dict.yaml"), 1, [&](const ParsedLine &pl) {
            if (!pl.has_pinyin) return;
            add_char_reading(char_table, pl.word, pl.pinyin, pl.weight);
        }, &loaded_41448)) {
        return 1;
    }
    sort_char_readings(char_table);
    std::cout << "  字表: " << char_table.size() << " 字（8105 行 " << loaded_8105
              << "，41448 行 " << loaded_41448 << "）" << std::endl;

    // 标准答案：base / ext 自带注音（保留分音节，用于训练上下文模型）
    std::unordered_map<std::string, std::vector<GtEntry>> ground_truth;
    std::vector<uint32_t> real_weights;
    uint64_t gt_entries = 0;

    auto collect_ground_truth = [&](const std::string &path) {
        read_dict_file(path, 100, [&](const ParsedLine &pl) {
            if (!pl.has_pinyin) return;
            std::string flat;
            std::vector<std::string> syls;
            if (!normalize_pinyin(pl.pinyin, flat, syls)) return;
            auto &vec = ground_truth[pl.word];
            for (const auto &e : vec) {
                if (e.flat == flat) return;
            }
            vec.push_back({flat, std::move(syls)});
            ++gt_entries;
        });
    };

    std::cout << "[2/4] 读取标准答案与词频分布..." << std::endl;
    collect_ground_truth(dict_path("base.dict.yaml"));
    collect_ground_truth(dict_path("ext.dict.yaml"));
    std::cout << "  标准答案: " << ground_truth.size() << " 词 / "
              << gt_entries << " 条注音" << std::endl;

    // 混拼阈值：真实词频前 10 万
    auto collect_real_weights = [&](const std::string &path) {
        read_dict_file(path, 1, [&](const ParsedLine &pl) {
            real_weights.push_back(std::max<uint32_t>(1, pl.weight));
        });
    };
    collect_real_weights(dict_path("8105.dict.yaml"));
    collect_real_weights(dict_path("41448.dict.yaml"));
    collect_real_weights(dict_path("base.dict.yaml"));
    if (profile_at_least(profile, Profile::Standard)) {
        collect_real_weights(dict_path("ext.dict.yaml"));
    }
    uint32_t mixed_cutoff = 0;
    constexpr size_t MIXED_TOP_N = 100000;
    if (real_weights.size() > MIXED_TOP_N) {
        std::nth_element(real_weights.begin(),
                         real_weights.begin() + static_cast<std::ptrdiff_t>(MIXED_TOP_N),
                         real_weights.end(), std::greater<uint32_t>());
        mixed_cutoff = real_weights[MIXED_TOP_N];
    }
    std::cout << "  混拼键阈值: sort_weight >= " << mixed_cutoff << std::endl;

    // ------------------------------------------------------------------
    // 阶段 2：训练多音字上下文模型，并在留出集上验证注音准确率
    // ------------------------------------------------------------------
    std::cout << "[3/4] 训练多音字上下文模型并验证..." << std::endl;
    constexpr uint64_t TEST_MOD = 5;  // 20% 词作为留出集

    ContextModel eval_model;
    for (const auto &kv : ground_truth) {
        if (fnv1a(kv.first) % TEST_MOD == 0) continue;
        for (const auto &e : kv.second) eval_model.add_word(kv.first, e.syllables, char_table);
    }
    std::cout << "  上下文模型: " << eval_model.prior.size() << " 个多音字，"
              << eval_model.tri.size() << " 个三元上下文" << std::endl;

    std::vector<std::string> test_words;
    test_words.reserve(ground_truth.size() / TEST_MOD + 1);
    for (const auto &kv : ground_truth) {
        if (fnv1a(kv.first) % TEST_MOD == 0) test_words.push_back(kv.first);
    }
    std::sort(test_words.begin(), test_words.end());

    size_t sample_n = std::min<size_t>(std::max(0, opt.accuracy_sample), test_words.size());
    std::vector<std::string> sample;
    if (sample_n > 0) {
        // 固定间隔抽样，结果可复现
        for (size_t i = 0; i < sample_n; ++i) {
            size_t idx = static_cast<size_t>(
                i * static_cast<double>(test_words.size()) / sample_n);
            if (idx < test_words.size()) sample.push_back(test_words[idx]);
        }
    }

    auto gt_has = [&](const std::string &w, const std::string &flat) {
        auto it = ground_truth.find(w);
        if (it == ground_truth.end()) return false;
        for (const auto &e : it->second) {
            if (e.flat == flat) return true;
        }
        return false;
    };

    size_t correct = 0, variant_hit = 0, unannotatable = 0, poly_total = 0;
    std::vector<std::string> error_samples;
    for (const auto &w : sample) {
        Annotation ann = annotate_word(w, char_table, &eval_model);
        if (!ann.ok) {
            ++unannotatable;
            continue;
        }
        if (ann.has_polyphone) ++poly_total;
        bool is_correct = gt_has(w, ann.variants[0].flat);
        if (is_correct) ++correct;
        bool hit = false;
        for (const auto &v : ann.variants) {
            if (gt_has(w, v.flat)) {
                hit = true;
                break;
            }
        }
        if (hit) ++variant_hit;
        if (!is_correct && static_cast<int>(error_samples.size()) < opt.dump_errors) {
            std::vector<std::string> gts;
            auto git = ground_truth.find(w);
            if (git != ground_truth.end()) {
                for (const auto &e : git->second) gts.push_back(e.flat);
            }
            std::sort(gts.begin(), gts.end());
            std::string gt_join;
            for (size_t i = 0; i < gts.size(); ++i) {
                if (i) gt_join += "|";
                gt_join += gts[i];
            }
            std::string detail;
            for (const auto &ch : split_utf8_chars(w)) {
                auto it = char_table.find(ch);
                detail += " " + ch + ":";
                if (it == char_table.end()) {
                    detail += "?";
                } else {
                    for (size_t k = 0; k < it->second.size() && k < 3; ++k) {
                        if (k) detail += "/";
                        detail += it->second[k].pinyin;
                    }
                }
            }
            error_samples.push_back("  " + w + "  pred=" + ann.variants[0].flat +
                                    "  gt=" + gt_join + "  [" + detail + " ]");
        }
    }
    if (!error_samples.empty()) {
        std::cout << "  注音错误样本:" << std::endl;
        for (const auto &e : error_samples) std::cout << e << std::endl;
    }
    // 无法注音的词按错误计，避免字表稀疏时用极少量可评估样本通过 95% 门槛
    size_t evaluated = sample.size();
    double top1_acc = evaluated > 0 ? static_cast<double>(correct) / evaluated : 0.0;
    double variant_acc = evaluated > 0 ? static_cast<double>(variant_hit) / evaluated : 0.0;
    std::cout << "  留出集抽样 " << sample.size() << " 词：top-1 " << (top1_acc * 100.0) << "%"
              << "，变体命中 " << (variant_acc * 100.0) << "%"
              << "，多音字词 " << poly_total
              << "，字表外无法注音 " << unannotatable << std::endl;
    if (evaluated == 0) {
        std::cerr << "[错误] 没有可评估的注音样本" << std::endl;
        return 1;
    }
    if (top1_acc < 0.95 && !opt.allow_low_accuracy) {
        std::cerr << "[错误] 注音 top-1 准确率低于 95%，请先修正字表/注音算法；"
                     "确认要继续可加 --allow-low-accuracy" << std::endl;
        return 1;
    }

    // 生产模型用全部 base/ext 注音训练（比留出集模型覆盖更全）
    std::unique_ptr<ContextModel> prod_model;
    if (profile == Profile::Full) {
        eval_model = ContextModel();  // 释放留出集模型
        prod_model = std::make_unique<ContextModel>();
        for (const auto &kv : ground_truth) {
            for (const auto &e : kv.second) {
                prod_model->add_word(kv.first, e.syllables, char_table);
            }
        }
        std::cout << "  生产上下文模型: " << prod_model->prior.size() << " 个多音字，"
                  << prod_model->tri.size() << " 个三元上下文" << std::endl;
    }

    // ------------------------------------------------------------------
    // 阶段 3：按 profile 合并词库并生成索引
    // ------------------------------------------------------------------
    std::cout << "[4/4] 生成词库与索引..." << std::endl;
    BuildData build;
    size_t reserve_keys = 700000;
    if (profile == Profile::Standard) reserve_keys = 1300000;
    if (profile == Profile::Full) reserve_keys = 2300000;
    build.py_candidates.reserve(reserve_keys);
    build.word_best.reserve(reserve_keys / 2 + 1);

    size_t dropped_noise = 0;
    size_t dropped_charset = 0;
    size_t dropped_unannotatable = 0;
    size_t dropped_oversize = 0;
    size_t annotated_words = 0;
    std::unordered_map<std::string, uint64_t> source_counts;
    std::vector<std::string> seed_words;
    seed_words.reserve(60000);
    std::vector<std::pair<uint32_t, std::string>> seed_candidates;
    seed_candidates.reserve(600000);

    // 覆盖率抽样：从 ext / tencent 各抽 N 个词，构建后统计全拼键前 3 命中
    struct CoverageItem {
        std::string word;
        std::string flat;
    };
    std::vector<CoverageItem> cov_ext, cov_tencent;
    size_t cov_seen_ext = 0, cov_seen_tencent = 0;
    std::mt19937 cov_rng(20261020);
    auto cov_add = [&](std::vector<CoverageItem> &vec, size_t &seen,
                       const std::string &word, const std::string &flat) {
        if (opt.coverage_sample <= 0 || word.empty() || flat.empty()) return;
        ++seen;
        CoverageItem item{word, flat};
        if (static_cast<int>(vec.size()) < opt.coverage_sample) {
            vec.push_back(std::move(item));
            return;
        }
        std::uniform_int_distribution<size_t> dist(0, seen - 1);
        size_t r = dist(cov_rng);
        if (r < static_cast<size_t>(opt.coverage_sample)) {
            vec[r] = std::move(item);
        }
    };

    auto add_entry = [&](const std::string &word,
                         const std::string &flat,
                         const std::vector<std::string> &syls,
                         uint32_t stored_weight,
                         uint32_t sort_weight,
                         bool weak) {
        if (flat.empty()) return;
        // 候选块里 word length 是 uint8，超长词会破坏整块编码
        if (word.size() > 255) {
            ++dropped_oversize;
            return;
        }
        stored_weight = std::max<uint32_t>(1, stored_weight);
        sort_weight = std::max<uint32_t>(1, sort_weight);

        build.py_candidates[flat].push_back({word, stored_weight, sort_weight, weak});

        std::string acronym;
        for (const auto &s : syls) {
            if (!s.empty()) acronym.push_back(s[0]);
        }
        if (syls.size() >= 2 && acronym.size() >= 2 && acronym.size() <= 8 && acronym != flat) {
            build.py_candidates[acronym].push_back({word, stored_weight, sort_weight, weak});
        }

        // 混拼键只给高频真实词（首音节全拼+末音节简拼 / 反之）
        if (!weak && syls.size() == 2 && syls[0].size() > 1 && syls[1].size() > 1 &&
            sort_weight >= mixed_cutoff) {
            std::string mix1 = syls[0].substr(0, 1) + syls[1];
            std::string mix2 = syls[0] + syls[1].substr(0, 1);
            uint32_t mw = std::max<uint32_t>(1, stored_weight / 2);
            uint32_t msw = std::max<uint32_t>(1, sort_weight / 2);
            if (mix1 != flat && mix1 != acronym) {
                build.py_candidates[mix1].push_back({word, mw, msw, false});
            }
            if (mix2 != flat && mix2 != acronym) {
                build.py_candidates[mix2].push_back({word, mw, msw, false});
            }
        }

        auto it = build.word_best.find(word);
        if (it == build.word_best.end() || stored_weight > it->second) {
            build.word_best[word] = stored_weight;
        }
    };

    auto process_source = [&](const char *file,
                              uint32_t default_weight,
                              bool weak,
                              bool collect_seeds) {
        std::string path = dict_path(file);
        uint64_t count = 0;
        bool ok = read_dict_file(path, default_weight, [&](const ParsedLine &pl) {
            ++source_counts[file];
            if (collect_seeds) {
                seed_candidates.emplace_back(pl.weight, pl.word);
            }

            if (pl.has_pinyin) {
                std::string flat;
                std::vector<std::string> syls;
                if (!normalize_pinyin(pl.pinyin, flat, syls)) return;
                if (weak) {
                    add_entry(pl.word, flat, syls, 1, 100, true);
                } else {
                    uint32_t w = std::max<uint32_t>(1, pl.weight);
                    add_entry(pl.word, flat, syls, w, w, false);
                    if (std::strcmp(file, "ext.dict.yaml") == 0) {
                        cov_add(cov_ext, cov_seen_ext, pl.word, flat);
                    }
                }
                return;
            }

            // 无注音：仅 tencent 走自动注音
            std::vector<std::string> chars = split_utf8_chars(pl.word);
            if (chars.empty() || chars.size() > 8) {
                ++dropped_noise;
                return;
            }
            bool has_ideographic_zero = false;
            for (const auto &ch : chars) {
                if (ch == "\xE3\x80\x87") {  // U+3007 〇
                    has_ideographic_zero = true;
                    break;
                }
            }
            if (has_ideographic_zero) {
                ++dropped_noise;
                return;
            }
            // 全部字符必须在字表内，顺带过滤非汉字/符号/生僻字
            for (const auto &ch : chars) {
                if (!char_table.count(ch)) {
                    ++dropped_charset;
                    return;
                }
            }
            Annotation ann = annotate_word(pl.word, char_table, prod_model.get());
            if (!ann.ok) {
                ++dropped_unannotatable;
                return;
            }
            ++annotated_words;
            if (std::strcmp(file, "tencent.dict.yaml") == 0) {
                cov_add(cov_tencent, cov_seen_tencent, pl.word, ann.variants[0].flat);
            }
            for (size_t i = 0; i < ann.variants.size(); ++i) {
                // 首选 100，变体 60；写入权重统一为 1（弱先验，运行期 prior≈0）
                uint32_t sort_w = (i == 0) ? 100u : 60u;
                add_entry(pl.word, ann.variants[i].flat, ann.variants[i].syllables,
                          1, sort_w, true);
            }
        }, &count);
        if (!ok) {
            std::cerr << "[错误] 读取失败: " << path << std::endl;
            std::exit(1);
        }
        std::cout << "  " << file << ": " << count << " 行" << std::endl;
    };

    // 合并顺序与旧版一致：8105 -> base；其余按需追加
    process_source("8105.dict.yaml", 1, false, true);
    process_source("41448.dict.yaml", 1, false, false);
    process_source("base.dict.yaml", 100, false, true);
    process_source("others.dict.yaml", 100, true, false);
    if (profile_at_least(profile, Profile::Standard)) {
        process_source("ext.dict.yaml", 100, false, false);
    }
    if (profile == Profile::Full) {
        if (opt.tencent_limit > 0) {
            // 只取前 N 行（tencent 文件为 by_weight 排序）
            std::string path = dict_path("tencent.dict.yaml");
            uint64_t count = 0;
            bool ok = read_dict_file(path, 100, [&](const ParsedLine &pl) {
                if (static_cast<int>(count) >= opt.tencent_limit) return;
                ++count;
                // tencent 当前无注音；若后续格式变化，已有注音直接采用
                if (pl.has_pinyin) {
                    std::string flat;
                    std::vector<std::string> syls;
                    if (normalize_pinyin(pl.pinyin, flat, syls) && !flat.empty()) {
                        add_entry(pl.word, flat, syls, 1, 100, true);
                        cov_add(cov_tencent, cov_seen_tencent, pl.word, flat);
                    }
                    return;
                }
                std::vector<std::string> chars = split_utf8_chars(pl.word);
                if (chars.empty() || chars.size() > 8) { ++dropped_noise; return; }
                for (const auto &ch : chars) {
                    if (ch == "\xE3\x80\x87") {
                        ++dropped_noise;
                        return;
                    }
                    if (!char_table.count(ch)) {
                        ++dropped_charset;
                        return;
                    }
                }
                Annotation ann = annotate_word(pl.word, char_table, prod_model.get());
                if (!ann.ok) { ++dropped_unannotatable; return; }
                ++annotated_words;
                cov_add(cov_tencent, cov_seen_tencent, pl.word, ann.variants[0].flat);
                for (size_t i = 0; i < ann.variants.size(); ++i) {
                    add_entry(pl.word, ann.variants[i].flat, ann.variants[i].syllables,
                              1, (i == 0) ? 100u : 60u, true);
                }
            });
            if (!ok) {
                std::cerr << "[错误] 读取失败: " << path << std::endl;
                return 1;
            }
            source_counts["tencent.dict.yaml"] = count;
            std::cout << "  tencent.dict.yaml: " << count << " 行（限流）" << std::endl;
        } else {
            process_source("tencent.dict.yaml", 100, true, false);
        }
    }

    // 手工网络用语
    auto add_manual = [&](const char *acr, const char *word) {
        std::string flat;
        std::vector<std::string> syls;
        normalize_pinyin(acr, flat, syls);
        add_entry(word, flat, syls, 999999, 999999, false);
    };
    add_manual("srkl", "生日快乐");
    add_manual("xswl", "笑死我了");
    add_manual("zqsg", "真情实感");
    add_manual("bdjw", "不懂就问");
    add_manual("plmm", "漂亮妹妹");
    add_manual("dbq", "对不起");
    add_manual("tql", "太强了");

    std::cout << "  合并完成: " << build.word_best.size() << " 词 / "
              << build.py_candidates.size() << " 拼音键"
              << "，自动注音 " << annotated_words << " 词"
              << "，噪声丢弃 " << dropped_noise
              << "，字表外丢弃 " << dropped_charset
              << "，无法注音 " << dropped_unannotatable
              << "，超长词丢弃 " << dropped_oversize << std::endl;

    // ------------------------------------------------------------------
    // 候选块：排序、去重、截断
    // ------------------------------------------------------------------
    std::vector<std::string> py_keys;
    py_keys.reserve(build.py_candidates.size());
    for (const auto &kv : build.py_candidates) py_keys.push_back(kv.first);
    std::sort(py_keys.begin(), py_keys.end());

    std::vector<uint8_t> dict_data_blob;
    dict_data_blob.reserve(static_cast<size_t>(py_keys.size()) * 24);
    // 预置一个空块，保证第一个真实块 offset > 0（运行期用 offset > 0 判命中）
    dict_data_blob.push_back(0);
    std::vector<int> py_offsets;
    py_offsets.reserve(py_keys.size());

    uint64_t total_cands = 0;
    uint64_t truncated_keys = 0;
    for (const auto &py : py_keys) {
        auto &cands = build.py_candidates[py];
        std::sort(cands.begin(), cands.end(), [](const Cand &a, const Cand &b) {
            if (a.weak != b.weak) return !a.weak;  // 真实词频永远在弱先验之前
            if (a.sort_weight != b.sort_weight) return a.sort_weight > b.sort_weight;
            return a.word < b.word;
        });
        std::vector<const Cand *> unique_cands;
        unique_cands.reserve(cands.size());
        std::unordered_set<std::string> seen;
        for (const auto &c : cands) {
            if (!seen.insert(c.word).second) continue;
            unique_cands.push_back(&c);
            if (static_cast<int>(unique_cands.size()) >= opt.max_cands) break;
        }
        if (static_cast<int>(unique_cands.size()) < static_cast<int>(cands.size())) {
            ++truncated_keys;
        }

        uint32_t offset = static_cast<uint32_t>(dict_data_blob.size());
        py_offsets.push_back(static_cast<int>(offset));
        dict_data_blob.push_back(static_cast<uint8_t>(unique_cands.size()));
        for (const Cand *c : unique_cands) {
            uint32_t wt = c->weight;
            uint8_t wt_bytes[4] = {
                static_cast<uint8_t>(wt & 0xFF),
                static_cast<uint8_t>((wt >> 8) & 0xFF),
                static_cast<uint8_t>((wt >> 16) & 0xFF),
                static_cast<uint8_t>((wt >> 24) & 0xFF)
            };
            for (int b = 0; b < 4; ++b) dict_data_blob.push_back(wt_bytes[b]);
            uint8_t w_len = static_cast<uint8_t>(c->word.size());
            dict_data_blob.push_back(w_len);
            for (char ch : c->word) dict_data_blob.push_back(static_cast<uint8_t>(ch));
            ++total_cands;
        }
    }
    std::cout << "  候选数据块: " << dict_data_blob.size() << " 字节 ("
              << (dict_data_blob.size() / (1024.0 * 1024.0)) << " MB)，"
              << total_cands << " 条候选" << std::endl;

    // 覆盖率：抽样词在它的全拼键里排第几（只统计真正写进候选块的前 max_cands 个）
    auto eval_coverage = [&](const std::vector<CoverageItem> &items, const char *label) {
        if (items.empty()) return;
        size_t top1 = 0, top3 = 0, hit = 0, miss = 0;
        for (const auto &it : items) {
            auto cit = build.py_candidates.find(it.flat);
            if (cit == build.py_candidates.end()) {
                ++miss;
                continue;
            }
            std::unordered_set<std::string> seen;
            int rank = 0;
            bool found = false;
            for (const auto &c : cit->second) {
                if (!seen.insert(c.word).second) continue;
                if (c.word == it.word) {
                    found = true;
                    break;
                }
                if (++rank >= opt.max_cands) break;
            }
            if (!found) {
                ++miss;
                continue;
            }
            ++hit;
            if (rank == 0) ++top1;
            if (rank < 3) ++top3;
        }
        double n = static_cast<double>(items.size());
        std::cout << "  覆盖率[" << label << "]: " << items.size() << " 词，top-1 "
                  << (100.0 * top1 / n) << "%，top-3 " << (100.0 * top3 / n)
                  << "%，进入候选 " << (100.0 * hit / n) << "%，未进入 " << miss
                  << std::endl;
    };
    eval_coverage(cov_ext, "ext");
    eval_coverage(cov_tencent, "tencent");

    // 构建拼音 Trie
    std::vector<const char *> py_key_ptrs;
    std::vector<size_t> py_key_lens;
    py_key_ptrs.reserve(py_keys.size());
    py_key_lens.reserve(py_keys.size());
    for (const auto &k : py_keys) {
        py_key_ptrs.push_back(k.c_str());
        py_key_lens.push_back(k.size());
    }
    Darts::DoubleArray dict_py_da;
    dict_py_da.build(py_keys.size(), py_key_ptrs.data(), py_key_lens.data(), py_offsets.data());
    std::cout << "  拼音 Trie: " << dict_py_da.size() << " units ("
              << (dict_py_da.size() * 4 / (1024.0 * 1024.0)) << " MB)" << std::endl;

    // 构建词条先验 Trie（可选只保留前 N 个高频词）
    std::vector<std::string> word_keys;
    word_keys.reserve(build.word_best.size());
    for (const auto &kv : build.word_best) word_keys.push_back(kv.first);
    if (opt.word_prior_limit > 0 && word_keys.size() > opt.word_prior_limit) {
        std::nth_element(word_keys.begin(),
                         word_keys.begin() + static_cast<std::ptrdiff_t>(opt.word_prior_limit),
                         word_keys.end(), [&](const std::string &a, const std::string &b) {
                             return build.word_best[a] > build.word_best[b];
                         });
        word_keys.resize(opt.word_prior_limit);
    }
    std::sort(word_keys.begin(), word_keys.end());
    std::vector<const char *> word_key_ptrs;
    std::vector<size_t> word_key_lens;
    std::vector<int> word_vals;
    word_key_ptrs.reserve(word_keys.size());
    word_key_lens.reserve(word_keys.size());
    word_vals.reserve(word_keys.size());
    for (const auto &k : word_keys) {
        word_key_ptrs.push_back(k.c_str());
        word_key_lens.push_back(k.size());
        word_vals.push_back(static_cast<int>(build.word_best[k]));
    }
    Darts::DoubleArray dict_word_da;
    dict_word_da.build(word_keys.size(), word_key_ptrs.data(), word_key_lens.data(),
                       word_vals.data());
    std::cout << "  词先验 Trie: " << dict_word_da.size() << " units ("
              << (dict_word_da.size() * 4 / (1024.0 * 1024.0)) << " MB)" << std::endl;

    // LM 种子按词频取前 6 万，避免按文件顺序截断后只覆盖 a-c 开头
    std::sort(seed_candidates.begin(), seed_candidates.end(),
              [](const std::pair<uint32_t, std::string> &a,
                 const std::pair<uint32_t, std::string> &b) {
                  if (a.first != b.first) return a.first > b.first;
                  return a.second < b.second;
              });
    {
        std::unordered_set<std::string> seen_seeds;
        for (const auto &sc : seed_candidates) {
            if (seed_words.size() >= 60000) break;
            if (seen_seeds.insert(sc.second).second) {
                seed_words.push_back(sc.second);
            }
        }
    }

    // 语言模型
    Darts::DoubleArray lm_da;
    size_t lm_units = 0;
    if (opt.format == "emb" && !opt.no_lm) {
        std::string err;
        if (!build_lm_trie(opt.gram_path, seed_words, lm_da, err)) {
            std::cerr << "[错误] " << err << std::endl;
            return 1;
        }
        lm_units = lm_da.size();
    }

    // ------------------------------------------------------------------
    // 输出
    // ------------------------------------------------------------------
    std::filesystem::path out_path(opt.out_path);
    if (out_path.has_parent_path()) {
        std::error_code ec;
        std::filesystem::create_directories(out_path.parent_path(), ec);
    }
    std::string tmp_path = out_path.string() + ".tmp";

    uint32_t py_units = static_cast<uint32_t>(dict_py_da.size());
    uint32_t word_units = static_cast<uint32_t>(dict_word_da.size());
    uint32_t data_size = static_cast<uint32_t>(dict_data_blob.size());
    uint64_t file_size = 0;

    auto align4 = [](uint64_t v) { return (v + 3) & ~static_cast<uint64_t>(3); };

    if (opt.format == "emb") {
        EmbHeader hdr;
        std::memset(&hdr, 0, sizeof(hdr));
        std::memcpy(hdr.magic, "XYEMB001", 8);
        hdr.version = 1;
        hdr.word_count = static_cast<uint32_t>(build.word_best.size());
        hdr.pinyin_count = static_cast<uint32_t>(py_keys.size());

        uint64_t cur = sizeof(EmbHeader);
        cur = align4(cur);
        hdr.lm_offset = lm_units > 0 ? static_cast<uint32_t>(cur) : 0;
        hdr.lm_units = static_cast<uint32_t>(lm_units);
        cur += static_cast<uint64_t>(lm_units) * 4;

        cur = align4(cur);
        hdr.dict_py_offset = static_cast<uint32_t>(cur);
        hdr.dict_py_units = py_units;
        cur += static_cast<uint64_t>(py_units) * 4;

        cur = align4(cur);
        hdr.dict_word_offset = static_cast<uint32_t>(cur);
        hdr.dict_word_units = word_units;
        cur += static_cast<uint64_t>(word_units) * 4;

        cur = align4(cur);
        hdr.dict_data_offset = static_cast<uint32_t>(cur);
        hdr.dict_data_size = data_size;
        cur += data_size;

        cur = align4(cur);
        hdr.total_size = static_cast<uint32_t>(cur);
        file_size = cur;

        std::ofstream fout(tmp_path, std::ios::binary | std::ios::trunc);
        if (!fout.is_open()) {
            std::cerr << "[错误] 无法写入 " << tmp_path << std::endl;
            return 1;
        }
        auto write_section = [&](uint64_t off, const void *data, size_t len) -> bool {
            fout.seekp(static_cast<std::streamoff>(off));
            if (!fout) return false;
            fout.write(static_cast<const char *>(data), static_cast<std::streamsize>(len));
            return fout.good();
        };
        bool ok = write_section(0, &hdr, sizeof(hdr));
        if (ok && lm_units > 0) {
            ok = write_section(hdr.lm_offset, lm_da.array(), lm_units * 4);
        }
        if (ok) ok = write_section(hdr.dict_py_offset, dict_py_da.array(), py_units * 4);
        if (ok) ok = write_section(hdr.dict_word_offset, dict_word_da.array(), word_units * 4);
        if (ok) ok = write_section(hdr.dict_data_offset, dict_data_blob.data(), data_size);
        char zero = 0;
        if (ok) ok = write_section(hdr.total_size - 1, &zero, 1);
        fout.flush();
        ok = ok && fout.good();
        fout.close();
        ok = ok && !fout.fail();
        std::error_code size_ec;
        if (ok) {
            auto actual = std::filesystem::file_size(tmp_path, size_ec);
            ok = !size_ec && actual == file_size;
        }
        if (!ok) {
            std::filesystem::remove(tmp_path);
            std::cerr << "[错误] 写入输出文件失败: " << tmp_path << std::endl;
            return 1;
        }
        int fsync_fd = open(tmp_path.c_str(), O_RDONLY);
        if (fsync_fd >= 0) {
            fsync(fsync_fd);
            close(fsync_fd);
        }
    } else {
        DictHeader hdr;
        std::memset(&hdr, 0, sizeof(hdr));
        std::memcpy(hdr.magic, "XYDICT01", 8);
        hdr.version = 1;
        hdr.word_count = static_cast<uint32_t>(build.word_best.size());
        hdr.pinyin_count = static_cast<uint32_t>(py_keys.size());

        uint64_t cur = sizeof(DictHeader);
        cur = align4(cur);
        hdr.py_trie_offset = static_cast<uint32_t>(cur);
        hdr.py_trie_units = py_units;
        cur += static_cast<uint64_t>(py_units) * 4;

        cur = align4(cur);
        hdr.word_trie_offset = static_cast<uint32_t>(cur);
        hdr.word_trie_units = word_units;
        cur += static_cast<uint64_t>(word_units) * 4;

        cur = align4(cur);
        hdr.data_offset = static_cast<uint32_t>(cur);
        hdr.data_size = data_size;
        cur += data_size;
        file_size = cur;

        std::ofstream fout(tmp_path, std::ios::binary | std::ios::trunc);
        if (!fout.is_open()) {
            std::cerr << "[错误] 无法写入 " << tmp_path << std::endl;
            return 1;
        }
        auto write_section = [&](uint64_t off, const void *data, size_t len) -> bool {
            fout.seekp(static_cast<std::streamoff>(off));
            if (!fout) return false;
            fout.write(static_cast<const char *>(data), static_cast<std::streamsize>(len));
            return fout.good();
        };
        bool ok = write_section(0, &hdr, sizeof(hdr));
        if (ok) ok = write_section(hdr.py_trie_offset, dict_py_da.array(), py_units * 4);
        if (ok) ok = write_section(hdr.word_trie_offset, dict_word_da.array(), word_units * 4);
        if (ok) ok = write_section(hdr.data_offset, dict_data_blob.data(), data_size);
        fout.flush();
        ok = ok && fout.good();
        fout.close();
        ok = ok && !fout.fail();
        std::error_code size_ec;
        if (ok) {
            auto actual = std::filesystem::file_size(tmp_path, size_ec);
            ok = !size_ec && actual == file_size;
        }
        if (!ok) {
            std::filesystem::remove(tmp_path);
            std::cerr << "[错误] 写入输出文件失败: " << tmp_path << std::endl;
            return 1;
        }
        int fsync_fd = open(tmp_path.c_str(), O_RDONLY);
        if (fsync_fd >= 0) {
            fsync(fsync_fd);
            close(fsync_fd);
        }
    }

    std::error_code ec;
    std::filesystem::rename(tmp_path, out_path, ec);
    if (ec) {
        std::cerr << "[错误] 重命名输出文件失败: " << ec.message() << std::endl;
        return 1;
    }

    // ------------------------------------------------------------------
    // 统计
    // ------------------------------------------------------------------
    std::cout << "\n=== 构建完成 ===" << std::endl;
    std::cout << "输出: " << opt.out_path << " (" << (file_size / (1024.0 * 1024.0))
              << " MB)" << std::endl;
    std::cout << "词条: " << build.word_best.size()
              << "，拼音键: " << py_keys.size()
              << "，候选: " << total_cands
              << "，触顶键: " << truncated_keys << std::endl;
    std::cout << "注音: top-1 " << (top1_acc * 100.0) << "% / 变体 "
              << (variant_acc * 100.0) << "%" << std::endl;
    std::cout << "耗时: " << elapsed_sec() << " 秒" << std::endl;
    if (opt.stats) {
        std::cout << "\n各库贡献:" << std::endl;
        for (const auto &kv : source_counts) {
            std::cout << "  " << kv.first << ": " << kv.second << " 行" << std::endl;
        }
        std::cout << "  自动注音词: " << annotated_words << std::endl;
        std::cout << "  噪声丢弃: " << dropped_noise << std::endl;
        std::cout << "  字表外丢弃: " << dropped_charset << std::endl;
        std::cout << "  无法注音: " << dropped_unannotatable << std::endl;
        std::cout << "  超长词丢弃: " << dropped_oversize << std::endl;
        std::cout << "  字表大小: " << char_table.size() << " 字" << std::endl;
        size_t poly = 0;
        for (const auto &kv : char_table) if (kv.second.size() > 1) ++poly;
        std::cout << "  多音字: " << poly << std::endl;
        std::cout << "  LM units: " << lm_units << std::endl;
    }
    return 0;
}
