package com.keyscafe.unlock

import java.io.File

/** Native 核心桥接 */
object XyCore {

    /** 个人词库条目 */
    data class UserLexiconEntry(
        val type: String,        // "P": 编码触发, "C": 上下文触发, "S": 坏词压制
        val key: String,         // 触发编码 / 前文语境 / "*"
        val word: String,        // 词条文本
        val frequency: Int,      // 频次 (>=1000 为手动添加的快捷短语, -1 为压制词)
        val timestamp: Long      // 最近更新时间戳
    ) {
        val isCustomPhrase: Boolean get() = frequency >= 1000
        val isSuppressed: Boolean get() = type == "S" || frequency < 0
    }

    init {
        runCatching {
            System.loadLibrary("xycore")
        }.onFailure {
            // 测试/宿主回退只在 debug 生效，release 不允许从可写目录加载 .so
            if (!BuildConfig.DEBUG) throw it
            val userDir = System.getProperty("user.dir") ?: "."
            val candidates = listOf(
                File(userDir, "build/host-lib/libxycore.so"),
                File(userDir, "../build/host-lib/libxycore.so"),
                File("build/host-lib/libxycore.so"),
                File("../build/host-lib/libxycore.so"),
                File("app/build/host-lib/libxycore.so")
            )
            val found = candidates.firstOrNull { f -> f.exists() }
            if (found != null) {
                System.load(found.absolutePath)
            } else {
                throw it
            }
        }
    }

    @JvmStatic
    private external fun nativeGetSecret(id: Int): String?

    @JvmStatic
    private external fun nativeComputeSilkWaves(
        y0: FloatArray,
        y1: FloatArray,
        y2: FloatArray,
        env: FloatArray,
        samples: Int,
        baseY: Float,
        amp: Float,
        spread: Float,
        phase: Float,
        spreadStep: Float
    ): Float

    @JvmStatic
    private external fun nativeEvaluateMath(
        text: String,
        reqEq: Boolean,
        rmb: Boolean,
        radix: Boolean
    ): Array<String>?

    @JvmStatic
    private external fun nativeParseOtp(text: String): String?

    @JvmStatic
    private external fun nativeLmInit(storageDir: String?, customModelPath: String?, customDictPath: String?)

    @JvmStatic
    private external fun nativeLmIsReady(): Boolean

    @JvmStatic
    private external fun nativeLmGetInfo(): String?

    @JvmStatic
    private external fun nativeDictIsReady(): Boolean

    @JvmStatic
    private external fun nativeDictGetInfo(): String?

    @JvmStatic
    private external fun nativeDictQueryPinyin(pinyin: String, maxCount: Int): Array<String>?

    @JvmStatic
    private external fun nativeDictQueryWordPrefix(prefix: String, maxCount: Int): Array<String>?

    @JvmStatic
    private external fun nativeIsValidPinyin(text: String): Boolean

    @JvmStatic
    private external fun nativeLmRerank(
        pinyin: String?,
        context: String?,
        candidates: Array<String>,
        isUserLearned: BooleanArray?
    ): IntArray?

    @JvmStatic
    private external fun nativeLmPredictNext(context: String, maxCount: Int): Array<String>?

    @JvmStatic
    private external fun nativeLmRecordCommit(pinyin: String?, prevContext: String?, committedText: String)

    @JvmStatic
    private external fun nativeLmRollbackCommit(pinyin: String?, prevContext: String?, committedText: String)

    @JvmStatic
    private external fun nativeLmClearUserMemory()

    @JvmStatic
    private external fun nativeLmSetLongPhraseAggressive(aggressive: Boolean)

    @JvmStatic
    private external fun nativeLmSync()

    @JvmStatic
    private external fun nativeLmDecodeSentence(context: String?, pinyin: String): String?

    @JvmStatic
    private external fun nativeCheckSecurityStatus(): Int

    @JvmStatic
    private external fun nativeLmSetQuanpinMode(enabled: Boolean)

    @JvmStatic
    private external fun nativeLmQueryUserCandidates(code: String?, context: String?, maxCount: Int): Array<String>?

    @JvmStatic
    private external fun nativeLmExportUserMemoryTsv(): String?

    @JvmStatic
    private external fun nativeLmImportUserMemoryTsv(tsv: String, merge: Boolean): Int

    @JvmStatic
    private external fun nativeLmAddCustomPhrase(triggerKey: String, word: String, isContextTrigger: Boolean): Boolean

    @JvmStatic
    private external fun nativeLmRemoveUserEntry(type: String, key: String, word: String, suppressBadWord: Boolean): Boolean

    @JvmStatic
    private external fun nativeTruncateSentence(ctx: String): String?

    @JvmStatic
    private external fun nativeContainsChinese(text: String): Boolean

    // -1 表示检查本身失败，不能当作安全
    fun checkSecurityStatus(): Int =
        runCatching { nativeCheckSecurityStatus() }.getOrDefault(-1)

    fun get(id: Int): String = nativeGetSecret(id) ?: ""

    fun computeSilkWaves(
        y0: FloatArray,
        y1: FloatArray,
        y2: FloatArray,
        env: FloatArray,
        samples: Int,
        baseY: Float,
        amp: Float,
        spread: Float,
        phase: Float,
        spreadStep: Float
    ): Float = nativeComputeSilkWaves(y0, y1, y2, env, samples, baseY, amp, spread, phase, spreadStep)

    fun evaluateMath(
        text: String,
        reqEq: Boolean,
        rmb: Boolean,
        radix: Boolean
    ): Array<String>? = nativeEvaluateMath(text, reqEq, rmb, radix)

    fun parseOtp(text: String): String? = nativeParseOtp(text)

    fun lmInit(storageDir: String?, customModelPath: String? = null, customDictPath: String? = null) =
        runCatching { nativeLmInit(storageDir, customModelPath, customDictPath) }

    fun isLmReady(): Boolean = runCatching { nativeLmIsReady() }.getOrDefault(false)

    fun getLmInfo(): String = runCatching { nativeLmGetInfo() }.getOrNull() ?: ""

    fun isDictReady(): Boolean = runCatching { nativeDictIsReady() }.getOrDefault(false)

    fun getDictInfo(): String = runCatching { nativeDictGetInfo() }.getOrNull() ?: ""

    fun dictQueryPinyin(pinyin: String, maxCount: Int = 3): List<String> =
        runCatching { nativeDictQueryPinyin(pinyin, maxCount)?.toList() }.getOrNull() ?: emptyList()

    fun dictQueryWordPrefix(prefix: String, maxCount: Int = 6): List<String> =
        runCatching { nativeDictQueryWordPrefix(prefix, maxCount)?.toList() }.getOrNull() ?: emptyList()

    fun isValidPinyin(text: String): Boolean =
        runCatching { nativeIsValidPinyin(text) }.getOrDefault(false)

    fun lmRerank(
        pinyin: String?,
        context: String?,
        candidates: Array<String>,
        isUserLearned: BooleanArray? = null
    ): IntArray? =
        runCatching { nativeLmRerank(pinyin, context, candidates, isUserLearned) }.getOrNull()

    fun lmRerank(context: String?, candidates: Array<String>): IntArray? =
        lmRerank(null, context, candidates, null)

    fun lmPredictNext(context: String, maxCount: Int = 3): List<String> =
        runCatching { nativeLmPredictNext(context, maxCount)?.toList() }.getOrNull() ?: emptyList()

    fun lmRecordCommit(pinyin: String?, prevContext: String?, committedText: String) =
        runCatching { nativeLmRecordCommit(pinyin, prevContext, committedText) }

    fun lmRecordCommit(prevContext: String?, committedText: String) =
        lmRecordCommit(null, prevContext, committedText)

    fun lmRollbackCommit(pinyin: String?, prevContext: String?, committedText: String) =
        runCatching { nativeLmRollbackCommit(pinyin, prevContext, committedText) }

    fun lmRollbackCommit(prevContext: String?, committedText: String) =
        lmRollbackCommit(null, prevContext, committedText)

    fun lmClearUserMemory() = runCatching { nativeLmClearUserMemory() }

    fun lmSetLongPhraseAggressive(aggressive: Boolean) =
        runCatching { nativeLmSetLongPhraseAggressive(aggressive) }

    fun lmSetQuanpinMode(enabled: Boolean) =
        runCatching { nativeLmSetQuanpinMode(enabled) }

    fun lmQueryUserCandidates(code: String?, context: String?, maxCount: Int = 3): List<String> =
        runCatching { nativeLmQueryUserCandidates(code, context, maxCount)?.toList() }.getOrNull() ?: emptyList()

    fun lmQueryUserCandidates(code: String?, maxCount: Int = 3): List<String> =
        lmQueryUserCandidates(code, null, maxCount)

    fun lmExportUserMemoryTsv(): String =
        runCatching { nativeLmExportUserMemoryTsv() }.getOrNull() ?: ""

    // 区分“词库为空”和“导出失败”：失败返回 null，调用方不得覆盖远端
    fun lmExportUserMemoryTsvOrNull(): String? =
        runCatching { nativeLmExportUserMemoryTsv() }.getOrNull()

    fun lmImportUserMemoryTsv(tsv: String, merge: Boolean = true): Int =
        runCatching { nativeLmImportUserMemoryTsv(tsv, merge) }.getOrDefault(0)

    fun lmAddCustomPhrase(triggerKey: String, word: String, isContextTrigger: Boolean = false): Boolean =
        runCatching { nativeLmAddCustomPhrase(triggerKey, word, isContextTrigger) }.getOrDefault(false)

    fun lmRemoveUserEntry(type: String, key: String, word: String, suppressBadWord: Boolean = false): Boolean =
        runCatching { nativeLmRemoveUserEntry(type, key, word, suppressBadWord) }.getOrDefault(false)

    fun truncateSentence(ctx: String): String =
        runCatching { nativeTruncateSentence(ctx) }.getOrNull() ?: ctx

    fun containsChinese(text: CharSequence?): Boolean =
        if (text.isNullOrEmpty()) false
        else runCatching { nativeContainsChinese(text.toString()) }.getOrDefault(false)

    fun lmListUserEntries(): List<UserLexiconEntry> {
        val raw = lmExportUserMemoryTsv()
        if (raw.isBlank()) return emptyList()
        val list = ArrayList<UserLexiconEntry>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val parts = trimmed.split('\t')
            if (parts.size >= 3) {
                val type = parts[0].uppercase()
                val key = parts[1]
                val word = parts[2]
                if (word.isEmpty() || (type == "C" && key == "^")) continue
                val freq = parts.getOrNull(3)?.toIntOrNull() ?: 1
                val ts = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                list.add(UserLexiconEntry(type, key, word, freq, ts))
            }
        }
        return list
    }

    fun lmSync() = runCatching { nativeLmSync() }

    fun lmDecodeSentence(context: String?, pinyin: String): String? =
        runCatching { nativeLmDecodeSentence(context, pinyin) }.getOrNull()
}
