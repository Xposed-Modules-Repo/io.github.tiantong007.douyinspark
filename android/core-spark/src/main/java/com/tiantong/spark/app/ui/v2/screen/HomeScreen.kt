package com.tiantong.spark.app.ui.v2.screen

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tiantong.spark.app.config.model.ConversationSnapshot
import com.tiantong.spark.app.ui.common.AvatarLoader
import com.tiantong.spark.app.ui.v2.comp.MATCH
import com.tiantong.spark.app.ui.v2.comp.WRAP
import com.tiantong.spark.app.ui.v2.comp.chips
import com.tiantong.spark.app.ui.v2.comp.column
import com.tiantong.spark.app.ui.v2.comp.divider
import com.tiantong.spark.app.ui.v2.comp.dp
import com.tiantong.spark.app.ui.v2.comp.dpF
import com.tiantong.spark.app.ui.v2.comp.emptyState
import com.tiantong.spark.app.ui.v2.comp.gap
import com.tiantong.spark.app.ui.v2.comp.gradient
import com.tiantong.spark.app.ui.v2.comp.icon
import com.tiantong.spark.app.ui.v2.comp.lp
import com.tiantong.spark.app.ui.v2.comp.rounded
import com.tiantong.spark.app.ui.v2.comp.row
import com.tiantong.spark.app.ui.v2.comp.shape
import com.tiantong.spark.app.ui.v2.comp.switchChip
import com.tiantong.spark.app.ui.v2.comp.text
import com.tiantong.spark.app.ui.v2.nav.PageView
import com.tiantong.spark.app.ui.v2.nav.Shell
import com.tiantong.spark.app.ui.v2.theme.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页：今天还有谁没续、谁续过了，拨一下开关就进待发队列。
 *
 * 层级：深色进度卡（一眼看到进度和下一轮时间）→ 三档分段（全部/待发/已续）
 * → 名单（头像 / 名字 / 状态 / 天数 / 开关）→ 底部说明卡。
 */
class HomeScreen(private val shell: Shell) : PageView {

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
        root.addView(hero())
        root.addView(segments())
        root.addView(listOfConversations())
    }

    // ── 深色进度卡 ────────────────────────────────────────────────

    private fun hero(): View {
        val state = shell.state
        val done = state.doneCount()
        val lit = state.litCount()
        val tpl = state.templateCount().coerceAtLeast(0)
        val todo = state.todoCount()
        val templates = state.config.groups.size

        val frame = FrameLayout(ctx).apply {
            background = ctx.gradient(Tokens.HERO_START, Tokens.HERO_END, Tokens.R_XL)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, ctx.dpF(Tokens.R_XL.toFloat()))
                }
            }
            layoutParams = ctx.lp(MATCH, WRAP, top = 10, bottom = 12)
        }

        // 右上角红光晕，跟原型的一个意思。
        val glow = ctx.dp(170)
        frame.addView(
            View(ctx).apply { background = GlowDrawable(Tokens.ACCENT) },
            FrameLayout.LayoutParams(glow, glow).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = -ctx.dp(50)
                rightMargin = -ctx.dp(40)
            },
        )

        val body = ctx.column().apply {
            setPadding(ctx.dp(18), ctx.dp(12), ctx.dp(18), ctx.dp(15))
        }
        frame.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))

        // 记录时间：最近一次抓到快照的时间，代替原来那句「今天」。
        body.addView(
            ctx.row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    View(ctx).apply { background = ctx.shape(0xFF35D0A5.toInt(), 5) },
                    ctx.lp(5, 5, right = 7),
                )
                addView(ctx.text("记录时间 ${recordTime()}", 11.5f, Tokens.ON_HERO_DIM))
            },
        )

        // 两个比值：今天已续 / 已燃，分母都取「联动模板的会话数」，跟手机上的口径一致。
        body.addView(
            ctx.row().apply {
                gravity = Gravity.BOTTOM
                addView(ratio("$done", "$tpl 已续"), ctx.lp(WRAP, WRAP))
                addView(View(ctx), ctx.lp(0, 1, 1f))          // 撑开中间，把第二个顶到右边
                addView(ratio("$lit", "$tpl 已燃"), ctx.lp(WRAP, WRAP))
            },
            ctx.lp(MATCH, WRAP, top = 0),
        )

        // 三个胶囊：待续(0 个时显示「今天都续完了」，与原型口径一致) / 下一轮 / 模板
        // 行距统一由行容器 top=6 控制，子项不再各自带 top，调间距只动这一个数
        val pills = ctx.row().apply {
            addView(pill(if (todo == 0) "今天都续完了" else "$todo 个待续", fire = true), ctx.lp(WRAP, WRAP, right = 8))
            addView(pill("下一轮 ${nextRound()}"), ctx.lp(WRAP, WRAP, right = 8))
            addView(pill("模板 $templates 个"), ctx.lp(WRAP, WRAP))
        }
        body.addView(pills, ctx.lp(MATCH, WRAP, top = 6))
        return frame
    }

    /** 胶囊标签：待续数 / 下一轮时间 / 模板数。 */
    private fun pill(label: String, fire: Boolean = false): View =
        ctx.text(label, 12f, if (fire) Tokens.PILL_FIRE_FG else Tokens.ON_HERO_DIM).apply {
            background = ctx.shape(if (fire) Tokens.PILL_FIRE_BG else Tokens.PILL_BG, 9)
            // 上下留 8dp：6dp 时中文的顶横会顶到胶囊边沿，被裁掉一截
            setPadding(ctx.dp(11), ctx.dp(8), ctx.dp(11), ctx.dp(8))
            minimumHeight = ctx.dp(28)
            gravity = Gravity.CENTER
            maxLines = 1
        }

    /** 大数字 + 后缀，例如 `16 / 18 已续`。 */
    private fun ratio(big: String, suffix: String): View =
        ctx.row().apply {
            gravity = Gravity.BOTTOM
            addView(ctx.text(big, 30f, Tokens.ON_HERO, bold = true))
            addView(ctx.text(" / $suffix", 14f, Tokens.ON_HERO_FAINT, bold = true), ctx.lp(WRAP, WRAP, left = 3, bottom = 2))
        }

    private fun recordTime(): String =
        if (shell.state.recordedAt() <= 0) "还没抓到" else
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(shell.state.recordedAt()))

    private fun nextRound(): String {
        val hour = shell.state.config.alarmHour
        return if (hour < 0) "未设" else String.format(Locale.US, "%02d:%02d", hour, shell.state.config.alarmMinute)
    }

    // ── 五档分段 ──────────────────────────────────────────────────

    private fun segments(): View {
        val state = shell.state
        val labels = listOf(
            "全部 ${state.visible().size}",
            "已燃 ${state.litCount()}",
            "待燃 ${state.waitingCount()}",
            "待续 ${state.todoCount()}",
            "无火花 ${state.deadCount()}",
        )
        return ctx.chips(labels, state.filter) { index ->
            state.filter = index
            shell.rebuild()
        }
    }

    // ── 名单 ──────────────────────────────────────────────────────

    private fun listOfConversations(): View {
        val state = shell.state
        val items = state.shown()
        val box = ctx.column().apply { background = ctx.shape(Tokens.SURFACE, 18) }

        if (items.isEmpty()) {
            box.addView(emptyStateFor())
            return box
        }

        items.forEachIndexed { index, snapshot ->
            box.addView(rowOf(snapshot))
            if (index != items.lastIndex) box.addView(ctx.divider())
        }
        return box
    }

    private fun emptyStateFor(): View {
        val state = shell.state
        return when {
            state.visible().isEmpty() ->
                ctx.emptyState(Tokens.Icon.INFO, "还没有会话数据", "进一次抖音，回来点右上角刷新")

            state.filter == 3 && state.todoCount() == 0 ->
                ctx.emptyState(Tokens.Icon.CHECK, "今天都续完了", "该续的 ${state.renewable().size} 个都已经发过，明天再来")

            else -> ctx.emptyState(Tokens.Icon.SEARCH, "这一档没有人", "换一个分类，或清掉搜索词")
        }
    }

    private fun rowOf(snapshot: ConversationSnapshot): View {
        val state = shell.state
        val sent = state.sentToday(snapshot.conversationId)
        val picked = state.isPicked(snapshot.conversationId)

        return ctx.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(15), ctx.dp(12), ctx.dp(15), ctx.dp(12))
            isClickable = true
            setOnClickListener { shell.openEditor(conversationId = snapshot.conversationId) }

            addView(
                ImageView(ctx).apply {
                    AvatarLoader.load(
                        ctx,
                        this,
                        snapshot.peerUid.ifBlank { snapshot.conversationId },
                        snapshot.displayName,
                        42,
                    )
                },
                ctx.lp(42, 42, right = 12),
            )

            val who = ctx.column()
            val nameRow = ctx.row().apply { gravity = Gravity.CENTER_VERTICAL }
            nameRow.addView(
                ctx.text(snapshot.displayName, 14.5f, Tokens.INK, bold = true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                ctx.lp(WRAP, WRAP, right = 6),
            )
            if (snapshot.isGroup) nameRow.addView(tag("群", Tokens.INFO, Tokens.INFO_SOFT))
            nameRow.addView(statusTag(snapshot))
            who.addView(nameRow, ctx.lp(MATCH, WRAP))

            val sub = subtitle(snapshot)
            if (sub.isNotBlank()) {
                who.addView(
                    ctx.text(sub, 12f, Tokens.INK_DIM).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                    ctx.lp(MATCH, WRAP, top = 1),
                )
            }
            addView(who, ctx.lp(0, WRAP, 1f))

            addView(
                ctx.row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ctx.text("${snapshot.days}", 15f, Tokens.INK, bold = true), ctx.lp(WRAP, WRAP))
                    addView(ctx.text("天", 10.5f, Tokens.INK_DIM), ctx.lp(WRAP, WRAP, left = 1, bottom = 1))
                },
                ctx.lp(WRAP, WRAP, right = 12),
            )

            addView(
                ctx.switchChip(picked) { on ->
                    if (state.isPicked(snapshot.conversationId) != on) state.togglePick(snapshot.conversationId)
                    shell.rebuild()
                },
                ctx.lp(44, 26),
            )
        }
    }

    /**
     * 行内状态标签，和首页五档一一对应：
     * 无火花 / 可点亮 / 重燃中 / 待燃 / 待续 / 已燃。
     */
    private fun statusTag(snapshot: ConversationSnapshot): TextView {
        val state = shell.state
        val sent = state.sentToday(snapshot.conversationId)
        return when {
            !snapshot.hasSpark && snapshot.stateText == "可点亮" -> tag("可点亮", Tokens.INFO, Tokens.INFO_SOFT)
            !snapshot.hasSpark -> tag("无火花", Tokens.MUTED, Tokens.MUTED_SOFT)
            snapshot.state == 1 -> tag("已燃", Tokens.OK, Tokens.OK_SOFT)
            snapshot.state == 3 -> tag("重燃中", 0xFFB45309.toInt(), Tokens.WARN_SOFT)
            sent -> tag("待燃", Tokens.WARN, Tokens.WARN_SOFT)
            else -> tag("待续", Tokens.WARN, Tokens.WARN_SOFT)
        }
    }

    private fun tag(label: String, color: Int, tint: Int): TextView =
        ctx.text(label, 10.5f, color, bold = true).apply {
            setPadding(ctx.dp(7), ctx.dp(2), ctx.dp(7), ctx.dp(2))
            background = ctx.shape(tint, 6)
        }

    private fun subtitle(snapshot: ConversationSnapshot): String {
        val config = shell.state.config
        val parts = mutableListOf<String>()
        if (config.conversation(snapshot.conversationId)?.body.isNullOrBlank()) parts += "未设置脚本"
        if (snapshot.days > 0) parts += "火花 ${snapshot.days} 天"
        val flame = snapshot.stateText.ifBlank { snapshot.flameText }
        if (flame.isNotBlank() && flame != snapshot.days.toString()) parts += flame
        if (snapshot.expireText.isNotBlank()) parts += snapshot.expireText
        if (parts.isEmpty() && snapshot.recoverNeedDays > 0) parts += "需 ${snapshot.recoverNeedDays} 天"
        return parts.joinToString(" · ").ifBlank { "还没开始" }
    }
}

/** 右上角的红色光晕，画一个径向渐变圆。 */
private class GlowDrawable(private val color: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }

    override fun draw(canvas: Canvas) {
        val r = bounds.width().coerceAtMost(bounds.height()) / 2f
        if (r <= 0f) return
        paint.shader = RadialGradient(
            bounds.exactCenterX(),
            bounds.exactCenterY(),
            r,
            intArrayOf((color and 0x00FFFFFF) or 0x8C000000.toInt(), color and 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), r, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
