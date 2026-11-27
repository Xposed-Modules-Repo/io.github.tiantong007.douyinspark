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

fun scriptFor(
    config: SparkConfig,
    conversationId: String,
): String {
    val body = config.conversation(conversationId)?.body?.trim().orEmpty()
    val macros = config.macrosFor(listOf(conversationId) + listOfNotNull(config.conversation(conversationId)?.inheritFrom))
    if (body.isEmpty() && macros.isEmpty()) return ""
    return config.composeProgram(body, macros)
}

/** 脚本可见的沙盒环境：归属、已有数据、会话列表。 */

internal fun sandboxEnv(
    context: Context,
    config: SparkConfig,
    conversationId: String,
    conversations: org.json.JSONArray,
    only: Set<String> = emptySet(),
    ignoreSentToday: Boolean = false,
): SparkScriptEngine.SandboxEnv =
    SparkScriptEngine.SandboxEnv(
        data = config.sandbox.groupBy { it.owner },
        conversations = conversationsJson(config, conversations, only, ignoreSentToday),
        mountTargets = mountTargetsJson(config, conversationId),
        templateInfo = templateInfoJson(config, conversationId),
        macroNames = macroNamesJson(config),
        info = infoJson(config, conversations, only, ignoreSentToday),
        // 表情目录：从抖音自己的 keva 策略里读出当前下发的表情（没读到就是空数组，脚本只能按名字发硬编码的那个）
        emojis = HostEmojiCatalog.json(context),
        preview = false,
    )

/** 宏 id → 名字（`ctx.macro.name` 用）。 */

internal fun macroNamesJson(config: SparkConfig): String {
    val names = JSONObject()
    config.macros.forEach { macro -> names.put(macro.id, macro.name) }
    return names.toString()
}

/** 一个会话在本轮里的三种发送口径（供脚本筛选）。 */

internal class SendFlags(
    /** 自动一轮会不会发它（按抖音判定 / 手动归属，且今天还没发）。 */
    val auto: Boolean,
    /** 手动一轮会不会发它（点名时只看点名，否则按候选；手动不受「今日已发」限制）。 */
    val manual: Boolean,
    /** 本轮实际会不会发（当前是哪种轮次就按哪种口径）。 */
    val will: Boolean,
)

/**
 * 算出会话的三种发送口径。
 *
 * [only] 非空 = 手动点名发送；[ignoreSentToday] = 这一轮不看「今日已发」（手动发送就是）。
 * 自动轮的口径和 [runRenew] 的候选筛选保持一致：群聊只有手动移进「已选目标」才发、
 * 强制移出的不发、其余看抖音的 needsRenewal。
 */

internal fun sendFlags(
    config: SparkConfig,
    id: String,
    isGroup: Boolean,
    spark: JSONObject?,
    sentToday: Boolean,
    only: Set<String>,
    ignoreSentToday: Boolean,
): SendFlags {
    val forced = id in config.forcedRenew
    val candidate =
        when {
            id in config.forcedSkip -> false
            isGroup -> forced
            else -> forced || (spark?.optBoolean("hasSpark") == true && spark.optBoolean("needsRenewal"))
        }
    val auto = candidate && !sentToday
    val manual = if (only.isNotEmpty()) id in only else candidate
    val manualRound = only.isNotEmpty() || ignoreSentToday
    val will = if (manualRound) manual && (ignoreSentToday || !sentToday) else auto
    return SendFlags(auto, manual, will)
}

/** `ctx.system.getAllConversation()` 的返回值（字段少而稳定，不把原始负载带进脚本）。 */

internal fun conversationsJson(
    config: SparkConfig,
    conversations: org.json.JSONArray,
    only: Set<String> = emptySet(),
    ignoreSentToday: Boolean = false,
): String {
    val array = org.json.JSONArray()
    val today = dayKey(Date())
    val selectedIds = config.selectedIds()
    for (index in 0 until conversations.length()) {
        val item = conversations.optJSONObject(index) ?: continue
        val spark = item.optJSONObject("spark")
        val id = item.optString("conversationId")
        val isGroup = item.optBoolean("isGroup")
        val selected = id in selectedIds
        val sentToday = config.sentDays[id] == today
        val flags = sendFlags(config, id, isGroup, spark, sentToday, only, ignoreSentToday)
        array.put(
            JSONObject()
                .put("id", id)
                .put("nickname", item.optString("name"))
                .put("name", displayName(item))
                .put("remark", item.optString("remarkName"))
                .put("peerUid", item.optString("peerUid"))
                // 标签功能已移除；保留空数组键，老脚本读 item.tags 不会拿到 undefined
                .put("tags", JSONArray())
                .put("days", spark?.optInt("days") ?: 0)
                .put("state", spark?.optInt("state", -1) ?: -1)
                .put("stateText", spark?.optString("stateText") ?: "")
                .put("isGroup", isGroup)
                .put("selected", selected)
                .put("sentToday", sentToday)
                .put("manualSend", flags.manual)
                .put("autoSend", flags.auto)
                .put("willSend", flags.will),
        )
    }
    return array.toString()
}

/** 这个会话要跑的宏名下的挂载目标（按宏名去重）。 */

internal fun mountTargetsJson(
    config: SparkConfig,
    conversationId: String,
): String {
    val targets = mutableSetOf<String>()
    val template = config.conversation(conversationId)
    config.macrosFor(listOf(conversationId) + listOfNotNull(template?.inheritFrom)).forEach { macro ->
        targets += macro.appliesTo
        targets += macro.templateOnly
    }
    return org.json.JSONArray(targets.toList()).toString()
}

/** 按 id 取信息的三张表：会话 / 模板 / 宏。 */

internal fun infoJson(
    config: SparkConfig,
    conversations: org.json.JSONArray,
    only: Set<String> = emptySet(),
    ignoreSentToday: Boolean = false,
): String {
    val convs = JSONObject()
    val today = dayKey(Date())
    val selectedIds = config.selectedIds()
    for (index in 0 until conversations.length()) {
        val item = conversations.optJSONObject(index) ?: continue
        val id = item.optString("conversationId")
        if (id.isEmpty()) continue
        val spark = item.optJSONObject("spark")
        val isGroup = item.optBoolean("isGroup")
        val sentToday = config.sentDays[id] == today
        val flags = sendFlags(config, id, isGroup, spark, sentToday, only, ignoreSentToday)
        val own = config.conversation(id)
        val parentId = own?.inheritFrom.orEmpty()
        val parentGroup = config.group(parentId)
        val parentBody =
            when {
                parentGroup != null -> parentGroup.body
                parentId == SparkConfig.BUILTIN_BLANK -> ""
                else -> BuiltinTemplates.byId(parentId.removePrefix("builtin:"))?.body.orEmpty()
            }
        val macroList = org.json.JSONArray()
        config.macrosFor(listOf(id) + listOfNotNull(parentId.takeIf { it.isNotEmpty() })).forEach { macro ->
            macroList.put(JSONObject().put("id", macro.id).put("name", macro.name))
        }
        convs.put(
            id,
            JSONObject()
                .put("id", id)
                .put("nickname", item.optString("name"))
                .put("name", displayName(item))
                .put("remark", item.optString("remarkName"))
                .put("peerUid", item.optString("peerUid"))
                // 标签功能已移除；保留空数组键，老脚本读 item.tags 不会拿到 undefined
                .put("tags", JSONArray())
                .put("days", spark?.optInt("days") ?: 0)
                .put("state", spark?.optInt("state", -1) ?: -1)
                .put("stateText", spark?.optString("stateText") ?: "")
                .put("isGroup", item.optBoolean("isGroup"))
                .put("selected", id in selectedIds)
                .put("sentToday", sentToday)
                .put("manualSend", flags.manual)
                .put("autoSend", flags.auto)
                .put("willSend", flags.will)
                .put("template", JSONObject().put("id", id).put("name", config.conversationLabel(id)).put("body", own?.body.orEmpty()))
                .put(
                    "parent",
                    JSONObject()
                        .put("id", parentId)
                        .put("name", templateNameOf(config, id))
                        .put("body", parentBody),
                )
                .put("macros", macroList),
        )
    }
    val templates = JSONObject()
    config.groups.forEach { group ->
        val childs = org.json.JSONArray()
        config.conversations.filter { it.inheritFrom == group.id }.forEach { child ->
            childs.put(JSONObject().put("id", child.conversationId).put("name", config.conversationLabel(child.conversationId)))
        }
        templates.put(
            group.id,
            JSONObject()
                .put("id", group.id)
                .put("name", group.name)
                .put("body", group.body)
                .put("childs", childs)
                .put("isBuiltin", false),
        )
    }
    val macros = JSONObject()
    config.macros.forEach { macro ->
        val templ = org.json.JSONArray()
        val conv = org.json.JSONArray()
        (macro.appliesTo + macro.templateOnly).forEach { target ->
            if (target.contains(":")) conv.put(target) else templ.put(target)
        }
        macros.put(
            macro.id,
            JSONObject()
                .put("id", macro.id)
                .put("name", macro.name)
                .put("body", macro.body)
                .put("templates", templ)
                .put("conversations", conv),
        )
    }
    return JSONObject()
        .put("conversations", convs)
        .put("templates", templates)
        .put("macros", macros)
        .toString()
}

/** 模板信息：当前模板 id、父模板 id、父模板的派生列表。 */

internal fun templateInfoJson(
    config: SparkConfig,
    conversationId: String,
): String {
    val template = config.conversation(conversationId)
    val parentId = template?.inheritFrom.orEmpty()
    val childs = org.json.JSONArray()
    config.conversations.filter { it.inheritFrom == parentId }.forEach { child ->
        childs.put(JSONObject().put("id", child.conversationId).put("name", config.conversationLabel(child.conversationId)))
    }
    val parent =
        JSONObject()
            .put("id", parentId)
            .put("name", templateNameOf(config, conversationId))
            .put("childs", childs)
    val current =
        JSONObject()
            .put("id", conversationId)
            .put("name", config.conversationLabel(conversationId))
    return JSONObject()
        .put("template", current)
        .put("parent", parent)
        .toString()
}

/** `ctx.template.template`：当前生效的模板名（空白模板就是「空白模板」）。 */

internal fun templateNameOf(
    config: SparkConfig,
    conversationId: String,
): String {
    val parent = config.conversation(conversationId)?.inheritFrom?.let { config.templateName(it) }
    return parent ?: BuiltinTemplates.BLANK.name
}

/** `ctx.template.parent_template`：父模板名（两层结构下一般为空）。 */

internal fun parentTemplateNameOf(
    config: SparkConfig,
    conversationId: String,
): String = config.conversation(conversationId)?.inheritFrom?.let { config.templateName(it) }?.let { "" } ?: ""

/** 把脚本改动写回配置（沙盒变量 + 宏挂载）。 */

internal fun applyScriptMutations(
    context: Context,
    config: SparkConfig,
    writes: List<com.tiantong.spark.app.config.model.SandboxVar>,
    deletes: List<Pair<String, String>>,
    macroOps: List<SparkScriptEngine.MacroOp>,
) {
    if (writes.isEmpty() && deletes.isEmpty() && macroOps.isEmpty()) return
    // 重新读一次：这一轮里 markSent 已经改过配置，
    // 拿跑步时的快照写回会把「今日已发」覆盖掉
    val latest = ConfigClient.read(context) ?: config
    val sandbox = latest.sandbox.toMutableList()
    writes.forEach { write ->
        val index = sandbox.indexOfFirst { it.owner == write.owner && it.key == write.key }
        if (index >= 0) sandbox[index] = write else sandbox += write
    }
    deletes.forEach { (owner, key) -> sandbox.removeAll { it.owner == owner && it.key == key } }
    var macros = latest.macros
    macroOps.forEach { op ->
        val targets = if (op.kind == "unmountAll") macros.indices.toList() else listOfNotNull(macros.indexOfFirst { it.id == op.name }.takeIf { it >= 0 })
        targets.forEach { index ->
            val macro = macros[index]
            val applies = macro.appliesTo.toMutableSet()
            val only = macro.templateOnly.toMutableSet()
            // 目标是模板时：传播 → appliesTo，仅模板 → templateOnly；两边互斥
            val isTemplate = op.targetId.isNotBlank() && !op.targetId.contains(":")
            when (op.kind) {
                "mount" -> if (isTemplate && !op.inherit) {
                    applies -= op.targetId
                    only += op.targetId
                } else {
                    only -= op.targetId
                    applies += op.targetId
                }
                else -> {
                    applies -= op.targetId
                    only -= op.targetId
                }
            }
            macros = macros.toMutableList().also { it[index] = macro.copy(appliesTo = applies, templateOnly = only) }
        }
    }
    if (macroOps.isNotEmpty()) {
        ModLog.i("宏挂载变更：" + macroOps.joinToString { it.kind + "/" + it.name.take(8) + "/" + it.targetId.take(12) })
    }
    val updated = latest.copy(sandbox = sandbox, macros = macros)
    if (!ConfigClient.write(context, updated)) {
        ModLog.w("脚本沙盒 / 宏挂载写入失败")
    }
}

/**
 * 触发脚本注册的会话结果回调，并把回调里改的沙盒 / 宏挂载并进这一轮统一落盘。
 *
 * @param result [SparkScriptEngine.SEND_SUCCEED] / [SparkScriptEngine.SEND_SKIPPED] / [SparkScriptEngine.SEND_FAILED]
 * @param reason 具体原因（成功时为空串）
 */

internal fun fireResultCallbacks(
    callbacks: List<Function>,
    conversationId: String,
    result: String,
    reason: String,
    pendingSandbox: MutableList<SandboxVar>,
    pendingSandboxDeletes: MutableList<Pair<String, String>>,
    macroOps: MutableList<SparkScriptEngine.MacroOp>,
    segmentsJson: String = "",
) {
    if (callbacks.isEmpty()) return
    val extra =
        runCatching { SparkScriptEngine.notifySendResult(callbacks, conversationId, result, reason, segmentsJson) }
            .getOrNull() ?: return
    pendingSandbox.addAll(extra.sandboxWrites)
    pendingSandboxDeletes.addAll(extra.sandboxDeletes.map { it.owner to it.key })
    macroOps += extra.macroOps.map { op -> if (op.targetId.isBlank()) op.copy(targetId = conversationId) else op }
}

/**
 * 把当前会话列表写入快照（供管理界面展示）。
 *
 * 空扫描结果不写：冷启动时 IM 还没加载完会先给空列表，此时写入没有意义，
 * 也让调用方的重试（[pushSnapshotToModule]、[scheduleOnAppStart]）继续等。
 * 非空结果由 App 侧 [SparkConfig.mergeSnapshots] 合并进已有快照
 * （IM 是分批吐会话的，一次扫描不能代表完整列表）。
 */
/**
 * 表情名 → content JSON。
 *
 * 两级：先按抖音当前下发的目录（keva `strategy_config.sgv`）取**有效签名地址**，
 * 套我们抓包的全量字段；目录里没有（或解析失败）再退回硬编码的「续火花」。
 */

internal fun emojiContentFor(context: Context, name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isBlank()) return null
    val item = HostEmojiCatalog.items(context).firstOrNull { it.name == trimmed }
    if (item != null) {
        val dynamic = EmojiCatalog.contentWithUrl(trimmed, item.staticUrl)
        if (dynamic != null) return dynamic
    }
    return EmojiCatalog.content(trimmed)
}

/**
 * 扫描会话并写快照。
 *
 * @param light 轻量模式：只取会话与火花，不查对方昵称/备注/头像。
 *   冷启动探测 IM 是否就绪时用它——全量模式每个单聊都要查一次用户仓库
 *   （单次最多 3 秒），22 个会话最坏 66 秒，探测循环根本等不到就绪。
 *   IM 一旦就绪立刻再跑一次全量，昵称与头像那时补上。
 */

internal fun buildVariables(
    conversation: JSONObject,
    spark: JSONObject?,
): Map<String, Any?> {
    val now = Date()
    val nickname = conversation.optString("name")
    val remark = conversation.optString("remarkName")
    return mapOf(
        "date" to dayKey(now),
        "time" to hhmm(now),
        "weekday" to weekday(now),
        "timestamp" to now.time,
        "nickname" to nickname,
        "remark" to remark,
        "name" to remark.ifBlank { nickname },
        "days" to (spark?.optInt("days") ?: 0),
        "state" to (spark?.optInt("state", -1) ?: -1),
        "stateText" to (spark?.optString("stateText") ?: ""),
        "peerUid" to conversation.optString("peerUid"),
        "id" to conversation.optString("conversationId"),
        "conversationId" to conversation.optString("conversationId"),
    )
}

internal fun displayName(conversation: JSONObject): String {
    val remark = conversation.optString("remarkName")
    return remark.ifBlank { conversation.optString("name") }
}

internal fun weekday(date: Date): String {
    val calendar = Calendar.getInstance().apply { time = date }
    return when (calendar.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "星期一"
        Calendar.TUESDAY -> "星期二"
        Calendar.WEDNESDAY -> "星期三"
        Calendar.THURSDAY -> "星期四"
        Calendar.FRIDAY -> "星期五"
        Calendar.SATURDAY -> "星期六"
        else -> "星期日"
    }
}
