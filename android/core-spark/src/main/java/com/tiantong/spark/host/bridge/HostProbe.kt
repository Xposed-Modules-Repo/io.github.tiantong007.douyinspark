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

internal fun wakeModule(context: Context) {
    ConfigClient.touchHost(context, System.currentTimeMillis())
    runCatching {
        context.sendBroadcast(
            Intent(ConfigContract.ACTION_WAKE)
                .setPackage(MODULE_PACKAGE)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
        )
    }.onFailure { ModLog.e("唤醒模块进程失败", it) }
    Thread.sleep(WAKE_WAIT_MS)
    if (ConfigClient.read(context) != null) return
    // 只有确认模块 App 处于「停用」状态才提示；进程忙、IM 未就绪等其它原因不打扰
    if (!isModuleStopped(context)) {
        ModLog.w("模块配置不可读：模块 App 仍在运行，只是这一轮没读到")
        return
    }
    ModLog.w("模块 App 处于停用状态，配置不可读")
    val today = dayKey(Date())
    // 今天已经自动跑过就不用提示（没有要发的东西）
    if (lastRunDay(context) == today) return
    hintModuleStoppedOncePerDay(context, today)
}

/** 模块 App 是否被系统置为「停用」（覆盖安装 / force-stop 之后）。 */

internal fun isModuleStopped(context: Context): Boolean =
    runCatching {
        val flags = context.packageManager.getApplicationInfo(MODULE_PACKAGE, 0).flags
        (flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
    }.getOrDefault(false)

private fun stoppedHintDayFile(context: Context) = java.io.File(context.filesDir, "spark_module_stopped_hint_day")

/** 确认停用后，提示用户打开一次；每天最多一次，避免反复打扰。 */

internal fun hintModuleStoppedOncePerDay(
    context: Context,
    today: String,
) {
    val file = stoppedHintDayFile(context)
    if (runCatching { file.readText().trim() }.getOrDefault("") == today) return
    runCatching { file.writeText(today) }
    com.tiantong.spark.core.DouToastHelper.show(
        context,
        "（抖音续火花cc：模块 App 被系统停用，请打开一次）",
    )
}

/** 手动刷新一次并通知界面（广播接收线程）。 */

fun scheduleOnAppStart() {
    val context = HostRuntime.context ?: return
    // 只在设定时间检查：不在窗口内直接返回，不白跑 60 秒的 IM 探测
    if (!shouldCheckNow(context)) {
        ModLog.i("不在检查时间窗内，本次不检查")
        return
    }
    Thread(
        {
            wakeModule(context)
            HostTrace.start(context, "已安排：探测 IM 就绪，就绪即发送")
            runCatching {
                Thread.sleep(START_DELAY_MS)
                // 冷启动时 IM 可能还没就绪，会话列表取不到就退避重试
                var attempt = 0
                // 探测用轻量模式：只确认「IM 能吐会话」，不查昵称头像
                var outcome = refreshSnapshot(context, light = true)
                while (!outcome.ok) {
                    attempt++
                    // 探测很密，每 4 次记一条，避免把宿主轨迹刷满
                    if (attempt % 4 == 0) {
                        HostTrace.add(context, "第 $attempt 次探测未就绪：${outcome.detail}")
                    }
                    if (attempt >= MAX_ATTEMPTS) {
                        HostTrace.add(context, "探测 $MAX_ATTEMPTS 次仍未就绪，本次跳过")
                        ModLog.i("会话列表未就绪，本次不续火花")
                        // 报结果：不能只弹「正在检测」就没下文
                        com.tiantong.spark.core.DouToastHelper.show(
                            context,
                            "（抖音续火花cc：IM 未就绪，本次已跳过）",
                        )
                        return@runCatching
                    }
                    Thread.sleep(RETRY_DELAY_MS)
                    outcome = refreshSnapshot(context, light = true)
                }
                HostTrace.add(context, "IM 已就绪（探测 $attempt 次），快照已写入")
                // 就绪后再扫一次全量：把对方昵称 / 备注 / 头像补齐（探测那次是轻量的）
                refreshSnapshot(context)
                val summary = runRenew(context, respectEnabled = true)
                if (summary.optBoolean("ok")) {
                    markRunDay(context, dayKey(Date()))
                    val line =
                        "续火花结果：已发 ${summary.optJSONArray("sent")?.length() ?: 0} / " +
                            "跳过 ${summary.optJSONArray("skipped")?.length() ?: 0} / " +
                            "失败 ${summary.optJSONArray("failed")?.length() ?: 0}"
                    HostTrace.add(context, line)
                    ModLog.i(line)
                } else {
                    val reason = summary.optString("error").ifBlank { "未知原因" }
                    HostTrace.add(context, "续火花未执行：$reason")
                    ModLog.i("自动续火花未执行：$reason")
                    com.tiantong.spark.core.DouToastHelper.show(context, "（抖音续火花cc：$reason）")
                }
            }.onFailure {
                HostTrace.add(context, "执行异常：${it.message ?: it.toString()}")
                ModLog.e("自动续火花执行异常", it)
                com.tiantong.spark.core.DouToastHelper.show(context, "（抖音续火花cc：执行异常）")
            }
        },
        "spark-autospark",
    ).apply { isDaemon = true }.start()
}
