package com.tiantong.spark.shell

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import com.tiantong.spark.SparkerEntry

/**
 * 模块入口。
 *
 * 全部注入逻辑在 [SparkerEntry]（`:core-spark`）里，本类只做生命周期转发。
 */
class ModuleMain : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        SparkerEntry.onModuleLoaded(this, param.processName)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        SparkerEntry.onPackageReady(this, param.packageName, param.classLoader)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        param.setSavedInstanceState(SparkerEntry.saveState())
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        param.oldHookHandles.forEach { it.unhook() }
        val saved: Array<*> = (param.savedInstanceState as? Array<*>) ?: emptyArray<Any?>()
        SparkerEntry.onHotReloaded(this, saved, param.processName)
    }
}
