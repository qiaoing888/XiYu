package com.keyscafe.unlock

import android.app.Activity
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference

/** 设置页实验室入口控制 */
object SettingsHooks {

    private val LAB_ROW_KEY get() = XyCore.get(16)
    private val SETTINGS_ACTIVITY get() = XyCore.get(18)

    @Volatile
    private var currentActivityRef: WeakReference<Activity>? = null

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val labKey = LAB_ROW_KEY
        XLog.safe("SettingsHooks.labRow") {
            val spImpl = Class.forName("android.app.SharedPreferencesImpl")
            val getBooleanMethod = spImpl.getDeclaredMethod("getBoolean", String::class.java, java.lang.Boolean.TYPE)
            module.hook(getBooleanMethod).intercept { chain ->
                val key = chain.getArg(0) as? String
                if (key == labKey) {
                    ConfigStore.lab
                } else {
                    chain.proceed()
                }
            }
        }
        hookSettingsActivity(module, classLoader)
        ConfigStore.onChanged {
            val act = currentActivityRef?.get() ?: return@onChanged
            act.runOnUiThread {
                updateLabPreference(act, labKey)
            }
        }
        XLog.i("实验室入口行已接管")
    }

    private fun hookSettingsActivity(module: XposedModule, classLoader: ClassLoader) {
        val actName = SETTINGS_ACTIVITY
        val labKey = LAB_ROW_KEY
        val cls = Reflect.findClassIfExists(actName, classLoader) ?: run {
            XLog.i("设置页未就绪，重进生效")
            return
        }
        XLog.safe("SettingsHooks.activity") {
            Reflect.methodInHierarchy(cls, "onCreate", android.os.Bundle::class.java)?.let { onCreate ->
                module.hook(onCreate).intercept { chain ->
                    ConfigStore.reload()
                    val activity = chain.thisObject as? Activity
                    if (activity != null) {
                        currentActivityRef = WeakReference(activity)
                        ConfigStore.registerReceiver(activity)
                        updateLabPreference(activity, labKey)
                    }
                    chain.proceed()
                }
            }
            Reflect.methodInHierarchy(cls, "onResume")?.let { onResume ->
                module.hook(onResume).intercept { chain ->
                    ConfigStore.reload()
                    val activity = chain.thisObject as? Activity
                    if (activity != null) {
                        currentActivityRef = WeakReference(activity)
                        updateLabPreference(activity, labKey)
                    }
                    chain.proceed()
                }
            }
            Reflect.methodInHierarchy(cls, "onDestroy")?.let { onDestroy ->
                module.hook(onDestroy).intercept { chain ->
                    val activity = chain.thisObject as? Activity
                    if (activity != null && currentActivityRef?.get() === activity) {
                        currentActivityRef = null
                    }
                    chain.proceed()
                }
            }
        }
    }

    private fun updateLabPreference(activity: Activity, labKey: String) {
        runCatching {
            val sp = activity.getSharedPreferences(activity.packageName + "_preferences", Activity.MODE_PRIVATE)
            sp.edit().putBoolean(labKey, ConfigStore.lab).apply()
        }
        runCatching {
            val frag = Reflect.field(activity, "fragment")
                ?: (activity as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager?.findFragmentByTag("phone_fragment")
            if (frag != null) {
                Reflect.call(frag, "updatePreference", labKey)
            }
        }
    }
}
