package com.keyscafe.unlock

import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputConnection
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference

// 光标前文本必须仍以原算式结尾；返回要删除的 UTF-16 长度（含尾随空格）
internal fun replaceDeleteLength(before: String?, expression: String): Int? {
    if (before.isNullOrEmpty() || expression.isEmpty()) return null
    if (!before.trimEnd().endsWith(expression)) return null
    val start = before.lastIndexOf(expression)
    if (start < 0) return null
    return before.length - start
}

/**
 * 打字即算 Hook
 * 监听输入法文本变化与候选词点击，实现算式实时求值与结果上屏。
 */
object CalculatorHooks {

    private const val IME_SERVICE_CLASS = "com.samsung.android.honeyboard.service.HoneyBoardService"
    private const val INPUT_CONNECTION_CLASS = "jh.a"
    private const val TEXT_COMMITTER_CLASS = "tj0.d"

    @Volatile
    private var lastActiveCalc: CalcResult? = null

    @Volatile
    private var lastCheckedText: String? = null

    @Volatile
    private var serviceRef: WeakReference<InputMethodService>? = null

    fun install(module: XposedModule, classLoader: ClassLoader) {
        hookHoneyBoardService(module, classLoader)
        hookInputConnection(module, classLoader)
        hookCandidateCommit(module, classLoader)

        XLog.i("CalculatorHooks: 就绪")
    }

    // 获取输入法 Service 引用
    private fun hookHoneyBoardService(module: XposedModule, classLoader: ClassLoader) {
        val serviceCls = Reflect.findClassIfExists(IME_SERVICE_CLASS, classLoader) ?: return
        Reflect.methodInHierarchy(serviceCls, "onCreate")?.let { m ->
            XLog.safe("CalculatorHooks.onCreate") {
                module.hook(m).intercept { chain ->
                    val r = chain.proceed()
                    (chain.thisObject as? InputMethodService)?.let { service ->
                        serviceRef = WeakReference(service)
                    }
                    r
                }
            }
        }
        // 切换输入框/隐藏键盘/服务销毁时清掉上一轮的算式建议，避免误点旧结果
        for (name in listOf("onStartInputView", "onFinishInputView", "onDestroy")) {
            Reflect.methodInHierarchy(serviceCls, name)?.let { m ->
                XLog.safe("CalculatorHooks.$name") {
                    module.hook(m).intercept { chain ->
                        lastActiveCalc = null
                        lastCheckedText = null
                        if (name == "onDestroy") serviceRef = null
                        chain.proceed()
                    }
                }
            }
        }
    }

    // 监听 commitText 与 deleteSurroundingText 输入
    private fun hookInputConnection(module: XposedModule, classLoader: ClassLoader) {
        val icCls = Reflect.findClassIfExists(INPUT_CONNECTION_CLASS, classLoader) ?: return

        Reflect.method(icCls, "commitText", CharSequence::class.java, java.lang.Integer.TYPE)?.let { m ->
            XLog.safe("CalculatorHooks.commitText") {
                module.hook(m).intercept { chain ->
                    val r = chain.proceed()
                    if (ConfigStore.calcEnabled) {
                        val text = chain.getArg(0) as? CharSequence
                        if (text != null && text.any { it.isDigit() || it in "+-*/×÷%=＝()（）^＋－＊／％０１２３４５６７８９" }) {
                            val ic = chain.thisObject as? InputConnection
                            checkInputConnection(ic, classLoader)
                        }
                    }
                    r
                }
            }
        }

        Reflect.method(icCls, "deleteSurroundingText", java.lang.Integer.TYPE, java.lang.Integer.TYPE)?.let { m ->
            XLog.safe("CalculatorHooks.deleteSurroundingText") {
                module.hook(m).intercept { chain ->
                    val r = chain.proceed()
                    if (ConfigStore.calcEnabled && lastActiveCalc != null) {
                        val ic = chain.thisObject as? InputConnection
                        checkInputConnection(ic, classLoader)
                    }
                    r
                }
            }
        }
    }

    // 检查光标前文本并分发候选结果
    private fun checkInputConnection(ic: InputConnection?, classLoader: ClassLoader) {
        ic ?: return
        runCatching {
            val textBefore = ic.getTextBeforeCursor(64, 0)?.toString()
            if (textBefore == lastCheckedText) return
            lastCheckedText = textBefore

            val endsWithEq = textBefore != null && (textBefore.trimEnd().endsWith('=') || textBefore.trimEnd().endsWith('＝'))

            val eval = MathEvaluator.evaluate(
                textBefore,
                requireEqualSign = true,
                enableRmb = ConfigStore.calcRmb,
                enableRadix = ConfigStore.calcRadix
            )

            if (eval != null) {
                if (eval != lastActiveCalc) {
                    lastActiveCalc = eval
                    OtpCandidateHooks.postOtp(classLoader, eval.displayText)
                    XLog.i("算式识别成功: [${eval.expression}] -> [${eval.resultText}]")
                }
            } else {
                if (endsWithEq) {
                    XLog.i("等号触发但未识别出有效算式: [$textBefore]")
                }
                if (lastActiveCalc != null) {
                    lastActiveCalc = null
                }
            }
        }.onFailure { XLog.e("checkInputConnection 异常", it) }
    }

    // 接管候选词点击，执行结果上屏或整式替换
    private fun hookCandidateCommit(module: XposedModule, classLoader: ClassLoader) {
        val committerCls = Reflect.findClassIfExists(TEXT_COMMITTER_CLASS, classLoader) ?: return
        val m = committerCls.declaredMethods.firstOrNull {
            it.name == "a" && it.parameterTypes.size == 1
        } ?: return

        XLog.safe("CalculatorHooks.candidateClick") {
            module.hook(m).intercept { chain ->
                val calc = lastActiveCalc
                if (ConfigStore.calcEnabled && calc != null) {
                    val candidate = chain.getArg(0) ?: return@intercept chain.proceed()
                    val text = runCatching { Reflect.call(candidate, "getText")?.toString() }.getOrNull()

                    if (text == calc.displayText || text == calc.resultText) {
                        lastActiveCalc = null
                        lastCheckedText = null

                        val committer = chain.thisObject
                        val ic = getCommitterInputConnection(committer) ?: serviceRef?.get()?.currentInputConnection

                        if (ic != null) {
                            if (ConfigStore.calcReplaceExpr) {
                                // 删除前确认光标前文本仍是原算式，避免光标移动/追加空格后删错字符
                                val before = runCatching {
                                    ic.getTextBeforeCursor(calc.exprLength + 32, 0)?.toString()
                                }.getOrNull()
                                val deleteLen = replaceDeleteLength(before, calc.expression)
                                if (deleteLen == null) {
                                    lastActiveCalc = null
                                    lastCheckedText = null
                                    XLog.i("算式已被修改，放弃替换: [${calc.expression}]")
                                    return@intercept null
                                }
                                // 连同表达式后的尾随空格一起删掉
                                ic.deleteSurroundingText(deleteLen, 0)
                                ic.commitText(calc.resultText, 1)
                                runCatching { ic.finishComposingText() }
                                XLog.i("算式替换上屏: [${calc.expression}] -> [${calc.resultText}]")
                            } else {
                                // 追加模式：算式必然以等号结尾，直接补结果
                                ic.commitText(calc.resultText, 1)
                                runCatching { ic.finishComposingText() }
                                XLog.i("算式追加上屏: [${calc.expression}] -> [${calc.fullEquation}]")
                            }
                            return@intercept null
                        }
                    }
                }
                chain.proceed()
            }
        }
    }

    private fun getCommitterInputConnection(committer: Any): InputConnection? {
        for (f in committer.javaClass.declaredFields) {
            if (InputConnection::class.java.isAssignableFrom(f.type)) {
                return runCatching {
                    f.isAccessible = true
                    f.get(committer) as? InputConnection
                }.getOrNull()
            }
        }
        return null
    }
}
