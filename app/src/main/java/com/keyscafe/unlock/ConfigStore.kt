package com.keyscafe.unlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.libxposed.api.XposedModule
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** 宿主进程配置读取与热更新监听 */
object ConfigStore {

    private var prefs: SharedPreferences? = null

    @Volatile
    private var snapshot: Map<String, Boolean> = Prefs.DEFAULTS

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    // 词库导入/清空属于重型 native 操作，避免阻塞输入法主线程
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cfg-lexicon-sync").apply { isDaemon = true }
    }

    @Volatile
    private var reloadPending = false
    @Volatile
    private var lastClearTimestamp = 0L
    @Volatile
    private var lastLexiconVersion = 0L

    val voiceGate: Boolean get() = snapshot[Prefs.VOICE_GATE] ?: true
    val voiceMelt: Boolean get() = snapshot[Prefs.VOICE_MELT] ?: true
    val otpEnhance: Boolean get() = snapshot[Prefs.OTP_ENHANCE] ?: true
    val lab: Boolean get() = snapshot[Prefs.LAB] ?: false
    val keysCafeBypass: Boolean get() = snapshot[Prefs.KEYSCAFE_BYPASS] ?: false
    val calcEnabled: Boolean get() = snapshot[Prefs.CALC_ENABLED] ?: true
    val calcReplaceExpr: Boolean get() = snapshot[Prefs.CALC_REPLACE_EXPR] ?: true
    val calcRmb: Boolean get() = snapshot[Prefs.CALC_RMB] ?: true
    val calcRadix: Boolean get() = snapshot[Prefs.CALC_RADIX] ?: true
    val lmEnabled: Boolean get() = snapshot[Prefs.LM_ENABLED] ?: true
    val lmQuanpinMode: Boolean get() = snapshot[Prefs.LM_QUANPIN_MODE] ?: true
    val lmEmoticon: Boolean get() = snapshot[Prefs.LM_EMOTICON] ?: false
    val lmLearning: Boolean get() = snapshot[Prefs.LM_LEARNING] ?: true
    val lmNextWord: Boolean get() = snapshot[Prefs.LM_NEXT_WORD] ?: true
    val lmLongPhraseAggressive: Boolean get() = snapshot[Prefs.LM_LONG_PHRASE_AGGRESSIVE] ?: true
    val userLexiconTsv: String get() = prefs?.getString(Prefs.LM_USER_LEXICON_TSV, "") ?: ""
    val userLexiconVersion: Long get() = prefs?.getLong(Prefs.LM_LEXICON_VERSION, 0L) ?: 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        XLog.i("收到配置变更推送 key=$key")
        scheduleReload()
    }

    @Volatile
    private var receiverRegistered = false

    fun registerReceiver(context: Context) {
        if (receiverRegistered) return
        runCatching {
            val ctx = context.applicationContext ?: context
            val filter = IntentFilter(Prefs.ACTION_CONFIG_CHANGED)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    // 配置变更只触发本进程重读偏好，不执行敏感操作；
                    // 不校验令牌，避免令牌首次下发前形成死锁
                    val key = intent?.getStringExtra("key")
                    XLog.i("ConfigStore: 收到跨进程广播 key=$key")
                    scheduleReload()
                }
            }
            ContextCompat.registerReceiver(
                ctx,
                receiver,
                filter,
                "com.keyscafe.unlock.permission.INTERNAL",
                null,
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
            XLog.i("ConfigStore: 跨进程广播监听注册成功")
        }.onFailure { XLog.e("ConfigStore: 注册广播失败", it) }
    }

    fun init(module: XposedModule) {
        val sp = runCatching { module.getRemotePreferences(Prefs.GROUP) }.getOrNull()
        prefs = sp

        // 清空时间戳与词库版本需要跨进程重启保持，避免漏做或重复执行
        runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? android.app.Application
            if (app != null) {
                stateFile = File(app.filesDir, "sync_state.dat")
                loadSyncState()
                registerReceiver(app)
            }
        }

        reload()

        runCatching {
            sp?.registerOnSharedPreferenceChangeListener(prefListener)
        }.onFailure { XLog.e("注册配置变更监听失败", it) }
    }

    @Volatile
    private var stateFile: File? = null

    @Synchronized
    private fun loadSyncState() {
        val f = stateFile ?: return
        runCatching {
            if (!f.exists()) return
            val parts = f.readText().trim().split(' ')
            lastClearTimestamp = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            lastLexiconVersion = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        }
    }

    @Synchronized
    private fun saveSyncState() {
        val f = stateFile ?: return
        runCatching { f.writeText("$lastClearTimestamp $lastLexiconVersion") }
    }

    // 输入法进程创建后补挂存储并重新检查一次，避免启动早期漏做清空/词库同步
    fun attachStorage(dir: File?) {
        if (stateFile != null || dir == null) return
        stateFile = File(dir, "sync_state.dat")
        loadSyncState()
        reload()
    }

    // 读取并与落盘内容对齐，供广播来源校验使用。
    // 令牌缺失时由宿主进程自己生成，避免必须先在 UI 里打开一次模块。
    fun shouldSkipLexiconImport(remoteVersion: Long): Boolean =
        lastClearTimestamp > remoteVersion

    fun filesDir(): File? = stateFile?.parentFile

    fun currentBroadcastToken(): String {
        val p = prefs ?: return ""
        runCatching {
            val m = p.javaClass.methods.firstOrNull { it.name == "reload" && it.parameterCount == 0 }
                ?: p.javaClass.declaredMethods.firstOrNull {
                    it.name == "reload" && it.parameterCount == 0
                }?.apply { isAccessible = true }
            m?.invoke(p)
        }
        // 宿主侧是只读偏好，不能在这里 edit()；令牌由 UI 进程写入
        return p.getString(LmCandidateHooks.EXTRA_BROADCAST_TOKEN, "") ?: ""
    }

    fun isTrustedBroadcast(intent: Intent?): Boolean {
        if (intent == null) return false
        val token = currentBroadcastToken()
        return token.isNotEmpty() && token == intent.getStringExtra(LmCandidateHooks.EXTRA_BROADCAST_TOKEN)
    }

    fun markLexiconVersionSynced(version: Long) {
        if (version > lastLexiconVersion) {
            lastLexiconVersion = version
            saveSyncState()
        }
    }

    private fun scheduleReload() {
        if (reloadPending) return
        reloadPending = true
        main.post {
            reloadPending = false
            reload()
        }
    }

    fun reload() {
        val p = prefs ?: return
        val next = HashMap(Prefs.DEFAULTS)
        for ((k, d) in Prefs.DEFAULTS) {
            next[k] = p.getBoolean(k, d)
        }

        val clearTs = p.getLong(Prefs.LM_CLEAR_TIMESTAMP, 0L)
        if (clearTs > lastClearTimestamp && stateFile != null) {
            lastClearTimestamp = clearTs
            ioExecutor.execute {
                XyCore.lmClearUserMemory()
                main.post { LmCandidateHooks.onMemoryCleared() }
                saveSyncState()
                XLog.i("ConfigStore: 已执行跨进程清空个人词库")
            }
        }

        val lexVer = p.getLong(Prefs.LM_LEXICON_VERSION, 0L)
        // 清空时间戳比词库快照新时，旧快照不能再导回来
        if (lexVer > lastLexiconVersion && lexVer >= lastClearTimestamp && stateFile != null) {
            lastLexiconVersion = lexVer
            val tsv = p.getString(Prefs.LM_USER_LEXICON_TSV, "") ?: ""
            // 运行期间收到 UI 端词库版本更新，一律使用覆盖同步使删除或压制立即生效
            ioExecutor.execute {
                XyCore.lmImportUserMemoryTsv(tsv, merge = false)
                XyCore.lmSync()
                saveSyncState()
                XLog.i("ConfigStore: 已覆盖同步个人词库 TSV (ver=$lexVer, len=${tsv.length})")
            }
        }

        val changed = (next != snapshot)
        snapshot = next
        XyCore.lmSetLongPhraseAggressive(lmLongPhraseAggressive)
        XyCore.lmSetQuanpinMode(lmQuanpinMode)
        if (changed) {
            for (l in listeners) runCatching { l() }
        }
    }

    fun onChanged(listener: () -> Unit) {
        listeners += listener
    }
}
