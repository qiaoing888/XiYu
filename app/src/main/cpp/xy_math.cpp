#include "xy_math.h"
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <deque>
#include <regex>
#include <string>
#include <string_view>
#include <vector>

namespace xy {

namespace {

// 计算 UTF-8 字符串对应的 UTF-16 字符数（Java String.length()）
int utf16Length(const std::string &str) {
    int len = 0;
    size_t i = 0;
    while (i < str.length()) {
        unsigned char c = static_cast<unsigned char>(str[i]);
        if (c < 0x80) {
            len += 1;
            i += 1;
        } else if ((c & 0xE0) == 0xC0) {
            len += 1;
            i += 2;
        } else if ((c & 0xF0) == 0xE0) {
            len += 1;
            i += 3;
        } else if ((c & 0xF8) == 0xF0) {
            len += 2; // 代理对
            i += 4;
        } else {
            len += 1;
            i += 1;
        }
    }
    return len;
}

// 检查是否以指定后缀结尾
bool endsWith(std::string_view str, std::string_view suffix, bool ignoreCase = false) {
    if (str.length() < suffix.length()) return false;
    std::string_view sub = str.substr(str.length() - suffix.length());
    if (!ignoreCase) return sub == suffix;
    for (size_t i = 0; i < suffix.length(); ++i) {
        if (std::tolower(static_cast<unsigned char>(sub[i])) !=
            std::tolower(static_cast<unsigned char>(suffix[i]))) {
            return false;
        }
    }
    return true;
}

// 检查是否以指定前缀开头
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

// 去除末尾空白与等号
std::string_view trimEnd(std::string_view str) {
    while (!str.empty()) {
        char c = str.back();
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
            str.remove_suffix(1);
        } else {
            break;
        }
    }
    return str;
}

// 检查末尾是否为 '=' 或全角 '＝' (\xEF\xBC\x9D)
bool checkEndsWithEqual(std::string_view str) {
    if (str.empty()) return false;
    if (str.back() == '=') return true;
    if (str.length() >= 3 && str.substr(str.length() - 3) == "\xEF\xBC\x9D") return true;
    return false;
}

// 移除末尾的一个等号（半角或全角）
std::string_view dropTrailingEqual(std::string_view str) {
    if (str.empty()) return str;
    if (str.back() == '=') {
        return str.substr(0, str.length() - 1);
    }
    if (str.length() >= 3 && str.substr(str.length() - 3) == "\xEF\xBC\x9D") {
        return str.substr(0, str.length() - 3);
    }
    return str;
}

// 格式化浮点数为紧凑字符串，去除多余末尾 0
std::string formatNumber(double val) {
    if (std::isnan(val) || std::isinf(val)) return "";
    // 动态缓冲避免大数被定长缓冲截断
    int len = snprintf(nullptr, 0, "%.10f", val);
    if (len <= 0) return "";
    std::vector<char> buf(static_cast<size_t>(len) + 1);
    snprintf(buf.data(), buf.size(), "%.10f", val);
    std::string s(buf.data());
    size_t dot = s.find('.');
    if (dot != std::string::npos) {
        while (s.back() == '0') s.pop_back();
        if (s.back() == '.') s.pop_back();
    }
    if (s == "-0") return "0";
    return s;
}

// =========================================================================
// 人民币大写金额转换
// =========================================================================
const char *const RMB_DIGITS[] = {"零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖"};
const char *const RMB_RADIX[] = {"", "拾", "佰", "仟"};
const char *const RMB_SECTION_UNITS[] = {"", "万", "亿", "万亿"};

} // namespace

std::string toChineseCurrency(double amount) {
    if (!std::isfinite(amount)) return "金额无效";
    if (std::abs(amount) < 0.00001) return "零元整";
    bool isNegative = amount < 0;
    double absAmount = std::abs(amount);
    // 超过 9999 万亿后无对应单位，同时避免 double→int64 溢出
    if (absAmount >= 1e15) return "金额超出范围";

    // 四舍五入到分
    int64_t totalCents = static_cast<int64_t>(std::round(absAmount * 100.0));
    if (totalCents == 0) return "零元整";
    int64_t intVal = totalCents / 100;
    int cents = static_cast<int>(totalCents % 100);
    int jiao = cents / 10;
    int fen = cents % 10;

    std::string sb;
    if (isNegative) sb += "负";

    if (intVal == 0) {
        if (jiao > 0 && fen == 0) {
            sb += RMB_DIGITS[jiao];
            sb += "角整";
        } else if (jiao == 0 && fen > 0) {
            sb += RMB_DIGITS[fen];
            sb += "分";
        } else if (jiao > 0 && fen > 0) {
            sb += RMB_DIGITS[jiao];
            sb += "角";
            sb += RMB_DIGITS[fen];
            sb += "分";
        }
        return sb;
    }

    // 拆分为万进制分段
    std::vector<int> sections;
    int64_t tempInt = intVal;
    while (tempInt > 0) {
        sections.push_back(static_cast<int>(tempInt % 10000));
        tempInt /= 10000;
    }

    bool zeroPending = false;
    bool hasDigit = false;
    for (int idx = static_cast<int>(sections.size()) - 1; idx >= 0; --idx) {
        int sec = sections[idx];
        const char *unit = (idx < 4) ? RMB_SECTION_UNITS[idx] : "";
        if (sec == 0) {
            if (hasDigit && !zeroPending) {
                zeroPending = true;
            }
            continue;
        }

        if (zeroPending) {
            sb += "零";
            zeroPending = false;
        } else if (idx < static_cast<int>(sections.size()) - 1 && sec < 1000 && hasDigit) {
            sb += "零";
        }

        int parts[4];
        int n = sec;
        for (int p = 0; p < 4; ++p) {
            parts[p] = n % 10;
            n /= 10;
        }

        bool secZero = false;
        for (int p = 3; p >= 0; --p) {
            int d = parts[p];
            if (d == 0) {
                if (!secZero && hasDigit && !endsWith(sb, "零")) {
                    secZero = true;
                }
            } else {
                if (secZero) {
                    sb += "零";
                    secZero = false;
                }
                sb += RMB_DIGITS[d];
                sb += RMB_RADIX[p];
                hasDigit = true;
            }
        }
        sb += unit;
    }

    sb += "元";

    if (jiao == 0 && fen == 0) {
        sb += "整";
    } else if (jiao > 0 && fen == 0) {
        sb += RMB_DIGITS[jiao];
        sb += "角整";
    } else if (jiao == 0 && fen > 0) {
        sb += "零";
        sb += RMB_DIGITS[fen];
        sb += "分";
    } else {
        sb += RMB_DIGITS[jiao];
        sb += "角";
        sb += RMB_DIGITS[fen];
        sb += "分";
    }

    return sb;
}

namespace {

// =========================================================================
// 表达式归一化与求值
// =========================================================================

// 归一化全半角及特殊运算符
std::string normalize(std::string_view expr) {
    expr = trimEnd(expr);
    expr = dropTrailingEqual(expr);
    expr = trimEnd(expr);

    std::string sb;
    sb.reserve(expr.length());
    size_t i = 0;
    while (i < expr.length()) {
        unsigned char c = static_cast<unsigned char>(expr[i]);
        if (c == 0xEF && i + 2 < expr.length()) {
            unsigned char c2 = static_cast<unsigned char>(expr[i + 1]);
            unsigned char c3 = static_cast<unsigned char>(expr[i + 2]);
            if (c2 == 0xBC) {
                if (c3 >= 0x90 && c3 <= 0x99) { sb += static_cast<char>('0' + (c3 - 0x90)); i += 3; continue; } // ０-９
                if (c3 == 0x8B) { sb += '+'; i += 3; continue; } // ＋
                if (c3 == 0x8D) { sb += '-'; i += 3; continue; } // －
                if (c3 == 0x8A) { sb += '*'; i += 3; continue; } // ＊
                if (c3 == 0x8F) { sb += '/'; i += 3; continue; } // ／
                if (c3 == 0x88) { sb += '('; i += 3; continue; } // （
                if (c3 == 0x89) { sb += ')'; i += 3; continue; } // ）
                if (c3 == 0x85) { sb += '%'; i += 3; continue; } // ％
                if (c3 == 0x8E) { sb += '.'; i += 3; continue; } // ．
            } else if (c2 == 0xBD && c3 == 0x9E) {
                sb += '^'; i += 3; continue; // ＾
            }
        } else if (c == 0xC3 && i + 1 < expr.length()) {
            unsigned char c2 = static_cast<unsigned char>(expr[i + 1]);
            if (c2 == 0x97) { sb += '*'; i += 2; continue; } // ×
            if (c2 == 0xB7) { sb += '/'; i += 2; continue; } // ÷
        }

        char ch = expr[i];
        if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
            i++;
            continue;
        }
        if (ch == 'x' || ch == 'X') {
            char prev = sb.empty() ? ' ' : sb.back();
            char next = (i + 1 < expr.length()) ? expr[i + 1] : ' ';
            bool isLeadingZero = (prev == '0' && (sb.length() == 1 || !std::isdigit(static_cast<unsigned char>(sb[sb.length() - 2]))));
            if (!isLeadingZero && std::isdigit(static_cast<unsigned char>(prev)) &&
                (std::isdigit(static_cast<unsigned char>(next)) || next == '.' || next == '(')) {
                sb += '*';
            } else {
                sb += ch;
            }
        } else {
            sb += ch;
        }
        i++;
    }
    return sb;
}

int precedence(char op) {
    if (op == '+' || op == '-') return 1;
    if (op == '*' || op == '/') return 2;
    if (op == '^') return 3;
    return 0;
}

bool shouldPop(char top, char incoming) {
    if (top == '(') return false;
    int pTop = precedence(top);
    int pInc = precedence(incoming);
    return (incoming == '^') ? (pTop > pInc) : (pTop >= pInc);
}

bool applyOp(std::deque<double> &numbers, char op) {
    if (numbers.size() < 2) return false;
    double b = numbers.back(); numbers.pop_back();
    double a = numbers.back(); numbers.pop_back();
    double res = 0.0;
    switch (op) {
        case '+': res = a + b; break;
        case '-': res = a - b; break;
        case '*': res = a * b; break;
        case '/':
            if (std::abs(b) < 1e-12) return false; // 除以 0 保护
            res = a / b;
            break;
        case '^':
            res = std::pow(a, b);
            break;
        default: return false;
    }
    numbers.push_back(res);
    return true;
}

bool calculate(const std::string &expr, double &outResult) {
    std::deque<double> numbers;
    std::deque<char> ops;
    size_t i = 0;
    const size_t n = expr.length();

    while (i < n) {
        char c = expr[i];

        if (std::isdigit(static_cast<unsigned char>(c)) || c == '.') {
            size_t start = i;
            while (i < n && (std::isdigit(static_cast<unsigned char>(expr[i])) || expr[i] == '.')) {
                i++;
            }
            try {
                numbers.push_back(std::stod(expr.substr(start, i - start)));
            } catch (...) {
                return false;
            }
            continue;
        }

        if (c == '(') {
            ops.push_back(c);
            i++;
            continue;
        }

        if (c == ')') {
            while (!ops.empty() && ops.back() != '(') {
                if (!applyOp(numbers, ops.back())) return false;
                ops.pop_back();
            }
            if (!ops.empty() && ops.back() == '(') {
                ops.pop_back();
            }
            i++;
            continue;
        }

        if (c == '%') {
            if (!numbers.empty()) {
                numbers.back() /= 100.0;
            }
            i++;
            continue;
        }

        // 一元负号
        if (c == '-' && (i == 0 || expr[i - 1] == '+' || expr[i - 1] == '-' ||
                         expr[i - 1] == '*' || expr[i - 1] == '/' ||
                         expr[i - 1] == '^' || expr[i - 1] == '(')) {
            i++;
            size_t start = i;
            while (i < n && (std::isdigit(static_cast<unsigned char>(expr[i])) || expr[i] == '.')) {
                i++;
            }
            if (i > start) {
                try {
                    numbers.push_back(-std::stod(expr.substr(start, i - start)));
                    continue;
                } catch (...) {
                    return false;
                }
            }
        }

        if (c == '+' || c == '-' || c == '*' || c == '/' || c == '^') {
            while (!ops.empty() && shouldPop(ops.back(), c)) {
                if (!applyOp(numbers, ops.back())) return false;
                ops.pop_back();
            }
            ops.push_back(c);
            i++;
            continue;
        }

        return false;
    }

    while (!ops.empty()) {
        char op = ops.back();
        ops.pop_back();
        if (op == '(' || op == ')') return false;
        if (!applyOp(numbers, op)) return false;
    }

    if (numbers.size() != 1) return false;
    outResult = numbers.back();
    return true;
}

bool hasValidOperator(std::string_view expr) {
    int opCount = 0;
    bool hasDigit = false;
    for (size_t i = 0; i < expr.length(); ++i) {
        char c = expr[i];
        if (std::isdigit(static_cast<unsigned char>(c))) {
            hasDigit = true;
        } else if (c == '+' || c == '-' || c == '*' || c == '/' || c == '^' || c == '%') {
            if (i > 0 && (std::isdigit(static_cast<unsigned char>(expr[i - 1])) || expr[i - 1] == ')' || expr[i - 1] == '%')) {
                opCount++;
            }
        }
    }
    return hasDigit && (opCount > 0);
}

// 负向过滤正则
bool isNegativeMatch(std::string_view expr) {
    std::string s(expr);
    static const std::regex DATE_P("^(?:19|20)\\d{2}[-/.]\\d{1,2}[-/.]\\d{1,2}$");
    static const std::regex IP_P("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");
    static const std::regex TIME_P("^\\d{1,2}:\\d{2}(?::\\d{2})?$");
    static const std::regex PHONE_P("^1[3-9]\\d{9}$");

    if (std::regex_match(s, DATE_P)) return true;
    if (std::regex_match(s, IP_P)) return true;
    if (std::regex_match(s, TIME_P)) return true;
    if (std::regex_match(s, PHONE_P)) return true;
    return false;
}

// =========================================================================
// 句子中切片提取
// =========================================================================

bool isAsciiDigit(char c) { return c >= '0' && c <= '9'; }

std::optional<std::string> extractMathSlice(std::string_view trimmed) {
    if (trimmed.empty()) return std::nullopt;

    bool endsWithEq = checkEndsWithEqual(trimmed);
    size_t searchEnd = endsWithEq ? dropTrailingEqual(trimmed).length() : trimmed.length();
    if (searchEnd == 0) return std::nullopt;

    int startIdx = static_cast<int>(searchEnd);
    bool hasDigit = false;
    int lastCharType = 0; // 1: 数字或右括号, 2: 运算符, 3: 左括号

    for (int i = static_cast<int>(searchEnd) - 1; i >= 0; --i) {
        char ch = trimmed[i];

        if (ch == '=' || ch == '\n' || ch == '\r') break;

        // 全角等号
        if (i >= 2 && static_cast<unsigned char>(trimmed[i - 2]) == 0xEF &&
            static_cast<unsigned char>(trimmed[i - 1]) == 0xBC &&
            static_cast<unsigned char>(ch) == 0x9D) {
            break;
        }

        if (ch == ' ' || ch == '\t') {
            if (startIdx == static_cast<int>(searchEnd)) continue;
            int prevIdx = i - 1;
            while (prevIdx >= 0 && (trimmed[prevIdx] == ' ' || trimmed[prevIdx] == '\t')) prevIdx--;
            if (prevIdx >= 0) {
                char prevChar = trimmed[prevIdx];
                bool prevIsOp = (prevChar == '+' || prevChar == '-' || prevChar == '*' ||
                                 prevChar == '/' || prevChar == '^' || prevChar == '%' ||
                                 prevChar == '(');
                bool currentIsOp = (lastCharType == 2 || lastCharType == 3);
                if (prevIsOp || currentIsOp) {
                    startIdx = i;
                    continue;
                }
            }
            break;
        }

        bool isDigit = isAsciiDigit(ch);
        bool isFullDigit = false;
        if (i >= 2 && static_cast<unsigned char>(trimmed[i - 2]) == 0xEF &&
            static_cast<unsigned char>(trimmed[i - 1]) == 0xBC &&
            static_cast<unsigned char>(ch) >= 0x90 && static_cast<unsigned char>(ch) <= 0x99) {
            isFullDigit = true;
        }

        bool isDot = (ch == '.');
        bool isOp = (ch == '+' || ch == '-' || ch == '*' || ch == '/' || ch == '^' || ch == '%');
        bool isParen = (ch == '(' || ch == ')');
        bool isX = (ch == 'x' || ch == 'X');

        // 全角操作符判定
        bool isFullOp = false;
        if (i >= 2 && static_cast<unsigned char>(trimmed[i - 2]) == 0xEF &&
            static_cast<unsigned char>(trimmed[i - 1]) == 0xBC) {
            unsigned char c3 = static_cast<unsigned char>(ch);
            if (c3 == 0x8B || c3 == 0x8D || c3 == 0x8A || c3 == 0x8F || c3 == 0x88 || c3 == 0x89 || c3 == 0x85 || c3 == 0x8E) {
                isFullOp = true;
            }
        } else if (i >= 1 && static_cast<unsigned char>(trimmed[i - 1]) == 0xC3) {
            unsigned char c2 = static_cast<unsigned char>(ch);
            if (c2 == 0x97 || c2 == 0xB7) isFullOp = true;
        }

        if (isDigit || isFullDigit) {
            hasDigit = true;
            lastCharType = 1;
            startIdx = isFullDigit ? (i - 2) : i;
            if (isFullDigit) i -= 2;
        } else if (isDot) {
            startIdx = i;
        } else if (isOp || isFullOp) {
            lastCharType = 2;
            startIdx = isFullOp ? (i - (static_cast<unsigned char>(trimmed[i - 1]) == 0xC3 ? 1 : 2)) : i;
            if (isFullOp) i -= (static_cast<unsigned char>(trimmed[i - 1]) == 0xC3 ? 1 : 2);
        } else if (isParen) {
            lastCharType = (ch == '(') ? 3 : 1;
            startIdx = i;
        } else if (isX) {
            lastCharType = 2;
            startIdx = i;
        } else {
            break;
        }
    }

    if (!hasDigit || startIdx >= static_cast<int>(searchEnd)) return std::nullopt;

    std::string rawCandidate(endsWithEq ? trimmed.substr(startIdx) : trimmed.substr(startIdx, searchEnd - startIdx));
    // 去除开头多余的操作符
    size_t lead = 0;
    while (lead < rawCandidate.length()) {
        char c = rawCandidate[lead];
        if (c == '*' || c == '/' || c == '^' || c == '%' || c == ')' || c == ' ') {
            lead++;
        } else {
            break;
        }
    }
    std::string candidate = rawCandidate.substr(lead);
    if (candidate.empty()) return std::nullopt;

    // 检查已完成等式防重复计算
    std::string candNoEq = candidate;
    if (checkEndsWithEqual(candNoEq)) {
        candNoEq = std::string(dropTrailingEqual(candNoEq));
    }
    if (candNoEq.find('=') != std::string::npos || candNoEq.find("\xEF\xBC\x9D") != std::string::npos) {
        return std::nullopt;
    }

    return candidate;
}

// 检查并求值 RMB 大写
std::optional<MathResult> evaluateRmb(std::string_view trimmed) {
    std::string_view body = dropTrailingEqual(trimmed);
    body = trimEnd(body);
    if (body.empty()) return std::nullopt;

    std::string_view mathPart;
    if (endsWith(body, "大写")) {
        mathPart = trimEnd(body.substr(0, body.length() - std::string_view("大写").length()));
    } else if (endsWith(body, "元")) {
        mathPart = trimEnd(body.substr(0, body.length() - std::string_view("元").length()));
    } else if (endsWith(body, "dx", true)) {
        mathPart = trimEnd(body.substr(0, body.length() - 2));
    } else {
        return std::nullopt;
    }

    if (mathPart.empty()) return std::nullopt;

    // 向前提取数字与算式
    int startIdx = static_cast<int>(mathPart.length());
    bool hasDigit = false;
    for (int i = static_cast<int>(mathPart.length()) - 1; i >= 0; --i) {
        char c = mathPart[i];
        if (std::isdigit(static_cast<unsigned char>(c))) {
            hasDigit = true;
            startIdx = i;
        } else if (c == '.' || c == '+' || c == '-' || c == '*' || c == '/' ||
                   c == '^' || c == '%' || c == '(' || c == ')' || c == 'x' || c == 'X' || c == ' ') {
            startIdx = i;
        } else {
            break;
        }
    }

    if (!hasDigit || startIdx >= static_cast<int>(mathPart.length())) return std::nullopt;

    std::string expr = std::string(mathPart.substr(startIdx));
    std::string normalized = normalize(expr);

    double amount = 0.0;
    if (hasValidOperator(normalized)) {
        if (!calculate(normalized, amount)) return std::nullopt;
    } else {
        try {
            amount = std::stod(normalized);
        } catch (...) {
            return std::nullopt;
        }
    }

    std::string rmbText = toChineseCurrency(amount);
    if (rmbText.empty()) return std::nullopt;

    std::string rawExpr = std::string(trimmed.substr(startIdx));
    MathResult res;
    res.expression = rawExpr;
    res.resultText = rmbText;
    res.displayText = "= " + rmbText;
    res.fullEquation = rawExpr + rmbText;
    res.exprLength = utf16Length(rawExpr);
    return res;
}

// 检查并求值程序员进制转换
std::optional<MathResult> evaluateRadix(std::string_view trimmed) {
    std::string_view body = dropTrailingEqual(trimmed);
    body = trimEnd(body);
    if (body.empty()) return std::nullopt;

    auto make_result = [&](const std::string &rawExpr, const std::string &resultText)
        -> std::optional<MathResult> {
        if (rawExpr.empty() || resultText.empty()) return std::nullopt;
        MathResult res;
        res.expression = rawExpr;
        res.resultText = resultText;
        res.displayText = "= " + resultText;
        res.fullEquation = rawExpr + resultText;
        res.exprLength = utf16Length(rawExpr);
        return res;
    };

    // 1. 后缀形式：先匹配长后缀，数字部分只取末尾 ASCII 数字
    struct SuffixSpec { const char *suffix; int base; };
    static const SuffixSpec suffixes[] = {
        {"转16进制", 16}, {"16进制", 16}, {"hex", 16}, {"HEX", 16},
        {"转2进制", 2}, {"2进制", 2}, {"bin", 2}, {"BIN", 2},
        {"转8进制", 8}, {"8进制", 8}, {"oct", 8}, {"OCT", 8}, {"cot", 8}, {"COT", 8}
    };
    for (const auto &spec : suffixes) {
        std::string suffix(spec.suffix);
        if (!endsWith(body, suffix, true)) continue;
        std::string_view prefix = body.substr(0, body.length() - suffix.length());
        while (!prefix.empty() && (prefix.back() == ' ' || prefix.back() == '\t')) {
            prefix.remove_suffix(1);
        }
        size_t numStart = prefix.size();
        while (numStart > 0 &&
               std::isdigit(static_cast<unsigned char>(prefix[numStart - 1]))) {
            --numStart;
        }
        std::string num(prefix.substr(numStart));
        if (num.empty()) continue;
        try {
            uint64_t val = std::stoull(num, nullptr, 10);
            char buf[32];
            std::string resultText;
            if (spec.base == 16) {
                snprintf(buf, sizeof(buf), "0x%llX", static_cast<unsigned long long>(val));
                resultText = buf;
            } else if (spec.base == 8) {
                snprintf(buf, sizeof(buf), "0o%llo", static_cast<unsigned long long>(val));
                resultText = buf;
            } else {
                std::string bin;
                if (val == 0) {
                    bin = "0";
                } else {
                    while (val > 0) {
                        bin += (val & 1) ? '1' : '0';
                        val >>= 1;
                    }
                    std::reverse(bin.begin(), bin.end());
                }
                resultText = "0b" + bin;
            }
            return make_result(std::string(body.substr(numStart)), resultText);
        } catch (...) {
            continue;
        }
    }

    // 2. 前缀形式：0x/0b/0o，只取末尾 ASCII 字母数字，避免把中文并进 token
    int startIdx = static_cast<int>(body.length());
    for (int i = static_cast<int>(body.length()) - 1; i >= 0; --i) {
        unsigned char c = static_cast<unsigned char>(body[i]);
        if (!std::isalnum(c)) break;
        startIdx = i;
    }
    if (startIdx >= static_cast<int>(body.length())) return std::nullopt;
    std::string token(body.substr(startIdx));

    std::string resultText;
    if (startsWith(token, "0x", true) && token.length() > 2) {
        std::string hex = token.substr(2);
        bool allHex = std::all_of(hex.begin(), hex.end(), [](char c) {
            return std::isxdigit(static_cast<unsigned char>(c));
        });
        if (allHex) {
            try {
                uint64_t val = std::stoull(hex, nullptr, 16);
                resultText = std::to_string(val);
            } catch (...) {}
        }
    } else if (startsWith(token, "0b", true) && token.length() > 2) {
        std::string bin = token.substr(2);
        bool allBin = std::all_of(bin.begin(), bin.end(), [](char c) {
            return c == '0' || c == '1';
        });
        if (allBin) {
            try {
                uint64_t val = std::stoull(bin, nullptr, 2);
                resultText = std::to_string(val);
            } catch (...) {}
        }
    } else if (startsWith(token, "0o", true) && token.length() > 2) {
        std::string oct = token.substr(2);
        bool allOct = std::all_of(oct.begin(), oct.end(), [](char c) {
            return c >= '0' && c <= '7';
        });
        if (allOct) {
            try {
                uint64_t val = std::stoull(oct, nullptr, 8);
                resultText = std::to_string(val);
            } catch (...) {}
        }
    }

    return make_result(token, resultText);
}

} // namespace

std::optional<MathResult> evaluate(
    const std::string &textBeforeCursor,
    bool requireEqualSign,
    bool enableRmb,
    bool enableRadix
) {
    if (textBeforeCursor.empty()) return std::nullopt;

    // 截取末尾最多 120 字节
    std::string_view text(textBeforeCursor);
    if (text.length() > 120) {
        text = text.substr(text.length() - 120);
    }
    text = trimEnd(text);
    if (text.empty()) return std::nullopt;

    bool endsWithEq = checkEndsWithEqual(text);

    // 1. 人民币大写与进制转换（严格要求等号）
    if (endsWithEq) {
        if (enableRmb) {
            auto rmbRes = evaluateRmb(text);
            if (rmbRes) return rmbRes;
        }
        if (enableRadix) {
            auto radixRes = evaluateRadix(text);
            if (radixRes) return radixRes;
        }
    }

    // 2. 普通四则运算
    if (requireEqualSign && !endsWithEq) return std::nullopt;

    auto rawExprOpt = extractMathSlice(text);
    if (!rawExprOpt) return std::nullopt;
    const std::string &rawExpr = *rawExprOpt;

    std::string normalized = normalize(rawExpr);
    if (isNegativeMatch(normalized)) return std::nullopt;
    if (!hasValidOperator(normalized)) return std::nullopt;

    double resultVal = 0.0;
    if (!calculate(normalized, resultVal)) return std::nullopt;

    std::string resultStr = formatNumber(resultVal);
    if (resultStr.empty()) return std::nullopt;

    std::string cleanExpr = rawExpr;
    std::string fullEq = checkEndsWithEqual(cleanExpr) ? (cleanExpr + resultStr) : (cleanExpr + "=" + resultStr);

    MathResult res;
    res.expression = rawExpr;
    res.resultText = resultStr;
    res.displayText = "= " + resultStr;
    res.fullEquation = fullEq;
    res.exprLength = utf16Length(rawExpr);
    return res;
}

} // namespace xy
