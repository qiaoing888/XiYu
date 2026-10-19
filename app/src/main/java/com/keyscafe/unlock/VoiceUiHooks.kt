package com.keyscafe.unlock

import android.content.res.Configuration
import android.graphics.Shader
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.libxposed.api.XposedModule

/** 语音丝绸动效与空格松手即停 */
object VoiceUiHooks {

    private val STATE_CLASS get() = XyCore.get(4)
    private val STATE_METHOD get() = XyCore.get(5)
    private val RMS_CLASS get() = XyCore.get(6)
    private val RMS_METHOD get() = XyCore.get(7)
    private val KB_PKG get() = XyCore.get(14)
    private val IME_SERVICE_CLASS get() = XyCore.get(19)

    private const val STATE_LISTENING = 1

    private val SPACE_CLASS get() = XyCore.get(8)
    private val SPACE_METHOD get() = XyCore.get(9)
    private val KOIN_CLASS get() = XyCore.get(10)
    private val KOIN_METHOD get() = XyCore.get(11)
    private val SESSION_CLASS get() = XyCore.get(12)
    private val SESSION_STOP get() = XyCore.get(13)

    private const val FROST_ALPHA = 0.88f
    private const val BLUR_DP = 2f

    // 状态与视图引用
    @Volatile
    private var listening = false

    @Volatile private var service: InputMethodService? = null
    @Volatile private var melt: KeyboardMeltView? = null
    @Volatile private var inputView: View? = null
    @Volatile private var blurred = false
    @Volatile private var melted = false

    @Volatile private var hits = 0
    private var cl: ClassLoader? = null
    @Volatile private var spacePressedAt = 0L
    @Volatile private var startedBySpace = false

    fun install(module: XposedModule, classLoader: ClassLoader) {
        ConfigStore.onChanged {
            if (!ConfigStore.voiceMelt && listening) onMain { restore() }
        }
        hookState(module, classLoader)
        hookRms(module, classLoader)
        hookService(module, classLoader)
        hookSpace(module, classLoader)
    }

    // 1. 麦克风状态
    private fun hookState(module: XposedModule, classLoader: ClassLoader) {
        val cls = Reflect.findClassIfExists(STATE_CLASS, classLoader) ?: run {
            XLog.e("找不到状态分发点 $STATE_CLASS", null)
            return
        }
        XLog.safe("VoiceUiHooks.state") {
            for (m in cls.declaredMethods) {
                if (m.name != STATE_METHOD) continue
                if (m.parameterTypes.size != 2 ||
                    m.parameterTypes[0] != java.lang.Integer.TYPE ||
                    m.parameterTypes[1] != java.lang.Boolean.TYPE
                ) continue
                module.hook(m).intercept { chain ->
                    val r = chain.proceed()
                    val state = chain.getArg(0) as? Int
                    if (state != null) {
                        val now = state == STATE_LISTENING
                        if (now != listening) {
                            listening = now
                            if (now) {
                                startedBySpace = android.os.SystemClock.uptimeMillis() - spacePressedAt < 800L
                            }
                            XLog.i("聆听状态 -> $state（键盘${if (now) "融化" else "复原"}）")
                            onMain { if (now) meltDown() else restore() }
                        }
                    }
                    r
                }
            }
        }
    }

    // 2. 音量
    private fun hookRms(module: XposedModule, classLoader: ClassLoader) {
        val cls = Reflect.findClassIfExists(RMS_CLASS, classLoader) ?: run {
            XLog.e("找不到音量钩点 $RMS_CLASS", null)
            return
        }
        XLog.safe("VoiceUiHooks.rms") {
            for (m in cls.declaredMethods) {
                if (m.name != RMS_METHOD) continue
                if (m.parameterTypes.size != 1 || m.parameterTypes[0] != java.lang.Float.TYPE) continue
                module.hook(m).intercept { chain ->
                    val r = chain.proceed()
                    if (listening) {
                        val rms = (chain.getArg(0) as? Float)?.toInt()
                        if (rms != null) {
                            melt?.setLevel(rms)
                        }
                    }
                    r
                }
            }
        }
    }

    // 缓存反射引用
    private var cachedKoinCls: Class<*>? = null
    private var cachedSessionCls: Class<*>? = null
    private var cachedKoinMethod: java.lang.reflect.Method? = null
    private var cachedStopMethod: java.lang.reflect.Method? = null

    // 3. 服务实例
    private fun hookService(module: XposedModule, classLoader: ClassLoader) {
        val cls = Reflect.findClassIfExists(IME_SERVICE_CLASS, classLoader)
        if (cls != null) {
            XLog.safe("VoiceUiHooks.onWindowShown") {
                Reflect.methodInHierarchy(cls, "onWindowShown")?.let { onWindowShown ->
                    module.hook(onWindowShown).intercept { chain ->
                        val r = chain.proceed()
                        service = chain.thisObject as? InputMethodService
                        onMain {
                            dropStale()
                            // 即使关闭融化动效也要记录输入视图，松手即停依赖它
                            if (inputView?.isAttachedToWindow != true) {
                                service?.window?.window?.decorView?.let { decor ->
                                    findInputView(decor)?.let { inputView = it }
                                }
                            }
                            if (listening) meltDown()
                        }
                        r
                    }
                }
            }
        } else {
            // 服务类不存在时兜底
            XLog.safe("VoiceUiHooks.getWindowFallback") {
                val getWindow = InputMethodService::class.java.getDeclaredMethod("getWindow")
                module.hook(getWindow).intercept { chain ->
                    val r = chain.proceed()
                    if (service !== chain.thisObject) service = chain.thisObject as? InputMethodService
                    r
                }
            }
        }

        // 输入法销毁时释放静态引用，避免泄漏旧 Service/View
        cls?.let { serviceCls -> Reflect.methodInHierarchy(serviceCls, "onDestroy") }?.let { onDestroy ->
            XLog.safe("VoiceUiHooks.onDestroy") {
                module.hook(onDestroy).intercept { chain ->
                    val r = chain.proceed()
                    service = null
                    onMain {
                        melt?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
                        melt = null
                        inputView = null
                        listening = false
                        melted = false
                        blurred = false
                    }
                    r
                }
            }
        }
    }

    // 空格长按与松手即停
    private fun hookSpace(module: XposedModule, classLoader: ClassLoader) {
        cl = classLoader

        // 预缓存方法
        runCatching {
            val koin = Reflect.findClassIfExists(KOIN_CLASS, classLoader)
            val sessionCls = Reflect.findClassIfExists(SESSION_CLASS, classLoader)
            if (koin != null && sessionCls != null) {
                cachedKoinCls = koin
                cachedSessionCls = sessionCls
                cachedKoinMethod = koin.declaredMethods.firstOrNull {
                    it.name == KOIN_METHOD && it.parameterTypes.size == 1
                }?.apply { isAccessible = true }
                cachedStopMethod = sessionCls.declaredMethods.firstOrNull {
                    it.name == SESSION_STOP && it.parameterTypes.size == 1
                }?.apply { isAccessible = true }
            }
        }

        val spaceCls = Reflect.findClassIfExists(SPACE_CLASS, classLoader)
        if (spaceCls == null) {
            XLog.e("找不到空格长按入口 $SPACE_CLASS", null)
        } else {
            XLog.safe("VoiceUiHooks.spacePress") {
                Reflect.methodInHierarchy(spaceCls, SPACE_METHOD)?.let { m ->
                    module.hook(m).intercept { chain ->
                        spacePressedAt = android.os.SystemClock.uptimeMillis()
                        chain.proceed()
                    }
                }
            }
        }

        XLog.safe("VoiceUiHooks.touchUp") {
            val dispatchTouchEvent = ViewGroup::class.java.getDeclaredMethod(
                "dispatchTouchEvent",
                android.view.MotionEvent::class.java
            )
            module.hook(dispatchTouchEvent).intercept { chain ->
                if (!listening) {
                    return@intercept chain.proceed()
                }
                if (chain.thisObject === inputView) {
                    val ev = chain.getArg(0) as? android.view.MotionEvent
                    if (ev != null) {
                        val a = ev.actionMasked
                        if ((a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) &&
                            startedBySpace
                        ) {
                            startedBySpace = false
                            stopListening()
                        }
                    }
                }
                chain.proceed()
            }
        }
        XLog.i("空格松手即停已安装")
    }

    // 停止语音识别
    private fun stopListening() {
        runCatching {
            val koin = cachedKoinCls
            val sessionCls = cachedSessionCls
            val getMethod = cachedKoinMethod
            val stopMethod = cachedStopMethod

            if (koin != null && sessionCls != null && getMethod != null && stopMethod != null) {
                val session = getMethod.invoke(null, sessionCls)
                if (session != null) {
                    stopMethod.invoke(session, false)
                    XLog.i("空格松手 -> 停止聆听")
                    return
                }
            }

            // 慢路径兜底
            val loader = cl ?: return
            val k = koin ?: Reflect.findClassIfExists(KOIN_CLASS, loader) ?: run {
                XLog.e("找不到 Koin 入口 $KOIN_CLASS", null); return
            }
            val sCls = sessionCls ?: Reflect.findClassIfExists(SESSION_CLASS, loader) ?: run {
                XLog.e("找不到会话接口 $SESSION_CLASS", null); return
            }
            val session = Reflect.callStatic(k, KOIN_METHOD, sCls) ?: run {
                XLog.e("Koin 取会话返回空", null); return
            }
            Reflect.call(session, SESSION_STOP, false)
            XLog.i("空格松手 -> 停止聆听(slow)")
        }.onFailure { XLog.e("停止聆听失败", it) }
    }

    // 4. 融化与复原
    private fun dropStale() {
        if (melt?.isAttachedToWindow == true && inputView?.isAttachedToWindow == true) return
        melt?.let { v ->
            runCatching { (v.parent as? ViewGroup)?.removeView(v) }
            runCatching { v.animate().cancel() }
            runCatching { v.reset() }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) inputView?.setRenderEffect(null)
        }
        melt = null
        inputView = null
        blurred = false
        melted = false
    }

    private fun meltDown() {
        if (!ConfigStore.voiceMelt) return
        dropStale()
        try {
            val decor = service?.window?.window?.decorView ?: run {
                XLog.e("拿不到输入法窗口根视图", null)
                return
            }
            val iv = inputView ?: findInputView(decor) ?: run {
                XLog.e("找不到键盘视图", null)
                return
            }
            inputView = iv

            // 键盘高斯模糊（Android 12+）
            if (!blurred && BLUR_DP > 0f && Build.VERSION.SDK_INT >= 31) {
                runCatching {
                    val r = BLUR_DP * decor.resources.displayMetrics.density
                    iv.setRenderEffect(
                        android.graphics.RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
                    )
                    blurred = true
                    XLog.i("键盘已高斯模糊（RenderEffect）")
                }.onFailure { XLog.i("模糊不可用，仅显示雾面：${it.javaClass.simpleName}") }
            }

            // 添加丝绸动效层
            val v = melt ?: KeyboardMeltView(decor.context).also { melt = it }
            if (v.parent == null) {
                (decor as ViewGroup).addView(
                    v,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            val decLoc = IntArray(2); decor.getLocationInWindow(decLoc)
            val ivLoc = IntArray(2); iv.getLocationInWindow(ivLoc)
            val rangeTop = (ivLoc[1] - decLoc[1]).toFloat()
            val rangeBottom = rangeTop + iv.height
            v.setFrostTint(frostColor(decor.context))
            v.setKeyboardRange(rangeTop, rangeBottom)
            v.fadeIn()
            melted = true
            hits++
            if (hits <= 6) {
                XLog.i(
                    "键盘已融化（第 $hits 次）decor=${decor.width}x${decor.height}@${decLoc[1]} " +
                        "iv=${iv.javaClass.simpleName} ${iv.width}x${iv.height}@${ivLoc[1]} " +
                        "-> 范围 $rangeTop..$rangeBottom"
                )
            }
        } catch (t: Throwable) {
            XLog.e("融化动效失败，复位状态", t)
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) inputView?.setRenderEffect(null)
            }
            blurred = false
            melted = false
        }
    }

    private fun restore() {
        if (!melted) return
        melted = false
        val v = melt ?: return
        v.reset()
        runCatching {
            if (blurred && Build.VERSION.SDK_INT >= 31) inputView?.setRenderEffect(null)
        }.onFailure { XLog.e("撤模糊失败", it) }
        blurred = false
        v.fadeOut { if (!melted) (v.parent as? ViewGroup)?.removeView(v) }
    }

    // 计算底色
    private fun frostColor(context: android.content.Context): Int {
        val night = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val a = (FROST_ALPHA * 255).toInt()
        return if (night) android.graphics.Color.argb(232, 0x10, 0x12, 0x1B)
        else android.graphics.Color.argb(a, 0xF5, 0xF6, 0xFA)
    }

    // 获取键盘根视图
    private fun findInputView(decor: View): View? {
        val res = decor.resources
        val rootId = res.getIdentifier("layout_base_frame_root", "id", KB_PKG)
        if (rootId != 0) {
            decor.findViewById<View>(rootId)?.let { return it }
        }
        // 兜底取最高的视图
        val root = decor as? ViewGroup ?: return null
        var best: View? = null
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (best == null || c.height > best.height) best = c
        }
        return best
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}
