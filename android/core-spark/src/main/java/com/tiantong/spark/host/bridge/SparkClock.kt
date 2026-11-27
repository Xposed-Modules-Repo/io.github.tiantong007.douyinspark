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
 * 日期时间。每次调用都新建 [SimpleDateFormat] —— 原来是共享实例，
 * 多线程下（宿主进程里探测线程与发送线程同时在跑）会串日期。
 */

internal fun dayKey(): String = dayKey(Date())

internal fun dayKey(at: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(at)

internal fun hhmm(at: Date): String = SimpleDateFormat("HH:mm", Locale.ROOT).format(at)
