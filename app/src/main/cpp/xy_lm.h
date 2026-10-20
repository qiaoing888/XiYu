#ifndef XY_LM_H
#define XY_LM_H

#include <cstdint>
#include <cstddef>
#include <string>
#include <vector>

namespace xy {

struct LmCandidateScore {
    int original_index;
    float score;
    size_t char_length;
};

void init_lm(const char *storage_dir, const char *custom_model_path = nullptr, const char *custom_dict_path = nullptr);

std::string get_lm_info();
bool is_lm_ready();

std::string get_dict_info();
bool is_dict_ready();

// 上下文分句截断（与词库侧边界规则保持一致）
std::string truncate_sentence_context(const std::string &ctx);
bool text_contains_chinese(const std::string &text);

void sync_user_memory();

void rerank_candidates(
    const char *pinyin,
    const char *context,
    const char **candidates,
    const bool *is_user_learned,
    int count,
    int *out_indices
);

inline void rerank_candidates(
    const char *context,
    const char **candidates,
    int count,
    int *out_indices
) {
    rerank_candidates(nullptr, context, candidates, nullptr, count, out_indices);
}

std::vector<std::string> predict_next_words(const char *context, int max_results);
std::vector<std::string> query_dict_pinyin(const char *pinyin, int max_results);

// 以已上屏的词为前缀，按词频返回联想词（如“我”->“我们/我的/我是”）
std::vector<std::string> query_dict_word_prefix(const char *prefix, int max_results);

// 校验拼音串是否能按合法音节切分；最后一段允许是不完整音节前缀
bool is_valid_pinyin_sequence(const char *pinyin);

// 查询用户自定义短语与自学习候选：code 非空时查编码词库，code 为空时仅查 @上下文 快捷短语
std::vector<std::string> query_user_candidates(const char *code, const char *context, int max_results);

inline std::vector<std::string> query_user_candidates(const char *code, int max_results) {
    return query_user_candidates(code, nullptr, max_results);
}

std::string decode_pinyin_sentence(const char *context, const char *pinyin);

void record_user_commit(const char *pinyin, const char *prev_context, const char *committed_text);

inline void record_user_commit(const char *prev_context, const char *committed_text) {
    record_user_commit(nullptr, prev_context, committed_text);
}

void rollback_user_commit(const char *pinyin, const char *prev_context, const char *committed_text);

inline void rollback_user_commit(const char *prev_context, const char *committed_text) {
    rollback_user_commit(nullptr, prev_context, committed_text);
}

void clear_user_memory();

// TSV 格式: 类型[P/C/S]\t触发键\t词条\t频次\t时间戳
std::string export_user_memory_tsv();
int import_user_memory_tsv(const char *tsv_data, bool merge = true);

bool add_user_custom_phrase(const char *trigger_key, const char *word, bool is_context_trigger = false);
bool remove_user_memory_entry(const char *type, const char *key, const char *word, bool suppress_bad_word = false);

void set_quanpin_mode(bool enabled);

void set_long_phrase_aggressive(bool aggressive);


} // namespace xy

#endif // XY_LM_H
