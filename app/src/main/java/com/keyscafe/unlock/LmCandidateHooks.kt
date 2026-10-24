package com.keyscafe.unlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import io.github.libxposed.api.XposedModule
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal fun countChineseCharacters(text: String): Int =
    text.count { c -> c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF }

internal fun containsChinese(text: CharSequence?): Boolean = XyCore.containsChinese(text)

internal fun hasSufficientNativeCoverage(
    chineseCount: Int,
    syllableMatchCount: Int,
    expectedLength: Int
): Boolean = chineseCount >= 5 && (expectedLength <= 0 || syllableMatchCount >= GBOARD_SLOT_COUNT)

private val STANDALONE_PINYIN_SYLLABLES = setOf(
    "a", "ai", "an", "ang", "ao",
    "e", "ei", "en", "eng", "er",
    "o", "ou"
)

private val PINYIN_FINALS = setOf(
    "a", "o", "e", "i", "u", "v", "ü",
    "ai", "ei", "ao", "ou", "an", "en", "in", "un", "vn", "ün",
    "ang", "eng", "ing", "ong",
    "ia", "ie", "iao", "iu", "ian", "iang", "iong",
    "ua", "uo", "uai", "ui", "uan", "uang", "ue", "ve", "üe", "van", "üan"
)

private val W_FINALS = setOf("a", "ai", "an", "ang", "ei", "en", "eng", "o", "u")
private val Y_FINALS = setOf("a", "an", "ang", "ao", "e", "i", "in", "ing", "o", "ong", "ou", "u", "uan", "ue", "un")

private fun isValidFullPinyinSyllable(s: String): Boolean {
    if (s.isEmpty() || s.length > 6) return false
    if (s in STANDALONE_PINYIN_SYLLABLES) return true
    if (s.startsWith("w")) return s.substring(1) in W_FINALS
    if (s.startsWith("y")) return s.substring(1) in Y_FINALS
    val rest = if (s.startsWith("zh") || s.startsWith("ch") || s.startsWith("sh")) {
        s.substring(2)
    } else if (s[0] in "bpmfdtnlgkhjqxrzcs") {
        s.substring(1)
    } else {
        return false
    }
    return rest in PINYIN_FINALS
}

internal fun normalizeSpellText(rawText: String): String =
    rawText.trim().lowercase().replace(Regex("[\\s']+"), "'").trim('\'')

internal fun countQuanpinSyllables(cleanPinyin: String): Int {
    val s = cleanPinyin.trim().lowercase().filter { it in 'a'..'z' || it == 'ü' }
    val n = s.length
    if (n == 0) return 0

    val bestPenalty = IntArray(n + 1) { Int.MAX_VALUE / 2 }
    val bestSyllables = IntArray(n + 1) { Int.MAX_VALUE / 2 }
    bestPenalty[0] = 0
    bestSyllables[0] = 0

    for (i in 0 until n) {
        val curPen = bestPenalty[i]
        val curSyl = bestSyllables[i]
        if (curPen >= Int.MAX_VALUE / 4) continue

        val maxEnd = minOf(n, i + 6)
        for (j in (i + 1)..maxEnd) {
            val sub = s.substring(i, j)
            val stepPenalty = when {
                isValidFullPinyinSyllable(sub) -> 0
                sub == "zh" || sub == "ch" || sub == "sh" || j == i + 1 -> 1
                else -> continue
            }
            val nextPen = curPen + stepPenalty
            val nextSyl = curSyl + 1
            if (nextPen < bestPenalty[j] || (nextPen == bestPenalty[j] && nextSyl < bestSyllables[j])) {
                bestPenalty[j] = nextPen
                bestSyllables[j] = nextSyl
            }
        }
    }
    return bestSyllables[n].coerceAtLeast(1)
}

internal fun estimateSyllableCount(
    rawSpellWithApos: String?,
    cleanPinyin: String?,
    quanpinMode: Boolean = true
): Int {
    if (cleanPinyin.isNullOrBlank()) return 0
    if (!rawSpellWithApos.isNullOrBlank() && rawSpellWithApos.contains('\'')) {
        val explicitCount = rawSpellWithApos.split('\'').count { it.isNotBlank() }
        if (explicitCount > 0) return explicitCount
    }
    if (!quanpinMode) return 0
    return countQuanpinSyllables(cleanPinyin)
}

internal fun <T> filterCandidatesBySyllableLength(
    items: List<T>,
    syllableCount: Int,
    userShortcuts: Set<String> = emptySet(),
    textExtractor: (T) -> String?
): List<T> {
    if (syllableCount <= 0 || items.isEmpty()) return items

    // 严禁出现 > syllableCount 的跨长度联想词；保留 <= syllableCount 的真实字词并按字长层级从长到短稳定排列
    val boundedMatches = items.filter { item ->
        val text = textExtractor(item) ?: return@filter true
        if (text in userShortcuts) return@filter true
        val zhCount = countChineseCharacters(text)
        zhCount in 0..syllableCount
    }
    if (boundedMatches.isEmpty()) return items

    return boundedMatches.sortedByDescending { item ->
        val text = textExtractor(item) ?: return@sortedByDescending 0
        if (text in userShortcuts) {
            Int.MAX_VALUE
        } else {
            countChineseCharacters(text)
        }
    }
}

/**
 * 语言模型与词库候选词 Hook
 * 1. 支持「通用智能记忆与重排（适用全拼/双拼/五笔）」与「全拼整句与词库增强（全拼专属）」双模式独立控制
 * 2. 监听文本上屏，更新本地动态记忆并支持 3 秒内退格事务回退
 * 3. 支持个人词库管理（编码/上文快捷短语直推、坏词剔除与防复发压制、跨进程实时同步）
 * 4. 补偿三星键盘工具栏折叠与上屏瞬间的 SpellText 残影，保证 @上文 语境短语可触发
 */
object LmCandidateHooks {

    private const val IME_SERVICE_CLASS = "com.samsung.android.honeyboard.service.HoneyBoardService"
    private const val INPUT_CONNECTION_CLASS = "jh.a"

    private var cachedClassLoader: ClassLoader? = null
    @Volatile
    private var serviceRef: WeakReference<InputMethodService>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 候选词反射属性高速缓存
    private class CandidateAccessor(
        val textMethod: Method?,
        val textField: Field?,
        val inputMethod: Method?,
        val inputField: Field?,
        val learnedMethod: Method?,
        val learnedField: Field?
    ) {
        fun getText(item: Any): String? {
            return try {
                textMethod?.invoke(item)?.toString()
                    ?: textField?.get(item)?.toString()
            } catch (_: Throwable) {
                null
            }
        }

        fun getInput(item: Any): String? {
            return try {
                inputMethod?.invoke(item)?.toString()
                    ?: inputField?.get(item)?.toString()
            } catch (_: Throwable) {
                null
            }
        }

        fun isUserLearned(item: Any): Boolean {
            return try {
                val value = learnedMethod?.invoke(item) ?: learnedField?.get(item)
                if (value is Number) {
                    value.toInt() == 2
                } else if (value is Boolean) {
                    value
                } else {
                    false
                }
            } catch (_: Throwable) {
                false
            }
        }
    }

    private val accessorMap = ConcurrentHashMap<Class<*>, CandidateAccessor>()

    // 候选词构造器持久句柄缓存
    private var modernBuilderConstructor: Constructor<*>? = null
    private var modernBuilderDMethod: Method? = null
    private var modernBuilderNMethod: Method? = null
    private var modernBuilderBuildMethod: Method? = null

    private var legacyBuilderConstructor: Constructor<*>? = null
    private var legacyBuilderBMethod: Method? = null
    private var legacyBuilderBuildMethod: Method? = null
    private var buildersInitialized = false

    // 候选词分发器实例缓存（用于上屏后命中 @上文 短语时主动唤醒候选栏）
    @Volatile
    private var emitterInstanceRef: WeakReference<Any>? = null
    @Volatile
    private var emitterMethodL: Method? = null
    @Volatile
    private var emitterMethodA: Method? = null
    @Volatile
    private var isInternalContextPush = false

    // 嵌套时保存并恢复原值，避免内层提前清除外层的保护
    private fun beginInternalPush(): Boolean {
        val prev = isInternalContextPush
        isInternalContextPush = true
        return prev
    }
    // 最近一次传给三星候选管线的列表，用于上屏后主动移除注入项
    @Volatile
    private var lastEmittedListRef: WeakReference<MutableList<Any>>? = null
    // 保存每次 eb0.a.l / f50.a.a 进入时的原生候选快照与时间戳，供拼写修正重算使用
    @Volatile
    private var lastRawNativeList: List<Any>? = null
    @Volatile
    private var lastRawNativeTime = 0L
    // 保存 setSpellText 最新调用值，用于检测时序差一拍后补推候选
    @Volatile
    private var latestSpellText: String = ""
    // 注入自定义编码短语后是否需要延迟重发一次候选，迫使工具栏模式下的候选栏立即展开
    @Volatile
    private var lastInjectionNeedsReemit = false
    @Volatile
    private var lastInjectionCode: String? = null
    // 候选列表发射序号：同一编码重新输入时也能区分成两次不同的注入
    @Volatile
    private var emissionSeq = 0L
    @Volatile
    private var lastReemitSeq = -1L
    // 记录上一次 @上文 语境推送的时间戳，用于兜底清空时判断是否已有联想推送
    @Volatile
    private var lastContextPushTimestamp = 0L
    // 记录最近一次 eb0.a.l / f50.a.a 的 slot，供 reemitWithLatestSpell 使用
    @Volatile
    private var lastRawSlot: Int = 0
    // 记录上次 processCandidates 处理的拼写串，用于检测 setSpellText 时序差
    @Volatile
    private var lastProcessedPinyin: String = ""
    private val reemitLock = Any()

    // 异步防抖落盘执行器
    private val syncExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "xy-lm-sync").apply { isDaemon = true }
    }
    private val syncLock = Any()
    private var syncFuture: ScheduledFuture<*>? = null

    // 误选退格自学习事务回退跟踪 (支持多步连击退格)
    private data class CommitRecord(
        val word: String,
        val pinyin: String,
        val context: String,
        val timestamp: Long,
        var remainingLength: Int,
        var rolledBack: Boolean = false
    )

    private val commitJournal = ArrayDeque<CommitRecord>()
    private val journalLock = Any()

    @Volatile
    private var lastKnownContextBefore = ""
    @Volatile
    private var lastCommittedWord = ""
    @Volatile
    private var lastCommittedPinyin = ""
    @Volatile
    private var lastCommitTimestamp = 0L
    @Volatile
    private var currentActivePinyin = ""
    @Volatile
    private var isSpellComposingActive = false
    @Volatile
    private var spellTextViewModelRef: WeakReference<Any>? = null
    @Volatile
    private var isChinesePinyinKeypadMethod: Method? = null
    @Volatile
    private var cachedIsChinese: Boolean = false
    @Volatile
    private var lastLanguageCheckTime: Long = 0L

    internal fun invalidateLanguageCache() {
        lastLanguageCheckTime = 0L
    }

    private data class SyntheticInfo(
        val text: String,
        val input: String,
        @Volatile var committed: Boolean = false
    )
    // 最近一次创建的合成短语编码，WeakHashMap 的键被回收后用它兜底匹配
    @Volatile
    private var lastSyntheticCode: String = ""
    private val syntheticCandidates = Collections.synchronizedMap(WeakHashMap<Any, SyntheticInfo>())
    private var suggestionField: Field? = null

    const val ACTION_CLEAR_LM_MEMORY = "com.keyscafe.unlock.ACTION_CLEAR_LM_MEMORY"
    const val ACTION_SYNC_USER_LEXICON = "com.keyscafe.unlock.ACTION_SYNC_USER_LEXICON"
    const val ACTION_REQUEST_LEXICON_EXPORT = "com.keyscafe.unlock.ACTION_REQUEST_LEXICON_EXPORT"
    const val ACTION_LEXICON_EXPORT_RESULT = "com.keyscafe.unlock.ACTION_LEXICON_EXPORT_RESULT"
    const val EXTRA_LEXICON_TSV = "tsv"
    const val EXTRA_LEXICON_OVERWRITE = "overwrite"
    const val EXTRA_LEXICON_VERSION = "version"
    const val EXTRA_BROADCAST_TOKEN = "lm_broadcast_token"
    private var lexiconReceiver: BroadcastReceiver? = null

    fun onMemoryCleared() {
        synchronized(journalLock) {
            commitJournal.clear()
        }
        currentActivePinyin = ""
        latestSpellText = ""
        lastProcessedPinyin = ""
        lastRawNativeList = emptyList()
        lastRawNativeTime = 0L
        lastKnownContextBefore = ""
        lastCommittedWord = ""
        lastCommittedPinyin = ""
        lastCommitTimestamp = 0L
        isSpellComposingActive = false
        lastSyntheticCode = ""
        lastReemitSeq = -1L
        lastInjectionNeedsReemit = false
        lastInjectionCode = null
        lastContextPushTimestamp = 0L
        synchronized(syntheticCandidates) {
            syntheticCandidates.clear()
        }
        clearNativeUserDict()
    }

    fun clearNativeUserDict() {
        val cl = cachedClassLoader
        if (cl != null) {
            runCatching {
                val cCls = Reflect.findClassIfExists("p50.c", cl)
                val sogou = cCls?.getMethod("b")?.invoke(null)
                sogou?.javaClass?.getMethod("X")?.invoke(sogou)
                XLog.i("LmCandidateHooks: 已触发原生引擎词库重置")
            }.onFailure { XLog.e("调用原生引擎清空接口失败", it) }
        }

        runCatching {
            val filesDir = ConfigStore.filesDir() ?: serviceRef?.get()?.filesDir
            if (filesDir == null) {
                XLog.e("清理原生词库失败：拿不到 filesDir", null)
                return@runCatching
            }
            val dictDir = File(filesDir, "dict")
            if (dictDir.exists()) {
                val usrFiles: Array<File>? = dictDir.listFiles { _: File, name: String ->
                    name.startsWith("sgim_gd_usr") || name.startsWith("sgim_en_usr")
                }
                usrFiles?.forEach { f: File ->
                    if (f.isFile) {
                        f.delete()
                        XLog.i("LmCandidateHooks: 已清理原生词库文件: ${f.name}")
                    }
                }
            }
        }.onFailure { XLog.e("物理清理原生词库文件失败", it) }
    }

    private fun rememberEmittedList(list: MutableList<Any>) {
        lastEmittedListRef = WeakReference(list)
        emissionSeq++
    }

    fun install(module: XposedModule, classLoader: ClassLoader) {
        cachedClassLoader = classLoader

        preheatAccessors(classLoader)
        preheatBuilders(classLoader)

        hookHoneyBoardService(module, classLoader)
        hookSpellTextViewModel(module, classLoader)
        hookCandidateEmitter(module, classLoader)
        hookCandidateClick(module, classLoader)
        hookCommitAndBackspace(module, classLoader)

        XLog.i("LmCandidateHooks: 就绪")
    }

    private fun hookSpellTextViewModel(module: XposedModule, classLoader: ClassLoader) {
        val spellVmName = "com.samsung.android.honeyboard.textboard.candidate.viewmodel.SpellTextViewModel"
        val spellVmCls = Reflect.findClassIfExists(spellVmName, classLoader)
        if (spellVmCls != null) {
            isChinesePinyinKeypadMethod = runCatching {
                spellVmCls.getDeclaredMethod("isChinesePinyinKeypad").apply { isAccessible = true }
            }.getOrNull()
        }

        // 监听配置/语言变化回调，实时失效语言状态缓存
        val listenerCls = Reflect.findClassIfExists(
            "com.samsung.android.honeyboard.textboard.candidate.viewmodel.SpellTextViewModel\$a",
            classLoader
        )
        listenerCls?.declaredMethods?.firstOrNull {
            it.name == "onBoardConfigChanged" && it.parameterTypes.size == 3
        }?.let { m ->
            XLog.safe("LmCandidateHooks.onBoardConfigChanged") {
                module.hook(m).intercept { chain ->
                    invalidateLanguageCache()
                    chain.proceed()
                }
            }
        }

        val targets = listOf(
            spellVmName,
            "com.samsung.android.honeyboard.textboard.candidate.viewmodel.PhoneticSpellViewModel"
        )
        for (name in targets) {
            val cls = Reflect.findClassIfExists(name, classLoader) ?: continue
            val isMainSpellVm = (name == spellVmName)
            for (ctor in cls.declaredConstructors) {
                XLog.safe("LmCandidateHooks.spellVm.$name.ctor") {
                    module.hook(ctor).intercept { chain ->
                        val r = chain.proceed()
                        if (isMainSpellVm) {
                            (chain.thisObject ?: r)?.let {
                                spellTextViewModelRef = WeakReference(it)
                                invalidateLanguageCache()
                            }
                        }
                        r
                    }
                }
            }
            // One UI 8 起拼写更新走 setSpell(dn.d)，其 a 字段为当前拼写文本
            cls.declaredMethods.firstOrNull { it.name == "setSpell" && it.parameterTypes.size == 1 }?.let { m ->
                val spellField = runCatching {
                    m.parameterTypes[0].getDeclaredField("a").apply { isAccessible = true }
                }.getOrNull()
                XLog.safe("LmCandidateHooks.spellVm.$name.setSpell") {
                    module.hook(m).intercept { chain ->
                        if (isMainSpellVm) {
                            chain.thisObject?.let { spellTextViewModelRef = WeakReference(it) }
                        }
                        val arg = chain.getArg(0)
                        if (arg == null) {
                            invalidateLanguageCache()
                            onSpellTextChanged("", classLoader)
                        } else {
                            val raw = spellField?.let {
                                runCatching { it.get(arg)?.toString() }.getOrNull()
                            }
                            if (raw != null) onSpellTextChanged(raw, classLoader)
                        }
                        chain.proceed()
                    }
                }
            }
            cls.declaredMethods.firstOrNull { it.name == "getSpellText" }?.let { m ->
                XLog.safe("LmCandidateHooks.spellVm.$name.getSpellText") {
                    module.hook(m).intercept { chain ->
                        if (isMainSpellVm) {
                            chain.thisObject?.let { spellTextViewModelRef = WeakReference(it) }
                        }
                        chain.proceed()
                    }
                }
            }
            // 监听拼音文本更新与可见性切换，识别上屏后的闲置态
            cls.declaredMethods.firstOrNull { it.name == "setSpellText" && it.parameterTypes.size == 1 }?.let { m ->
                XLog.safe("LmCandidateHooks.spellVm.$name.setSpellText") {
                    module.hook(m).intercept { chain ->
                        if (isMainSpellVm) {
                            chain.thisObject?.let { spellTextViewModelRef = WeakReference(it) }
                        }
                        val text = chain.getArg(0)?.toString()?.trim().orEmpty()
                        val r = chain.proceed()
                        onSpellTextChanged(text, classLoader)
                        r
                    }
                }
            }
            for (visMethodName in listOf("setSpellTextVisible", "setSpellLayoutVisibility")) {
                cls.declaredMethods.firstOrNull {
                    it.name == visMethodName && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == java.lang.Boolean.TYPE
                }?.let { m ->
                    XLog.safe("LmCandidateHooks.spellVm.$name.$visMethodName") {
                        module.hook(m).intercept { chain ->
                            val visible = chain.getArg(0) as? Boolean ?: false
                            if (!visible) {
                                isSpellComposingActive = false
                                currentActivePinyin = ""
                                latestSpellText = ""
                                lastProcessedPinyin = ""
                                lastRawNativeTime = 0L
                            }
                            chain.proceed()
                        }
                    }
                }
            }
        }
    }

    // 拼写文本变化统一入口：更新组合态；若晚于 eb0.a.l 到达，立即用最新拼写重算候选
    private fun onSpellTextChanged(rawText: String, classLoader: ClassLoader) {
        if (!isChineseLanguageActive(classLoader)) {
            latestSpellText = ""
            isSpellComposingActive = false
            currentActivePinyin = ""
            lastProcessedPinyin = ""
            lastRawNativeList = emptyList()
            lastRawNativeTime = 0L
            return
        }
        val normalized = normalizeSpellText(rawText)
        val clean = normalized.replace("'", "")
        // 上屏后 220ms 内，若回调仍是刚上屏拼音的残影，忽略本次更新，
        // 避免重新置位组合态后卡住 35ms 的 @上文 主动推送
        val ghostAfterCommit = clean.isNotEmpty() &&
            lastCommittedPinyin.isNotEmpty() &&
            System.currentTimeMillis() - lastCommitTimestamp < 220L &&
            clean == lastCommittedPinyin
        if (!ghostAfterCommit) {
            latestSpellText = normalized
            isSpellComposingActive = clean.isNotEmpty()
            currentActivePinyin = clean
            if (clean.isEmpty()) {
                lastProcessedPinyin = ""
                lastRawNativeTime = 0L
            }
        }
        if (!ghostAfterCommit && clean.isNotEmpty() && clean != lastProcessedPinyin) {
            reemitWithLatestSpell(classLoader)
        }
    }

    private fun preheatAccessors(classLoader: ClassLoader) {
        val targets = listOf("dn.b", "k30.f", "eb0.a", "f50.a")
        for (name in targets) {
            Reflect.findClassIfExists(name, classLoader)?.let { cls ->
                accessorMap[cls] = resolveAccessor(cls)
            }
        }
    }

    private fun preheatBuilders(classLoader: ClassLoader) {
        if (buildersInitialized) return
        modernBuilderConstructor = null
        modernBuilderDMethod = null
        modernBuilderNMethod = null
        modernBuilderBuildMethod = null
        legacyBuilderConstructor = null
        legacyBuilderBMethod = null
        legacyBuilderBuildMethod = null

        var modernReady = false
        Reflect.findClassIfExists("dn.b\$a", classLoader)?.let { cls ->
            val ctor = runCatching {
                cls.getConstructor(
                    java.lang.Integer.TYPE,
                    CharSequence::class.java,
                    java.lang.Boolean.TYPE
                ).apply { isAccessible = true }
            }.getOrNull()
            val dMethod = runCatching {
                cls.getMethod("D", CharSequence::class.java).apply { isAccessible = true }
            }.getOrNull()
            val nMethod = runCatching {
                cls.getMethod("N", String::class.java).apply { isAccessible = true }
            }.getOrNull()
            val buildMethod = runCatching {
                cls.getMethod("a").apply { isAccessible = true }
            }.getOrNull()
            if (ctor != null && dMethod != null && buildMethod != null) {
                modernBuilderConstructor = ctor
                modernBuilderDMethod = dMethod
                modernBuilderNMethod = nMethod
                modernBuilderBuildMethod = buildMethod
                modernReady = true
            }
        }

        var legacyReady = false
        Reflect.findClassIfExists("k30.f\$a", classLoader)?.let { cls ->
            val ctor = runCatching {
                cls.getConstructor(CharSequence::class.java).apply { isAccessible = true }
            }.getOrNull()
            val bMethod = runCatching {
                cls.getMethod("b", String::class.java).apply { isAccessible = true }
            }.getOrNull()
            val buildMethod = runCatching {
                cls.getMethod("a").apply { isAccessible = true }
            }.getOrNull()
            if (ctor != null && bMethod != null && buildMethod != null) {
                legacyBuilderConstructor = ctor
                legacyBuilderBMethod = bMethod
                legacyBuilderBuildMethod = buildMethod
                legacyReady = true
            }
        }
        // 只有完整解析成功才置位；部分成功保留可重试状态
        buildersInitialized = modernReady || legacyReady
    }

    private fun resolveAccessor(cls: Class<*>): CandidateAccessor {
        var tm: Method? = null
        for (mName in listOf("c", "getText", "a")) {
            try {
                tm = cls.getMethod(mName).apply { isAccessible = true }
                break
            } catch (_: Throwable) {}
        }
        var tf: Field? = null
        if (tm == null) {
            for (fName in listOf("b", "a", "mText")) {
                try {
                    tf = cls.getDeclaredField(fName).apply { isAccessible = true }
                    break
                } catch (_: Throwable) {}
            }
        }

        var im: Method? = null
        for (mName in listOf("j", "getInput", "d", "a")) {
            try {
                im = cls.getMethod(mName).apply { isAccessible = true }
                break
            } catch (_: Throwable) {}
        }
        var inf: Field? = null
        if (im == null) {
            for (fName in listOf("u", "c", "f", "mInput")) {
                try {
                    inf = cls.getDeclaredField(fName).apply { isAccessible = true }
                    break
                } catch (_: Throwable) {}
            }
        }

        var lm: Method? = null
        for (mName in listOf("e", "getType", "isUserLearned", "f")) {
            try {
                lm = cls.getMethod(mName).apply { isAccessible = true }
                break
            } catch (_: Throwable) {}
        }
        var lf: Field? = null
        if (lm == null) {
            for (fName in listOf("f", "f25790f", "mType", "type")) {
                try {
                    lf = cls.getDeclaredField(fName).apply { isAccessible = true }
                    break
                } catch (_: Throwable) {}
            }
        }

        return CandidateAccessor(tm, tf, im, inf, lm, lf)
    }

    private fun getCandidateText(item: Any): String? {
        val accessor = accessorMap.computeIfAbsent(item.javaClass) { resolveAccessor(it) }
        return accessor.getText(item)
    }

    internal fun getCandidateTextForLayout(item: Any): String? = getCandidateText(item)

    private fun getCandidateInput(item: Any): String? {
        val accessor = accessorMap.computeIfAbsent(item.javaClass) { resolveAccessor(it) }
        return accessor.getInput(item)
    }

    private fun isCandidateUserLearned(item: Any): Boolean {
        val accessor = accessorMap.computeIfAbsent(item.javaClass) { resolveAccessor(it) }
        return accessor.isUserLearned(item)
    }

    private fun broadcastLexiconToUiApp(tsv: String) {
        val service = serviceRef?.get() ?: return
        runCatching {
            val intent = Intent(ACTION_LEXICON_EXPORT_RESULT).apply {
                setPackage("com.keyscafe.unlock")
                putExtra(EXTRA_LEXICON_TSV, tsv)
                putExtra(EXTRA_BROADCAST_TOKEN, ConfigStore.currentBroadcastToken())
            }
            service.sendBroadcast(intent)
        }
    }

    // 调度 3 秒防抖异步写盘
    private fun scheduleDebouncedSync() {
        synchronized(syncLock) {
            syncFuture?.cancel(false)
            syncFuture = syncExecutor.schedule({
                XyCore.lmSync()
                val tsv = XyCore.lmExportUserMemoryTsv()
                broadcastLexiconToUiApp(tsv)
                XLog.i("自学习记忆异步防抖落盘与跨进程快照同步完成")
            }, 3, TimeUnit.SECONDS)
        }
    }

    // 窗口隐藏或退出时立即异步刷盘
    private fun flushSyncImmediate() {
        synchronized(syncLock) {
            syncFuture?.cancel(false)
            syncFuture = null
        }
        syncExecutor.execute {
            XyCore.lmSync()
            val tsv = XyCore.lmExportUserMemoryTsv()
            broadcastLexiconToUiApp(tsv)
            XLog.i("自学习记忆即时刷盘完成")
        }
    }

    // 分句截断与中文判定由 native 实现，与词库侧的边界规则保持一致
    private fun truncateToSentence(ctx: String): String = XyCore.truncateSentence(ctx)

    internal fun isChineseLanguageActive(classLoader: ClassLoader? = null): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastLanguageCheckTime < 250L) {
            return cachedIsChinese
        }
        val isZh = queryIsChineseActive(classLoader)
        cachedIsChinese = isZh
        lastLanguageCheckTime = now
        return isZh
    }

    private fun queryIsChineseActive(classLoader: ClassLoader? = null): Boolean {
        // 1. 优先通过 SpellTextViewModel.isChinesePinyinKeypad()
        val vm = spellTextViewModelRef?.get()
        if (vm != null) {
            var m = isChinesePinyinKeypadMethod
            if (m == null) {
                m = runCatching {
                    vm.javaClass.declaredMethods.firstOrNull {
                        it.returnType == java.lang.Boolean.TYPE &&
                            it.parameterTypes.isEmpty() &&
                            it.name.contains("Chinese", ignoreCase = true)
                    }?.apply { setAccessible(true) }
                }.getOrNull()
                isChinesePinyinKeypadMethod = m
            }
            if (m != null) {
                val result = runCatching { m.invoke(vm) as? Boolean }.getOrNull()
                if (result != null) return result
            }
        }

        // 2. 检查系统标准 InputMethodSubtype (无反射、极速)
        val service = serviceRef?.get()
        if (service != null) {
            val imm = ContextCompat.getSystemService(service, android.view.inputmethod.InputMethodManager::class.java)
            val subtype = runCatching { imm?.currentInputMethodSubtype }.getOrNull()
            if (subtype != null) {
                val tag = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    subtype.languageTag.ifEmpty { subtype.locale }
                } else {
                    subtype.locale
                }
                if (tag.isNotEmpty()) {
                    return tag.startsWith("zh", ignoreCase = true)
                }
            }
        }

        return false
    }

    // 1. 获取输入法 Service 引用与生命周期
    private fun hookHoneyBoardService(module: XposedModule, classLoader: ClassLoader) {
        val serviceCls = Reflect.findClassIfExists(IME_SERVICE_CLASS, classLoader) ?: return

        Reflect.methodInHierarchy(serviceCls, "onCreate")?.let { m ->
            XLog.safe("LmCandidateHooks.onCreate") {
                module.hook(m).intercept { chain ->
                    val r = chain.proceed()
                    (chain.thisObject as? InputMethodService)?.let { service ->
                        serviceRef = WeakReference(service)
                        val filesDir = service.filesDir
                        val dir = filesDir?.absolutePath
                        filesDir?.let { fd ->
                            val legacy = File(fd, "XiYu")
                            if (legacy.exists()) {
                                runCatching { legacy.deleteRecursively() }
                            }
                        }
                        // 必须先完成 native 初始化，attachStorage/reload 里的 clear/import
                        // 才有落盘路径；否则待清空任务会被随后的 init_lm 重新加载旧词库覆盖
                        XyCore.lmInit(dir)
                        ConfigStore.attachStorage(filesDir)
                        ConfigStore.registerReceiver(service.applicationContext ?: service)
                        XyCore.lmSetLongPhraseAggressive(ConfigStore.lmLongPhraseAggressive)
                        XyCore.lmSetQuanpinMode(ConfigStore.lmQuanpinMode)
                        // 启动词库同步放到后台，避免阻塞输入法启动
                        val localDictFile = if (filesDir != null) File(filesDir, "xy_user_dict.dat") else null
                        val localModTime = if (localDictFile?.exists() == true) localDictFile.lastModified() else 0L
                        Thread({
                            val remoteVer = ConfigStore.userLexiconVersion
                            val initialTsv = ConfigStore.userLexiconTsv
                            if (ConfigStore.shouldSkipLexiconImport(remoteVer)) {
                                XLog.i("启动同步跳过：清空时间戳晚于词库快照")
                            } else if (remoteVer > localModTime) {
                                XyCore.lmImportUserMemoryTsv(initialTsv, merge = false)
                                XyCore.lmSync()
                            } else if (initialTsv.isNotBlank()) {
                                XyCore.lmImportUserMemoryTsv(initialTsv, merge = true)
                            }
                        }, "xy-lexicon-import").apply { isDaemon = true }.start()
                        XLog.i("LmCandidateHooks: 语言模型初始化完成 info=${XyCore.getLmInfo()}")

                        if (lexiconReceiver == null) {
                            val filter = IntentFilter().apply {
                                addAction(ACTION_CLEAR_LM_MEMORY)
                                addAction(ACTION_SYNC_USER_LEXICON)
                                addAction(ACTION_REQUEST_LEXICON_EXPORT)
                            }
                            val receiver = object : BroadcastReceiver() {
                                override fun onReceive(context: Context?, intent: Intent?) {
                                    if (!ConfigStore.isTrustedBroadcast(intent)) {
                                        XLog.e("LmCandidateHooks: 丢弃来源不明的词库广播", null)
                                        return
                                    }
                                    when (intent?.action) {
                                        ACTION_CLEAR_LM_MEMORY -> {
                                            XyCore.lmClearUserMemory()
                                            onMemoryCleared()
                                            XLog.i("LmCandidateHooks: 收到跨进程广播，已清空三星键盘自学习词库")
                                        }
                                        ACTION_SYNC_USER_LEXICON -> {
                                            val tsv = intent.getStringExtra(EXTRA_LEXICON_TSV) ?: ""
                                            val overwrite = intent.getBooleanExtra(EXTRA_LEXICON_OVERWRITE, true)
                                            val ver = intent.getLongExtra(EXTRA_LEXICON_VERSION, 0L)
                                            if (ver > 0L) {
                                                ConfigStore.markLexiconVersionSynced(ver)
                                            }
                                            XyCore.lmImportUserMemoryTsv(tsv, merge = !overwrite)
                                            XyCore.lmSync()
                                            XLog.i("LmCandidateHooks: 收到 UI 端个人词库同步广播 (len=${tsv.length}, overwrite=$overwrite)")
                                        }
                                        ACTION_REQUEST_LEXICON_EXPORT -> {
                                            val tsv = XyCore.lmExportUserMemoryTsv()
                                            broadcastLexiconToUiApp(tsv)
                                            XLog.i("LmCandidateHooks: 已响应 UI 端词库拉取请求 (len=${tsv.length})")
                                        }
                                    }
                                }
                            }
                            ContextCompat.registerReceiver(
                                service,
                                receiver,
                                filter,
                                "com.keyscafe.unlock.permission.INTERNAL",
                                null,
                                ContextCompat.RECEIVER_EXPORTED
                            )
                            lexiconReceiver = receiver
                        }
                    }
                    r
                }
            }
        }

        Reflect.methodInHierarchy(
            serviceCls,
            "onUpdateSelection",
            java.lang.Integer.TYPE,
            java.lang.Integer.TYPE,
            java.lang.Integer.TYPE,
            java.lang.Integer.TYPE,
            java.lang.Integer.TYPE,
            java.lang.Integer.TYPE
        )?.let { m ->
            XLog.safe("LmCandidateHooks.onUpdateSelection") {
                module.hook(m).intercept { chain ->
                    val oldSelStart = chain.getArg(0) as? Int ?: 0
                    val newSelStart = chain.getArg(2) as? Int ?: 0
                    val candStart = chain.getArg(4) as? Int ?: -1
                    val candEnd = chain.getArg(5) as? Int ?: -1

                    // 若当前无 composing 区间，标记拼写组合态结束
                    if (candStart < 0 || candEnd <= candStart) {
                        if (System.currentTimeMillis() - lastCommitTimestamp < 300L) {
                            isSpellComposingActive = false
                            currentActivePinyin = ""
                        }
                    }

                    // 非输入法拼音组合态下的光标跳跃移动 (如用户点击文本其他段落，非单步输入/退格)
                    if (candStart < 0 && oldSelStart >= 0) {
                        val step = kotlin.math.abs(newSelStart - oldSelStart)
                        if (step > 2 && System.currentTimeMillis() - lastCommitTimestamp > 350L) {
                            synchronized(journalLock) {
                                commitJournal.clear()
                            }
                            lastKnownContextBefore = ""
                        }
                    }

                    val r = chain.proceed()
                    (chain.thisObject as? InputMethodService)?.let { service ->
                        serviceRef = WeakReference(service)
                    }
                    r
                }
            }
        }

        Reflect.methodInHierarchy(
            serviceCls,
            "onStartInputView",
            android.view.inputmethod.EditorInfo::class.java,
            java.lang.Boolean.TYPE
        )?.let { m ->
            XLog.safe("LmCandidateHooks.onStartInputView") {
                module.hook(m).intercept { chain ->
                    synchronized(journalLock) {
                        commitJournal.clear()
                    }
                    invalidateLanguageCache()
                    currentActivePinyin = ""
                    lastKnownContextBefore = ""
                    lastCommittedWord = ""
                    lastCommittedPinyin = ""
                    isSpellComposingActive = false
                    lastReemitSeq = -1L
                    latestSpellText = ""
                    lastProcessedPinyin = ""
                    lastRawNativeList = emptyList()
                    lastRawNativeTime = 0L
                    lastInjectionNeedsReemit = false
                    lastInjectionCode = null
                    lastContextPushTimestamp = 0L

                    val r = chain.proceed()
                    (chain.thisObject as? InputMethodService)?.let { service ->
                        serviceRef = WeakReference(service)
                    }
                    r
                }
            }
        }

        // 窗口隐藏时立即触发异步刷盘
        Reflect.methodInHierarchy(serviceCls, "onWindowHidden")?.let { m ->
            XLog.safe("LmCandidateHooks.onWindowHidden") {
                module.hook(m).intercept { chain ->
                    flushSyncImmediate()
                    chain.proceed()
                }
            }
        }

        // 结束输入视图时立即触发异步刷盘
        Reflect.methodInHierarchy(serviceCls, "onFinishInputView", java.lang.Boolean.TYPE)?.let { m ->
            XLog.safe("LmCandidateHooks.onFinishInputView") {
                module.hook(m).intercept { chain ->
                    flushSyncImmediate()
                    chain.proceed()
                }
            }
        }

        Reflect.methodInHierarchy(serviceCls, "onDestroy")?.let { m ->
            XLog.safe("LmCandidateHooks.onDestroy") {
                module.hook(m).intercept { chain ->
                    flushSyncImmediate()
                    (chain.thisObject as? Context)?.let { ctx ->
                        lexiconReceiver?.let {
                            runCatching { ctx.unregisterReceiver(it) }
                            lexiconReceiver = null
                        }
                    }
                    serviceRef = null
                    chain.proceed()
                }
            }
        }
    }

    // 2. 候选词发射总管道同步拦截与重排（支持空列表注入与不可变列表替换）
    private fun hookCandidateEmitter(module: XposedModule, classLoader: ClassLoader) {
        val targetClasses = listOf("eb0.a", "f50.a")
        var hooked = false

        for (clsName in targetClasses) {
            val cls = Reflect.findClassIfExists(clsName, classLoader) ?: continue

            // 针对 eb0.a.l(List, int) (One UI 6/7 主候选分发通道)
            val methodL = cls.declaredMethods.firstOrNull {
                it.name == "l" && it.parameterTypes.size == 2 &&
                java.util.List::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                it.parameterTypes[1] == java.lang.Integer.TYPE
            }
            if (methodL != null) {
                methodL.isAccessible = true
                emitterMethodL = methodL
                XLog.safe("LmCandidateHooks.emitCandidates.$clsName.l") {
                    module.hook(methodL).intercept { chain ->
                        chain.thisObject?.let { emitterInstanceRef = WeakReference(it) }
                        if (isInternalContextPush) {
                            return@intercept chain.proceed()
                        }
                        @Suppress("UNCHECKED_CAST")
                        val rawList = chain.getArg(0) as? List<Any>
                        val slot = chain.getArg(1) as? Int ?: 0
                        val rawHasChinese = rawList?.any { containsChinese(getCandidateText(it).orEmpty()) } == true
                        val isZh = isChineseLanguageActive(classLoader) || rawHasChinese
                        if (rawHasChinese) {
                            cachedIsChinese = true
                            lastLanguageCheckTime = System.currentTimeMillis()
                        }
                        if (!isZh) {
                            latestSpellText = ""
                            currentActivePinyin = ""
                            isSpellComposingActive = false
                            lastRawNativeList = emptyList()
                            lastRawNativeTime = 0L
                            return@intercept chain.proceed()
                        }
                        if ((ConfigStore.lmEnabled || ConfigStore.lmQuanpinMode) && rawList != null) {
                            // 注入前保存原生快照，供拼写修正重推使用
                            lastRawNativeList = ArrayList<Any>(rawList)
                            lastRawNativeTime = System.currentTimeMillis()
                            lastRawSlot = slot
                            val workingList = ArrayList<Any>(rawList)
                            val changed = processCandidates(workingList, classLoader)
                            if (changed) {
                                var mutatedInPlace = false
                                if (rawList is MutableList<*>) {
                                    mutatedInPlace = runCatching {
                                        @Suppress("UNCHECKED_CAST")
                                        val mut = rawList as MutableList<Any>
                                        mut.clear()
                                        mut.addAll(workingList)
                                        true
                                    }.getOrDefault(false)
                                }
                                val finalList = if (mutatedInPlace) {
                                    @Suppress("UNCHECKED_CAST")
                                    rawList as MutableList<Any>
                                } else {
                                    workingList
                                }
                                rememberEmittedList(finalList)
                                if (!mutatedInPlace) {
                                    val result = chain.proceed(arrayOf(workingList, slot))
                                    scheduleCandidateReemit(slot)
                                    return@intercept result
                                }
                                scheduleCandidateReemit(slot)
                            }
                        }
                        chain.proceed()
                    }
                }
                hooked = true
                XLog.i("LmCandidateHooks: 成功挂钩候选词管线 $clsName.l(List, int)")
            }

            // 兼容旧版 f50.a.a(List)
            val methodA = cls.declaredMethods.firstOrNull {
                it.name == "a" && it.parameterTypes.size == 1 &&
                java.util.List::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            if (methodA != null) {
                methodA.isAccessible = true
                emitterMethodA = methodA
                XLog.safe("LmCandidateHooks.emitCandidates.$clsName.a") {
                    module.hook(methodA).intercept { chain ->
                        chain.thisObject?.let { emitterInstanceRef = WeakReference(it) }
                        if (isInternalContextPush) {
                            return@intercept chain.proceed()
                        }
                        @Suppress("UNCHECKED_CAST")
                        val rawList = chain.getArg(0) as? List<Any>
                        val rawHasChinese = rawList?.any { containsChinese(getCandidateText(it).orEmpty()) } == true
                        val isZh = isChineseLanguageActive(classLoader) || rawHasChinese
                        if (rawHasChinese) {
                            cachedIsChinese = true
                            lastLanguageCheckTime = System.currentTimeMillis()
                        }
                        if (!isZh) {
                            latestSpellText = ""
                            currentActivePinyin = ""
                            isSpellComposingActive = false
                            lastRawNativeList = emptyList()
                            lastRawNativeTime = 0L
                            return@intercept chain.proceed()
                        }
                        if ((ConfigStore.lmEnabled || ConfigStore.lmQuanpinMode) && rawList != null) {
                            // 注入前保存原生快照，供拼写修正重推使用
                            lastRawNativeList = ArrayList<Any>(rawList)
                            lastRawNativeTime = System.currentTimeMillis()
                            lastRawSlot = 0
                            val workingList = ArrayList<Any>(rawList)
                            val changed = processCandidates(workingList, classLoader)
                            if (changed) {
                                var mutatedInPlace = false
                                if (rawList is MutableList<*>) {
                                    mutatedInPlace = runCatching {
                                        @Suppress("UNCHECKED_CAST")
                                        val mut = rawList as MutableList<Any>
                                        mut.clear()
                                        mut.addAll(workingList)
                                        true
                                    }.getOrDefault(false)
                                }
                                val finalList = if (mutatedInPlace) {
                                    @Suppress("UNCHECKED_CAST")
                                    rawList as MutableList<Any>
                                } else {
                                    workingList
                                }
                                rememberEmittedList(finalList)
                                if (!mutatedInPlace) {
                                    val result = chain.proceed(arrayOf(workingList))
                                    scheduleCandidateReemit(0)
                                    return@intercept result
                                }
                                scheduleCandidateReemit(0)
                            }
                        }
                        chain.proceed()
                    }
                }
                hooked = true
                XLog.i("LmCandidateHooks: 成功挂钩候选词管线 $clsName.a(List)")
            }
        }

        if (!hooked) {
            XLog.i("LmCandidateHooks: 未找到候选词管线类")
        }
    }

    // 自定义编码短语注入空候选池/无中文候选池后，延迟重发一次候选。
    // 三星在工具栏模式下可能不会因首次注入立即展开候选栏，需要下一帧再推一次。
    private fun canInvokeEmitter(emitter: Any, method: Method?): Boolean =
        method != null && method.declaringClass.isAssignableFrom(emitter.javaClass)

    private fun scheduleCandidateReemit(slot: Int) {
        if (!lastInjectionNeedsReemit) return
        val list = lastEmittedListRef?.get() ?: return
        val code = lastInjectionCode ?: return
        val emitter = emitterInstanceRef?.get() ?: return
        val mL = emitterMethodL
        val mA = emitterMethodA
        if (mL == null && mA == null) return
        synchronized(reemitLock) {
            if (emissionSeq == lastReemitSeq) return
            lastReemitSeq = emissionSeq
        }

        mainHandler.postDelayed({
            val prevPush = beginInternalPush()
            try {
                val snapshot = ArrayList(list)
                if (canInvokeEmitter(emitter, mL)) {
                    mL?.invoke(emitter, snapshot, slot)
                } else if (canInvokeEmitter(emitter, mA)) {
                    mA?.invoke(emitter, snapshot)
                } else {
                    return@postDelayed
                }
                XLog.i("LmCandidateHooks: 编码快捷短语已入池，主动重发候选刷新候选栏 [code='$code', slot=$slot]")
            } catch (_: Throwable) {
            } finally {
                isInternalContextPush = prevPush
            }
        }, 32L)
    }

    // 非中文与特殊输入模式快速短路 (密码框、纯数字、电话、日期时间)
    private fun isLmUnsupportedInputType(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return false

        val inputType = editorInfo.inputType
        val classType = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        if (classType == InputType.TYPE_CLASS_NUMBER ||
            classType == InputType.TYPE_CLASS_PHONE ||
            classType == InputType.TYPE_CLASS_DATETIME) {
            return true
        }

        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }

    /**
     * 处理候选词列表（支持非空重排与上屏闲置态 @上文 短语空列表注入）
     * @return 列表是否发生变更
     */
    private fun processCandidates(list: MutableList<Any>, classLoader: ClassLoader): Boolean {
        lastInjectionNeedsReemit = false
        lastInjectionCode = null
        var listModified = false
        runCatching {
            val service = serviceRef?.get() ?: return false
            if (isLmUnsupportedInputType(service.currentInputEditorInfo)) return false
            if (!isChineseLanguageActive(classLoader)) return false

            val ic = service.currentInputConnection
            val rawContext = ic?.getTextBeforeCursor(32, 0)?.toString() ?: ""
            val now = System.currentTimeMillis()

            // InputConnection 跨进程读取存在延迟：上屏后 300ms 内若 getTextBeforeCursor
            // 尚未包含刚上屏的词，改用本地维护的 lastKnownContextBefore
            val contextBefore = if (
                now - lastCommitTimestamp < 300L &&
                lastCommittedWord.isNotEmpty() &&
                !rawContext.endsWith(lastCommittedWord)
            ) {
                lastKnownContextBefore
            } else {
                truncateToSentence(rawContext).also { lastKnownContextBefore = it }
            }

            // 2. 提取当前按键编码输入，并过滤上屏瞬间的 SpellTextViewModel 残影
            var rawSpellWithApos: String? = null
            var cleanPinyinInput: String? = null
            var nativeInputCode: String? = null

            // 检查原生候选列表自身是否携带未上屏编码
            for (item in list) {
                val itemInput = getCandidateInput(item)?.trim()
                if (!itemInput.isNullOrEmpty()) {
                    nativeInputCode = itemInput
                    break
                }
            }
            val listHasNativeInputCode = !nativeInputCode.isNullOrEmpty()

            // 优先使用 setSpellText 实时捕获的拼写串，避免 getSpellText() 读取滞后一拍
            val hookedSpell = latestSpellText
            if (hookedSpell.isNotBlank()) {
                val clean = hookedSpell.replace("'", "")
                val isStaleGhost = (
                    (now - lastCommitTimestamp < 220L && clean == lastCommittedPinyin) ||
                    (!isSpellComposingActive && !listHasNativeInputCode && now - lastCommitTimestamp < 450L)
                )
                if (!isStaleGhost) {
                    rawSpellWithApos = hookedSpell
                    cleanPinyinInput = clean
                }
            }

            if (cleanPinyinInput.isNullOrBlank()) {
                spellTextViewModelRef?.get()?.let { vm ->
                    val spellText = runCatching {
                        val m = vm.javaClass.getMethod("getSpellText")
                        m.setAccessible(true)
                        m.invoke(vm)?.toString()
                    }.getOrNull()
                    if (!spellText.isNullOrBlank()) {
                        val normalized = normalizeSpellText(spellText)
                        val clean = normalized.replace("'", "")
                        // 若刚完成上屏 (< 220ms) 且当前 spellText 与刚上屏词的拼音完全相同，或者拼写框已隐藏且候选词无 input 编码，判定为残影并丢弃
                        val isStaleGhostAfterCommit = (
                            (now - lastCommitTimestamp < 220L && clean == lastCommittedPinyin) ||
                            (!isSpellComposingActive && !listHasNativeInputCode && now - lastCommitTimestamp < 450L)
                        )
                        if (!isStaleGhostAfterCommit) {
                            rawSpellWithApos = normalized
                            cleanPinyinInput = clean
                        }
                    }
                }
            }

            if (cleanPinyinInput.isNullOrBlank() && !nativeInputCode.isNullOrBlank()) {
                val normalized = normalizeSpellText(nativeInputCode)
                cleanPinyinInput = normalized.replace("'", "")
                rawSpellWithApos = normalized
            }

            val py = cleanPinyinInput
            val pyForSentence = rawSpellWithApos
            val candidateInput = py ?: ""
            if (!py.isNullOrBlank()) {
                currentActivePinyin = py
            }
            lastProcessedPinyin = py ?: ""

            // 3. 保护英文输入：无候选汉字、无中文语境、且无有效按键拼音时直接跳过
            //    同时构建去重集并统计原生中文候选数，避免后续二次遍历
            var chineseInListCount = 0
            val existing = HashSet<String>(list.size + 8)
            for (item in list) {
                val text = getCandidateText(item) ?: continue
                existing.add(text)
                if (containsChinese(text)) {
                    chineseInListCount++
                }
            }
            val hasChineseInList = chineseInListCount > 0

            val hasChineseContext = containsChinese(contextBefore)
            val hasPinyin = !py.isNullOrBlank()

            if (!hasChineseInList && !hasChineseContext && !hasPinyin) {
                return false
            }

            val universalEnabled = ConfigStore.lmEnabled
            val quanpinEnabled = ConfigStore.lmQuanpinMode
            XyCore.lmSetQuanpinMode(quanpinEnabled)

            val syllableCount = estimateSyllableCount(pyForSentence, py, quanpinEnabled)

            val wasInitiallyEmpty = list.isEmpty()

            // 原生候选已有充足中文覆盖时跳过词库/整句额外注入，仅保留个人词库直推
            // 避免在三星引擎已给出良好结果时注入冗余候选，保持候选栏自然简洁
            val nativeSyllableMatchCount = if (syllableCount > 0) {
                list.count { item ->
                    val text = getCandidateText(item)
                    text != null && countChineseCharacters(text) == syllableCount
                }
            } else {
                chineseInListCount
            }
            val nativeSufficient =
                hasSufficientNativeCoverage(chineseInListCount, nativeSyllableMatchCount, syllableCount)

            val syntheticList = ArrayList<Any>()
            val userShortcuts = HashSet<String>(4)
            synchronized(syntheticCandidates) {
                syntheticCandidates.clear()
            }

            // 4.0 个人词库与自定义短语直推 (含按键编码短语 dz -> 地址，以及上文语境短语 @收到 -> 请查收)
            // 有按键编码时不过滤刚上屏的词：同一编码连续输入是本模块的核心场景，
            // 过滤会导致第二次输入时快捷短语不再出现
            val skipLastCommitted = py.isNullOrBlank()
            val queryKey = if (!pyForSentence.isNullOrBlank()) pyForSentence else py
            if (universalEnabled) {
                val userWords = XyCore.lmQueryUserCandidates(queryKey, contextBefore, 2)
                for (w in userWords) {
                    if (w.isNotBlank() && !(skipLastCommitted && w == lastCommittedWord)) {
                        userShortcuts.add(w)
                        if (!existing.contains(w)) {
                            val newCand = createCandidate(classLoader, w, py ?: "")
                            if (newCand != null) {
                                syntheticList.add(newCand)
                                existing.add(w)
                                XLog.i("个人词库/快捷短语入池: [code: '$py', ctx: '$contextBefore'] -> '$w'")
                            }
                        }
                    }
                }
            }

            // 空列表且处于闲置态时保持工具栏；有编码输入时继续走词库/整句注入
            if (wasInitiallyEmpty && syntheticList.isEmpty() && py.isNullOrBlank()) {
                return false
            }

            // 4.1 全拼词格整句求解 (双拼/五笔关闭 lmQuanpinMode 时跳过)
            // 严格要求整句字数与当前输入音节数一致，防止跨长度联想到更长短语
            if (quanpinEnabled && !nativeSufficient && XyCore.isDictReady() && !pyForSentence.isNullOrBlank() && py != null && py.length >= 6) {
                val decodedSentence = XyCore.lmDecodeSentence(contextBefore, pyForSentence)
                if (!decodedSentence.isNullOrBlank() && decodedSentence.length <= 24) {
                    val decodedLen = countChineseCharacters(decodedSentence)
                    if ((syllableCount <= 0 || decodedLen == syllableCount) && !existing.contains(decodedSentence)) {
                        val newCand = createCandidate(classLoader, decodedSentence, candidateInput)
                        if (newCand != null) {
                            syntheticList.add(newCand)
                            existing.add(decodedSentence)
                            XLog.i("全拼整句解码入池: [$pyForSentence] -> '$decodedSentence'")
                        }
                    }
                }
            }

            // 4.2 全拼词库真实词条与前缀单字补齐 (非全拼模式跳过；原生候选已充足时跳过)
            if (quanpinEnabled && !nativeSufficient && XyCore.isDictReady() && !py.isNullOrBlank()) {
                val dictWords = XyCore.dictQueryPinyin(queryKey ?: py, 24)
                for (w in dictWords) {
                    if (w.isBlank() || existing.contains(w)) continue
                    val cCount = countChineseCharacters(w)
                    if (syllableCount > 0) {
                        // 原生候选已有中文且音节数 >= 2 时，仅注入完整音节长度词条，
                        // 短于当前音节的前缀单字保留给原生引擎进行分步选词
                        if (hasChineseInList && syllableCount >= 2) {
                            if (cCount != syllableCount) continue
                        } else if (cCount !in 1..syllableCount) {
                            continue
                        }
                    }
                    val newCand = createCandidate(classLoader, w, candidateInput)
                    if (newCand != null) {
                        syntheticList.add(newCand)
                        existing.add(w)
                        XLog.i("全拼词库候选入池: [$py] -> '$w'")
                    }
                }
            }

            if (syntheticList.isNotEmpty()) {
                if (py.isNullOrBlank() && !hasChineseInList) {
                    // 闲置态下若原列表仅有标点符号或为空，直接将 @上文 联想短语置于队首第 0 位
                    list.addAll(0, syntheticList)
                } else {
                    list.addAll(syntheticList)
                }
                listModified = true
                // 原候选池为空或完全没有中文时，三星往往仍停留在工具栏布局；
                // 标记后由发射管道延迟重发一次，确保编码快捷短语立刻可见
                if (wasInitiallyEmpty || !hasChineseInList) {
                    lastInjectionNeedsReemit = true
                    lastInjectionCode = py ?: ""
                }
            }

            if (list.isEmpty()) return false

            // 5. 按拼音音节数严格过滤跨长度联想（杜绝 > syllableCount 的候选），并按全长 -> 前缀词 -> 单字层级排序
            if (syllableCount > 0) {
                val before = list.size
                val filtered = filterCandidatesBySyllableLength(list, syllableCount, userShortcuts) { item ->
                    getCandidateText(item)
                }
                if (filtered.isNotEmpty() && filtered != list) {
                    list.clear()
                    list.addAll(filtered)
                    listModified = true
                    XLog.i("音节长度层级过滤: syllables=$syllableCount, $before -> ${filtered.size}")
                }
            }

            // 6. 候选词统一综合打分重排 (含 N-gram 语境消歧、个人记忆提权、防复发坏词压制与翻转门禁)
            val candTexts = Array(list.size) { i ->
                getCandidateText(list[i]) ?: ""
            }
            val isLearnedArray = BooleanArray(list.size) { i ->
                isCandidateUserLearned(list[i])
            }

            val reorderedIndices = XyCore.lmRerank(queryKey ?: py, contextBefore, candTexts, isLearnedArray)
            if (reorderedIndices != null && reorderedIndices.size == list.size) {
                var orderChanged = false
                for (i in reorderedIndices.indices) {
                    if (reorderedIndices[i] != i) {
                        orderChanged = true
                        break
                    }
                }

                if (orderChanged) {
                    val reorderedList = ArrayList<Any>(list.size)
                    for (idx in reorderedIndices) {
                        if (idx in list.indices) {
                            reorderedList.add(list[idx])
                        }
                    }
                    val topBefore = candTexts.firstOrNull() ?: ""
                    list.clear()
                    list.addAll(reorderedList)
                    listModified = true
                    val topAfter = getCandidateText(list[0]) ?: ""
                    XLog.i("候选统一重排完成: [ctx: '$contextBefore', code: '$py', qp=$quanpinEnabled] 首选: '$topBefore' -> '$topAfter'")
                }
            }

            // 候选池上限设为 60 个，首行 6 格，其余候选供展开选词框浏览
            val maxCandidatePool = 60
            if (list.size > maxCandidatePool) {
                list.subList(maxCandidatePool, list.size).clear()
                listModified = true
            }
        }.onFailure { XLog.e("LmCandidateHooks 候选重排异常", it) }
        return listModified
    }

    /**
     * 上屏词条命中用户显式配置的 @上文 语境规则时，主动向候选栏推送快捷短语（去除生硬统计联想）
     */
    private fun pushContextCandidatesAfterCommit(contextBefore: String, classLoader: ClassLoader) {
        if (!ConfigStore.lmEnabled || contextBefore.isBlank()) return
        // 仅对中文上屏做主动联想，避免在英文/密码/纯数字输入域污染候选栏
        if (!containsChinese(lastCommittedWord)) return
        if (isLmUnsupportedInputType(serviceRef?.get()?.currentInputEditorInfo)) return
        val userCtxWords = XyCore.lmQueryUserCandidates("", contextBefore, 2)
            .filter { it.isNotBlank() && it != lastCommittedWord }
        if (userCtxWords.isEmpty()) return

        val mergedWords = ArrayList<String>(userCtxWords)

        val postTask = Runnable {
            // 若用户在这几十毫秒内已经开始敲下一个字母，交由常规打字候选流处理
            if (isSpellComposingActive || currentActivePinyin.isNotBlank()) return@Runnable
            val emitter = emitterInstanceRef?.get() ?: return@Runnable
            val candObjects = ArrayList<Any>(mergedWords.size)
            for (w in mergedWords) {
                createCandidate(classLoader, w, "")?.let { candObjects.add(it) }
            }
            if (candObjects.isEmpty()) return@Runnable

            val prevPush = beginInternalPush()
            try {
                // 直接推送联想候选覆盖旧列表，不先清空（清空会收起候选栏导致推送不可见）
                // 必须沿用原生候选最近的槽位，写死槽位会推到不渲染的通道，候选栏停在旧列表
                val mL = emitterMethodL
                val mA = emitterMethodA
                val slot = lastRawSlot
                if (canInvokeEmitter(emitter, mL)) {
                    mL?.invoke(emitter, candObjects, slot)
                } else if (canInvokeEmitter(emitter, mA)) {
                    mA?.invoke(emitter, candObjects)
                } else {
                    return@Runnable
                }
                rememberEmittedList(candObjects)
                lastContextPushTimestamp = System.currentTimeMillis()
                XLog.i("LmCandidateHooks: 上屏后推送 @上下文 语境候选 -> $mergedWords [slot=$slot]")
                refreshCandidateBar()
            } catch (t: Throwable) {
                XLog.i("推送 @上下文 语境候选异常: ${t.message}")
            } finally {
                isInternalContextPush = prevPush
            }
        }

        mainHandler.postDelayed(postTask, 80L)
    }

    // 6. 监听上屏行为与误选退格事务回退 (支持多步连击退格)
    private fun recordCommitInternal(word: String, py: String, classLoader: ClassLoader) {
        val now = System.currentTimeMillis()
        // 防止 handleSyntheticCommit 与 jh.a.commitText 对同一次上屏重复记录
        if (word == lastCommittedWord && now - lastCommitTimestamp < 60L) {
            return
        }
        val ctx = lastKnownContextBefore
        lastCommittedWord = word
        lastCommittedPinyin = py
        lastCommitTimestamp = now
        isSpellComposingActive = false
        latestSpellText = ""
        currentActivePinyin = ""
        lastProcessedPinyin = ""
        lastRawNativeList = emptyList()
        lastRawNativeTime = 0L
        lastReemitSeq = -1L

        val updatedContext = truncateToSentence(ctx + word)
        lastKnownContextBefore = updatedContext

        // 粘贴/自动填充可能提交长文本，限制学习长度避免污染词库
        if (ConfigStore.lmEnabled && ConfigStore.lmLearning && word.length <= 32 && containsChinese(word)) {
            val record = CommitRecord(
                word = word,
                pinyin = py,
                context = ctx,
                timestamp = now,
                remainingLength = word.codePointCount(0, word.length).coerceAtLeast(1)
            )
            synchronized(journalLock) {
                if (commitJournal.size >= 5) {
                    commitJournal.removeLast()
                }
                commitJournal.addFirst(record)
            }

            XLog.i("上屏自学习: [code: '$py', ctx: '$ctx'] + commit: '$word'")
            XyCore.lmRecordCommit(py, ctx, word)
            scheduleDebouncedSync()
        }

        if (ConfigStore.lmEnabled && updatedContext.isNotBlank()) {
            pushContextCandidatesAfterCommit(updatedContext, classLoader)
        }
    }

    private fun hookCommitAndBackspace(module: XposedModule, classLoader: ClassLoader) {
        val icCls = Reflect.findClassIfExists(INPUT_CONNECTION_CLASS, classLoader) ?: return

        Reflect.method(icCls, "commitText", CharSequence::class.java, java.lang.Integer.TYPE)?.let { m ->
            XLog.safe("LmCandidateHooks.commitText") {
                module.hook(m).intercept { chain ->
                    val committed = chain.getArg(0) as? CharSequence
                    val word = committed?.toString().orEmpty()
                    val py = currentActivePinyin.ifBlank {
                        if (isSpellComposingActive) {
                            latestSpellText.replace("'", "")
                        } else {
                            ""
                        }
                    }

                    val r = chain.proceed()

                    if (isChineseLanguageActive(classLoader) && word.isNotBlank() && (ConfigStore.lmEnabled || ConfigStore.lmQuanpinMode)) {
                        recordCommitInternal(word, py, classLoader)
                    }

                    r
                }
            }
        }

        Reflect.methodInHierarchy(icCls, "deleteSurroundingText", java.lang.Integer.TYPE, java.lang.Integer.TYPE)?.let { m ->
            XLog.safe("LmCandidateHooks.deleteSurroundingText") {
                module.hook(m).intercept { chain ->
                    val beforeLen = chain.getArg(0) as? Int ?: 0
                    if (isChineseLanguageActive(classLoader) && beforeLen > 0 && ConfigStore.lmEnabled && ConfigStore.lmLearning) {
                        checkAndRollbackCommit(beforeLen)
                    }
                    chain.proceed()
                }
            }
        }

        Reflect.methodInHierarchy(icCls, "deleteSurroundingTextInCodePoints", java.lang.Integer.TYPE, java.lang.Integer.TYPE)?.let { m ->
            XLog.safe("LmCandidateHooks.deleteSurroundingTextInCodePoints") {
                module.hook(m).intercept { chain ->
                    val beforeLen = chain.getArg(0) as? Int ?: 0
                    if (isChineseLanguageActive(classLoader) && beforeLen > 0 && ConfigStore.lmEnabled && ConfigStore.lmLearning) {
                        checkAndRollbackCommit(beforeLen)
                    }
                    chain.proceed()
                }
            }
        }
    }

    private fun checkAndRollbackCommit(deleteChars: Int) {
        if (deleteChars <= 0) return
        val now = System.currentTimeMillis()
        var remainingToDelete = deleteChars

        synchronized(journalLock) {
            val it = commitJournal.iterator()
            while (it.hasNext() && remainingToDelete > 0) {
                val record = it.next()
                val elapsed = now - record.timestamp
                if (elapsed > 3000) {
                    it.remove()
                    continue
                }

                val word = record.word
                val ctx = record.context
                val py = record.pinyin
                val deleteCount = minOf(remainingToDelete, record.remainingLength)
                record.remainingLength -= deleteCount
                remainingToDelete -= deleteCount

                if (!record.rolledBack) {
                    record.rolledBack = true
                    XyCore.lmRollbackCommit(py, ctx, word)
                    lastKnownContextBefore = ctx
                    scheduleDebouncedSync()
                    XLog.i("退格回退完成: [code: '$py', ctx: '$ctx'] - rollback: '$word'")
                }

                if (record.remainingLength <= 0) {
                    it.remove()
                }
            }
        }
    }

    private fun createCandidate(classLoader: ClassLoader, text: String, input: String = ""): Any? {
        preheatBuilders(classLoader)

        var cand: Any? = null
        val mConstructor = modernBuilderConstructor
        val mBuildMethod = modernBuilderBuildMethod
        if (mConstructor != null && mBuildMethod != null) {
            try {
                val builder = mConstructor.newInstance(0, text, false)
                if (input.isNotBlank()) {
                    modernBuilderDMethod?.invoke(builder, input)
                    modernBuilderNMethod?.invoke(builder, input)
                }
                cand = mBuildMethod.invoke(builder)
            } catch (t: Throwable) {
                XLog.e("现代候选构造器失败", t)
            }
        }

        if (cand == null) {
            val lConstructor = legacyBuilderConstructor
            val lBuildMethod = legacyBuilderBuildMethod
            if (lConstructor != null && lBuildMethod != null) {
                try {
                    val builder = lConstructor.newInstance(text)
                    if (input.isNotBlank()) {
                        legacyBuilderBMethod?.invoke(builder, input)
                    }
                    cand = lBuildMethod.invoke(builder)
                } catch (t: Throwable) {
                    XLog.e("旧版候选构造器失败", t)
                }
            }
        }

        if (cand != null) {
            syntheticCandidates[cand] = SyntheticInfo(text, input)
            lastSyntheticCode = input
        }
        return cand
    }

    private fun hookCandidateClick(module: XposedModule, classLoader: ClassLoader) {
        val vmCls = Reflect.findClassIfExists(
            "com.samsung.android.honeyboard.textboard.candidate.viewmodel.CandidateViewModel",
            classLoader
        )
        if (vmCls != null) {
            vmCls.declaredMethods.firstOrNull { it.name == "pickSuggestion" && it.parameterTypes.isEmpty() }?.let { m ->
                // 命中合成候选后返回 null；非 void 方法会触发宿主拆箱崩溃
                if (m.returnType != Void.TYPE) return@let
                XLog.safe("LmCandidateHooks.pickSuggestion") {
                    module.hook(m).intercept { chain ->
                        val vm = chain.thisObject
                        if (vm != null) {
                            val cand = getSuggestionFromViewModel(vm)
                            if (cand != null && handleSyntheticCommit(cand, classLoader)) {
                                return@intercept null
                            }
                        }
                        chain.proceed()
                    }
                }
                XLog.i("LmCandidateHooks: 已挂钩 CandidateViewModel.pickSuggestion")
            }
        }

        val actionCls = Reflect.findClassIfExists("lb0.a", classLoader)
        if (actionCls != null) {
            for (m in actionCls.declaredMethods) {
                if ((m.name == "e" || m.name == "i") && m.parameterTypes.size == 2 && m.parameterTypes[1] == java.lang.Integer.TYPE) {
                    if (m.returnType != Void.TYPE) continue
                    XLog.safe("LmCandidateHooks.action.${m.name}") {
                        module.hook(m).intercept { chain ->
                            val cand = chain.getArg(0)
                            if (cand != null && handleSyntheticCommit(cand, classLoader)) {
                                return@intercept null
                            }
                            chain.proceed()
                        }
                    }
                    XLog.i("LmCandidateHooks: 已挂钩 lb0.a.${m.name}(cand, int)")
                }
            }
        }
    }

    private fun getSuggestionFromViewModel(vm: Any): Any? {
        if (suggestionField == null) {
            suggestionField = runCatching {
                vm.javaClass.getDeclaredField("mSuggestion").apply { isAccessible = true }
            }.getOrNull()
            if (suggestionField == null) {
                suggestionField = vm.javaClass.declaredFields.firstOrNull {
                    it.type.name.startsWith("dn.") || it.type.name.startsWith("k30.")
                }?.apply { isAccessible = true }
            }
        }
        return runCatching { suggestionField?.get(vm) }.getOrNull()
    }

    private fun handleSyntheticCommit(cand: Any, classLoader: ClassLoader): Boolean {
        val info = synchronized(syntheticCandidates) {
            syntheticCandidates[cand]
        }

        // 当候选对象在工具栏刷新后被重新包装时，必须同时校验候选文本一致，防止误拦截普通原生候选词
        val clickedText = if (info == null) getCandidateText(cand) else null
        val fallbackInfo = if (info == null && !clickedText.isNullOrEmpty()) {
            synchronized(syntheticCandidates) {
                syntheticCandidates.values.firstOrNull { v ->
                    !v.committed && v.text == clickedText &&
                            (v.input == currentActivePinyin || v.input == lastSyntheticCode || v.input.isEmpty())
                }
            }
        } else null

        val resolvedInfo = info ?: fallbackInfo ?: return false
        if (resolvedInfo.committed) {
            return true
        }

        // 多音节打字状态下，单字点击绝不走合成整句提交，交由原生分步选词处理
        val activeSyls = estimateSyllableCount(latestSpellText, currentActivePinyin, ConfigStore.lmQuanpinMode)
        if (isSpellComposingActive && activeSyls >= 2 && countChineseCharacters(resolvedInfo.text) == 1) {
            return false
        }

        resolvedInfo.committed = true
        val clickTs = System.currentTimeMillis()

        val word = resolvedInfo.text
        // 空 input 的候选是 @上文/下一词联想，只按语境学习；不要在点击时借用当前输入的编码
        val py = resolvedInfo.input
        XLog.i("LmCandidateHooks: 拦截自注入候选词提交: word='$word', code='$py'")

        val service = serviceRef?.get()
        val ic = service?.currentInputConnection
        if (ic == null) {
            resolvedInfo.committed = false
            XLog.e("LmCandidateHooks: currentInputConnection 为空，无法提交合成候选词")
            return false
        }

        runCatching { lastEmittedListRef?.get()?.remove(cand) }
        val committed = runCatching { ic.commitText(word, 1) }
        if (committed.isFailure) {
            resolvedInfo.committed = false
            XLog.e("LmCandidateHooks: 提交合成候选词失败", committed.exceptionOrNull())
            return false
        }
        runCatching { ic.finishComposingText() }

        // 同步阶段不推空列表，避免压掉紧随其后的 @上文 联想候选；
        // 由 150ms 兜底回调在确认无联想推送后再彻底清空候选栏
        recordCommitInternal(word, py, classLoader)
        clearNativeEngineState()
        clearSpellTextAndRefreshCandidates()

        mainHandler.postDelayed({
            if (System.currentTimeMillis() - clickTs < 400L &&
                !isSpellComposingActive &&
                currentActivePinyin.isBlank() &&
                lastContextPushTimestamp < clickTs
            ) {
                clearEmittedCandidates(deep = true)
            }
        }, 150L)

        return true
    }

    private fun clearNativeEngineState() {
        val service = serviceRef?.get()
        var handled = false

        if (service != null) {
            runCatching {
                val m = service.javaClass.getDeclaredMethod("getEngineManager").apply { isAccessible = true }
                val engine = m.invoke(service)
                if (engine != null) {
                    val nMethod = engine.javaClass.getMethod("n").apply { isAccessible = true }
                    nMethod.invoke(engine)
                    handled = true
                    XLog.i("LmCandidateHooks: 通过 HoneyBoardService.getEngineManager().n() 复位引擎成功")
                }
            }.onFailure { XLog.e("通过 getEngineManager 复位引擎失败: ${it.message}") }
        }

        if (!handled) {
            val cl = cachedClassLoader
            if (cl != null) {
                runCatching {
                    val yzCls = Reflect.findClassIfExists("yz0.a", cl)
                    val p50Cls = Reflect.findClassIfExists("p50.f", cl)
                    if (yzCls != null && p50Cls != null) {
                        val getMethod = yzCls.getMethod("a", Class::class.java)
                        val engine = getMethod.invoke(null, p50Cls)
                        if (engine != null) {
                            val nMethod = engine.javaClass.getMethod("n").apply { isAccessible = true }
                            nMethod.invoke(engine)
                            handled = true
                            XLog.i("LmCandidateHooks: 通过 Koin yz0.a(p50.f).n() 复位引擎成功")
                        }
                    }
                }.onFailure { XLog.e("通过 Koin 获取 engine 复位失败: ${it.message}") }
            }
        }
    }

    private fun clearSpellTextAndRefreshCandidates() {
        spellTextViewModelRef?.get()?.let { vm ->
            runCatching {
                val setTextMethod = vm.javaClass.declaredMethods.firstOrNull {
                    it.name == "setSpellText" && it.parameterTypes.size == 1
                }?.apply { setAccessible(true) }
                setTextMethod?.invoke(vm, "")
            }
            runCatching {
                val m = vm.javaClass.getDeclaredMethod("setSpellTextVisible", java.lang.Boolean.TYPE).apply { setAccessible(true) }
                m.invoke(vm, false)
            }
            runCatching {
                val m = vm.javaClass.getDeclaredMethod("setSpellLayoutVisibility", java.lang.Boolean.TYPE).apply { setAccessible(true) }
                m.invoke(vm, false)
            }
        }

        refreshCandidateBar()
    }

    private fun refreshCandidateBar() {
        val service = serviceRef?.get()
        var refreshed = false

        if (service != null) {
            runCatching {
                val m = service.javaClass.getDeclaredMethod("getCandidateUpdater").apply { isAccessible = true }
                val updater = m.invoke(service)
                if (updater != null) {
                    val zMethod = updater.javaClass.getMethod("z", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                    zMethod.invoke(updater, true)
                    refreshed = true
                    XLog.i("LmCandidateHooks: 已通知 CandidateUpdater.z(true) 刷新候选栏")
                }
            }.onFailure { XLog.e("调用 getCandidateUpdater 刷新失败: ${it.message}") }
        }

        if (!refreshed) {
            val cl = cachedClassLoader
            if (cl != null) {
                runCatching {
                    val yzCls = Reflect.findClassIfExists("yz0.a", cl)
                    val cnCls = Reflect.findClassIfExists("cn.c", cl)
                    if (yzCls != null && cnCls != null) {
                        val getMethod = yzCls.getMethod("a", Class::class.java)
                        val updater = getMethod.invoke(null, cnCls)
                        if (updater != null) {
                            val zMethod = updater.javaClass.getMethod("z", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                            zMethod.invoke(updater, true)
                        }
                    }
                }.onFailure { XLog.e("通过 Koin 刷新 CandidateUpdater 失败: ${it.message}") }
            }
        }
    }

    // 清空候选栏。deep 模式同时置空 CandidateUpdater，会收起候选栏，
    // 只用于确认没有后续联想推送的兜底场景
    private fun clearEmittedCandidates(deep: Boolean = false) {
        // 三星内部持有同一列表引用并原地渲染，先清空原列表再推空列表双保险
        runCatching { lastEmittedListRef?.get()?.clear() }
        if (deep) {
            runCatching {
                val service = serviceRef?.get() ?: return@runCatching
                val m = service.javaClass.getDeclaredMethod("getCandidateUpdater").apply { isAccessible = true }
                val updater = m.invoke(service) ?: return@runCatching
                val bMethod = updater.javaClass.getMethod("b", java.util.List::class.java).apply { isAccessible = true }
                bMethod.invoke(updater, ArrayList<Any>())
                XLog.i("LmCandidateHooks: 已通过 CandidateUpdater.b([]) 清空候选列表")
            }
        }
        val emitter = emitterInstanceRef?.get() ?: return
        val mL = emitterMethodL
        val mA = emitterMethodA
        if (mL == null && mA == null) return
        val prevPush = beginInternalPush()
        try {
            if (canInvokeEmitter(emitter, mL)) {
                mL?.invoke(emitter, emptyList<Any>(), lastRawSlot)
                XLog.i("LmCandidateHooks: 已清空候选栏")
            } else if (canInvokeEmitter(emitter, mA)) {
                mA?.invoke(emitter, emptyList<Any>())
            } else {
                return
            }
        } catch (t: Throwable) {
            XLog.i("LmCandidateHooks: 清空候选栏异常: ${t.message}")
        } finally {
            isInternalContextPush = prevPush
        }
    }

    // setSpell 晚于候选管线到达时，用最新拼写重算候选
    private fun reemitWithLatestSpell(classLoader: ClassLoader) {
        if (!ConfigStore.lmEnabled && !ConfigStore.lmQuanpinMode) return
        if (!isChineseLanguageActive(classLoader)) return
        val emitter = emitterInstanceRef?.get() ?: return
        val mL = emitterMethodL
        val mA = emitterMethodA
        if (mL == null && mA == null) return
        val rawList = lastRawNativeList ?: return
        // 只要原生快照含有中文且处于打字有效时间内 (1500ms)，即可复用作为基底候选，避免时序差清空候选池
        val now = System.currentTimeMillis()
        val isValidNative = (now - lastRawNativeTime < 1500L) &&
            rawList.any { containsChinese(getCandidateText(it).orEmpty()) }
        val baseList = if (isValidNative) rawList else emptyList()
        val workingList = ArrayList<Any>(baseList)
        val slot = lastRawSlot
        val changed = processCandidates(workingList, classLoader)
        if (!changed) return
        rememberEmittedList(workingList)
        val prevPush = beginInternalPush()
        try {
            if (canInvokeEmitter(emitter, mL)) {
                mL?.invoke(emitter, workingList, slot)
            } else if (canInvokeEmitter(emitter, mA)) {
                mA?.invoke(emitter, workingList)
            } else {
                return
            }
        } catch (_: Throwable) {
        } finally {
            isInternalContextPush = prevPush
        }
    }
}
