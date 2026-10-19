package com.keyscafe.unlock

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import io.github.libxposed.api.XposedModule

/** 智能建议（SmartSuggestions）验证码拦截与广播 Hook */
object SmartSuggestionsHooks {

    private const val LOCAL_PKG_MANAGER_CLASS =
        "com.samsung.android.smartsuggestions.service.util.LocalPackageManager"
    private const val EXTRACTION_HELPER_CLASS =
        "com.samsung.android.smartsuggestions.service.entity.entityTextExtraction.EntityExtractionHelper"
    private const val NOTIFICATION_LISTENER_CLASS =
        "com.samsung.android.moneta.datacollector.application.notification.PdeNotificationListenerService"

    private const val SAMSUNG_OTP_ACTION = "com.samsung.android.intent.action.SMART_SUGGESTIONS_OTP"
    private const val SAMSUNG_OTP_PERMISSION = "com.samsung.android.permission.SMART_SUGGESTIONS_OTP"
    private const val HONEYBOARD_PKG = "com.samsung.android.honeyboard"
    private const val SELF_PKG = "com.keyscafe.unlock"

    @Volatile
    private var lastExtractedCode: String? = null
    @Volatile
    private var lastExtractedTime = 0L

    fun install(module: XposedModule, classLoader: ClassLoader) {
        hookBrowserLimitation(module, classLoader)
        hookOtpExtraction(module, classLoader)
        hookNotificationListener(module, classLoader)
        XLog.i("SmartSuggestionsHooks: 就绪")
    }

    // 解除仅在浏览器前台时才监听短信的包名限制
    private fun hookBrowserLimitation(module: XposedModule, classLoader: ClassLoader) {
        val cls = Reflect.findClassIfExists(LOCAL_PKG_MANAGER_CLASS, classLoader) ?: return
        val m = cls.declaredMethods.firstOrNull {
            it.name == "isBrowserPackage" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
        } ?: return

        XLog.safe("SmartSuggestionsHooks.isBrowserPackage") {
            module.hook(m).intercept { chain ->
                if (ConfigStore.otpEnhance) {
                    val pkg = chain.getArg(0) as? String
                    if (pkg != HONEYBOARD_PKG && pkg != SELF_PKG) {
                        return@intercept true
                    }
                }
                chain.proceed()
            }
        }
    }

    // 替换原版 SCS 实体提取为本地解析器
    private fun hookOtpExtraction(module: XposedModule, classLoader: ClassLoader) {
        val cls = Reflect.findClassIfExists(EXTRACTION_HELPER_CLASS, classLoader) ?: return
        val m = cls.declaredMethods.firstOrNull {
            it.name == "extractOTP" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java
        } ?: return

        XLog.safe("SmartSuggestionsHooks.extractOTP") {
            module.hook(m).intercept { chain ->
                if (!ConfigStore.otpEnhance) return@intercept chain.proceed()
                val text = chain.getArg(0) as? String
                val parsed = text?.let { XyCore.parseOtp(it) }
                if (parsed != null) {
                    XLog.i("SmartSuggestions.extractOTP: 命中 [$parsed]")
                    return@intercept parsed
                }
                chain.proceed()
            }
        }
    }

    // 监听通知栏以捕获验证码
    private fun hookNotificationListener(module: XposedModule, classLoader: ClassLoader) {
        val cls = Reflect.findClassIfExists(NOTIFICATION_LISTENER_CLASS, classLoader) ?: return
        val m = cls.declaredMethods.firstOrNull {
            it.name == "onNotificationPosted" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == StatusBarNotification::class.java
        } ?: return

        XLog.safe("SmartSuggestionsHooks.onNotificationPosted") {
            module.hook(m).intercept { chain ->
                val r = chain.proceed()
                if (!ConfigStore.otpEnhance) return@intercept r

                val sbn = chain.getArg(0) as? StatusBarNotification ?: return@intercept r
                val pkg = sbn.packageName
                if (pkg == HONEYBOARD_PKG || pkg == SELF_PKG) return@intercept r

                val notif = sbn.notification ?: return@intercept r
                val extras = notif.extras ?: return@intercept r

                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                val content = listOfNotNull(title, text, bigText).joinToString("\n")

                val code = XyCore.parseOtp(content)
                if (code != null) {
                    val now = System.currentTimeMillis()
                    if (code == lastExtractedCode && (now - lastExtractedTime) < 3000L) {
                        return@intercept r
                    }
                    lastExtractedCode = code
                    lastExtractedTime = now

                    XLog.i("通知捕获验证码 [$code] pkg=$pkg")
                    val context = chain.thisObject as? Context ?: return@intercept r

                    // 1. 发送标准三星系统广播
                    val samsungIntent = Intent(SAMSUNG_OTP_ACTION).apply {
                        setPackage(HONEYBOARD_PKG)
                        putExtra("SMART_SUGGESTIONS_OTP", code)
                        putExtra("SMART_SUGGESTIONS_OTP_ICON", notif.smallIcon)
                        putExtra("android.intent.extra.INTENT", notif.contentIntent)
                    }
                    context.sendBroadcast(samsungIntent, SAMSUNG_OTP_PERMISSION)

                    // 2. 发送内部直推广播
                    val injectIntent = Intent(OtpCandidateHooks.ACTION_OTP_INJECT).apply {
                        setPackage(HONEYBOARD_PKG)
                        putExtra("code", code)
                        putExtra("icon", notif.smallIcon)
                        putExtra("intent", notif.contentIntent)
                        putExtra(LmCandidateHooks.EXTRA_BROADCAST_TOKEN, ConfigStore.currentBroadcastToken())
                    }
                    context.sendBroadcast(injectIntent)
                }

                r
            }
        }
    }
}
