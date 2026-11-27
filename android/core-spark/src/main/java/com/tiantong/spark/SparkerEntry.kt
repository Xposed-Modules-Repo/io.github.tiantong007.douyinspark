package com.tiantong.spark

import android.app.Activity
import android.app.Application
import android.content.Context
import com.tiantong.spark.core.HostRuntime
import com.tiantong.spark.core.ModLog
import com.tiantong.spark.host.bridge.installRefreshReceiver
import com.tiantong.spark.host.bridge.scheduleOnAppStart
import com.tiantong.spark.app.provider.ConfigContract
import com.tiantong.spark.host.bridge.MODULE_PACKAGE
import com.tiantong.spark.host.bridge.startDayWatcher
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 续火花的核心钩子逻辑。
 *
 * `ModuleMain : XposedModule` 只做一件事：把生命周期回调转发到这里。
 */
object SparkerEntry {

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    @Volatile
    private var appContext: Context? = null

    private val installed = AtomicBoolean(false)
    private val attached = AtomicBoolean(false)

    fun onModuleLoaded(module: XposedInterface, processName: String) {
        ModLog.bind(module)
        HostRuntime.module = module
        HostRuntime.processName = processName
        ModLog.i("onModuleLoaded: $processName")
    }

    fun onPackageReady(module: XposedInterface, packageName: String, classLoader: ClassLoader) {
        if (HostRuntime.processName != packageName) return
        if (!installed.compareAndSet(false, true)) return

        ModLog.i("onPackageReady: $packageName")
        // 抖音进程没有 SparkApp，authority 必须在这里绑定一次，否则读模块配置全失败。
        ConfigContract.install(MODULE_PACKAGE)
        hostClassLoader = classLoader
        HostRuntime.classLoader = classLoader

        hookApplicationAttach(module)
        hookSettingsPage(module, classLoader)
    }

    /** 热重载前保存的状态，交给 [onHotReloaded] 恢复。 */
    fun saveState(): Array<Any?> = arrayOf(appContext, hostClassLoader)

    fun onHotReloaded(module: XposedInterface, saved: Array<*>, processName: String) {
        ModLog.bind(module)
        HostRuntime.module = module

        val context = saved.getOrNull(0) as? Context
        val classLoader = saved.getOrNull(1) as? ClassLoader

        appContext = context
        if (context != null) HostRuntime.context = context
        if (classLoader != null) {
            hostClassLoader = classLoader
            HostRuntime.classLoader = classLoader
            hookSettingsPage(module, classLoader)
        }
        HostRuntime.processName = processName
        ModLog.i("热重载完成")
    }

    /** 宿主 Application attach 后取 Context 并安排开屏续火花（只取第一次 attach）。 */
    private fun hookApplicationAttach(module: XposedInterface) {
        val attach =
            runCatching { Application::class.java.getDeclaredMethod("attach", Context::class.java) }
                .onFailure { ModLog.e("Application.attach 定位失败，跳过", it) }
                .getOrNull() ?: return
        module.hook(attach).intercept { chain ->
            val first = attached.compareAndSet(false, true)
            val raw = chain.getArg(0) as Context
            try {
                // 先让 attach 跑完：attach 完成前 ContextWrapper.mBase 还是 null，
                // 这时去取 applicationContext 会 NPE（跨天值守就是这么挂不上的）
                chain.proceed()
            } finally {
                if (first) {
                    val context = raw.applicationContext ?: raw
                    appContext = context
                    HostRuntime.context = context
                    runCatching { installRefreshReceiver(context) }
                        .onFailure { ModLog.e("刷新会话广播注册失败", it) }
                    runCatching { scheduleOnAppStart() }
                        .onFailure { ModLog.e("续火花任务安排失败", it) }
                    runCatching { (chain.thisObject as? Application)?.let { startDayWatcher(it) } }
                        .onFailure { ModLog.e("跨天值守挂载失败", it) }
                }
            }
        }
    }

    /** 设置页创建后注入「续火花」入口。 */
    private fun hookSettingsPage(module: XposedInterface, classLoader: ClassLoader) {
        val onCreate = SettingsInjector.targetCreateMethod(classLoader) ?: return
        module.hook(onCreate).intercept { chain ->
            chain.proceed()
            (chain.thisObject as? Activity)?.let { SettingsInjector.injectEntry(it) }
        }
    }
}
