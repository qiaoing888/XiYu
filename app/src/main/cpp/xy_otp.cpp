#include "xy_otp.h"
#include <algorithm>
#include <cctype>
#include <cmath>
#include <regex>
#include <string>
#include <string_view>
#include <vector>

namespace xy {

namespace {

// 转小写
std::string toLower(std::string_view str) {
    std::string s;
    s.reserve(str.length());
    for (char c : str) {
        s += static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    return s;
}

// 验证码智能归一：连字符/空格折叠，剥离 G- / g- 谷歌/微软格式前缀
std::string normalizeOtpCandidate(std::string_view raw) {
    std::string s;
    s.reserve(raw.length());

    std::string_view cur = raw;
    if (cur.length() >= 3 && (cur[0] == 'G' || cur[0] == 'g') && (cur[1] == '-' || cur[1] == ' ')) {
        cur.remove_prefix(2);
    }

    for (char c : cur) {
        if (c != '-' && c != ' ') {
            s += c;
        }
    }
    return s;
}

bool isValidCandidate(std::string_view candidate) {
    if (candidate.length() < 4 || candidate.length() > 8) return false;
    // 候选必须包含数字，避免误判纯英文单词（如 code）
    bool hasDigit = false;
    for (char c : candidate) {
        if (std::isdigit(static_cast<unsigned char>(c))) {
            hasDigit = true;
            break;
        }
    }
    return hasDigit;
}

bool startsWith(std::string_view str, std::string_view prefix, bool ignoreCase = false) {
    if (str.length() < prefix.length()) return false;
    std::string_view sub = str.substr(0, prefix.length());
    if (!ignoreCase) return sub == prefix;
    for (size_t i = 0; i < prefix.length(); ++i) {
        if (std::tolower(static_cast<unsigned char>(sub[i])) !=
            std::tolower(static_cast<unsigned char>(prefix[i]))) {
            return false;
        }
    }
    return true;
}

bool isValidContext(std::string_view code, const std::string &text, size_t startIndex, size_t matchLength = 0) {
    if (code.length() == 11) return false;

    size_t span = (matchLength > 0) ? matchLength : code.length();
    size_t idx = (startIndex < text.length()) ? startIndex : text.find(code);
    if (idx == std::string::npos) return true;

    char prevChar = (idx > 0) ? text[idx - 1] : ' ';
    char nextChar = (idx + span < text.length()) ? text[idx + span] : ' ';

    // 货币符号前缀
    if (prevChar == '$') return false;
    if (idx >= 3) {
        std::string_view p3 = std::string_view(text).substr(idx - 3, 3);
        if (p3 == "\xC2\xA5" || p3 == "\xEF\xBF\xA5") return false; // ¥ 或 ￥
    }
    if (idx >= 2) {
        std::string_view p2 = std::string_view(text).substr(idx - 2, 2);
        if (p2 == "\xC2\xA5") return false;
    }

    // 时间、日期或小数格式
    if ((prevChar == ':' || nextChar == ':') && code.length() <= 4) return false;
    if ((prevChar == '-' || nextChar == '-') && (code.length() <= 4 || matchLength > 0)) return false;
    if ((prevChar == '/' || nextChar == '/') && code.length() <= 4) return false;
    if ((prevChar == '.' || nextChar == '.') &&
        (std::isdigit(static_cast<unsigned char>(prevChar)) || std::isdigit(static_cast<unsigned char>(nextChar)))) {
        return false;
    }

    // 后续文本检查
    std::string_view following = std::string_view(text).substr(idx + span);
    while (!following.empty() && (following.front() == ' ' || following.front() == '\t')) {
        following.remove_prefix(1);
    }

    if (code.length() == 4 && startsWith(following, "年")) return false;

    if (startsWith(following, "元") || startsWith(following, "块") ||
        startsWith(following, "分") || startsWith(following, "角") ||
        startsWith(following, "USD", true) || startsWith(following, "CNY", true) ||
        startsWith(following, "RMB", true)) {
        return false;
    }

    if (startsWith(following, "分钟") || startsWith(following, "小时") ||
        startsWith(following, "秒钟") || startsWith(following, "秒内") ||
        startsWith(following, "秒有效") || startsWith(following, "min", true) ||
        startsWith(following, "sec", true) || startsWith(following, "hour", true)) {
        return false;
    }

    // 前置文本检查（前 32 字节）
    size_t preLen = std::min(idx, static_cast<size_t>(32));
    std::string preceding = toLower(std::string_view(text).substr(idx - preLen, preLen));

    if (preceding.find("尾号") != std::string::npos ||
        preceding.find("卡号") != std::string::npos ||
        preceding.find("card") != std::string::npos ||
        preceding.find("ending") != std::string::npos) {
        return false;
    }

    if (preceding.find("订单") != std::string::npos ||
        preceding.find("快递") != std::string::npos ||
        preceding.find("运单") != std::string::npos ||
        preceding.find("order") != std::string::npos) {
        return false;
    }

    if (preceding.find("电话") != std::string::npos ||
        preceding.find("致电") != std::string::npos ||
        preceding.find("热线") != std::string::npos ||
        preceding.find("拨打") != std::string::npos ||
        preceding.find("call") != std::string::npos) {
        return false;
    }

    return true;
}

} // namespace

std::optional<std::string> parseOtp(const std::string &text) {
    if (text.empty()) return std::nullopt;

    // 关键词门禁（英文需单词边界）
    static const std::regex KEYWORD_GATE(
        "验证码|校验码|动态码|动态口令|确认码|授权码|安全码|随机码|识别码|口令|提取码|激活码|代码|"
        "\\b(?:verification\\s*code|security\\s*code|auth\\s*code|otp|code|token|pin)\\b",
        std::regex::icase
    );

    if (!std::regex_search(text, KEYWORD_GATE)) {
        return std::nullopt;
    }

    // 1. 紧邻关键词匹配 (如 "验证码：123456", "Your OTP is: 284910", "code 8392", "G-829104", "829-104")
    static const std::regex DIRECT_PATTERN(
        "(?:验证码|校验码|动态码|动态口令|确认码|授权码|安全码|随机码|识别码|口令|提取码|激活码|代码|"
        "\\bverification\\s*code|\\bsecurity\\s*code|\\bauth\\s*code|\\botp|\\bcode|\\btoken|\\bpin)"
        "(?:\\s*is[:\\s]*|[\\s:：为是\\->=])*"
        "([Gg][-\\s][0-9]{4,8}|[0-9]{3,4}[-\\s][0-9]{3,4}|[0-9a-zA-Z]{4,8})\\b",
        std::regex::icase
    );

    auto directBegin = std::sregex_iterator(text.begin(), text.end(), DIRECT_PATTERN);
    auto directEnd = std::sregex_iterator();
    for (auto it = directBegin; it != directEnd; ++it) {
        std::string rawCand = (*it)[1].str();
        size_t start = static_cast<size_t>(it->position(1));
        size_t matchLen = rawCand.length();
        std::string cand = normalizeOtpCandidate(rawCand);
        if (isValidCandidate(cand) && isValidContext(cand, text, start, matchLen)) {
            return cand;
        }
    }

    // 2. 括号包裹匹配 (如 【591823】、[382910]、[G-829104])
    static const std::regex ENCLOSED_PATTERN(
        "(?:【|\\[|'|\"|`|“|‘|\\()([Gg][-\\s][0-9]{4,8}|[0-9]{3,4}[-\\s][0-9]{3,4}|[0-9a-zA-Z]{4,8})(?:】|\\]|'|\"|`|”|’|\\))"
    );

    auto encBegin = std::sregex_iterator(text.begin(), text.end(), ENCLOSED_PATTERN);
    auto encEnd = std::sregex_iterator();
    for (auto it = encBegin; it != encEnd; ++it) {
        std::string rawCand = (*it)[1].str();
        size_t start = static_cast<size_t>(it->position());
        size_t matchLen = rawCand.length();
        std::string cand = normalizeOtpCandidate(rawCand);
        // 短信开头的纯英文/带字母签名头（如【V2EX】）排除
        if (start <= 3 && std::any_of(cand.begin(), cand.end(), [](char c) { return std::isalpha(static_cast<unsigned char>(c)); })) {
            continue;
        }
        if (isValidCandidate(cand) && isValidContext(cand, text, static_cast<size_t>(it->position(1)), matchLen)) {
            return cand;
        }
    }

    // 3. 关键词前置匹配 (如 "482910 是您的动态口令", "G-829104 为您的身份验证码", "829-104 as Microsoft code")
    static const std::regex BEFORE_PATTERN(
        "\\b([Gg][-\\s][0-9]{4,8}|[0-9]{3,4}[-\\s][0-9]{3,4}|[0-9a-zA-Z]{4,8})\\s*(?:为|是|作为|即为|is(?:\\s+your|\\s+the)?|as)\\s*(?:您(?:的)?|本次|该次|账户)?\\s*(?:登录|注册|修改|操作|交易)?\\s*"
        "(?:验证码|校验码|动态码|动态口令|确认码|授权码|安全码|随机码|识别码|口令|代码|\\bverification\\s*code|\\bsecurity\\s*code|\\bauth\\s*code|\\botp|\\bcode)\\b",
        std::regex::icase
    );

    auto beforeBegin = std::sregex_iterator(text.begin(), text.end(), BEFORE_PATTERN);
    auto beforeEnd = std::sregex_iterator();
    for (auto it = beforeBegin; it != beforeEnd; ++it) {
        std::string rawCand = (*it)[1].str();
        size_t start = static_cast<size_t>(it->position(1));
        size_t matchLen = rawCand.length();
        std::string cand = normalizeOtpCandidate(rawCand);
        if (isValidCandidate(cand) && isValidContext(cand, text, start, matchLen)) {
            return cand;
        }
    }

    // 4. 距离临近搜索兜底
    std::vector<int> keywordIndices;
    auto gateIt = std::sregex_iterator(text.begin(), text.end(), KEYWORD_GATE);
    for (; gateIt != directEnd; ++gateIt) {
        keywordIndices.push_back(static_cast<int>(gateIt->position()));
    }

    if (!keywordIndices.empty()) {
        static const std::regex DIGITS_PATTERN("([Gg][-\\s][0-9]{4,8}|\\b[0-9]{3,4}[-\\s][0-9]{3,4}\\b|\\b\\d{4,8}\\b)");
        auto dBegin = std::sregex_iterator(text.begin(), text.end(), DIGITS_PATTERN);
        std::string bestCandidate;
        int minDistance = 999999;

        for (auto it = dBegin; it != directEnd; ++it) {
            std::string rawCand = (*it)[1].str();
            int start = static_cast<int>(it->position(1));
            size_t matchLen = rawCand.length();
            std::string cand = normalizeOtpCandidate(rawCand);
            if (!isValidCandidate(cand)) continue;

            // 年份排除
            if (cand.length() == 4 && (cand.rfind("19", 0) == 0 || cand.rfind("20", 0) == 0)) {
                if (start + static_cast<int>(matchLen) < static_cast<int>(text.length())) {
                    std::string_view fol = std::string_view(text).substr(start + matchLen);
                    if (fol.rfind("年", 0) == 0 || fol.rfind("-", 0) == 0 || fol.rfind("/", 0) == 0) {
                        continue;
                    }
                }
            }

            if (!isValidContext(cand, text, static_cast<size_t>(start), matchLen)) continue;

            int dist = 999999;
            for (int kIdx : keywordIndices) {
                int d = std::abs(start - kIdx);
                if (d < dist) dist = d;
            }

            if (dist < 80 && dist < minDistance) {
                minDistance = dist;
                bestCandidate = cand;
            }
        }

        if (!bestCandidate.empty()) {
            return bestCandidate;
        }
    }

    return std::nullopt;
}

} // namespace xy
