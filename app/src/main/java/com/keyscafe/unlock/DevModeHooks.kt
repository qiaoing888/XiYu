package com.keyscafe.unlock

import io.github.libxposed.api.XposedModule

/** 键盘开发者选项解锁 */
object DevModeHooks {

    private val LAB_ACTIVITY get() = XyCore.get(17)
    private val GATE_CLASS get() = XyCore.get(2)
    private val FLAG_FIELD get() = XyCore.get(3)

    private var classLoader: ClassLoader? = null
    private var original: Boolean? = null

    fun install(module: XposedModule, classLoader: ClassLoader) {
        this.classLoader = classLoader
        val labAct = LAB_ACTIVITY
        val gateCls = GATE_CLASS
        val flagField = FLAG_FIELD

        val cls = Reflect.findClassIfExists(labAct, classLoader) ?: run {
            XLog.e("找不到 $labAct", null)
            return
        }

        XLog.safe("DevModeHooks.labActivity") {
            val onCreate = Reflect.methodInHierarchy(cls, "onCreate", android.os.Bundle::class.java)
            if (onCreate != null) {
                module.hook(onCreate).intercept { chain ->
                    apply()
                    chain.proceed()
                }
            } else {
                XLog.e("找不到 $labAct.onCreate", null)
            }
        }

        ConfigStore.onChanged { apply() }

        XLog.i("键盘实验室解锁已安装：$gateCls.$flagField")
    }

    // 写入标志位
    private fun apply() {
        val loader = classLoader ?: return
        val gateCls = GATE_CLASS
        val flagField = FLAG_FIELD
        runCatching {
            val gate = Reflect.findClassIfExists(gateCls, loader) ?: return
            val base = original ?: Reflect.staticBoolean(gate, flagField).also { original = it }
            val want = if (ConfigStore.lab) true else (original ?: false)
            Reflect.setStaticBoolean(gate, flagField, want)
            XLog.i("$gateCls.$flagField: $base -> $want")
        }.onFailure { XLog.e("写 $gateCls.$flagField 失败", it) }
    }
}
