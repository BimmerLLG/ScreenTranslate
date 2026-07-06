package com.screentranslate

import android.app.Application
import android.os.Build
import com.screentranslate.logger.L

/**
 * App - 应用程序入口类
 * 负责初始化全局配置和日志系统
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 保存全局应用实例
        instance = this
        // 初始化日志系统
        L.init(this)
        L.d("App", "设备: ${Build.MANUFACTURER} ${Build.MODEL}, SDK: ${Build.VERSION.SDK_INT}, 目标SDK: ${Build.VERSION_CODES::class.java.fields.size}")
        L.d("App", "应用版本: ${packageManager.getPackageInfo(packageName, 0).versionName}")
        L.d("App", "Application created")
    }

    companion object {
        // 全局应用实例，可通过 App.instance 访问
        lateinit var instance: App
            private set
    }
}
