package com.keyscafe.unlock

import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayList
import kotlin.math.ceil

/**
 * 候选词左对齐
 *
 * 三星中文候选栏会按“槽位”把候选词拉伸铺满整行：单个候选被拉宽居中，
 * 少量候选被均匀摊开。这里在原生分槽计算完成后，把每项宽度改回
 * “文字宽度 + 左右内边距”，使候选从左侧紧凑排列；候选超出时仍可横向滚动。
 */
object CandidateAlignHooks {

    private const val CONTROLLER_CLASS = "ob0.z0"
    private const val WIDTH_PROVIDER_CLASS = "ob0.a1"

    private var fractionsField: Field? = null
    private var providerGetter: Method? = null
    private var widthGetter: Method? = null
    private var measureMethod: Method? = null

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val cls = Reflect.findClassIfExists(CONTROLLER_CLASS, classLoader)
        if (cls == null) {
            XLog.e("CandidateAlignHooks: 找不到 $CONTROLLER_CLASS", null)
            return
        }

        val method = cls.declaredMethods.firstOrNull {
            it.name == "m0" &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == java.util.List::class.java &&
                it.parameterTypes[1] == java.lang.Boolean.TYPE &&
                it.parameterTypes[2] == java.lang.Boolean.TYPE
        }
        if (method == null) {
            XLog.e("CandidateAlignHooks: 找不到候选宽度计算方法", null)
            return
        }

        val base = cls.superclass
        fractionsField = runCatching { base.getField("v").apply { isAccessible = true } }.getOrNull()
        providerGetter = runCatching { base.getMethod("u") }.getOrNull()
        val providerCls = Reflect.findClassIfExists(WIDTH_PROVIDER_CLASS, classLoader)
        widthGetter = providerCls?.let { Reflect.method(it, "d") }
        measureMethod = providerCls?.let { Reflect.method(it, "e", String::class.java) }

        if (fractionsField == null || providerGetter == null || widthGetter == null || measureMethod == null) {
            XLog.e("CandidateAlignHooks: 候选宽度反射句柄不完整", null)
            return
        }

        XLog.safe("CandidateAlignHooks.install") {
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val controller = chain.thisObject ?: return@runCatching
                    val candidates = chain.getArg(0) as? List<*> ?: return@runCatching
                    applyNaturalWidths(controller, candidates)
                }.onFailure { XLog.e("CandidateAlignHooks: 调整候选宽度失败", it) }
                result
            }
        }
        XLog.i("候选词左对齐已安装")
    }

    private fun applyNaturalWidths(controller: Any, candidates: List<*>) {
        if (candidates.isEmpty()) return
        val texts = candidates.map { candidateText(it).orEmpty() }
        if (texts.none { XyCore.containsChinese(it) }) return

        val provider = providerGetter?.invoke(controller) ?: return
        val available = widthGetter?.invoke(provider) as? Int ?: return
        if (available <= 0) return
        val fractions = fractionsField?.get(controller) as? ArrayList<*> ?: return
        val measure = measureMethod ?: return

        @Suppress("UNCHECKED_CAST")
        val widths = fractions as ArrayList<Float>
        val count = minOf(texts.size, widths.size)
        for (i in 0 until count) {
            val naturalWidth = measure.invoke(provider, texts[i]) as? Float ?: continue
            // 向上取整并留 2px 余量，避免 TextView 因亚像素宽度差触发省略号
            widths[i] = ((ceil(naturalWidth) + 2f) / available).coerceIn(0f, 1f)
        }
    }

    private fun candidateText(candidate: Any?): String? =
        candidate?.let {
            runCatching { it.javaClass.getMethod("c").invoke(it)?.toString() }.getOrNull()
        }
}
