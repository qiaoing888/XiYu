package com.keyscafe.unlock

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.inputmethodservice.InputMethodService
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/** 三星键盘候选栏验证码直推 Hook */
object OtpCandidateHooks {

    private const val CANDY_OTP_RECEIVER_CLASS =
        "com.samsung.android.honeyboard.base.broadcastreceiver.broadcastreceiverlist.CandyOtpReceiver"
    private const val DEEP_SKY_GATE_CLASS = "dk.b"
    private const val IME_SERVICE_CLASS_NAME = "com.samsung.android.honeyboard.service.HoneyBoardService"

    const val ACTION_OTP_INJECT = "com.keyscafe.unlock.ACTION_OTP_INJECT"

    private var cachedClassLoader: ClassLoader? = null
    private var internalReceiver: BroadcastReceiver? = null
    private var methodE: Method? = null
    private var methodG: Method? = null

    fun install(module: XposedModule, classLoader: ClassLoader) {
        cachedClassLoader = classLoader

        hookDeepSkyGate(module, classLoader)
        hookCandyOtpReceiver(module, classLoader)
        hookHoneyBoardService(module, classLoader)

        XLog.i("OtpCandidateHooks: 就绪")
    }

    // 绕过 DeepSky 智能建议开关检查，避免关闭智能建议时丢弃验证码
    private fun hookDeepSkyGate(module: XposedModule, classLoader: ClassLoader) {
        val gateCls = Reflect.findClassIfExists(DEEP_SKY_GATE_CLASS, classLoader) ?: return
        val m = gateCls.declaredMethods.firstOrNull {
            it.name == "b" && it.parameterTypes.isEmpty() && it.returnType == java.lang.Boolean.TYPE
        } ?: return

        XLog.safe("OtpCandidateHooks.deepSkyGate") {
            module.hook(m).intercept { chain ->
                if (ConfigStore.otpEnhance || ConfigStore.calcEnabled) true else chain.proceed()
            }
        }
    }

    // 拦截广播并直推候选栏
    private fun hookCandyOtpReceiver(module: XposedModule, classLoader: ClassLoader) {
        val receiverCls = Reflect.findClassIfExists(CANDY_OTP_RECEIVER_CLASS, classLoader) ?: return
        val m = receiverCls.declaredMethods.firstOrNull {
            it.name == "i" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Intent::class.java
        } ?: return

        XLog.safe("OtpCandidateHooks.candyReceiver") {
            module.hook(m).intercept { chain ->
                val intent = chain.getArg(0) as? Intent
                if (ConfigStore.otpEnhance && intent != null) {
                    val code = intent.getCharSequenceExtra("SMART_SUGGESTIONS_OTP")?.toString()
                    if (!code.isNullOrBlank() && code != "null") {
                        val receiver = chain.thisObject
                        runCatching {
                            val e = methodE ?: receiver.javaClass.getDeclaredMethod("e", Intent::class.java).apply {
                                isAccessible = true
                                methodE = this
                            }
                            val g = methodG ?: receiver.javaClass.getDeclaredMethod("g").apply {
                                isAccessible = true
                                methodG = this
                            }
                            val data = e.invoke(receiver, intent)
                            val updater = g.invoke(receiver)
                            if (data != null && updater != null) {
                                Reflect.call(updater, "a", data)
                                XLog.i("CandyOtpReceiver: 直推候选成功")
                                return@intercept null
                            }
                        }.onFailure { XLog.e("CandyOtpReceiver 分发失败", it) }
                    }
                }
                chain.proceed()
            }
        }
    }

    // 挂钩输入法服务生命周期
    private fun hookHoneyBoardService(module: XposedModule, classLoader: ClassLoader) {
        val serviceCls = Reflect.findClassIfExists(IME_SERVICE_CLASS_NAME, classLoader) ?: return
        val onCreate = Reflect.methodInHierarchy(serviceCls, "onCreate")
        val onDestroy = Reflect.methodInHierarchy(serviceCls, "onDestroy")

        if (onCreate != null) {
            XLog.safe("OtpCandidateHooks.serviceOnCreate") {
                module.hook(onCreate).intercept { chain ->
                    val r = chain.proceed()
                    (chain.thisObject as? InputMethodService)?.let {
                        ConfigStore.registerReceiver(it)
                        registerReceiver(it)
                    }
                    r
                }
            }
        }

        if (onDestroy != null) {
            XLog.safe("OtpCandidateHooks.serviceOnDestroy") {
                module.hook(onDestroy).intercept { chain ->
                    (chain.thisObject as? InputMethodService)?.let { unregisterReceiver(it) }
                    chain.proceed()
                }
            }
        }
    }

    // Android 14+ 可拿到真实发送包名；三星建议服务没有我们的令牌，但 UID 不可伪造
    private fun isTrustedOtpSender(receiver: BroadcastReceiver): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return when (receiver.sentFromPackage) {
            "com.samsung.android.smartsuggestions",
            "com.samsung.android.rubin.app",
            "com.samsung.android.honeyboard" -> true
            else -> false
        }
    }

    private fun registerReceiver(context: Context) {
        if (internalReceiver != null) return
        // 没有令牌就不注册导出接收器，宁可直推功能不可用，也不接受未认证广播
        if (ConfigStore.currentBroadcastToken().isEmpty()) {
            XLog.e("OTP 直推令牌不可用，跳过接收器注册", null)
            return
        }
        runCatching {
            val filter = IntentFilter(ACTION_OTP_INJECT)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (!ConfigStore.otpEnhance || intent == null) return
                    // 令牌校验失败时，只放行三星自己的建议/键盘进程（UID/包名不可伪造）
                    if (!ConfigStore.isTrustedBroadcast(intent) && !isTrustedOtpSender(this)) return
                    val code = intent.getStringExtra("code")
                        ?: intent.getCharSequenceExtra("SMART_SUGGESTIONS_OTP")?.toString()
                        ?: return
                    if (code.isBlank() || code == "null" || code.length > 64) return
                    if (code.any { it.isISOControl() }) return

                    val icon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("icon", Icon::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra("icon")
                    }

                    val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("intent", PendingIntent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra("intent")
                    }

                    XLog.i("收到直推验证码: $code")
                    postOtp(cachedClassLoader ?: context.classLoader, code, icon, pi)
                }
            }

            // 发送方是三星键盘进程自身，不能用本应用的签名权限限制
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            internalReceiver = receiver
            XLog.i("已注册直推通道 ($ACTION_OTP_INJECT)")
        }.onFailure { XLog.e("注册直推通道失败", it) }
    }

    private fun unregisterReceiver(context: Context) {
        internalReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
            internalReceiver = null
            XLog.i("已注销直推通道")
        }
    }

    private var cachedCtor: java.lang.reflect.Constructor<*>? = null

    fun postOtp(
        classLoader: ClassLoader,
        code: String,
        icon: Icon? = null,
        pendingIntent: PendingIntent? = null
    ) {
        runCatching {
            val koinCls = Reflect.findClassIfExists(XyCore.get(10), classLoader) ?: return
            val updaterInterface = Reflect.findClassIfExists("ap.g", classLoader) ?: return
            val updater = Reflect.callStatic(koinCls, XyCore.get(11), updaterInterface) ?: return
            val dataCls = Reflect.findClassIfExists("ap.f", classLoader) ?: return
            val ctor = cachedCtor ?: dataCls.getDeclaredConstructor(
                String::class.java,
                Icon::class.java,
                PendingIntent::class.java
            ).apply {
                isAccessible = true
                cachedCtor = this
            }
            val data = ctor.newInstance(code, icon, pendingIntent)
            Reflect.call(updater, "a", data)
            XLog.i("postOtp: 上屏成功 [$code]")
        }.onFailure {
            XLog.e("postOtp 失败", it)
        }
    }
}
