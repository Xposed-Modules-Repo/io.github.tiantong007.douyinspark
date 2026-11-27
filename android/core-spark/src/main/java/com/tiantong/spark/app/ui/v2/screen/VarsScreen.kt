package com.tiantong.spark.app.ui.v2.screen

import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import com.tiantong.spark.app.config.model.TemplateVar
import com.tiantong.spark.app.ui.v2.comp.MATCH
import com.tiantong.spark.app.ui.v2.comp.WRAP
import com.tiantong.spark.app.ui.v2.comp.badge
import com.tiantong.spark.app.ui.v2.comp.card
import com.tiantong.spark.app.ui.v2.comp.column
import com.tiantong.spark.app.ui.v2.comp.dp
import com.tiantong.spark.app.ui.v2.comp.emptyState
import com.tiantong.spark.app.ui.v2.comp.formHint
import com.tiantong.spark.app.ui.v2.comp.lp
import com.tiantong.spark.app.ui.v2.comp.mono
import com.tiantong.spark.app.ui.v2.comp.note
import com.tiantong.spark.app.ui.v2.comp.row
import com.tiantong.spark.app.ui.v2.comp.sectionLabel
import com.tiantong.spark.app.ui.v2.comp.text
import com.tiantong.spark.app.ui.v2.comp.textAction
import com.tiantong.spark.app.ui.v2.comp.tip
import com.tiantong.spark.app.script.SparkPreview
import com.tiantong.spark.app.ui.v2.nav.PageView
import com.tiantong.spark.app.ui.v2.nav.Shell
import com.tiantong.spark.app.ui.v2.theme.Tokens

/**
 * 变量页：谁定义了哪些变量，值是什么。
 *
 * 变量是「模板/会话各自的局部常量」——脚本里写 `$名字` 就能用。
 * 这里只做查看与改值，不新增语法。
 */
class VarsScreen(private val shell: Shell) : PageView {

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

        root.addView(
            ctx.note(
                "变量在脚本里用 \${变量名} 引用。模板上的变量对它下面的会话生效，会话自己的变量优先级更高。",
                Tokens.INFO,
                Tokens.INFO_SOFT,
            ),
        )

        root.addView(builtinCard())

        val groupCards = config.groups.filter { it.variables.isNotEmpty() }
        val convCards = config.conversations.filter { it.variables.isNotEmpty() }

        if (groupCards.isEmpty() && convCards.isEmpty()) {
            root.addView(
                ctx.emptyState(Tokens.Icon.VARS, "还没有变量", "在模板或会话的编辑页里加一条，就会出现在这里"),
                ctx.lp(MATCH, WRAP),
            )
            return
        }

        if (groupCards.isNotEmpty()) {
            root.addView(ctx.sectionLabel("模板变量"))
            groupCards.forEach { group ->
                root.addView(
                    ownerCard(
                        title = group.name.ifBlank { "未命名模板" },
                        tag = "模板",
                        vars = group.variables,
                        onEdit = { index, value ->
                            shell.state.update { current ->
                                current.copy(
                                    groups = current.groups.map {
                                        if (it.id != group.id) it
                                        else it.copy(variables = it.variables.replaceAt(index, value))
                                    },
                                )
                            }
                            shell.rebuild()
                        },
                        onDelete = { index ->
                            shell.state.update { current ->
                                current.copy(
                                    groups = current.groups.map {
                                        if (it.id != group.id) it
                                        else it.copy(variables = it.variables.filterIndexed { i, _ -> i != index })
                                    },
                                )
                            }
                            shell.rebuild()
                        },
                        onAdd = {
                            shell.state.update { current ->
                                current.copy(
                                    groups = current.groups.map {
                                        if (it.id != group.id) it
                                        else it.copy(variables = it.variables + TemplateVar("新变量", "\"\""))
                                    },
                                )
                            }
                            shell.rebuild()
                        },
                    ),
                )
            }
        }

        if (convCards.isNotEmpty()) {
            root.addView(ctx.sectionLabel("会话变量"))
            convCards.forEach { conversation ->
                root.addView(
                    ownerCard(
                        title = config.conversationLabel(conversation.conversationId),
                        tag = "会话",
                        vars = conversation.variables,
                        onEdit = { index, value ->
                            shell.state.update { current ->
                                current.copy(
                                    conversations = current.conversations.map {
                                        if (it.conversationId != conversation.conversationId) it
                                        else it.copy(variables = it.variables.replaceAt(index, value))
                                    },
                                )
                            }
                            shell.rebuild()
                        },
                        onDelete = { index ->
                            shell.state.update { current ->
                                current.copy(
                                    conversations = current.conversations.map {
                                        if (it.conversationId != conversation.conversationId) it
                                        else it.copy(variables = it.variables.filterIndexed { i, _ -> i != index })
                                    },
                                )
                            }
                            shell.rebuild()
                        },
                        onAdd = {
                            shell.state.update { current ->
                                current.copy(
                                    conversations = current.conversations.map {
                                        if (it.conversationId != conversation.conversationId) it
                                        else it.copy(variables = it.variables + TemplateVar("新变量", "\"\""))
                                    },
                                )
                            }
                            shell.rebuild()
                        },
                    ),
                )
            }
        }
    }

    private fun List<TemplateVar>.replaceAt(index: Int, value: TemplateVar): List<TemplateVar> =
        mapIndexed { i, item -> if (i == index) value else item }

    /** 引擎内置变量：写 ${名字} 就有值，不用自己定义。 */
    private fun builtinCard(): View {
        val sample = SparkPreview.variables(shell.state.config.snapshots.firstOrNull())
        val card = ctx.card(14)
        card.addView(ctx.text("脚本里能直接用的", 14.5f, Tokens.INK, bold = true), ctx.lp(MATCH, WRAP))
        card.addView(
            ctx.formHint("写 \${名字} 就会替换掉。右边是当前样例值。"),
            ctx.lp(MATCH, WRAP, top = 3, bottom = 8),
        )
        BUILTIN.forEach { (name, desc) ->
            val value = sample[name]?.toString().orEmpty().replace("\n", " ")
            card.addView(
                ctx.row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, ctx.dp(9), 0, ctx.dp(9))
                    addView(ctx.mono("$" + name, 12.5f, Tokens.ACCENT), ctx.lp(WRAP, WRAP, right = 8))
                    addView(ctx.text(desc, 12f, Tokens.INK_DIM), ctx.lp(0, WRAP, 1f))
                    addView(
                        ctx.text(value, 12.5f, Tokens.INK_DIM).apply {
                            maxLines = 1
                            maxWidth = ctx.dp(132)
                            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        },
                        ctx.lp(WRAP, WRAP, left = 8),
                    )
                },
                ctx.lp(MATCH, WRAP),
            )
        }
        return card
    }

    private companion object {
        val BUILTIN = listOf(
            "date" to "今天的日期",
            "time" to "现在的时间",
            "weekday" to "星期几",
            "timestamp" to "毫秒时间戳",
            "nickname" to "对方昵称",
            "remark" to "你给对方的备注",
            "name" to "会话显示名",
            "days" to "火花天数",
            "state" to "状态码",
            "stateText" to "状态说明",
            "peerUid" to "对方 uid",
            "conversationId" to "会话 id",
        )
    }

    private fun ownerCard(
        title: String,
        tag: String,
        vars: List<TemplateVar>,
        onEdit: (Int, TemplateVar) -> Unit,
        onDelete: (Int) -> Unit,
        onAdd: () -> Unit,
    ): View =
        ctx.card(12).apply {
            addView(
                ctx.row().apply {
                    addView(ctx.text(title, Tokens.T_BODY, Tokens.INK, bold = true), ctx.lp(0, WRAP, 1f))
                    addView(ctx.badge(tag, Tokens.INFO, Tokens.INFO_SOFT), ctx.lp(WRAP, WRAP))
                },
                ctx.lp(MATCH, WRAP),
            )
            vars.forEachIndexed { index, variable ->
                addView(
                    ctx.row().apply {
                        setPadding(ctx.dp(2), ctx.dp(9), ctx.dp(2), ctx.dp(9))
                        addView(
                            ctx.mono(variable.name, 13f, Tokens.ACCENT),
                            ctx.lp(0, WRAP, 0.4f),
                        )
                        addView(
                            ctx.mono(variable.expr, 12f, Tokens.INK_DIM).apply {
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            },
                            ctx.lp(0, WRAP, 0.6f),
                        )
                        isClickable = true
                        setOnClickListener { editVar(variable, onEdit, onDelete, index) }
                    },
                    ctx.lp(MATCH, WRAP),
                )
                if (index != vars.lastIndex) addView(android.view.View(ctx).apply { setBackgroundColor(Tokens.LINE) }, ctx.lp(MATCH, 1))
            }
            addView(
                ctx.row().apply {
                    gravity = Gravity.END
                    addView(ctx.textAction("＋ 加一条") { onAdd() }, ctx.lp(WRAP, WRAP))
                },
                ctx.lp(MATCH, WRAP, top = 2),
            )
        }

    /** 变量编辑：名字 + 表达式两个输入框。 */
    private fun editVar(variable: TemplateVar, onEdit: (Int, TemplateVar) -> Unit, onDelete: (Int) -> Unit, index: Int) {
        val name = EditText(ctx).apply { setText(variable.name); hint = "变量名" }
        val expr = EditText(ctx).apply { setText(variable.expr); hint = "值，例如 \"你好\" 或 ctx.io.days" }
        val box = ctx.column().apply {
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
            addView(name, ctx.lp(MATCH, WRAP))
            addView(expr, ctx.lp(MATCH, WRAP, top = 8))
            addView(ctx.formHint("值是一个 JS 表达式：字符串要带引号，数字直接写。"), ctx.lp(MATCH, WRAP))
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("编辑变量")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) {
                    ctx.tip("变量名不能为空")
                    return@setPositiveButton
                }
                onEdit(index, TemplateVar(n, expr.text.toString()))
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("删除") { _, _ -> onDelete(index) }
            .show()
    }
}
