package com.keyscafe.unlock

import android.annotation.SuppressLint
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/** 模块入口 */
class UnlockHook : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        XLog.i("模块已加载 process=${param.processName}")
    }

    @SuppressLint("NewApi")
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val cl = param.defaultClassLoader
        val pkg = param.packageName
        when {
            pkg == XyCore.get(14) -> {
                XLog.i("注入三星键盘")
                ConfigStore.init(this)
                VoiceInputHooks.install(this, cl)
                SettingsHooks.install(this, cl)
                DevModeHooks.install(this, cl)
                VoiceUiHooks.install(this, cl)
                OtpCandidateHooks.install(this, cl)
                CalculatorHooks.install(this, cl)
                LmCandidateHooks.install(this, cl)
                CandidateAlignHooks.install(this, cl)
            }
            pkg == XyCore.get(15) -> {
                XLog.i("注入 Keys Cafe")
                ConfigStore.init(this)
                KeysCafeHooks.install(this, cl)
            }
            pkg == XyCore.get(21) -> {
                XLog.i("注入三星智能建议服务")
                ConfigStore.init(this)
                SmartSuggestionsHooks.install(this, cl)
            }
        }
    }
}
