package com.keyscafe.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpParserTest {

    @Test
    fun testCommonDomesticTemplates() {
        // 冒号与等号连接
        assertEquals("849201", XyCore.parseOtp("【招商银行】您的验证码是 849201，5分钟内有效，切勿泄露给他人。"))
        assertEquals("663921", XyCore.parseOtp("【支付宝】验证码：663921。您正在进行敏感操作，请勿向任何人透露。"))
        assertEquals("123456", XyCore.parseOtp("【工商银行】您的验证码为：123456，工作人员不会向您索取。"))
        assertEquals("7821", XyCore.parseOtp("【哔哩哔哩】本次登录验证码为: 7821，如非本人操作请忽略。"))

        // 4位包含20/19开头的正常验证码
        assertEquals("2038", XyCore.parseOtp("【建设银行】您的验证码是 2038，有效时间5分钟。"))

        // 括号包裹
        assertEquals("591823", XyCore.parseOtp("【腾讯科技】您正在申请重置密码，验证码【591823】，有效时间10分钟。"))
        assertEquals("382910", XyCore.parseOtp("【美团】动态码 [382910]，仅用于登录，打死也不能告诉别人！"))

        // 关键词在后
        assertEquals("482910", XyCore.parseOtp("482910 是您的动态口令，请在页面中输入完成校验。"))
        assertEquals("9318", XyCore.parseOtp("【京东】9318 为您的身份验证码，有效时间10分钟。"))

        // 平台带英文签名的短信（不能把【V2EX】误当成验证码）
        assertEquals("829103", XyCore.parseOtp("【V2EX】829103 为您的验证码"))

        // 个人小站及非标准格式
        assertEquals("4912", XyCore.parseOtp("【老王的小站】欢迎注册，本次验证码 4912，请勿告知他人。"))
        assertEquals("829102", XyCore.parseOtp("您好，博客登录口令为 829102，30分钟内有效。"))
        assertEquals("3719", XyCore.parseOtp("论坛账号安全验证：输入代码 3719 完成邮箱绑定。"))

        // 英文与字母数字混合
        assertEquals("749201", XyCore.parseOtp("Your GitHub verification code is 749201. Don't share it."))
        assertEquals("8392", XyCore.parseOtp("Use code 8392 to verify your Steam account login."))
        assertEquals("284910", XyCore.parseOtp("Your OTP is: 284910, valid for 5 minutes."))
        assertEquals("9K3M2", XyCore.parseOtp("【Steam】您的登录代码为 9K3M2。"))
        assertEquals("123456", XyCore.parseOtp("123456 is your verification code."))

        // 微软/谷歌格式与连字符、空格折叠归一
        assertEquals("829104", XyCore.parseOtp("G-829104 是您的 Google 验证码。"))
        assertEquals("829104", XyCore.parseOtp("Your Google verification code is G-829104. Don't share it."))
        assertEquals("123456", XyCore.parseOtp("【Google】G-123456 为您的身份验证码。"))
        assertEquals("829104", XyCore.parseOtp("Use 829-104 as Microsoft account security code."))
        assertEquals("829104", XyCore.parseOtp("您的微软账户安全验证码为 829 104，5分钟内有效。"))
        assertEquals("948201", XyCore.parseOtp("【Microsoft】验证码: 948-201，切勿向他人出示。"))

        // 复杂干扰项（含年份、手机号、时间、金额、运单号、客服电话、英文卡尾号与货币）
        assertEquals("948271", XyCore.parseOtp("【中国建设银行】2026年10月17日16:30，您尾号8888的账户支出100.00元，本次大额转账验证码为 948271。"))
        assertEquals("581920", XyCore.parseOtp("【顺丰速运】运单号SF1234567890已发出，收件验证码 581920。"))
        assertEquals("491820", XyCore.parseOtp("【民生银行】您的签约验证码为 491820。如有疑问请致电客服热线 95568。"))
        assertEquals("639102", XyCore.parseOtp("Your card ending in 8888 was charged $50. Your OTP is 639102."))
        assertEquals("582104", XyCore.parseOtp("Payment of $1000 was approved. Use code 582104 to confirm."))
    }

    @Test
    fun testNegativeCases() {
        assertNull(XyCore.parseOtp("【中国移动】您当前的手机话费余额为 32.50 元，请及时充值。"))
        assertNull(XyCore.parseOtp("明天下午 15:30 在三号会议室开会，请带上笔记本。"))
        assertNull(XyCore.parseOtp("您的快递已到菜鸟驿站，请凭手机号后四位取件。"))
        assertNull(XyCore.parseOtp("【系统通知】2026年年度账单已生成，请在系统内查看。"))
        assertNull(XyCore.parseOtp("【业务通知】如有任何问题，请拨打客服电话 95555 或联系在线客服。"))

        // 单词词干防误判（qrcode / unicode / spinning）
        assertNull(XyCore.parseOtp("Please scan qrcode 998811 to proceed"))
        assertNull(XyCore.parseOtp("Your unicode text has 123456 characters"))
        assertNull(XyCore.parseOtp("Wheel is spinning fast at 8888 rpm"))
    }
}
