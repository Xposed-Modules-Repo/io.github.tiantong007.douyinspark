package com.tiantong.spark.host.bridge

import android.content.Context
import com.tiantong.spark.app.config.ConfigClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 宿主（抖音进程）侧运行简报。
 *
 * 本机的 LSPosed 日志与 logcat 都取不到模块内容，宿主进程又无法写模块 App 的私有目录，
 * 所以宿主只能把「自己做了什么、失败在哪一步」写回配置，由管理界面的「设置 → 宿主状态」展示。
 * 内容只保留最近 [MAX_LINES] 行，避免配置文件无限增长。
 */
object HostTrace {
    private const val MAX_LINES = 60

    private val lines = mutableListOf<String>()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    /**
     * 新的一轮宿主进程（Application.attach）开始时插一条分隔线。
     *
     * 以前这里是 `lines.clear()`：冷启动阶段那十几行诊断（IM 会话多少条、每条的火花字段）
     * 会被后面陆续写进来的发送日志挤掉，出问题回头就看不到「启动时到底读到了什么」。
     * 现在只插分隔线，靠 [MAX_LINES] 自然滚动，保底能看到最近两三轮。
     */
    @Synchronized
    fun start(
        context: Context,
        text: String,
    ) {
        if (lines.isNotEmpty()) {
            lines += "──── 新一轮 ────"
            while (lines.size > MAX_LINES) lines.removeAt(0)
        }
        add(context, text)
    }

    /** 追加一行简报并立刻写进配置。 */
    @Synchronized
    fun add(
        context: Context,
        text: String,
    ) {
        lines += format.format(Date()) + "  " + text
        while (lines.size > MAX_LINES) lines.removeAt(0)
        ConfigClient.note(context, lines.joinToString("\n"), System.currentTimeMillis())
    }
}
