package com.keyscafe.unlock

import io.github.libxposed.api.XposedModule

/** 语音输入解锁 */
object VoiceInputHooks {

    private val GATE_CLASS get() = XyCore.get(0)
    private val GATE_METHOD get() = XyCore.get(1)

    @Volatile
    private var hits = 0

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val gateCls = GATE_CLASS
        val gateMethod = GATE_METHOD
        val cls = Reflect.findClassIfExists(gateCls, classLoader)
        if (cls == null) {
            XLog.e("找不到语音闸门类 $gateCls", null)
            return
        }

        val target = cls.declaredMethods.firstOrNull {
            it.name == gateMethod && it.parameterTypes.isEmpty() && it.returnType == java.lang.Boolean.TYPE
        }
        if (target == null) {
            XLog.e("找不到 $gateCls.$gateMethod()", null)
            return
        }

        XLog.safe("VoiceInputHooks.$gateMethod") {
            module.hook(target).intercept { chain ->
                if (ConfigStore.voiceGate) {
                    val count = ++hits
                    if (count <= 3) XLog.i("语音闸门放行: $gateCls.$gateMethod ($count)")
                    false
                } else {
                    chain.proceed()
                }
            }
        }
        XLog.i("语音输入解锁已安装：$gateCls.$gateMethod")
    }

}
