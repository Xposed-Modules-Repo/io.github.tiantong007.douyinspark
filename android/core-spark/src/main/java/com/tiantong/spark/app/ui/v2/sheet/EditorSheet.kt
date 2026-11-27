package com.tiantong.spark.app.ui.v2.sheet

import android.app.AlertDialog
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.tiantong.spark.app.config.Hash
import com.tiantong.spark.app.config.SparkConfig
import com.tiantong.spark.app.config.EmojiCatalog
import com.tiantong.spark.app.config.EmojiImages
import com.tiantong.spark.app.config.model.ConversationTemplate
import com.tiantong.spark.app.config.model.TemplateGroup
import com.tiantong.spark.app.ui.v2.comp.MATCH
import com.tiantong.spark.app.ui.v2.comp.WRAP
import com.tiantong.spark.app.ui.v2.comp.Sheet
import com.tiantong.spark.app.ui.v2.comp.card
import com.tiantong.spark.app.ui.v2.comp.chips
import com.tiantong.spark.app.ui.v2.comp.divider
import com.tiantong.spark.app.ui.v2.comp.field
import com.tiantong.spark.app.ui.v2.comp.formHint
import com.tiantong.spark.app.ui.v2.comp.icon
import com.tiantong.spark.app.ui.v2.comp.iconTap
import com.tiantong.spark.app.ui.v2.comp.tbtn
import com.tiantong.spark.app.ui.v2.comp.lp
import com.tiantong.spark.app.ui.v2.comp.note
import com.tiantong.spark.app.ui.v2.comp.row
import com.tiantong.spark.app.ui.v2.comp.scriptField
import com.tiantong.spark.app.ui.v2.comp.text
import com.tiantong.spark.app.ui.v2.comp.tip
import com.tiantong.spark.app.ui.v2.nav.Shell
import com.tiantong.spark.app.ui.v2.theme.Tokens
import java.util.UUID
import com.tiantong.spark.app.ui.v2.comp.dp
import com.tiantong.spark.app.ui.v2.comp.dpF

/**
 * 脚本编辑弹层：模板与会话共用一套。
 *
 * 会话模式多两件事 —— 告诉你现在继承的是谁、以及「重置为父模板」的入口。
 * 工具条四个动作都是从左到右变重的顺序：表情 → 变量 → 格式化 → 预览。
 */
object EditorSheet {

    private const val DEFAULT_BODY = "ctx.result.push({ type: \"emoji\", name: \"续火花\" });"

    /**
     * 引擎内置的变量，与 [com.tiantong.spark.app.script.PreviewData] 注入的一致。
     * 分组 + 说明，与原型 pickVar 的 VARLIST 对齐；插入仍是引擎认的 \${ctx.template.vars["..."]}。
     */
    private val VAR_GROUPS = listOf(
        "时间" to listOf(
            "date" to "今天日期，如 2026-11-26",
            "time" to "当前时间，如 22:14",
            "weekday" to "星期几",
            "timestamp" to "Unix 时间戳（秒）",
        ),
        "对方" to listOf(
            "nickname" to "对方昵称",
            "remark" to "对方备注名",
            "name" to "显示名（备注优先）",
            "days" to "火花天数",
        ),
        "会话" to listOf(
            "state" to "火花状态数字（1 已燃 / 2 待燃 / 3 重燃中）",
            "stateText" to "状态文字，如 待续 / 已燃",
            "peerUid" to "对方 UID",
            "conversationId" to "会话 ID",
        ),
    )

    /** 和旧编辑器同款清理：零宽字符、花引号、Markdown 围栏。 */
    private fun normalize(text: String): String {
        val cleaned = text
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\uFEFF", "")
            .replace('\u201C', '"')
            .replace('\u201D', '"')
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
        return Regex("^```[a-zA-Z]*\\s*$", RegexOption.MULTILINE).replace(cleaned, "").trim()
    }

    fun open(shell: Shell, conversationId: String? = null, templateId: String? = null) {
        val activity = shell.activity
        val context = shell.context
        val config = shell.state.config
        val group = templateId?.let { config.group(it) }
        val conversation = conversationId?.let { config.conversation(it) }
        val snapshot = conversationId?.let { config.snapshot(it) }
        val isNewTemplate = templateId != null && group == null

        val title = when {
            conversationId != null -> snapshot?.displayName ?: "会话文案"
            isNewTemplate -> "新建模板"
            else -> group?.name ?: "模板"
        }
        val subtitle = when {
            conversationId != null -> config.inheritLabel(conversation?.inheritFrom).let { "继承自 $it" }
            isNewTemplate -> "先写文案，之后可以被会话继承"
            else -> "被 ${memberCount(config, group?.id).let { if (it > 0) "$it 个会话" else "0 个会话" }} 继承"
        }

        val body = conversation?.body ?: group?.body ?: DEFAULT_BODY
        val nameField: EditText? = if (isNewTemplate) context.field("模板名字", "新模板", numeric = false) else null
        // 重置按钮在编辑器创建之前就挂上了，所以用一个可空引用回指。
        var editorRef: EditText? = null
        // 「模板」按钮选了新继承时记在这里，保存时才落盘（null=没动；""=不继承；其余=模板 id）
        var pendingInherit: String? = null
        // 继承提示文字（原型 tplhint），套模板/不继承后要跟着改。
        var inheritHint: TextView? = null

        Sheet.open(activity, title, subtitle) { bodyView ->
            var previewBlock: View? = null
            if (conversationId != null) {
                val inheritKey = conversation?.inheritFrom
                bodyView.addView(
                    context.row().apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(context.icon(Tokens.Icon.CLONE, 15f, Tokens.INK_DIM), context.lp(24, WRAP))
                        addView(
                            context.text(config.inheritLabel(inheritKey), Tokens.T_SUB, Tokens.INK, bold = true)
                                .also { inheritHint = it },
                            context.lp(0, WRAP, 1f),
                        )
                        if (inheritKey != null && inheritKey != SparkConfig.BUILTIN_BLANK) {
                            addView(
                                context.text("重置", Tokens.T_SUB, Tokens.WARN, bold = true).apply {
                                    setPadding(context.dp(10), context.dp(4), context.dp(4), context.dp(4))
                                    setOnClickListener {
                                        val parentBody = config.group(inheritKey)?.body
                                            ?: if (inheritKey == SparkConfig.BUILTIN_BLANK) "" else null
                                        if (parentBody == null) {
                                            context.tip("父模板已删除")
                                        } else {
                                            editorRef?.let { field ->
                                                field.setText(parentBody)
                                                field.setSelection(parentBody.length)
                                            }
                                            context.tip("已恢复为父模板内容，保存后生效")
                                        }
                                    }
                                },
                                context.lp(WRAP, WRAP),
                            )
                        }
                    },
                    context.lp(MATCH, WRAP, top = 10),
                )
            }

            if (nameField != null) {
                bodyView.addView(nameField, context.lp(MATCH, WRAP, top = 12))
            }

            val editor = context.scriptField(body, "在这里写脚本")
            editorRef = editor
            bodyView.addView(editor, context.lp(MATCH, WRAP, top = 12))

            // 与原型一致：没写脚本的会话发送时会被跳过，提前说一声
            if (conversationId != null && body.isBlank()) {
                bodyView.addView(context.note("这个会话还没有文案，发送时会被跳过。"), context.lp(MATCH, WRAP))
            }

            bodyView.addView(
                context.row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    // 工具条顺序对齐原型：模板 → 表情 → 变量 → 预览（模板只在会话模式有）
                    if (conversationId != null) {
                        addView(context.tbtn(Tokens.Icon.CLONE, "模板") { pickTemplate(shell, config, conversation, editor, onPick = { pendingInherit = it }, inheritHint = inheritHint) }, context.lp(WRAP, WRAP))
                        addView(context.tbtn(Tokens.Icon.FIRE, "表情", fire = true) { pickEmoji(shell, editor) }, context.lp(WRAP, WRAP, left = 7))
                        addView(context.tbtn(Tokens.Icon.VARS, "变量") { pickVar(shell, config, conversationId, editor) }, context.lp(WRAP, WRAP, left = 7))
                    } else {
                        addView(context.tbtn(Tokens.Icon.FIRE, "表情", fire = true) { pickEmoji(shell, editor) }, context.lp(WRAP, WRAP))
                        addView(context.tbtn(Tokens.Icon.VARS, "变量") { pickVar(shell, config, conversationId, editor) }, context.lp(WRAP, WRAP, left = 7))
                    }
                    addView(context.tbtn(Tokens.Icon.PREVIEW, "预览") {
                        // 内联展开：不另开一层弹窗，避免叠在编辑弹层上面
                        previewBlock?.let { bodyView.removeView(it) }
                        previewBlock = PreviewSheet.inline(
                            context,
                            bodyView,
                            programOf(config, editor.text.toString(), conversationId, templateId),
                            conversationId,
                        )
                    }, context.lp(WRAP, WRAP, left = 7))
                    addView(android.view.View(context), context.lp(0, 1, 1f))
                    val countView = context.text("${body.length} 字符", Tokens.T_TINY, Tokens.INK_FAINT)
                    editor.addTextChangedListener(
                        object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun afterTextChanged(s: android.text.Editable?) {
                                countView.text = "${s?.length ?: 0} 字符"
                            }
                        },
                    )
                    addView(countView, context.lp(WRAP, WRAP))
                },
                context.lp(MATCH, WRAP, top = 8),
            )

            bodyView.addView(context.formHint("ctx.result 是这一轮要发的内容；ctx.commit() 可以分成多条发。"))
        }.footerButton("保存") { sheet ->
            // 保存时顺手清掉零宽字符与花引号：从聊天里复制过来的脚本常夹着看不见的字符
            val text = normalize(editorRef?.text?.toString().orEmpty())
            val name = nameField?.text?.toString()?.trim().orEmpty()
            if (conversationId != null) {
                saveConversation(shell, config, conversationId, text, pendingInherit)
            } else {
                saveTemplate(shell, config, group, if (group == null) name.ifBlank { "新模板" } else group.name, text)
            }
            sheet.dismiss()
            context.tip("已保存")
        }
    }

    private fun memberCount(config: SparkConfig, groupId: String?): Int {
        if (groupId == null) return 0
        return config.conversations.count { it.inheritFrom == groupId }
    }

    private fun programOf(config: SparkConfig, body: String, conversationId: String?, templateId: String?): String =
        runCatching {
            when {
                conversationId != null -> {
                    val inherit = config.conversation(conversationId)?.inheritFrom
                    config.composeProgram(body, config.macrosFor(listOfNotNull(conversationId, inherit)))
                }
                templateId != null -> config.composeProgram(body, config.macrosForTemplate(templateId))
                else -> body
            }
        }.getOrDefault(body)

    private fun saveTemplate(shell: Shell, config: SparkConfig, group: TemplateGroup?, name: String, body: String) {
        val now = System.currentTimeMillis()
        val nextGroups = if (group == null) {
            config.groups + TemplateGroup(id = UUID.randomUUID().toString(), name = name, body = body, updatedAt = now)
        } else {
            config.groups.map { if (it.id == group.id) it.copy(name = name, body = body, updatedAt = now) else it }
        }
        shell.state.save(config.copy(groups = nextGroups))
        shell.rebuild()
    }

    private fun saveConversation(
        shell: Shell,
        config: SparkConfig,
        conversationId: String,
        body: String,
        pendingInherit: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val existing = config.conversation(conversationId)
        // pendingInherit：null=不动继承；""=改「不继承」；其余=换绑到该模板 id
        val nextInherit =
            if (pendingInherit == null) {
                existing?.inheritFrom
            } else {
                pendingInherit.takeIf { it.isNotEmpty() }
            }
        val next = if (existing == null) {
            ConversationTemplate(
                conversationId = conversationId,
                inheritFrom = nextInherit,
                body = body,
                parentHashAtSync = Hash.of(body),
                autoSync = false,
                updatedAt = now,
                selectedAt = now,
            )
        } else {
            existing.copy(body = body, inheritFrom = nextInherit, parentHashAtSync = Hash.of(body), updatedAt = now, selectedAt = now)
        }
        val nextConversations = if (existing == null) {
            config.conversations + next
        } else {
            config.conversations.map { if (it.conversationId == conversationId) next else it }
        }
        shell.state.save(config.copy(conversations = nextConversations))
        shell.rebuild()
    }

    private fun pickEmoji(shell: Shell, editor: EditText) {
        // 全量目录 = 宿主同步的 index.json ∪ 硬编码兜底（原型 pickEmoji 的 STICKER 列表）
        val names = EmojiImages.names(shell.context)
        val labels = (names + listOf("手动输入名字…")).toTypedArray()
        AlertDialog.Builder(shell.activity)
            .setTitle("插入表情")
            .setItems(labels) { _, which ->
                if (which < names.size) {
                    insertEmoji(shell, editor, names[which])
                } else {
                    askEmojiName(shell, editor)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 空脚本插整句、有内容插片段 —— 与原型 pickEmoji 一致。 */
    private fun insertEmoji(shell: Shell, editor: EditText, name: String) {
        if (editor.text.isBlank()) {
            insert(editor, "ctx.result.push({ type: \"emoji\", name: \"$name\" });")
        } else {
            insert(editor, "{ type: \"emoji\", name: \"$name\" },")
        }
        shell.context.tip("已插入表情「$name」")
    }

    private fun askEmojiName(shell: Shell, editor: EditText) {
        val input = shell.context.field("抖音里显示的名字，例如 比心", "")
        AlertDialog.Builder(shell.activity)
            .setTitle("表情名字")
            .setView(input)
            .setPositiveButton("插入") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) insertEmoji(shell, editor, name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickVar(shell: Shell, config: SparkConfig, conversationId: String?, editor: EditText) {
        val ctx = shell.context
        var dialog: AlertDialog? = null

        val container = LinearLayout(ctx)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), ctx.dp(12))

        fun section(title: String, vars: List<Pair<String, String>>) {
            if (vars.isEmpty()) return
            container.addView(
                ctx.text(title, Tokens.T_TINY, Tokens.INK_DIM, bold = true),
                ctx.lp(MATCH, WRAP, top = 12),
            )
            vars.forEach { (name, desc) ->
                container.addView(
                    ctx.row().apply {
                        setPadding(0, ctx.dp(8), 0, ctx.dp(8))
                        addView(ctx.text(name, Tokens.T_BODY, Tokens.ACCENT, bold = true), ctx.lp(WRAP, WRAP))
                        addView(ctx.text(desc, Tokens.T_TINY, Tokens.INK_FAINT), ctx.lp(0, WRAP, 1f, left = 10))
                        setOnClickListener {
                            // 引擎只认 \${ctx.template.vars["..."]}，与预览/执行同一套取值
                            insert(editor, "\${ctx.template.vars[\"$name\"]}")
                            dialog?.dismiss()
                        }
                    },
                    ctx.lp(MATCH, WRAP),
                )
            }
        }

        VAR_GROUPS.forEach { (title, vars) -> section(title, vars) }
        if (conversationId != null) {
            // 这个会话自己的变量 + 继承父模板的变量（说明列统一写「模板变量」）
            val own = config.conversation(conversationId)?.variables?.map { it.name } ?: emptyList()
            val fromParent = config.conversation(conversationId)?.inheritFrom
                ?.let { config.group(it)?.variables?.map { v -> v.name } } ?: emptyList()
            section("这个会话的变量", own.map { it to "模板变量" })
            section("继承模板的变量", fromParent.map { it to "模板变量" })
        }

        dialog = AlertDialog.Builder(shell.activity)
            .setTitle("插入变量")
            .setView(container)
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 「模板」按钮（仅会话模式）：列全部模板 + 当前在用 + 「不继承」。
     * 选中模板 → 编辑框换成模板 body + 记 pendingInherit（保存时落盘）；
     * 「不继承」 → 只换绑，正文不动。
     */
    private fun pickTemplate(
        shell: Shell,
        config: SparkConfig,
        conversation: ConversationTemplate?,
        editor: EditText,
        onPick: (String) -> Unit,
        inheritHint: TextView? = null,
    ) {
        val ctx = shell.context
        val groups = config.groups
        val labels = ArrayList<String>(groups.size + 1)
        groups.forEach { g ->
            val inUse = config.conversations.count { it.inheritFrom == g.id }
            val mark = if (conversation?.inheritFrom == g.id) "（当前）" else ""
            labels.add("「${g.name}」 · $inUse 个会话在用$mark")
        }
        val blankIdx = labels.size
        val blankMark = if (conversation?.inheritFrom == null) "（当前）" else ""
        labels.add("不继承 · 用这个会话自己的一份$blankMark")

        AlertDialog.Builder(shell.activity)
            .setTitle("套用模板")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == blankIdx) {
                    onPick("")
                    inheritHint?.text = config.inheritLabel(null)
                    shell.context.tip("已改为不继承，模板改动不再同步过来")
                } else {
                    val g = groups[which]
                    editor.setText(g.body)
                    editor.setSelection(editor.text.length)
                    onPick(g.id)
                    inheritHint?.text = config.inheritLabel(g.id)
                    shell.context.tip("已套用模板「${g.name}」，保存后生效")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun insert(editor: EditText, snippet: String) {
        val start = editor.selectionStart.coerceAtLeast(0)
        val end = editor.selectionEnd.coerceAtLeast(start)
        editor.text.replace(start, end, snippet)
        editor.setSelection((start + snippet.length).coerceAtMost(editor.text.length))
    }
}
