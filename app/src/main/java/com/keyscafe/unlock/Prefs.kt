package com.keyscafe.unlock

import android.content.Intent
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArrayList

/** 配置管理 (UI 进程侧) */
object Prefs {

    const val GROUP = "config"
    const val ACTION_CONFIG_CHANGED = "com.keyscafe.unlock.ACTION_CONFIG_CHANGED"

    const val VOICE_GATE = "voice_gate"
    const val VOICE_MELT = "voice_melt"
    const val OTP_ENHANCE = "otp_enhance"
    const val LAB = "lab"
    const val KEYSCAFE_BYPASS = "keyscafe_bypass"
    const val CALC_ENABLED = "calc_enabled"
    const val CALC_REPLACE_EXPR = "calc_replace_expr"
    const val CALC_RMB = "calc_rmb"
    const val CALC_RADIX = "calc_radix"
    const val LM_ENABLED = "lm_enabled"
    const val LM_QUANPIN_MODE = "lm_quanpin_mode"
    const val LM_EMOTICON = "lm_emoticon"
    const val LM_LEARNING = "lm_learning"
    const val LM_NEXT_WORD = "lm_next_word"
    const val LM_LONG_PHRASE_AGGRESSIVE = "lm_long_phrase_aggressive"
    const val LM_CLEAR_TIMESTAMP = "lm_clear_timestamp"
    const val LM_LEXICON_VERSION = "lm_lexicon_version"
    const val LM_USER_LEXICON_TSV = "lm_user_lexicon_tsv"

    val DEFAULTS: Map<String, Boolean> = mapOf(
        VOICE_GATE to true,
        VOICE_MELT to true,
        OTP_ENHANCE to true,
        LAB to false,
        KEYSCAFE_BYPASS to false,
        CALC_ENABLED to true,
        CALC_REPLACE_EXPR to true,
        CALC_RMB to true,
        CALC_RADIX to true,
        LM_ENABLED to true,
        LM_QUANPIN_MODE to true,
        LM_EMOTICON to false,
        LM_LEARNING to true,
        LM_NEXT_WORD to true,
        LM_LONG_PHRASE_AGGRESSIVE to true,
    )

    @Volatile
    private var service: XposedService? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun bind() {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(s: XposedService) {
                service = s
                XLog.i("XposedService 绑定成功 apiVersion=${s.apiVersion} fw=${s.frameworkName}")
                listeners.forEach { runCatching { it() } }
            }

            override fun onServiceDied(s: XposedService) {
                service = null
                XLog.i("XposedService 连接断开")
                listeners.forEach { runCatching { it() } }
            }
        })
    }

    fun isActive(): Boolean = service != null

    fun onStateChanged(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    fun read(key: String): Boolean =
        service?.getRemotePreferences(GROUP)?.getBoolean(key, DEFAULTS[key] == true)
            ?: (DEFAULTS[key] == true)

    fun write(key: String, value: Boolean) {
        val sp = service?.getRemotePreferences(GROUP)
        val success = sp?.edit()?.putBoolean(key, value)?.commit() ?: false
        XLog.i("Prefs.write key=$key val=$value commit=$success")
        notifyChange(key)
    }

    fun readLong(key: String, def: Long = 0L): Long =
        service?.getRemotePreferences(GROUP)?.getLong(key, def) ?: def

    fun writeLong(key: String, value: Long) {
        val sp = service?.getRemotePreferences(GROUP)
        val success = sp?.edit()?.putLong(key, value)?.commit() ?: false
        XLog.i("Prefs.writeLong key=$key val=$value commit=$success")
        notifyChange(key)
    }

    fun readString(key: String, def: String = ""): String =
        service?.getRemotePreferences(GROUP)?.getString(key, def) ?: def

    fun writeString(key: String, value: String) {
        val sp = service?.getRemotePreferences(GROUP)
        val success = sp?.edit()?.putString(key, value)?.commit() ?: false
        XLog.i("Prefs.writeString key=$key len=${value.length} commit=$success")
        notifyChange(key)
    }

    // 跨进程广播令牌，首次使用时生成并落盘，宿主进程据此校验广播来源
    fun broadcastToken(): String {
        val sp = service?.getRemotePreferences(GROUP) ?: return ""
        val existing = sp.getString(LmCandidateHooks.EXTRA_BROADCAST_TOKEN, "") ?: ""
        if (existing.isNotEmpty()) return existing
        val token = java.util.UUID.randomUUID().toString()
        sp.edit().putString(LmCandidateHooks.EXTRA_BROADCAST_TOKEN, token).commit()
        return token
    }

    // 将 UI 侧主动修改后的个人词库同步至跨进程存储，并广播通知三星键盘进程
    fun syncLexiconTsvToRemote(tsv: String, overwrite: Boolean = true) {
        val now = System.currentTimeMillis()
        val sp = service?.getRemotePreferences(GROUP)
        sp?.edit()
            ?.putString(LM_USER_LEXICON_TSV, tsv)
            ?.putLong(LM_LEXICON_VERSION, now)
            ?.commit()
        notifyChange(LM_USER_LEXICON_TSV)
        runCatching {
            val intent = Intent(LmCandidateHooks.ACTION_SYNC_USER_LEXICON).apply {
                setPackage("com.samsung.android.honeyboard")
                putExtra(LmCandidateHooks.EXTRA_LEXICON_TSV, tsv)
                putExtra(LmCandidateHooks.EXTRA_LEXICON_OVERWRITE, overwrite)
                putExtra(LmCandidateHooks.EXTRA_LEXICON_VERSION, now)
                putExtra(LmCandidateHooks.EXTRA_BROADCAST_TOKEN, broadcastToken())
            }
            XyApp.instance.sendBroadcast(intent)
        }
    }

    private fun notifyChange(key: String) {
        runCatching {
            val app = XyApp.instance
            val token = broadcastToken()
            listOf(
                "com.samsung.android.honeyboard",
                "com.samsung.android.keyscafe",
                "com.samsung.android.rubin.app"
            ).forEach { pkg ->
                val intent = Intent(ACTION_CONFIG_CHANGED).apply {
                    setPackage(pkg)
                    putExtra("key", key)
                    putExtra(LmCandidateHooks.EXTRA_BROADCAST_TOKEN, token)
                }
                app.sendBroadcast(intent)
            }
        }
    }
}
