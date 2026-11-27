package com.tiantong.spark.app.ui.v2.comp

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.widget.EditText
import android.widget.LinearLayout
import com.tiantong.spark.app.ui.v2.theme.Tokens

/**
 * 输入控件：脚本编辑器、变量表达式、名字。
 *
 * 脚本编辑器就是原生 EditText + 等宽字体 —— 不引 CodeMirror、不引 WebView，
 * 体积为零，输入法行为正常，光标与选区都靠系统。
 */

/** 单行输入。 */
fun Context.field(
    hint: CharSequence = "",
    value: String = "",
    numeric: Boolean = false,
    password: Boolean = false,
): EditText =
    EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.T_BODY)
        setTextColor(Tokens.INK)
        setHintTextColor(Tokens.INK_FAINT)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        rounded(Tokens.SURFACE_SUNK, Tokens.R_SM)
        backgroundTintList = null
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = lp(MATCH, WRAP, top = 6)
    }

/** 多行脚本编辑器。 */
fun Context.scriptField(
    value: String = "",
    hint: CharSequence = "在这里写脚本…",
    minLines: Int = 5,
): EditText =
    EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(Tokens.EDITOR_TEXT)
        setHintTextColor(Tokens.EDITOR_DIM)
        typeface = Tokens.monoFont()
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        setPadding(dp(14), dp(13), dp(14), dp(13))
        setLineSpacing(dpF(3f), 1f)
        rounded(Tokens.EDITOR_BG, 14)
        backgroundTintList = null
        this.minLines = minLines
        this.minHeight = dp(140)
        setHorizontallyScrolling(false)
        isSingleLine = false
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        layoutParams = lp(MATCH, WRAP, top = 8)
    }

/** 下拉选择行：点击弹原生列表。 */
fun Context.selectRow(
    label: CharSequence,
    value: CharSequence,
    onClick: () -> Unit,
): LinearLayout =
    row().apply {
        setPadding(0, dp(9), 0, dp(9))
        layoutParams = lp(MATCH, WRAP)
        addView(text(label, 13.5f, Tokens.INK), lp(0, WRAP, 1f))
        addView(text(value, 12.5f, Tokens.INK_DIM), lp(WRAP, WRAP, right = 6))
        addView(icon(Tokens.Icon.CHEVRON, 13f, Tokens.CHEV), lp(WRAP, WRAP))
        isClickable = true
        setOnClickListener { onClick() }
    }

/** 开关行（原生 Switch，无需 material）。 */
fun Context.toggleRow(
    label: CharSequence,
    hint: CharSequence = "",
    checked: Boolean,
    onChange: (Boolean) -> Unit,
): LinearLayout =
    row().apply {
        setPadding(0, dp(10), 0, dp(10))
        layoutParams = lp(MATCH, WRAP)
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(
            column().apply {
                addView(text(label, 14f, Tokens.INK))
                if (hint.isNotBlank()) {
                    addView(text(hint, 11.5f, Tokens.INK_DIM), lp(MATCH, WRAP, top = 3))
                }
            },
            lp(0, WRAP, 1f),
        )
        addView(switchChip(checked, onChange), lp(44, 26, left = 8))
    }

/** 数字步进行（1 / 2 / 3 段数这类）。 */
fun Context.stepperRow(
    label: CharSequence,
    hint: CharSequence,
    options: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
): LinearLayout =
    row().apply {
        setPadding(0, dp(10), 0, dp(10))
        layoutParams = lp(MATCH, WRAP)
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(
            column().apply {
                addView(text(label, 14f, Tokens.INK))
                if (hint.isNotBlank()) addView(text(hint, 11.5f, Tokens.INK_DIM), lp(MATCH, WRAP, top = 3))
            },
            lp(0, WRAP, 1f),
        )
        addView(
            row().apply {
                options.forEachIndexed { index, label2 ->
                    val on = index == selected
                    addView(
                        text(label2, 12f, if (on) 0xFFFFFFFF.toInt() else Tokens.INK_DIM, bold = on).apply {
                            setPadding(dp(11), dp(6), dp(11), dp(6))
                            rounded(if (on) Tokens.ACCENT else Tokens.SURFACE_SUNK, Tokens.R_PILL)
                            isClickable = true
                            setOnClickListener { onPick(index) }
                        },
                        lp(WRAP, WRAP, left = 6),
                    )
                }
            },
            lp(WRAP, WRAP),
        )
    }
