package com.keyscafe.unlock

import android.app.Application

/** 汐语主 Application */
class XyApp : Application() {
    companion object {
        lateinit var instance: XyApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 注册框架服务监听
        Prefs.bind()
    }
}
