#ifndef XY_MATH_H
#define XY_MATH_H

#include <string>
#include <vector>
#include <optional>

namespace xy {

struct MathResult {
    std::string expression;
    std::string resultText;
    std::string displayText;
    std::string fullEquation;
    int exprLength = 0;
};

std::optional<MathResult> evaluate(
    const std::string &textBeforeCursor,
    bool requireEqualSign = false,
    bool enableRmb = true,
    bool enableRadix = true
);

std::string toChineseCurrency(double amount);

} // namespace xy

#endif // XY_MATH_H
