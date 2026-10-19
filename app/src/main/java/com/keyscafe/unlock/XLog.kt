package com.keyscafe.unlock

import android.util.Log

/** 日志工具 */
object XLog {

    const val TAG = "汐语"

    // release 包不输出日志，避免把验证码、算式等写入 logcat
    @Volatile
    var enabled: Boolean = BuildConfig.DEBUG

    fun i(msg: String) {
        if (!enabled) return
        runCatching { Log.i(TAG, msg) }
    }

    fun e(msg: String, t: Throwable? = null) {
        if (!enabled) return
        runCatching { Log.e(TAG, msg, t) }
    }

    // 安全执行 hook
    inline fun safe(name: String, block: () -> Unit) {
        try {
            block()
            i("hook OK: $name")
        } catch (t: Throwable) {
            e("hook FAILED: $name", t)
        }
    }
}
