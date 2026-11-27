package com.tiantong.spark.shell

import android.app.Application
import com.tiantong.spark.app.config.ConfigClient
import com.tiantong.spark.app.provider.ConfigContract

/**
 * 模块自己的进程入口（不是抖音进程）。
 *
 * 这里只做两件事：
 *  1. 把 `ConfigProvider` 的 authority 绑定成「本包名 + .config」；
 *  2. 告知宿主侧「模块界面还活着」（用于「模块 App 被系统停用」的判定）。
 */
class SparkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ConfigContract.install(packageName)
        ConfigClient.touchHost(this, System.currentTimeMillis())
    }
}
