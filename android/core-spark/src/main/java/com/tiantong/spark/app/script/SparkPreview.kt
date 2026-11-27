package com.tiantong.spark.app.script

import com.tiantong.spark.app.config.model.ConversationSnapshot
import com.tiantong.spark.app.config.SparkConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 预览用变量：抖音侧的值优先用「会话快照」这个缓存（昵称/备注/天数/状态…），
 * 只有完全没有快照时才退到占位值，避免预览因为缺变量而报错。
 */
object SparkPreview {

    fun variables(snapshot: ConversationSnapshot? = null): Map<String, Any?> {
        val now = Date()
        val name = snapshot?.displayName?.takeIf { it.isNotBlank() } ?: "小美"
        return mapOf(
            "date" to SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now),
            "time" to SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
            "weekday" to SimpleDateFormat("EEEE", Locale.getDefault()).format(now),
            "timestamp" to now.time,
            "nickname" to (snapshot?.name?.takeIf { it.isNotBlank() } ?: name),
            "remark" to (snapshot?.remark ?: ""),
            "name" to name,
            "days" to (snapshot?.days ?: 7),
            "state" to (snapshot?.state ?: 2),
            "stateText" to (snapshot?.stateText?.takeIf { it.isNotBlank() } ?: "未燃"),
            "peerUid" to (snapshot?.peerUid?.takeIf { it.isNotBlank() } ?: "0"),
            "conversationId" to (snapshot?.conversationId ?: "sample"),
        )
    }

    /** 样例候选：需要续的排前面，其它单聊跟在后面。 */
    fun samples(config: SparkConfig): List<ConversationSnapshot> {
        val singles = config.snapshots.filter { !it.isGroup && !it.isHidden }
        val selected = singles.filter { config.needsRenewal(it) }
        return selected + singles.filterNot { config.needsRenewal(it) }
    }

    /** 第 [index] 个样例（按行轮换，避免整页预览都是同一个人）。 */
    fun sampleAt(config: SparkConfig, index: Int): ConversationSnapshot? {
        val list = samples(config)
        return if (list.isEmpty()) null else list[index.mod(list.size)]
    }

    fun sample(config: SparkConfig): ConversationSnapshot? = sampleAt(config, 0)
}
