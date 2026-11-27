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

private val refreshReceiverInstalled = AtomicBoolean(false)

private var refreshReceiver: BroadcastReceiver? = null

/**
 * 无任何模板时的兜底文案（模板页的「按时问候」内置模板用的就是这份）。
 * 注意：它**不会**自动生效——没写文案的会话一律不发。
 */

fun refreshSnapshot(context: Context, light: Boolean = false): RefreshOutcome {
    val fetched = fetchConversations(light)
    val conversations = fetched.conversations ?: return RefreshOutcome(false, fetched.error)
    if (conversations.length() == 0) return RefreshOutcome(false, "IM 会话列表为空（还没加载完）")
    val now = System.currentTimeMillis()
    val snapshots = mutableListOf<ConversationSnapshot>()
    for (index in 0 until conversations.length()) {
        val conversation = conversations.optJSONObject(index) ?: continue
        val spark = conversation.optJSONObject("spark")
        snapshots +=
            ConversationSnapshot(
                conversationId = conversation.optString("conversationId"),
                name = conversation.optString("name"),
                remark = conversation.optString("remarkName"),
                avatar = conversation.optString("peerAvatar"),
                peerUid = conversation.optString("peerUid"),
                days = spark?.optInt("days") ?: 0,
                state = spark?.optInt("state", -1) ?: -1,
                stateText = spark?.optString("stateText") ?: "",
                recoverDays = spark?.optInt("recoverDays") ?: 0,
                recoverNeedDays = spark?.optInt("recoverNeedDays") ?: 0,
                hasSpark = spark?.optBoolean("hasSpark") == true,
                needsRenewal = spark?.optBoolean("needsRenewal") == true,
                isGroup = conversation.optBoolean("isGroup"),
                updatedAt = now,
            )
    }
    traceConversations(context, fetched)
    val written = ConfigClient.putSnapshots(context, snapshots)
    if (!written) return RefreshOutcome(false, "快照写入配置失败（跨进程通道不可用）")
    // 顺带把头像推过去：模块不申请 INTERNET，只能用抖音已经缓存好的图片。
    // 失败不影响快照（拿不到就显示首字占位图），所以不改变 RefreshOutcome。
    runCatching {
        // 群聊没有 peerUid，退回用 conversationId 当 key（模块侧 AvatarLoader 用同一规则）
        AvatarCache.sync(
            context,
            snapshots
                .map { it.peerUid.ifBlank { it.conversationId } to it.avatar }
                .filter { it.first.isNotBlank() && it.second.isNotBlank() },
        )
    }.onFailure { ModLog.w("头像同步失败", it) }
    // 顺带把互动表情图也推过去（同样由宿主下载），预览页才能显示真实表情图。
    runCatching {
        EmojiImageCache.sync(context, HostEmojiCatalog.items(context).map { it.name to it.staticUrl })
    }.onFailure { ModLog.w("表情图同步失败", it) }
    // 快照落盘后按保留策略清理：不在已选目标里的会话，模板与发送记录按 30 天宽限期清掉
    ConfigClient.prune(context, now)
    return RefreshOutcome(true, "${snapshots.size} 个会话")
}

/**
 * 注册「刷新会话」与「立即发送」广播接收器（跑在抖音进程内）。
 *
 * 管理界面在模块进程发 [ConfigContract.ACTION_REFRESH_SNAPSHOT]，宿主收到后重新扫描会话，
 * 成功再回发 [ConfigContract.ACTION_SNAPSHOT_UPDATED] 通知界面刷新；
 * 发 [ConfigContract.ACTION_SEND_NOW] 则立即手动发送一轮，结果通过
 * [ConfigContract.ACTION_SEND_DONE] 回给界面。
 */

fun installRefreshReceiver(context: Context) {
    if (!refreshReceiverInstalled.compareAndSet(false, true)) return
    val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                received: Context?,
                intent: Intent?,
            ) {
                val action = intent?.action
                ConfigClient.touchHost(context, System.currentTimeMillis())
                when (action) {
                    ConfigContract.ACTION_PING_HOST -> {
                        // 探活：立刻回执（界面拿到回执才认为宿主在线）
                        runCatching { context.sendBroadcast(Intent(ConfigContract.ACTION_HOST_ALIVE)) }
                            .onFailure { ModLog.e("探活回执发送失败", it) }
                    }

                    ConfigContract.ACTION_SEND_NOW -> {
                        val targets = intent.getStringArrayListExtra(ConfigContract.EXTRA_TARGETS).orEmpty()
                        Thread({ sendNowAndNotify(context, targets) }, "spark-send").apply { isDaemon = true }.start()
                    }

                    else -> Thread({ refreshAndNotify(context) }, "spark-refresh").apply { isDaemon = true }.start()
                }
            }
        }
    refreshReceiver = receiver
    val filter =
        IntentFilter().apply {
            addAction(ConfigContract.ACTION_REFRESH_SNAPSHOT)
            addAction(ConfigContract.ACTION_SEND_NOW)
            addAction(ConfigContract.ACTION_PING_HOST)
        }
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }.onFailure { ModLog.e("刷新会话广播注册失败", it) }
}

/**
 * 手动发送一轮（界面点「立即发送」，或抖音设置页入口选「立即续火花」）。
 *
 * 不受总开关与「今日已发」限制：这是用户明确点的操作；[targets] 为空表示发给所有「已选目标」。
 * [toast] 为 true 时直接在宿主里弹提示（抖音设置页入口那条路径用，管理界面不在前台）。
 */

internal fun refreshAndNotify(context: Context) {
    wakeModule(context)
    val outcome = refreshSnapshot(context)
    HostTrace.add(context, if (outcome.ok) "手动刷新成功：${outcome.detail}" else "手动刷新失败：${outcome.detail}")
    if (!outcome.ok) return
    runCatching { context.sendBroadcast(Intent(ConfigContract.ACTION_SNAPSHOT_UPDATED)) }
        .onFailure { ModLog.e("快照更新广播发送失败", it) }
}

// ------------------------------------------------------------ 跨天值守

/** 上次把快照写回模块 App 的时间。前台切换很频繁，靠它限流。 */
@Volatile
private var lastSnapshotPushMs = 0L

/** 上次回前台扫描的时间。 */
@Volatile
private var lastForegroundScanMs = 0L

internal fun scanOnForeground(context: Context) {
    val now = System.currentTimeMillis()
    // 同 scheduleOnAppStart：不在检查时间窗内就不扫
    if (!shouldCheckNow(context)) return
    if (now - lastForegroundScanMs < FOREGROUND_SCAN_INTERVAL_MS) return
    if (now - lastAutoRunMs < FOREGROUND_SCAN_AFTER_AUTO_MS) return
    lastForegroundScanMs = now
    if (renewRunning.get()) return
    val outcome = refreshSnapshot(context)
    if (!outcome.ok) return
    val summary = runRenew(context, respectEnabled = true)
    if (summary.optBoolean("ok")) lastAutoRunMs = System.currentTimeMillis()
}

/**
 * 回到前台时刷新一下 App 里的快照（限流）。
 *
 * 以前只有「今天还没自动跑过」时才会刷，白天对方回复了、火花变成已续，
 * App 里还是早上那份旧数据（表现就是「明明已续了还算待对方回复」）。
 */

internal fun pushSnapshotIfStale(context: Context) {
    val now = System.currentTimeMillis()
    if (now - lastSnapshotPushMs < SNAPSHOT_PUSH_INTERVAL_MS) return
    lastSnapshotPushMs = now
    pushSnapshotToModule(context, attempts = 3)
}

/**
 * 把当前会话快照写回模块 App（就是界面里看到的那份缓存）。
 *
 * IM 没就绪就短退避重试几次；写入走的是 ContentProvider，
 * 所以**不需要用户打开 App**。
 */

internal fun pushSnapshotToModule(
    context: Context,
    attempts: Int = 6,
): Boolean {
    wakeModule(context)
    var attempt = 0
    while (true) {
        val outcome = refreshSnapshot(context)
        if (outcome.ok) {
            HostTrace.add(context, "快照已同步到 App：${outcome.detail}")
            return true
        }
        attempt++
        if (attempt >= attempts) {
            HostTrace.add(context, "快照同步失败：${outcome.detail}")
            return false
        }
        runCatching { Thread.sleep(RETRY_DELAY_MS) }
    }
}

/**
 * 扫描并发送一轮。
 *
 * @param respectEnabled 是否遵守总开关（自动触发时为 true，手动运行不考虑）
 * @param only 只处理这些会话（手动发送时用）；为空表示按抖音判定挑「需要续」的
 * @param ignoreSentToday 忽略「今日已发」记录（手动发送可以再发一次）
 */

internal fun loadConversations(): JSONArray? = fetchConversations().conversations

/** 单聊会话的火花数据摘进宿主简报：IM 返回了多少条、每条的火花字段与原始负载。 */

internal fun traceConversations(
    context: Context,
    fetched: Fetched,
) {
    val conversations = fetched.conversations ?: return
    HostTrace.add(context, "IM 会话 ${fetched.total} 条，进入快照的 ${conversations.length()} 条")
    for (index in 0 until conversations.length()) {
        val conversation = conversations.optJSONObject(index) ?: continue
        val name = conversation.optString("remarkName").ifBlank { conversation.optString("name") }
        val spark = conversation.optJSONObject("spark")
        val summary =
            if (spark == null) {
                "无火花数据"
            } else {
                "hasSpark=${spark.optBoolean("hasSpark")} state=${spark.optInt("state", -1)} " +
                    "days=${spark.optInt("days")} text=${spark.optString("stateText")} " +
                    "level=${spark.optString("level")} start=${spark.optLong("start")} end=${spark.optLong("end")} " +
                    "petElf=${spark.optBoolean("petElf")} relation=${spark.optString("relationType")} " +
                    "effect=${spark.optString("effectKind")}"
            }
        HostTrace.add(context, "$name · $summary")
        spark?.optString("raw")?.takeIf { it.isNotBlank() }?.let { raw ->
            HostTrace.add(context, "raw[$name]=${raw.take(700)}")
        }
    }
}

/** 会话列表 + 失败原因（失败原因会写进宿主简报，便于定位是哪一步断了）。 */

internal class Fetched(
    val conversations: JSONArray?,
    val error: String,
    val total: Int = 0,
)

internal fun fetchConversations(light: Boolean = false): Fetched =
    runCatching {
        val json =
            JSONObject(
                ImBridge.getConversationsJson(DEFAULT_LIMIT, withSpark = true, resolveUsers = !light),
            )
        if (json.optBoolean("ok")) {
            Fetched(json.optJSONArray("conversations"), "", json.optInt("total"))
        } else {
            Fetched(null, json.optString("error").ifBlank { "会话列表读取失败" })
        }
    }.getOrElse { Fetched(null, it.message ?: it.toString()) }

