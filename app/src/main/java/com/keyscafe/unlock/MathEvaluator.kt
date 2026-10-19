package com.keyscafe.unlock


/**
 * 算式解析求值结果
 */
data class CalcResult(
    /** 匹配到的原始算式，如 "128*34=" 或 "255hex=" */
    val expression: String,
    /** 纯计算结果文本，如 "4352" 或 "0xFF" */
    val resultText: String,
    /** 候选栏展示文本，如 "= 4352" */
    val displayText: String,
    /** 完整等式文本，如 "128*34=4352" */
    val fullEquation: String,
    /** 算式字符长度（包含等号） */
    val exprLength: Int
)

/**
 * 算式与进制转换求值器
 */
object MathEvaluator {

    /**
     * 从光标前文本中检测并计算算式
     */
    fun evaluate(
        textBeforeCursor: String?,
        requireEqualSign: Boolean = false,
        enableRmb: Boolean = true,
        enableRadix: Boolean = true
    ): CalcResult? {
        if (textBeforeCursor.isNullOrBlank()) return null
        val raw = XyCore.evaluateMath(textBeforeCursor, requireEqualSign, enableRmb, enableRadix) ?: return null
        if (raw.size < 5) return null
        val exprLength = raw[4].toIntOrNull() ?: raw[0].length
        return CalcResult(
            expression = raw[0],
            resultText = raw[1],
            displayText = raw[2],
            fullEquation = raw[3],
            exprLength = exprLength
        )
    }
}
