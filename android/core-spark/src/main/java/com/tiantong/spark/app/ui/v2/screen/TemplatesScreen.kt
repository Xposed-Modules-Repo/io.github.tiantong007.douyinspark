package com.tiantong.spark.app.ui.v2.screen

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.tiantong.spark.app.config.BuiltinTemplates
import com.tiantong.spark.app.config.model.TemplateGroup
import com.tiantong.spark.app.ui.v2.comp.MATCH
import com.tiantong.spark.app.ui.v2.comp.WRAP
import com.tiantong.spark.app.ui.v2.comp.badge
import com.tiantong.spark.app.ui.v2.comp.card
import com.tiantong.spark.app.ui.v2.comp.column
import com.tiantong.spark.app.ui.v2.comp.dp
import com.tiantong.spark.app.ui.v2.comp.emptyState
import com.tiantong.spark.app.ui.v2.comp.icon
import com.tiantong.spark.app.ui.v2.comp.lp
import com.tiantong.spark.app.ui.v2.comp.mono
import com.tiantong.spark.app.ui.v2.comp.pick
import com.tiantong.spark.app.ui.v2.comp.row
import com.tiantong.spark.app.ui.v2.comp.shape
import com.tiantong.spark.app.ui.v2.comp.sectionLabel
import com.tiantong.spark.app.ui.v2.comp.text
import com.tiantong.spark.app.ui.v2.comp.miniButton
import com.tiantong.spark.app.ui.v2.comp.tip
import com.tiantong.spark.app.ui.v2.nav.PageView
import com.tiantong.spark.app.ui.v2.nav.Shell
import com.tiantong.spark.app.ui.v2.sheet.PreviewSheet
import com.tiantong.spark.app.ui.v2.theme.Tokens
import java.util.UUID
import com.tiantong.spark.app.ui.v2.comp.dpF

/**
 * 模板页：内置的两条起点在上，自己的模板在下。
 *
 * 每张卡就是「一个模板 + 谁在用它 + 几个动作」。不做展开折叠，
 * 动作直接摆在卡片底部，点一下就发生。
 */
class TemplatesScreen(private val shell: Shell) : PageView {

    private val ctx = shell.context
    private var rootView: LinearLayout? = null

    override fun root(): View {
        val view = rootView ?: ctx.column().also { rootView = it }
        fill(view)
        return view
    }

    override fun refresh() {
        rootView?.let { fill(it) }
    }

    private fun fill(root: LinearLayout) {
        root.removeAllViews()
        val config = shell.state.config

        root.addView(ctx.sectionLabel("内置起点"))
        BuiltinTemplates.ALL.forEach { builtin ->
            root.addView(
                ctx.card(14).apply {
                    addView(
                        ctx.row().apply {
                            addView(ctx.icon(Tokens.Icon.FIRE, 16f, Tokens.ACCENT), ctx.lp(24, WRAP))
                            addView(ctx.text(builtin.name, Tokens.T_BODY, Tokens.INK, bold = true), ctx.lp(0, WRAP, 1f))
                            addView(ctx.badge("内置", Tokens.INK_DIM, Tokens.MUTED_SOFT), ctx.lp(WRAP, WRAP))
                        },
                        ctx.lp(MATCH, WRAP),
                    )
                    addView(ctx.text(builtin.desc, Tokens.T_TINY, Tokens.INK_DIM), ctx.lp(MATCH, WRAP, top = 4))
                    addView(
                        ctx.row().apply {
                            gravity = Gravity.END
                            addView(
                                ctx.miniButton("用这个建模板") { createFrom(builtin.name, builtin.body) },
                                ctx.lp(WRAP, WRAP),
                            )
                            addView(
                                ctx.miniButton("按条预览") {
                                    PreviewSheet.open(shell.activity, builtin.name, builtin.body, null)
                                },
                                ctx.lp(WRAP, WRAP, left = 4),
                            )
                        },
                        ctx.lp(MATCH, WRAP, top = 2),
                    )
                },
            )
        }

        root.addView(
            ctx.row().apply {
                addView(ctx.sectionLabel("我的模板（${config.groups.size}）"), ctx.lp(0, WRAP, 1f))
                addView(
                    ctx.text("＋ 新建", Tokens.T_SUB, Tokens.ACCENT, bold = true).apply {
                        setPadding(ctx.dp(8), ctx.dp(18), ctx.dp(4), ctx.dp(8))
                        isClickable = true
                        setOnClickListener { createTemplate() }
                    },
                    ctx.lp(WRAP, WRAP),
                )
            },
            ctx.lp(MATCH, WRAP),
        )

        if (config.groups.isEmpty()) {
            root.addView(
                ctx.emptyState(Tokens.Icon.TEMPLATE, "还没有自己的模板", "上面的内置模板点「用这个建模板」就会出现在这里"),
                ctx.lp(MATCH, WRAP),
            )
            return
        }

        config.groups.forEach { group -> root.addView(templateCard(group)) }
    }

    private fun templateCard(group: TemplateGroup): View {
        val config = shell.state.config
        val members = config.membersOf(group.id)
        val card = ctx.card(14)

        card.addView(
            ctx.row().apply {
                addView(
                    ctx.text(group.name.ifBlank { "未命名模板" }, Tokens.T_BODY, Tokens.INK, bold = true)
                        .apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    ctx.lp(0, WRAP, 1f),
                )
                addView(
                    ctx.badge("${members.size} 个会话", Tokens.INK_DIM, Tokens.MUTED_SOFT),
                    ctx.lp(WRAP, WRAP, left = 6),
                )
            },
            ctx.lp(MATCH, WRAP),
        )

        val preview = group.body.lineSequence().filter { it.isNotBlank() }.take(3).joinToString("\n")
        card.addView(
            ctx.mono(preview.ifBlank { "（空模板）" }, 12f, Tokens.EDITOR_TEXT).apply {
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(ctx.dpF(2f), 1f)
                setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
                background = ctx.shape(Tokens.EDITOR_BG, 14)
            },
            ctx.lp(MATCH, WRAP, top = 8),
        )

        card.addView(
            ctx.row().apply {
                gravity = Gravity.END
                addView(ctx.miniButton("编辑脚本", primary = true) {
                    shell.openEditor(templateId = group.id)
                }, ctx.lp(WRAP, WRAP))
                addView(ctx.miniButton("按条预览") {
                    PreviewSheet.open(shell.activity,
                        group.name.ifBlank { "未命名模板" },
                        config.composeProgram(group.body, config.macrosForTemplate(group.id)),
                        group.id,
                    )
                }, ctx.lp(WRAP, WRAP, left = 4))
                addView(ctx.miniButton("更多") {
                    more(group)
                }, ctx.lp(WRAP, WRAP, left = 4))
            },
            ctx.lp(MATCH, WRAP, top = 4),
        )
        return card
    }

    private fun more(group: TemplateGroup) {
        shell.context.pick(
            group.name.ifBlank { "未命名模板" },
            listOf("克隆到会话…", "重命名", "删除模板"),
        ) { which ->
            when (which) {
                0 -> cloneTo(group)
                1 -> rename(group)
                2 -> remove(group)
            }
        }
    }

    private fun createTemplate() {
        shell.openEditor(templateId = "")
    }

    private fun createFrom(name: String, body: String) {
        val id = UUID.randomUUID().toString()
        shell.state.update { config ->
            config.copy(
                groups = config.groups + TemplateGroup(
                    id = id,
                    name = name + " 副本",
                    body = body,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        shell.context.tip("已新建模板")
        shell.rebuild()
    }

    private fun rename(group: TemplateGroup) {
        val input = android.widget.EditText(shell.context).apply { setText(group.name) }
        shell.context.let { c ->
            android.app.AlertDialog.Builder(c)
                .setTitle("重命名模板")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isEmpty()) return@setPositiveButton
                    shell.state.update { config ->
                        config.copy(
                            groups = config.groups.map {
                                if (it.id == group.id) it.copy(name = name, updatedAt = System.currentTimeMillis()) else it
                            },
                        )
                    }
                    shell.rebuild()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun remove(group: TemplateGroup) {
        val members = shell.state.config.membersOf(group.id)
        shell.context.let { c ->
            android.app.AlertDialog.Builder(c)
                .setTitle("删除模板")
                .setMessage(
                    if (members.isEmpty()) "确定删除「${group.name}」？" else
                        "「${group.name}」有 ${members.size} 个会话在继承。删除后这些会话保留各自的文案副本，不会丢内容。",
                )
                .setPositiveButton("删除") { _, _ ->
                    shell.state.update { config ->
                        config.copy(groups = config.groups.filterNot { it.id == group.id })
                    }
                    shell.context.tip("已删除")
                    shell.rebuild()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun cloneTo(group: TemplateGroup) {
        val config = shell.state.config
        val targets = config.snapshots.filter { !it.isHidden }
        if (targets.isEmpty()) {
            shell.context.tip("还没有会话快照，先进一次抖音")
            return
        }
        val labels = targets.map { it.displayName }
        shell.context.pick("继承「${group.name}」的会话", labels) { which ->
            val target = targets[which]
            shell.state.update { current ->
                val existing = current.conversation(target.conversationId)
                val next = if (existing == null) {
                    com.tiantong.spark.app.config.model.ConversationTemplate(
                        conversationId = target.conversationId,
                        inheritFrom = group.id,
                        body = group.body,
                        parentHashAtSync = group.bodyHash,
                        autoSync = true,
                        updatedAt = System.currentTimeMillis(),
                    )
                } else {
                    existing.copy(
                        inheritFrom = group.id,
                        body = group.body,
                        parentHashAtSync = group.bodyHash,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                current.copy(
                    conversations = current.conversations.filterNot {
                        it.conversationId == target.conversationId
                    } + next,
                )
            }
            shell.context.tip("${target.displayName} 已继承")
            shell.rebuild()
        }
    }
}
