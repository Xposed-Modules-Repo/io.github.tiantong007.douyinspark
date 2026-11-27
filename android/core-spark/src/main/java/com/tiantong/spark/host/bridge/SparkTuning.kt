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

/**
 * 各项超时与重试节奏。集中放在这里，调行为时不用在业务代码里翻常量。
 */

internal const val DEFAULT_LIMIT = 200

/** 打开抖音后稍等 IM 初始化；之后就靠探测（refreshSnapshot 成功即视为就绪）。 */

internal const val START_DELAY_MS = 5_000L

/** 探测间隔：取不到会话就等这么久再试，近似握手重试。 */

internal const val RETRY_DELAY_MS = 2_500L

/** 最多探测这么多次（约 5s + 22×2.5s ≈ 60s 封顶）。 */

internal const val MAX_ATTEMPTS = 22

/** 唤醒模块进程后等它就绪的时间。 */

internal const val WAKE_WAIT_MS = 2_000L

/** 模块 App 的 applicationId（广播 / Activity 跳转用）。 */

internal const val MODULE_PACKAGE = "io.github.tiantong007.douyinspark"

/** 空页面的全限定类名：代码包名与 applicationId 不同，不能拼。 */

internal const val SNAPSHOT_PUSH_INTERVAL_MS = 60_000L

/** 心跳刷新间隔：模块 App 用 hostAliveAt 判断抖音是否在运行。 */

internal const val HOST_TOUCH_INTERVAL_MS = 15_000L

/** 回前台扫描间隔：避免快速切换界面时重复扫描。 */

internal const val FOREGROUND_SCAN_INTERVAL_MS = 10_000L

/** 自动任务刚跑完的这段时间内，回前台不再重复扫描。 */

internal const val FOREGROUND_SCAN_AFTER_AUTO_MS = 60_000L

internal const val ALARM_WINDOW_AFTER_MS = 6L * 60 * 60 * 1000

internal const val ALARM_WINDOW_BEFORE_MS = 10L * 60 * 1000

/**
 * 该不该跑这一轮检查。
 *
 * 「只在设定时间检查」开着时，只有落在 [SparkConfig.alarmHour]:[SparkConfig.alarmMinute]
 * 附近才跑，避免用户每次打开抖音都白等一轮扫描（冷启动探测 IM 要 5 秒起）。
 *
 * 三种情况一律放行：开关关着 / 没设闹钟时间 / 配置读不到。
 */
