package com.tiantong.spark.app.ui.v2.nav

import android.app.Activity
import android.content.Context
import com.tiantong.spark.app.ui.v2.state.AppState

/**
 * 页面能对外做的事，全部收在这里。
 *
 * 页面不持有 Activity，也拿不到别的页面 —— 想跳转就找 [go]，
 * 想编辑就找 [openEditor]，想让外壳重画就找 [rebuild]。
 */
interface Shell {

    val activity: Activity

    val context: Context

    val state: AppState

    fun go(page: Page)

    /** 打开脚本编辑（[conversationId] 与 [templateId] 二选一，都给就是会话优先）。 */
    fun openEditor(conversationId: String? = null, templateId: String? = null)

    /** 立即发送给这些会话。 */
    fun sendNow(ids: List<String>)

    /** 向宿主请求刷新会话快照。 */
    fun refreshFromHost()

    /** 数据变了，重画当前页。 */
    fun rebuild()

    /** 轻提示。 */
    fun say(message: CharSequence)
}
