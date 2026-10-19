package com.keyscafe.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 语言模型与拼音词库单元测试
 */
class LmEnhancerTest {

    companion object {
        private lateinit var tempDir: File

        @BeforeClass
        @JvmStatic
        fun setup() {
            tempDir = File(System.getProperty("java.io.tmpdir"), "xy_lm_deep_test")
            tempDir.mkdirs()
            XyCore.lmInit(tempDir.absolutePath)
        }
    }

    @Test
    fun test01_coreModelIntegrity() {
        assertTrue("核心引擎必须就绪", XyCore.isLmReady())
        val info = XyCore.getLmInfo()
        assertNotNull(info)
        assertTrue("信息需包含单元", info.contains("单元"))
        println("[OK] 核心引擎状态: $info")
    }

    @Test
    fun test02_coreDictIntegrity() {
        assertTrue("核心词库必须就绪", XyCore.isDictReady())
        val info = XyCore.getDictInfo()
        assertNotNull(info)
        assertTrue("信息需包含词条", info.contains("词条"))
        assertTrue("信息需包含拼音键", info.contains("拼音键"))
        println("[OK] 核心词库状态: $info")
    }

    @Test
    fun test03_fullPinyinVocabulary() {
        val testCases = mapOf(
            "nihao" to "你好",
            "chifan" to "吃饭",
            "daima" to "代码",
            "gongzuo" to "工作",
            "ceshi" to "测试",
            "xiyu" to "西域",
            "shouji" to "手机",
            "tianqi" to "天气"
        )
        for ((py, expectedWord) in testCases) {
            val candidates = XyCore.dictQueryPinyin(py, 5)
            assertTrue("拼音 [$py] 必须包含词条 [$expectedWord]，实际返回: $candidates", candidates.contains(expectedWord))
        }
        println("[OK] 拼音全拼 8 组高频词条全量验证通过")
    }

    @Test
    fun test04_acronymMatrix() {
        val acronymCases = mapOf(
            "srkl" to "生日快乐",
            "xswl" to "笑死我了",
            "zqsg" to "真情实感",
            "bdjw" to "不懂就问",
            "plmm" to "漂亮妹妹",
            "dbq" to "对不起",
            "tql" to "太强了"
        )
        for ((acronym, expectedPhrase) in acronymCases) {
            val list = XyCore.dictQueryPinyin(acronym, 5)
            assertTrue("缩写简拼 [$acronym] 必须召回短语 [$expectedPhrase]，实际: $list", list.contains(expectedPhrase))
        }

        val uniqueAcronyms = mapOf(
            "srkl" to "生日快乐",
            "zqsg" to "真情实感",
            "bdjw" to "不懂就问",
            "plmm" to "漂亮妹妹",
            "dbq" to "对不起"
        )
        for ((acronym, expectedPhrase) in uniqueAcronyms) {
            val list = XyCore.dictQueryPinyin(acronym, 5)
            assertEquals("缩写简拼 [$acronym] 的首选必须为 [$expectedPhrase]", expectedPhrase, list.firstOrNull())
        }
        println("[OK] 首字母简拼 7 组主流短语召回与首选验证通过")
    }

    @Test
    fun test05_contextTransitionsMatrix() {
        val matrix = listOf(
            Triple("祝你", "生发", "生日快乐"),
            Triple("代码", "提货", "提交"),
            Triple("我们去", "迟反", "吃饭"),
            Triple("今天", "填气", "天气"),
            Triple("收到", "请假", "请回复"),
            Triple("身体", "键位", "健康"),
            Triple("毕业", "轮训", "论文"),
            Triple("依法", "版图", "办理"),
            Triple("市场", "行迹", "行情"),
            Triple("手机", "排查", "拍照"),
            Triple("项目", "进食", "进度"),
            Triple("语音", "束缚", "输入"),
            Triple("网络", "连杆", "连接"),
            Triple("系统", "奔放", "崩溃"),
            Triple("用户", "天梯", "体验"),
            Triple("互相", "遵照", "尊重"),
            Triple("会议", "祭奠", "记录"),
            Triple("安全", "黄昏", "防护"),
            Triple("大力发展", "法度", "经济"),
            Triple("环境", "抱负", "保护")
        )

        var passedCount = 0
        for ((ctx, noise, target) in matrix) {
            val candidates = arrayOf(noise, target, "其他杂词")
            val reorder = checkNotNull(XyCore.lmRerank(ctx, candidates))
            assertEquals("在语境 [$ctx] 下，目标词 [$target] 必须被提升至首位，原首选为 [$noise]", 1, reorder[0])
            passedCount++
        }
        println("[OK] 语言模型 20 组跨场景语境转移重排 100% 通过 (通过数: $passedCount/20)")
    }

    @Test
    fun test06_homophoneDisambiguation() {
        // 场景 1：IT 编程 vs 物流寄送
        val candCodeOrExpress = arrayOf("快递", "代码")
        val order1 = checkNotNull(XyCore.lmRerank("编写", candCodeOrExpress))
        assertEquals("前文 [编写] 必须消歧首选 [代码]", 1, order1[0])
        val order2 = checkNotNull(XyCore.lmRerank("寄送", candCodeOrExpress))
        assertEquals("前文 [寄送] 必须消歧首选 [快递]", 0, order2[0])

        // 场景 2：校园备考 vs 故意欺骗
        val candExamOrDeceive = arrayOf("欺瞒", "期末")
        val order3 = checkNotNull(XyCore.lmRerank("迎战", candExamOrDeceive))
        assertEquals("前文 [迎战] 必须消歧首选 [期末]", 1, order3[0])
        val order4 = checkNotNull(XyCore.lmRerank("故意", candExamOrDeceive))
        assertEquals("前文 [故意] 必须消歧首选 [欺瞒]", 0, order4[0])

        // 场景 3：文旅出行 vs 目标规划
        val candReserveOrTarget = arrayOf("预定", "预订")
        val order5 = checkNotNull(XyCore.lmRerank("酒店", candReserveOrTarget))
        assertEquals("前文 [酒店] 必须消歧首选 [预订]", 1, order5[0])
        val order6 = checkNotNull(XyCore.lmRerank("目标", candReserveOrTarget))
        assertEquals("前文 [目标] 必须消歧首选 [预定]", 0, order6[0])

        // 场景 4：医疗诊断 vs 物理震动
        val candMedicalOrShock = arrayOf("震荡", "诊断")
        val order7 = checkNotNull(XyCore.lmRerank("医生", candMedicalOrShock))
        assertEquals("前文 [医生] 必须消歧首选 [诊断]", 1, order7[0])
        val order8 = checkNotNull(XyCore.lmRerank("剧烈", candMedicalOrShock))
        assertEquals("前文 [剧烈] 必须消歧首选 [震荡]", 0, order8[0])

        println("[OK] 同音/同形候选词 4 组双向多重语境消歧能力 100% 验证通过")
    }

    @Test
    fun test07_userDynamicLearningPersistence() {
        val isolatedDir = File(tempDir, "isolated_mem_${System.nanoTime()}").apply { mkdirs() }
        XyCore.lmInit(isolatedDir.absolutePath)

        val ctx = "深度测试语境"
        val customRarePhrase = "汐语定制短语"

        val cands = arrayOf("常见词A", customRarePhrase, "常见词B")
        val initialOrder = checkNotNull(XyCore.lmRerank(ctx, cands))
        assertTrue("初始未经学习时罕见词不应排在首位", initialOrder[0] != 1)

        XyCore.lmRecordCommit(ctx, customRarePhrase)
        XyCore.lmRecordCommit(ctx, customRarePhrase)

        val trainedOrder = checkNotNull(XyCore.lmRerank(ctx, cands))
        assertEquals("连续上屏后专属词必须提升至首选第0位", 1, trainedOrder[0])

        XyCore.lmSync()
        val dataFile = File(isolatedDir, "xy_user_dict.dat")
        assertTrue("自学习记忆数据文件必须存在于磁盘", dataFile.exists() && dataFile.length() > 0)

        // 模拟重新初始化
        XyCore.lmInit(isolatedDir.absolutePath)

        val reloadedOrder = checkNotNull(XyCore.lmRerank(ctx, cands))
        assertEquals("重启加载后专属词依然保持首选第0位", 1, reloadedOrder[0])
        println("[OK] 本地动态自学习提权与磁盘持久化验证通过")
    }

    @Test
    fun test08_nextWordPredictionMultiLevel() {
        val contexts = listOf("祝你", "我们", "收到", "非常")
        for (ctx in contexts) {
            val preds = XyCore.lmPredictNext(ctx, 3)
            assertTrue("语境 [$ctx] 必须预测出下一词", preds.isNotEmpty())
            println("[OK] 光标前文 [$ctx] 下一词预测: $preds")
        }
    }

    @Test
    fun test09_microsecondPerformanceBenchmark10000Queries() {
        val candidates = arrayOf("迟反", "吃饭", "赤反", "出差", "开会", "看电影")
        val context = "我们去"

        repeat(1000) {
            XyCore.lmRerank(context, candidates)
        }

        val iterations = 10000
        val durationsNano = LongArray(iterations)

        for (i in 0 until iterations) {
            val start = System.nanoTime()
            XyCore.lmRerank(context, candidates)
            durationsNano[i] = System.nanoTime() - start
        }

        durationsNano.sort()
        val avgNs = durationsNano.average()
        val p50Ns = durationsNano[(iterations * 0.50).toInt()]
        val p95Ns = durationsNano[(iterations * 0.95).toInt()]
        val p99Ns = durationsNano[(iterations * 0.99).toInt()]

        val avgUs = avgNs / 1000.0
        val p50Us = p50Ns / 1000.0
        val p95Us = p95Ns / 1000.0
        val p99Us = p99Ns / 1000.0

        println("----------------------------------------------------------------")
        println("【候选词重排序 10,000 次性能压测结果】")
        println("平均耗时: %.2f 微秒 (%.4f 毫秒)".format(avgUs, avgUs / 1000.0))
        println("P50  耗时: %.2f 微秒".format(p50Us))
        println("P95  耗时: %.2f 微秒".format(p95Us))
        println("P99  耗时: %.2f 微秒".format(p99Us))
        println("----------------------------------------------------------------")

        assertTrue("平均耗时必须严格小于 50 微秒 (0.05ms)，实际为 ${avgUs} 微秒", avgUs < 50.0)
        assertTrue("P99 极值耗时必须严格小于 150 微秒 (0.15ms)，实际为 ${p99Us} 微秒", p99Us < 150.0)
    }

    @Test
    fun test10_sentenceBoundaryTruncation() {
        val candidates = arrayOf("迟反", "吃饭", "其他")

        val reorderPeriod = checkNotNull(XyCore.lmRerank("前文无关句子。我们去", candidates))
        assertEquals("遇中文句号截断后仍能识别当前句 [我们去]，提升 [吃饭]", 1, reorderPeriod[0])

        val reorderComma = checkNotNull(XyCore.lmRerank("今天天气真好，我们去", candidates))
        assertEquals("遇中文逗号截断后识别当前句 [我们去]，提升 [吃饭]", 1, reorderComma[0])

        val reorderSpace = checkNotNull(XyCore.lmRerank("Hello 我们去", candidates))
        assertEquals("遇半角空格截断后识别当前句 [我们去]，提升 [吃饭]", 1, reorderSpace[0])
        val reorderFullSpace = checkNotNull(XyCore.lmRerank("前缀词\u3000我们去", candidates))
        assertEquals("遇全角空格截断后识别当前句 [我们去]，提升 [吃饭]", 1, reorderFullSpace[0])

        val isolatedCands = arrayOf("提货", "提交", "生日快乐")
        val reorderIsolated = checkNotNull(XyCore.lmRerank("祝你。代码", isolatedCands))
        assertEquals("遇句号截断前句 [祝你]，仅根据当前句 [代码] 提升 [提交]", 1, reorderIsolated[0])

        val reorderSpaceIsolated = checkNotNull(XyCore.lmRerank("祝你 代码", isolatedCands))
        assertEquals("遇空格截断前词 [祝你]，仅根据当前词 [代码] 提升 [提交]", 1, reorderSpaceIsolated[0])

        println("[OK] 分句与边界截断验证通过")
    }

    @Test
    fun test11_rollbackCommitAndClearMemory() {
        val isolatedDir = File(tempDir, "isolated_rollback_${System.nanoTime()}").apply { mkdirs() }
        XyCore.lmInit(isolatedDir.absolutePath)

        val ctx = "测试回退语境"
        val typoPhrase = "错别字词组"
        val cands = arrayOf("标准首选", typoPhrase, "备用词")

        val initOrder = checkNotNull(XyCore.lmRerank(ctx, cands))
        assertEquals("初始状态保持首选", 0, initOrder[0])

        XyCore.lmRecordCommit(ctx, typoPhrase)
        val afterCommit = checkNotNull(XyCore.lmRerank(ctx, cands))
        assertEquals("误选上屏后被学习提权", 1, afterCommit[0])

        XyCore.lmRollbackCommit(ctx, typoPhrase)
        val afterRollback = checkNotNull(XyCore.lmRerank(ctx, cands))
        assertEquals("误选回退后扣除学习频次，恢复原首选", 0, afterRollback[0])

        XyCore.lmRecordCommit(ctx, typoPhrase)
        XyCore.lmRecordCommit(ctx, typoPhrase)
        val learnedAgain = checkNotNull(XyCore.lmRerank(ctx, cands))
        assertEquals(1, learnedAgain[0])

        XyCore.lmClearUserMemory()
        val afterClear = checkNotNull(XyCore.lmRerank(ctx, cands))
        assertEquals("清空自学习词库后彻底重置记忆", 0, afterClear[0])

        println("[OK] 误选退格自学习回退与清空自学习词库验证通过")
    }

    @Test
    fun test12_longPhrasePreferenceAndFlipGate() {
        XyCore.lmSetLongPhraseAggressive(true)
        val longCand = arrayOf("生发", "祝你生日快乐天天开心", "其他")
        val orderAggressive = checkNotNull(XyCore.lmRerank("祝你", longCand))
        assertEquals("激进长句优先模式下整句获得字长加成优先首选", 1, orderAggressive[0])

        XyCore.lmSetLongPhraseAggressive(false)
        val orderBalanced = checkNotNull(XyCore.lmRerank("祝你", longCand))
        assertEquals("常规均衡模式下整句依据语境匹配排在首选", 1, orderBalanced[0])

        val noContextCands = arrayOf("默认首选", "备选词条", "第三候选")
        val gateOrder = checkNotNull(XyCore.lmRerank("", noContextCands))
        assertEquals("无显著胜出分差时第 0 位首选不可被颠覆", 0, gateOrder[0])
        assertTrue("辅推位包含其余全部候选", gateOrder.contains(1) && gateOrder.contains(2))

        println("[OK] 长词覆盖效用增益、激进/常规模式切换与翻转门禁机制验证通过")
    }

    @Test
    fun test13_viterbiPinyinSentenceDecoding() {
        if (!XyCore.isDictReady()) return

        val pinyin = "wojuedezheshiyigefeichanghaoderen"
        // 预热消除 JNI 首次方法解析开销
        XyCore.lmDecodeSentence("", "nihao")
        val startNanos = System.nanoTime()
        val decoded = checkNotNull(XyCore.lmDecodeSentence("", pinyin))
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0
        println("[OK] 整句拼音解码结果: [$pinyin] -> '$decoded' (耗时: ${String.format("%.3f", elapsedMs)}ms)")

        assertTrue("整句必须解码出'我觉得'", decoded.contains("我觉得"))
        assertTrue("整句必须解码出'一个'", decoded.contains("一个"))
        assertTrue("整句必须解码出'非常'", decoded.contains("非常"))
        assertTrue("整句必须包含'人'", decoded.contains("人"))
        assertTrue("超长整句解码耗时必须在毫秒级 (< 15ms)", elapsedMs < 15.0)
    }

    @Test
    fun test14_pinyinUserLearningAndRollback() {
        val isolatedDir = File(tempDir, "isolated_py_mem_${System.nanoTime()}").apply { mkdirs() }
        XyCore.lmInit(isolatedDir.absolutePath)

        val py = "zidingyi"
        val userWord = "自定义新词条"
        val cands = arrayOf("默认首推词", userWord, "备选词")

        val initOrder = checkNotNull(XyCore.lmRerank(py, null, cands))
        assertEquals("未学习前保持原首选", 0, initOrder[0])

        XyCore.lmRecordCommit(py, null, userWord)

        val learnedOrder = checkNotNull(XyCore.lmRerank(py, null, cands))
        assertEquals("拼音自学习后必须推至首选第0位", 1, learnedOrder[0])

        XyCore.lmRollbackCommit(py, null, userWord)
        val rollbackOrder = checkNotNull(XyCore.lmRerank(py, null, cands))
        assertEquals("退格撤销后恢复原首选", 0, rollbackOrder[0])
        println("[OK] 拼音自学词典提权与退格撤销验证通过")
    }

    @Test
    fun test15_nativeBluePencilLearnedPromotion() {
        val cands = arrayOf("保守第一位", "原生用户词", "第三位")
        val isLearned = booleanArrayOf(false, true, false)

        val order = checkNotNull(XyCore.lmRerank(null, null, cands, isLearned))
        assertEquals("原生用户词必须打破门禁直接推至首选第0位", 1, order[0])
        println("[OK] 原生用户词提权首选验证通过")
    }

    @Test
    fun test16_incompleteTrailingPinyinNotPolluted() {
        if (!XyCore.isDictReady()) return

        val incompletePinyin = "suoyouderendoushuowomenbuhes"
        val decoded = XyCore.lmDecodeSentence("", incompletePinyin)

        assertNotNull("末尾含未完成声母前缀时仍应平滑产出已完整音节的整句候选，消除候选栏闪烁", decoded)
        if (!decoded.isNullOrEmpty()) {
            val hasAscii = decoded.any { it in 'a'..'z' || it in 'A'..'Z' }
            assertTrue("整句解码结果严禁包含任何未转化的英文字母: '$decoded'", !hasAscii)
        }
        println("[OK] 未完成末尾声母输入态纯净度与防闪烁平滑解码验证通过 (返回: '$decoded')")
    }

    @Test
    fun test17_adaptiveFlipGateShortPinyinStability() {
        // 短拼音（<=3 字符）高门禁保护：常规微弱语境下保持原生高频单字首选
        val shortPy = "de"
        val cands = arrayOf("的", "得到", "德国")
        val order = checkNotNull(XyCore.lmRerank(shortPy, "我们", cands))
        assertEquals("短拼音输入时强力保护原生第0位高频首选 [的]", 0, order[0])
        println("[OK] 短拼音自适应翻转门禁稳定性验证通过")
    }

    @Test
    fun test18_longPinyinSyllableCoverageProtection() {
        // 长拼音输入时，单字严禁截断翻转多音节候选
        val longPy = "chifandian"
        val cands = arrayOf("吃饭", "吃", "其他")
        val order = checkNotNull(XyCore.lmRerank(longPy, null, cands))
        assertEquals("长拼音输入时单字 [吃] 严禁截断翻转多音节首选 [吃饭]", 0, order[0])
        println("[OK] 长拼音音节覆盖率保护验证通过")
    }

    @Test
    fun test19_syllableAmbiguityAndApostropheDecoding() {
        if (!XyCore.isDictReady()) return

        // 验证多音节切分与显式隔音符号解码
        val decoded1 = checkNotNull(XyCore.lmDecodeSentence("", "shejifangan"))
        assertEquals("连写歧义切分解码必须准确识别 [设计方案]", "设计方案", decoded1)

        val decoded2 = checkNotNull(XyCore.lmDecodeSentence("", "sheji'fangan"))
        assertEquals("显式隔音符号切分解码必须准确识别 [设计方案]", "设计方案", decoded2)

        val decoded3 = checkNotNull(XyCore.lmDecodeSentence("", "womenquchifan"))
        assertEquals("多音节连贯解码必须识别 [我们去吃饭]", "我们去吃饭", decoded3)

        val decoded4 = checkNotNull(XyCore.lmDecodeSentence("", "jin'tian'tian'qi'zhen'hao"))
        assertEquals("全音节显式隔音符号解码必须识别 [今天天气真好]", "今天天气真好", decoded4)

        // 验证跨词音节边界歧义 (很深奥)
        val decoded5 = checkNotNull(XyCore.lmDecodeSentence("", "henshenao"))
        assertEquals("跨词音节边界歧义解码必须准确识别 [很深奥]", "很深奥", decoded5)

        println("[OK] 词格多路径音节歧义切分与显式隔音符号解码全量验证通过")
    }

    @Test
    fun test20_progressiveShortTruncationGuard() {
        val isolatedDir = File(tempDir, "isolated_short_guard_${System.nanoTime()}").apply { mkdirs() }
        XyCore.lmInit(isolatedDir.absolutePath)

        val longPy = "shengrikuaile"
        val cands = arrayOf("生日快乐", "生日", "其他")

        // 单次误触短词不应截断 >=4 字长句首选
        XyCore.lmRecordCommit(longPy, null, "生日")
        val afterOneCommit = checkNotNull(XyCore.lmRerank(longPy, null, cands))
        assertEquals("单次上屏短词不应截断4字整句首选 [生日快乐]", 0, afterOneCommit[0])

        // 连续第 2 次明确选择该短词后，尊重用户真实意图允许置顶
        XyCore.lmRecordCommit(longPy, null, "生日")
        val afterTwoCommits = checkNotNull(XyCore.lmRerank(longPy, null, cands))
        assertEquals("累计2次明确上屏短词后允许突破防截断门禁置顶", 1, afterTwoCommits[0])

        println("[OK] 渐进式防截断调频门禁验证通过")
    }

    @Test
    fun test21_universalModeAndCustomPhrase() {
        val isolatedDir = File(tempDir, "isolated_universal_${System.nanoTime()}").apply { mkdirs() }
        XyCore.lmInit(isolatedDir.absolutePath)
        XyCore.lmClearUserMemory()

        // 验证通用模式开关切换
        XyCore.lmSetQuanpinMode(false)

        // 添加自定义快捷短语 (按键编码触发与上文语境触发)
        val addedCode = XyCore.lmAddCustomPhrase("dz", "常用收货地址", false)
        assertTrue("添加编码快捷短语成功", addedCode)

        val addedCtx = XyCore.lmAddCustomPhrase("@收到", "已收到，谢谢！", true)
        assertTrue("添加上文快捷短语成功", addedCtx)

        // 编码检索验证
        val codeResults = XyCore.lmQueryUserCandidates("dz", 3)
        assertTrue("编码检索应召回自定义快捷短语", codeResults.contains("常用收货地址"))

        // 上文检索验证
        val ctxResults = XyCore.lmQueryUserCandidates("", "收到", 3)
        assertTrue("上文检索应召回自定义上文快捷短语", ctxResults.contains("已收到，谢谢！"))

        // 通用模式重排：自定义短语应置顶
        val cands = arrayOf("地址", "常用收货地址", "打折")
        val order = checkNotNull(XyCore.lmRerank("dz", null, cands))
        assertEquals("自定义快捷短语在重排中应获得绝对置顶权", 1, order[0])

        println("[OK] 双拼/五笔通用模式与自定义快捷短语验证通过")
    }

    @Test
    fun test22_badWordSuppressionAndUnsuppress() {
        val isolatedDir = File(tempDir, "isolated_suppress_${System.nanoTime()}").apply { mkdirs() }
        XyCore.lmInit(isolatedDir.absolutePath)
        XyCore.lmClearUserMemory()

        val badWord = "某个错词"
        // 将错词加入防复发压制黑名单
        val suppressed = XyCore.lmRemoveUserEntry("*", "*", badWord, suppressBadWord = true)
        assertTrue("压制坏词成功", suppressed)

        // 验证即使原生排在第 0 位，被压制后也必须强行降权、失去首选保护并沉底
        val cands = arrayOf(badWord, "正确候选", "其他备选")
        val order = checkNotNull(XyCore.lmRerank("code", "ctx", cands))
        assertTrue("被压制的坏词严禁排在首位", order[0] != 0)
        assertEquals("健康候选词应接管首位", 1, order[0])

        // 验证被压制的词禁止再次被自学习
        XyCore.lmRecordCommit("code", "ctx", badWord)
        val afterCommit = XyCore.lmQueryUserCandidates("code", 3)
        assertFalse("已压制坏词禁止自学习重新入库", afterCommit.contains(badWord))

        // 验证解除压制
        val unsuppressed = XyCore.lmRemoveUserEntry("S", "", badWord, suppressBadWord = false)
        assertTrue("解除压制成功", unsuppressed)

        println("[OK] 坏词防复发压制、强力降权与解除压制全流程验证通过")
    }

    @Test
    fun test23_userLexiconTsvExportImportRoundtrip() {
        val isolatedDir = File(tempDir, "isolated_tsv_${System.nanoTime()}").apply { mkdirs() }
        XyCore.lmInit(isolatedDir.absolutePath)
        XyCore.lmClearUserMemory()

        XyCore.lmAddCustomPhrase("wb", "五笔字型", false)
        XyCore.lmAddCustomPhrase("@好的", "收到马上处理", true)
        XyCore.lmRemoveUserEntry("*", "*", "错乱词", suppressBadWord = true)

        // 导出 TSV
        val tsv = XyCore.lmExportUserMemoryTsv()
        assertTrue("TSV 备份必须包含版本标识", tsv.contains("# XY_USER_LEXICON_TSV"))
        assertTrue("TSV 备份必须包含编码短语", tsv.contains("五笔字型"))
        assertTrue("TSV 备份必须包含上文短语", tsv.contains("收到马上处理"))
        assertTrue("TSV 备份必须包含压制记录", tsv.contains("错乱词"))

        // 解析列表模型验证
        val entries = XyCore.lmListUserEntries()
        assertEquals("应包含 3 条记录 (2短语 + 1压制)", 3, entries.size)
        assertTrue(entries.any { it.word == "五笔字型" && it.isCustomPhrase })
        assertTrue(entries.any { it.word == "错乱词" && it.isSuppressed })

        // 导入覆盖测试
        val count = XyCore.lmImportUserMemoryTsv(tsv, merge = false)
        assertTrue("导入条数应 >= 3", count >= 3)

        val entriesAfter = XyCore.lmListUserEntries()
        assertEquals("覆盖导入后记录数保持一致", 3, entriesAfter.size)

        println("[OK] 个人词库 TSV 序列化、导出与覆盖导入闭环验证通过")
    }
}