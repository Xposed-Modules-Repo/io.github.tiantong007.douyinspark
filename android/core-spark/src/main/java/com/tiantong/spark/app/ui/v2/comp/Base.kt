package com.tiantong.spark.app.ui.v2.comp

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tiantong.spark.app.ui.v2.theme.Tokens

/**
 * 基础积木：只做「造一个 View」这一件事，不含任何业务。
 *
 * 约定：
 *  - 所有尺寸单位都是 dp / sp，内部换算，调用方不写 px；
 *  - 不抛异常，参数离谱时退化成合理值；
 *  - 函数名短，因为它们在一页里会出现几十次。
 */

const val MATCH = MATCH_PARENT
const val WRAP = WRAP_CONTENT

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.dpF(value: Float): Float = value * resources.displayMetrics.density

/**
 * 造一个 LinearLayout.LayoutParams。
 *
 * width / height 传 **dp 数值**，或者 [MATCH] / [WRAP] / 0；边距一律 dp。
 * 这样调用处不必到处写 dp()，一页代码能少一半噪音。
 */
fun Context.lp(
    width: Int = WRAP,
    height: Int = WRAP,
    weight: Float = 0f,
    left: Int = 0,
    top: Int = 0,
    right: Int = 0,
    bottom: Int = 0,
): LinearLayout.LayoutParams {
    fun size(value: Int): Int = when {
        value == MATCH || value == WRAP || value == 0 -> value
        else -> dp(value)
    }
    return LinearLayout.LayoutParams(size(width), size(height)).also {
        it.weight = weight
        it.setMargins(dp(left), dp(top), dp(right), dp(bottom))
    }
}

fun Context.column(): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

fun Context.row(): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

/** 竖向滚动容器；内容宽度撑满。 */
fun Context.scroll(): ScrollView =
    ScrollView(this).apply {
        isFillViewport = true
        layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        addView(column(), LinearLayout.LayoutParams(MATCH, WRAP))
    }

/** 取出 [scroll] 里那个唯一的竖向容器。 */
fun ScrollView.body(): LinearLayout = getChildAt(0) as LinearLayout

fun Context.hscroll(): HorizontalScrollView =
    HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

fun Context.text(
    value: CharSequence,
    size: Float = Tokens.T_BODY,
    color: Int = Tokens.INK,
    bold: Boolean = false,
): TextView =
    TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        // 保持 true：关掉之后中文的顶横会顶到 View 边界外，被父容器裁掉一截
        includeFontPadding = true
    }

/** 等宽文本：脚本、指纹、路径。 */
fun Context.mono(
    value: CharSequence,
    size: Float = Tokens.T_MONO,
    color: Int = Tokens.INK,
): TextView =
    TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = Tokens.monoFont()
        includeFontPadding = true
    }

/** 图标字形（codicon）。 */
fun Context.icon(
    glyph: String,
    size: Float = 18f,
    color: Int = Tokens.INK,
): TextView =
    TextView(this).apply {
        text = glyph
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = Tokens.iconFont(this@icon)
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

/** 圆角背景。 */
fun View.rounded(
    color: Int,
    radiusDp: Int = Tokens.R_MD,
    strokeDp: Int = 0,
    strokeColor: Int = 0,
): View =
    apply {
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = context.dpF(radiusDp.toFloat())
            if (strokeDp > 0) setStroke(context.dp(strokeDp), strokeColor)
        }
        background = shape
    }

/** 只要一个圆角 Drawable（给 background 直接赋值时用）。 */
fun Context.shape(
    color: Int,
    radiusDp: Int = Tokens.R_MD,
): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dpF(radiusDp.toFloat())
    }

/** 斜向渐变圆角块（深色进度卡用）。 */
fun Context.gradient(
    start: Int,
    end: Int,
    radiusDp: Int,
): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(radiusDp.toFloat())
    }

/** 只圆上面两个角（底部弹层）。 */
fun Context.topRounded(color: Int, radiusDp: Int): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        val r = dpF(radiusDp.toFloat())
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

/** 原型里的胶囊开关：44x26，开了是强调色。 */
fun Context.switchChip(on: Boolean, onToggle: (Boolean) -> Unit): View =
    FrameLayout(this).apply {
        layoutParams = lp(44, 26)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(13f)
            setColor(if (on) Tokens.ACCENT else Tokens.SWITCH_OFF)
        }
        val knob = View(this@switchChip).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
        }
        val size = dp(20)
        addView(knob, FrameLayout.LayoutParams(size, size).apply {
            leftMargin = dp(3)
            topMargin = dp(3)
        })
        knob.translationX = if (on) dpF(18f) else 0f
        setOnClickListener { onToggle(!on) }
    }

/** 只设一个角的圆角（聊天气泡）。 */
fun View.bubble(
    color: Int,
    radiusDp: Int,
    cornerDp: Int,
): View =
    apply {
        val big = context.dpF(radiusDp.toFloat())
        val small = context.dpF(cornerDp.toFloat())
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadii = floatArrayOf(big, big, big, big, small, small, big, big)
        }
    }

fun View.tap(block: () -> Unit): View =
    apply {
        isClickable = true
        setOnClickListener { block() }
        setBackgroundColor(0x00000000)
    }

fun View.pad(all: Int): View = apply { setPadding(context.dp(all), context.dp(all), context.dp(all), context.dp(all)) }

fun View.padH(h: Int): View = apply { setPadding(context.dp(h), paddingTop, context.dp(h), paddingBottom) }

fun Context.divider(color: Int = Tokens.LINE, heightDp: Int = 1): View =
    View(this).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
    }

/** 竖向留白。 */
fun Context.gap(dp: Int): View =
    View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, dp(dp)) }

/** 横向留白（放在 row 里）。 */
fun Context.hgap(dp: Int): View =
    View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(dp), 1) }

/** 让子 View 在 row 里占据剩余空间。 */
fun View.stretch(): View =
    apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) }

fun ViewGroup.margins(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) {
    (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
        it.setMargins(context.dp(left), context.dp(top), context.dp(right), context.dp(bottom))
        layoutParams = it
    }
}
