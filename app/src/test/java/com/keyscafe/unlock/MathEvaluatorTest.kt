package com.keyscafe.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MathEvaluatorTest {

    @Test
    fun testBasicArithmetic() {
        // 简单加减乘除
        assertEquals("2", MathEvaluator.evaluate("1+1=")?.resultText)
        assertEquals("579", MathEvaluator.evaluate("123+456=")?.resultText)
        assertEquals("4352", MathEvaluator.evaluate("128*34=")?.resultText)
        assertEquals("75", MathEvaluator.evaluate("100-25=")?.resultText)
        assertEquals("22", MathEvaluator.evaluate("88/4=")?.resultText)

        // 浮点数精度（无 IEEE 754 0.30000000000000004 问题）
        assertEquals("0.3", MathEvaluator.evaluate("0.1+0.2=")?.resultText)
        assertEquals("6.28", MathEvaluator.evaluate("3.14*2=")?.resultText)
        assertEquals("12", MathEvaluator.evaluate("2.5*4.8=")?.resultText)

        // 除不尽时保留合理位数并四舍五入
        assertEquals("3.3333333333", MathEvaluator.evaluate("10/3=")?.resultText)
        assertEquals("0.6666666667", MathEvaluator.evaluate("2/3=")?.resultText)
    }

    @Test
    fun testChineseAndFullWidthSymbols() {
        // 全角中文符号
        assertEquals("4352", MathEvaluator.evaluate("128×34＝")?.resultText)
        assertEquals("25", MathEvaluator.evaluate("100÷4＝")?.resultText)
        assertEquals("50", MathEvaluator.evaluate("20＋30＝")?.resultText)
        assertEquals("70", MathEvaluator.evaluate("100－30＝")?.resultText)

        // 中文全角括号
        assertEquals("100", MathEvaluator.evaluate("（20＋30）×2＝")?.resultText)

        // 字符 'x' 与 'X' 代替乘号
        assertEquals("4352", MathEvaluator.evaluate("128x34=")?.resultText)
        assertEquals("200", MathEvaluator.evaluate("25X8=")?.resultText)
    }

    @Test
    fun testPrecedenceAndParentheses() {
        // 先乘除后加减
        assertEquals("14", MathEvaluator.evaluate("2+3*4=")?.resultText)
        assertEquals("20", MathEvaluator.evaluate("100-20*4=")?.resultText)

        // 括号改变优先级
        assertEquals("20", MathEvaluator.evaluate("(2+3)*4=")?.resultText)
        assertEquals("150", MathEvaluator.evaluate("(100-50)*(1+2)=")?.resultText)
        assertEquals("15", MathEvaluator.evaluate("((2+3)*4+10)/2=")?.resultText)

        // 乘方 ^
        assertEquals("1024", MathEvaluator.evaluate("2^10=")?.resultText)
        assertEquals("81", MathEvaluator.evaluate("3^4=")?.resultText)
        assertEquals("100", MathEvaluator.evaluate("10^2=")?.resultText)
    }

    @Test
    fun testPercentage() {
        // 百分号后缀
        assertEquals("10", MathEvaluator.evaluate("50*20%=")?.resultText)
        assertEquals("25", MathEvaluator.evaluate("100*25%=")?.resultText)
        assertEquals("0.5", MathEvaluator.evaluate("50%=")?.resultText)
    }

    @Test
    fun testSentenceContextExtraction() {
        // 处于句子末尾的算式提取
        val res1 = MathEvaluator.evaluate("今天买了水果，总共花了 12+34=")
        assertNotNull(res1)
        assertEquals("46", res1?.resultText)
        assertEquals("= 46", res1?.displayText)
        assertEquals("12+34=46", res1?.fullEquation)

        val res2 = MathEvaluator.evaluate("账单核对： (1500+200)*2=")
        assertNotNull(res2)
        assertEquals("3400", res2?.resultText)
        assertEquals("(1500+200)*2=3400", res2?.fullEquation)

        // 中文后紧跟算式（无空格）
        val res3 = MathEvaluator.evaluate("你好1+1=")
        assertNotNull("你好1+1= 应该能提取出 1+1=", res3)
        assertEquals("2", res3?.resultText)

        // 中文后带空格紧跟算式
        val res4 = MathEvaluator.evaluate("你好 1+1=")
        assertNotNull("你好 1+1= 应该能提取出 1+1=", res4)
        assertEquals("2", res4?.resultText)

        // 中文带标点后紧跟算式
        val res5 = MathEvaluator.evaluate("你好，1+1=")
        assertNotNull(res5)
        assertEquals("2", res5?.resultText)

        // 前文带有数字
        val res6 = MathEvaluator.evaluate("我有100元，花了1+1=")
        assertNotNull(res6)
        assertEquals("2", res6?.resultText)

        // 前文带有已完成的算式
        val res7 = MathEvaluator.evaluate("123x123=15129 1+1=")
        assertNotNull("123x123=15129 1+1= 应该只提取 1+1=", res7)
        assertEquals("2", res7?.resultText)

        // 已完成算式自身不应再被求值
        val resCompleted = MathEvaluator.evaluate("123*123=15129")
        assertNull("已完成的算式不应被计算", resCompleted)

        // 全角数字与符号
        val resFullWidth = MathEvaluator.evaluate("１＋１＝")
        assertNotNull("全角 １＋１＝ 应该能计算", resFullWidth)
        assertEquals("2", resFullWidth?.resultText)

        val resFullWidthSentence = MathEvaluator.evaluate("你好１＋１＝")
        assertNotNull("中文后全角 １＋１＝ 应该能计算", resFullWidthSentence)
        assertEquals("2", resFullWidthSentence?.resultText)
    }

    @Test
    fun testOptionalEqualSign() {
        // 在 requireEqualSign = false 时，允许不带等号直接计算
        assertEquals("46", MathEvaluator.evaluate("12+34", requireEqualSign = false)?.resultText)
        assertEquals("4352", MathEvaluator.evaluate("128*34", requireEqualSign = false)?.resultText)
        assertEquals("1024", MathEvaluator.evaluate("2^10", requireEqualSign = false)?.resultText)
        assertEquals("90", MathEvaluator.evaluate("(10+20)*3", requireEqualSign = false)?.resultText)

        // 但在 requireEqualSign = true 时，不带等号不应触发
        assertNull(MathEvaluator.evaluate("12+34", requireEqualSign = true))
    }

    @Test
    fun testRmbConversion() {
        // 单个数字转大写金额
        val res1 = MathEvaluator.evaluate("3500大写=")
        assertNotNull(res1)
        assertEquals("叁仟伍佰元整", res1?.resultText)
        assertEquals("= 叁仟伍佰元整", res1?.displayText)
        assertEquals("3500大写=", res1?.expression)

        // 算式结合大写金额（用户重点诉求：1x3大写=）
        val res2 = MathEvaluator.evaluate("1x3大写=")
        assertNotNull(res2)
        assertEquals("叁元整", res2?.resultText)
        assertEquals("= 叁元整", res2?.displayText)

        // 复杂算式结合元结尾
        val res3 = MathEvaluator.evaluate("100+50元=")
        assertNotNull(res3)
        assertEquals("壹佰伍拾元整", res3?.resultText)

        // 零头与分角处理
        assertEquals("叁仟伍佰元伍角整", MathEvaluator.evaluate("3500.5大写=")?.resultText)
        assertEquals("叁仟伍佰元零伍分", MathEvaluator.evaluate("3500.05大写=")?.resultText)
        assertEquals("叁仟伍佰元伍角伍分", MathEvaluator.evaluate("3500.55大写=")?.resultText)
        assertEquals("伍角整", MathEvaluator.evaluate("0.5大写=")?.resultText)
        assertEquals("伍分", MathEvaluator.evaluate("0.05元=")?.resultText)
        assertEquals("零元整", MathEvaluator.evaluate("0大写=")?.resultText)

        // 大额数字与段内零
        assertEquals("壹仟零贰拾肆元整", MathEvaluator.evaluate("1024大写=")?.resultText)
        assertEquals("壹仟零伍元整", MathEvaluator.evaluate("1005大写=")?.resultText)
        assertEquals("壹亿元整", MathEvaluator.evaluate("100000000大写=")?.resultText)
        assertEquals("壹亿零壹元整", MathEvaluator.evaluate("100000001大写=")?.resultText)

        // 负金额不应出现"负零"
        assertEquals("负壹佰元整", MathEvaluator.evaluate("-100元=")?.resultText)
        assertEquals("负壹佰元整", MathEvaluator.evaluate("-100大写=")?.resultText)

        // 超范围金额不能越界，返回提示
        assertEquals("金额超出范围", MathEvaluator.evaluate("999999999999999999元=")?.resultText)

        // 四舍五入到零分的极小金额
        assertEquals("零元整", MathEvaluator.evaluate("0.00002元=")?.resultText)

        // 句子中提取
        val resSentence = MathEvaluator.evaluate("请尽快完成汇款3500大写=")
        assertNotNull(resSentence)
        assertEquals("3500大写=", resSentence?.expression)
        assertEquals("叁仟伍佰元整", resSentence?.resultText)
    }

    @Test
    fun testRadixConversion() {
        // 16 进制转 10 进制 (前缀 0x)
        val resHex = MathEvaluator.evaluate("0x10=")
        assertNotNull(resHex)
        assertEquals("16", resHex?.resultText)
        assertEquals("= 16", resHex?.displayText)
        assertEquals("0x10=", resHex?.expression)

        assertEquals("272", MathEvaluator.evaluate("0x0110=")?.resultText)
        assertEquals("255", MathEvaluator.evaluate("0xFF=")?.resultText)

        // 2 进制转 10 进制 (前缀 0b，严格限制仅 0 和 1)
        val resBin = MathEvaluator.evaluate("0b0110=")
        assertNotNull(resBin)
        assertEquals("6", resBin?.resultText)
        assertEquals("= 6", resBin?.displayText)

        assertEquals("10", MathEvaluator.evaluate("0b1010=")?.resultText)
        assertEquals("42", MathEvaluator.evaluate("0b101010=")?.resultText)

        // 8 进制转 10 进制 (前缀 0o)
        assertEquals("63", MathEvaluator.evaluate("0o77=")?.resultText)

        // 10 进制转 16 进制 (后缀 hex)
        val resDecToHex = MathEvaluator.evaluate("255hex=")
        assertNotNull(resDecToHex)
        assertEquals("0xFF", resDecToHex?.resultText)
        assertEquals("= 0xFF", resDecToHex?.displayText)

        assertEquals("0x10", MathEvaluator.evaluate("16hex=")?.resultText)

        // 10 进制转 2 进制 (后缀 bin)
        val resDecToBin = MathEvaluator.evaluate("6bin=")
        assertNotNull(resDecToBin)
        assertEquals("0b110", resDecToBin?.resultText)

        assertEquals("0b1010", MathEvaluator.evaluate("10bin=")?.resultText)

        // 10 进制转 8 进制 (后缀 oct 或 cot)
        assertEquals("0o77", MathEvaluator.evaluate("63oct=")?.resultText)
        assertEquals("0o77", MathEvaluator.evaluate("63cot=")?.resultText)

        // 非法字符过滤（如 2 进制出现 2）
        assertNull(MathEvaluator.evaluate("0b102="))
        assertNull(MathEvaluator.evaluate("0x12G="))

        // 严格防误触规则：不输入等号不触发！
        assertNull(MathEvaluator.evaluate("0x10"))
        assertNull(MathEvaluator.evaluate("3500大写"))
        assertNull(MathEvaluator.evaluate("63oct"))
        assertNull(MathEvaluator.evaluate("63cot"))

        // 已废弃生活单位换算，不应触发
        assertNull(MathEvaluator.evaluate("1024mb="))
        assertNull(MathEvaluator.evaluate("1kg="))
    }

    @Test
    fun testNegativeCases() {
        // 单个数字不能触发普通计算
        assertNull(MathEvaluator.evaluate("123="))
        assertNull(MathEvaluator.evaluate("123"))

        // 日期排除
        assertNull(MathEvaluator.evaluate("2026-10-17"))
        assertNull(MathEvaluator.evaluate("2026/10/17"))

        // IP 地址排除
        assertNull(MathEvaluator.evaluate("192.168.1.1"))

        // 时间排除
        assertNull(MathEvaluator.evaluate("15:30"))

        // 手机号排除
        assertNull(MathEvaluator.evaluate("13800138000"))

        // 除以 0 保护
        assertNull(MathEvaluator.evaluate("100/0="))

        // 不完整算式
        assertNull(MathEvaluator.evaluate("12+"))
        assertNull(MathEvaluator.evaluate("12*"))
        assertNull(MathEvaluator.evaluate("(12+34"))
    }
}
