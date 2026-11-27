package com.tiantong.spark.app.ui.v2.comp

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tiantong.spark.app.ui.v2.theme.Tokens

/**
 * 底部弹层：编辑脚本、选模板、按条预览都在这里，不再开新 Activity。
 *
 * 结构固定：[抓手条] [标题 + 关闭] [可滚动内容] [底部按钮区（可选）]。
 * 高度自适应，最高 92% 屏高；键盘弹起时整体上推。
 */
class Sheet private constructor(
    private val dialog: Dialog,
    val body: LinearLayout,
    private val footer: LinearLayout,
) {

    fun dismiss() {
        runCatching { dialog.dismiss() }
    }

    fun footerButton(label: CharSequence, primary: Boolean = true, onClick: (Sheet) -> Unit) {
        val button =
            if (primary) {
                body.context.primaryButton(label) {}
            } else {
                body.context.ghostButton(label) {}
            }
        button.setOnClickListener { onClick(this) }
        footer.addView(button, body.context.lp(if (primary) 0 else WRAP, WRAP, if (primary) 1f else 0f, right = 8))
    }

    companion object {
        /** 打开一个弹层；[build] 往里塞内容，返回的 [Sheet] 可用来关闭或加底部按钮。 */
        fun open(
            activity: Activity,
            title: CharSequence,
            subtitle: CharSequence = "",
            build: (LinearLayout) -> Unit,
        ): Sheet {
            val dialog = Dialog(activity)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val root = activity.column()
            root.setBackgroundColor(Tokens.SURFACE)

            // 抓手条：告诉用户这里可以下拉（实际用系统返回/点遮罩关闭）
            root.addView(
                View(activity).apply { setBackgroundColor(Tokens.SWITCH_OFF) },
                activity.lp(38, 4, top = 8).also { it.gravity = Gravity.CENTER_HORIZONTAL },
            )

            val header = activity.row().apply {
                setPadding(activity.dp(18), activity.dp(12), activity.dp(10), activity.dp(4))
            }
            header.addView(
                activity.column().apply {
                    addView(activity.text(title, 17f, Tokens.INK, bold = true))
                    if (subtitle.isNotBlank()) {
                        addView(activity.text(subtitle, Tokens.T_SUB, Tokens.INK_DIM), activity.lp(MATCH, WRAP, top = 3))
                    }
                },
                activity.lp(0, WRAP, 1f),
            )
            header.addView(
                activity.icon(Tokens.Icon.CLOSE, 17f, Tokens.INK_DIM).apply {
                    isClickable = true
                    setOnClickListener { dialog.dismiss() }
                },
                activity.lp(40, 40),
            )
            root.addView(header, activity.lp(MATCH, WRAP))

            val scroll = ScrollView(activity).apply { isFillViewport = false }
            val body = activity.column().apply { setPadding(activity.dp(18), activity.dp(4), activity.dp(18), activity.dp(22)) }
            scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
            root.addView(scroll, activity.lp(MATCH, 0, 1f))

            val footer = activity.row().apply {
                setPadding(activity.dp(18), activity.dp(6), activity.dp(18), activity.dp(14))
            }
            root.addView(footer, activity.lp(MATCH, WRAP))

            build(body)

            dialog.setContentView(root)
            dialog.window?.let { window ->
                window.setBackgroundDrawableResource(android.R.color.transparent)
                window.setLayout(MATCH, WRAP)
                window.setGravity(Gravity.BOTTOM)
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(0.42f)
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                val attrs = window.attributes
                attrs.width = MATCH
                window.attributes = attrs
            }
            dialog.setOnShowListener {
                dialog.window?.setLayout(MATCH, WRAP)
                root.background = activity.topRounded(Tokens.SURFACE, 24)
            }
            runCatching { dialog.show() }
            return Sheet(dialog, body, footer)
        }
    }
}

/** 轻提示：不打断操作。 */
fun Context.tip(message: CharSequence, long: Boolean = false) {
    runCatching { Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
}

/** 确认对话框（原生，无第三方依赖）。 */
fun Context.confirm(
    title: CharSequence,
    message: CharSequence,
    positive: CharSequence = "确定",
    negative: CharSequence = "取消",
    danger: Boolean = false,
    onOk: () -> Unit,
) {
    runCatching {
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onOk() }
            .setNegativeButton(negative, null)
            .show()
    }
}

/** 单选列表对话框。 */
fun Context.pick(
    title: CharSequence,
    items: List<String>,
    onPick: (Int) -> Unit,
) {
    if (items.isEmpty()) return
    runCatching {
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .setNegativeButton("取消", null)
            .show()
    }
}

/** 一行说明文字（表单里的帮助语）。 */
fun Context.formHint(value: CharSequence): TextView =
    text(value, Tokens.T_TINY, Tokens.INK_DIM).apply {
        setLineSpacing(dpF(2f), 1f)
        layoutParams = lp(MATCH, WRAP, top = 6)
    }
