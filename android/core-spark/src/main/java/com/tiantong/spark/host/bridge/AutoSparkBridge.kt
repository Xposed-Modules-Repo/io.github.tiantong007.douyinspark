package com.tiantong.spark.host.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.Toast
import com.tiantong.spark.core.HostRuntime
import com.tiantong.spark.core.ModLog
import com.tiantong.spark.host.account.AccountBridge
import com.tiantong.spark.app.config.BuiltinTemplates
import com.tiantong.spark.app.config.model.ConversationSnapshot
import com.tiantong.spark.app.config.SparkConfig
import com.tiantong.spark.app.config.ConfigClient
import com.tiantong.spark.host.im.AvatarCache
import com.tiantong.spark.host.im.EmojiImageCache
import com.tiantong.spark.app.provider.ConfigContract
import com.tiantong.spark.app.script.EmptyResultException
import com.tiantong.spark.app.script.SparkScriptEngine
import com.tiantong.spark.host.im.ImBridge
import com.tiantong.spark.app.config.EmojiCatalog
import com.tiantong.spark.host.im.EmojiCatalog as HostEmojiCatalog
import com.tiantong.spark.host.im.ImMsgBridge
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.Function
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import com.tiantong.spark.app.config.model.SandboxVar
import com.tiantong.spark.app.script.TemplateVarPrelude
import com.tiantong.spark.core.DouToastHelper

/**
 * 自动续火花（运行在抖音进程内）。
 *
 * 流程：读配置 → 扫描会话 → 取「需要续」的 → 用模板脚本渲染文案 → 发送 → 记录当日已发。
 * 文案来源：该会话的模板内容；无则用默认模板；再无则用 [DEFAULT_SCRIPT]。
 */

val DEFAULT_SCRIPT: String get() = BuiltinTemplates.GREETING.body

private val watcherHandler = android.os.Handler(android.os.Looper.getMainLooper())

/** 当前是否有可见 Activity（用 started 计数判断）。 */

private val visibleActivities = java.util.concurrent.atomic.AtomicInteger(0)

/** 值守是否已经挂上（避免重复注册生命周期回调）。 */

private val watcherInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

/** 自动任务互斥：上一轮没跑完就不再起新的。 */

private val autoRunning = java.util.concurrent.atomic.AtomicBoolean(false)

/** 发送互斥：启动补跑 / 前台补跑 / 手动发送之间只允许一轮在跑，避免同一会话发两条。 */

private var lastHostTouchMs = 0L

@Volatile

internal var lastAutoRunMs = 0L

/** 回到前台时刷新 hostAliveAt。 */

private fun touchHostIfStale(context: Context) {
    val now = System.currentTimeMillis()
    if (now - lastHostTouchMs < HOST_TOUCH_INTERVAL_MS) return
    lastHostTouchMs = now
    ConfigClient.touchHost(context, now)
}

private var watcherContext: Context? = null

/** 「上次自动跑完的日期」落在抖音自己的目录里，跟着抖音的数据走。 */

private fun lastRunFile(context: Context) = java.io.File(context.filesDir, "spark_last_auto_day")

internal fun lastRunDay(context: Context): String =
    runCatching { lastRunFile(context).readText().trim() }.getOrDefault("")

internal fun markRunDay(
    context: Context,
    day: String,
) {
    runCatching { lastRunFile(context).writeText(day) }
}

/** 闹钟时间窗：设定点前 10 分钟到后 6 小时，命中就算「现在是该检查的时候」。 */

internal fun shouldCheckNow(context: Context): Boolean {
    val config = ConfigClient.read(context) ?: return true
    if (!config.checkAtAlarmOnly) return true
    if (config.alarmHour !in 0..23) return true
    val target =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, config.alarmHour)
            set(Calendar.MINUTE, config.alarmMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    val diff = System.currentTimeMillis() - target
    return diff >= -ALARM_WINDOW_BEFORE_MS && diff <= ALARM_WINDOW_AFTER_MS
}

/**
 * 挂上值守：抖音有 Activity 可见时按时间自动升级检查频率。
 *
 * 平时不轮询 —— 只在 23:30 之后才密集检查，白天几乎零开销。
 */

fun startDayWatcher(application: android.app.Application) {
    if (!watcherInstalled.compareAndSet(false, true)) return
    val context = application.applicationContext ?: application
    watcherContext = context
    application.registerActivityLifecycleCallbacks(
        object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit

            override fun onActivityPaused(activity: android.app.Activity) = Unit

            override fun onActivityStarted(activity: android.app.Activity) {
                val enteredForeground = visibleActivities.getAndIncrement() == 0
                // 回到前台：刷新心跳；从后台切回来时扫一轮，页内切换只刷快照
                Thread {
                    touchHostIfStale(context)
                    if (enteredForeground) scanOnForeground(context) else pushSnapshotIfStale(context)
                    autoRunIfDayChanged(context)
                }.start()
                rescheduleWatcher()
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                if (visibleActivities.decrementAndGet() <= 0) {
                    visibleActivities.set(0)
                    watcherHandler.removeCallbacksAndMessages(null)
                }
            }

            override fun onActivitySaveInstanceState(
                activity: android.app.Activity,
                outState: android.os.Bundle,
            ) = Unit

            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        },
    )
}

/** 按当前时间和可见性重排下一次检查（这就是"每次唤醒界面检查条件、切换定时器"）。 */

private fun rescheduleWatcher() {
    val context = watcherContext ?: return
    watcherHandler.removeCallbacksAndMessages(null)
    if (visibleActivities.get() <= 0) return
    watcherHandler.postDelayed(
        {
            Thread { autoRunIfDayChanged(context) }.start()
            rescheduleWatcher()
        },
        watcherDelayMs(),
    )
}

/** 离零点越近检查越密：平时一次等到 23:30，之后每分钟，23:58 起每 5 秒。 */

private fun watcherDelayMs(): Long {
    val calendar = java.util.Calendar.getInstance()
    val minutes = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
    val lateWindow = 23 * 60 + 58
    val eveningStart = 23 * 60 + 30
    val earlyWindow = 5
    return when {
        // 零点前后：5 秒一次，抓住跨天的那一刻
        minutes >= lateWindow || minutes < earlyWindow -> 5_000L
        // 23:30 之后：每分钟
        minutes >= eveningStart -> 60_000L
        // 其余时间：一次性等到 23:30，中间不轮询
        else -> ((eveningStart - minutes).toLong() * 60_000L).coerceAtLeast(60_000L)
    }
}

/** 今天还没自动跑过就跑一轮（自动任务，遵守总开关）。 */

private fun autoRunIfDayChanged(context: Context) {
    val today = dayKey(Date())
    if (lastRunDay(context) == today) return
    if (!autoRunning.compareAndSet(false, true)) return
    try {
        // 先把快照写回模块 App：跨天补跑不需要用户打开 App 也能看到最新数据
        pushSnapshotToModule(context)
        val summary = runRenew(context, respectEnabled = true)
        if (summary.optBoolean("ok")) {
            markRunDay(context, today)
            lastAutoRunMs = System.currentTimeMillis()
            val sent = summary.optJSONArray("sent")?.length() ?: 0
            HostTrace.add(context, "跨天补跑完成：已发 $sent 个")
        }
        // 发完再刷一次：状态 / 已发记录都变了
        pushSnapshotToModule(context, attempts = 2)
    } catch (error: Throwable) {
        ModLog.e("跨天补跑失败", error)
    } finally {
        autoRunning.set(false)
    }
}

internal fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

/** 一次快照刷新的结果：[detail] 在成功时是会话数，失败时是原因。 */
data class RefreshOutcome(
    val ok: Boolean,
    val detail: String,
)
