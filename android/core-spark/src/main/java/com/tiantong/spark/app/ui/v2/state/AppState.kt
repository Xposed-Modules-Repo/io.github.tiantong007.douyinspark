package com.tiantong.spark.app.ui.v2.state

import android.content.Context
import com.tiantong.spark.app.config.ConfigStore
import com.tiantong.spark.app.config.SparkConfig
import com.tiantong.spark.app.config.model.ConversationSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 页面共享的状态：一份配置 + 三个界面偏好（过滤、搜索词、勾选）。
 *
 * 只有这里持有可变字段；页面每次刷新都从这里取快照，算完就丢，
 * 不做双向绑定，也不缓存 View。数据量是几十条会话，现算足够快。
 */
class AppState(context: Context) {

    private val app = context.applicationContext
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    var config: SparkConfig = SparkConfig()
        private set

    /**
     * 首页分段：0 全部 / 1 已燃 / 2 待燃 / 3 待续 / 4 无火花。
     *
     * 「已燃」= 今天有互动、火花已经点燃；「待燃」= 今天发过了但对方还没回，等他回就燃；
     * 「待续」= 今天还没发、抖音说该续；「无火花」= 两边根本没有火花，发也燃不起来。
     */
    var filter: Int = 0

    var query: String = ""

    /** 首页勾选的会话 id（跨刷新保留，已消失的自动剔除）。 */
    val picked: MutableSet<String> = linkedSetOf()

    fun reload() {
        config = ConfigStore.load(app)
        val alive = config.snapshots.map { it.conversationId }.toSet()
        picked.retainAll { it in alive }
    }

    fun save(next: SparkConfig) {
        runCatching { ConfigStore.save(app, next) }
        config = next
    }

    /** 读改写；失败（配置损坏）时保持原状并返回 false。 */
    fun update(block: (SparkConfig) -> SparkConfig): Boolean {
        val next = runCatching { ConfigStore.update(app, block) }.getOrNull() ?: return false
        config = next
        return true
    }

    // ── 派生视图 ────────────────────────────────────────────────

    fun today(): String = dayFormat.format(Date())

    fun sentToday(id: String): Boolean = config.sentDays[id] == today()

    /** 可见会话（去掉连续多次没再出现的）。 */
    fun visible(): List<ConversationSnapshot> = config.snapshots.filter { !it.isHidden }

    /** 该续的会话：今天已经发过的 + 现在抖音还说该续的。火花天数多的排前面。 */
    fun renewable(): List<ConversationSnapshot> =
        visible()
            .filter { sentToday(it.conversationId) || needsRenew(it) }
            .sortedByDescending { it.days }

    /**
     * 该续但今天还没发 —— 「待续」页签与发送按钮的作用对象。
     *
     * 注意：发完一条之后抖音会把 state 改成 1（已燃），needsRenewal 立刻变 false。
     * 如果这里只看 needsRenewal，已发的会话会从分母里掉出去，
     * 出现「明明发了 16 个，界面只显示 5/5」这种对不上的情况。
     * 所以分母用 [renewable]（并上今天已发），分子用 [sentList]。
     */
    fun pending(): List<ConversationSnapshot> =
        renewable().filter { !sentToday(it.conversationId) && needsRenew(it) }

    fun needsRenew(snapshot: ConversationSnapshot): Boolean =
        config.needsRenewal(snapshot) || snapshot.conversationId in config.forcedRenew

    /** 该续且今天已经发过。 */
    fun sentList(): List<ConversationSnapshot> =
        renewable().filter { sentToday(it.conversationId) }

    // ── 首页用的四个计数 ──────────────────────────────────────────

    /** 今天已发：只要今天发过就算，不再要求「现在还需要续」。 */
    fun doneCount(): Int = visible().count { sentToday(it.conversationId) }

    /** 已经点燃：抖音说 state=1。 */
    fun litCount(): Int = visible().count { it.state == 1 }

    /** 发过了、等对方回（state 2 待对方回 / 3 可重燃）。 */
    fun waitingCount(): Int = visible().count { sentToday(it.conversationId) && (it.state == 2 || it.state == 3) }

    /** 根本没有火花。 */
    fun deadCount(): Int = visible().count { !it.hasSpark }

    /** 有火花但今天还没续。 */
    fun todoCount(): Int = visible().count { it.hasSpark && needsRenew(it) && !sentToday(it.conversationId) }

    /** 联动模板的会话数（去重）—— hero 两个比值的分母。 */
    fun templateCount(): Int =
        config.conversations.count { (it.inheritFrom ?: SparkConfig.BUILTIN_BLANK) != SparkConfig.BUILTIN_BLANK }

    /** 最近一次抓到快照的时间（首页「记录时间」）。 */
    fun recordedAt(): Long =
        config.snapshots.maxOfOrNull { it.updatedAt }?.takeIf { it > 0 } ?: config.hostNoteAt

    /** 今天进度：已发 / 该续。 */
    fun progress(): Pair<Int, Int> = sentList().size to renewable().size

    /** 当前过滤 + 搜索词下的列表。 */
    fun shown(): List<ConversationSnapshot> {
        val base = when (filter) {
            1 -> visible().filter { it.state == 1 }
            2 -> visible().filter { sentToday(it.conversationId) && (it.state == 2 || it.state == 3) }
            3 -> visible().filter { needsRenew(it) && !sentToday(it.conversationId) }
            4 -> visible().filter { !it.hasSpark }
            else -> visible()
        }
        val key = query.trim()
        if (key.isEmpty()) return base
        return base.filter {
            it.displayName.contains(key, ignoreCase = true) ||
                it.conversationId.contains(key, ignoreCase = true)
        }
    }

    /** 勾选且还没发的会话 —— 首页「发送」按钮的作用对象。 */
    fun targets(): List<String> =
        pending().filter { it.conversationId in picked && !sentToday(it.conversationId) }
            .map { it.conversationId }

    fun isPicked(id: String): Boolean = id in picked

    fun togglePick(id: String) {
        if (!picked.add(id)) picked.remove(id)
    }

    fun pickAll() {
        pending().filterNot { sentToday(it.conversationId) }.forEach { picked.add(it.conversationId) }
    }

    /** 当前筛选分组（shown）内可发会话是否已全部勾选 —— 「全选/取消全选」按钮文案依据。 */
    fun groupAllPicked(): Boolean {
        val sel = shown().filter { it.hasSpark }
        return sel.isNotEmpty() && sel.all { it.conversationId in picked }
    }

    /** 全选/取消全选当前分组（排除无火花），口径与原型 pickAll 一致。 */
    fun togglePickGroup() {
        val sel = shown().filter { it.hasSpark }
        if (sel.isEmpty()) return
        if (sel.all { it.conversationId in picked }) {
            sel.forEach { picked.remove(it.conversationId) }
        } else {
            picked.addAll(sel.map { it.conversationId })
        }
    }

    fun clearPick() {
        picked.clear()
    }
}
