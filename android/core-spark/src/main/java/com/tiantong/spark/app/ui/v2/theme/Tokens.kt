package com.tiantong.spark.app.ui.v2.theme

import android.content.Context
import android.graphics.Typeface

/**
 * 设计令牌：颜色、间距、字号、圆角、图标字形。
 *
 * 全模块只在这里出现字面量，组件与页面一律引用令牌 —— 换皮只改这一个文件。
 * 配色基调：抖音红作唯一强调色，冷灰作底，状态色只有三种（已燃/待续/异常）。
 */
object Tokens {

    // ── 底色与墨色 ────────────────────────────────────────────────
    val BG = 0xFFF4F5F7.toInt()
    val SURFACE = 0xFFFFFFFF.toInt()
    val SURFACE_SUNK = 0xFFEDEFF3.toInt()
    val INK = 0xFF14161C.toInt()
    val INK_DIM = 0xFF8A8F9A.toInt()
    val INK_FAINT = 0xFFB9BEC8.toInt()
    val LINE = 0x14000000

    // ── 强调与状态 ────────────────────────────────────────────────
    val ACCENT = 0xFFFE2C55.toInt()
    val ACCENT_SOFT = 0x14FE2C55
    val OK = 0xFF12B886.toInt()
    val OK_SOFT = 0x1412B886
    val WARN = 0xFFF59E0B.toInt()
    val WARN_SOFT = 0x1AF59E0B
    val DANGER = 0xFFE5484D.toInt()
    val DANGER_SOFT = 0x14E5484D
    val INFO = 0xFF3B82F6.toInt()
    val INFO_SOFT = 0x143B82F6

    // ── 深色进度卡（原型里的 hero） ──────────────────────────────
    val HERO_START = 0xFF1B1D24.toInt()
    val HERO_END = 0xFF2B2F3A.toInt()
    val ON_HERO = 0xFFFFFFFF.toInt()
    val ON_HERO_DIM = 0x9EFFFFFF.toInt()
    val ON_HERO_FAINT = 0x8CFFFFFF.toInt()
    val PILL_BG = 0x1AFFFFFF
    val PILL_FIRE_BG = 0x2EFE2C55
    val PILL_FIRE_FG = 0xFFFF8FA5.toInt()
    val SEG_BG = 0xFFE7E9EE.toInt()
    val SWITCH_OFF = 0xFFDDE0E6.toInt()
    val CHEV = 0xFFC3C7CF.toInt()

    // 深色代码块（原型 .editor）：输入框和预览共用
    val EDITOR_BG = 0xFF17191F.toInt()
    val EDITOR_TEXT = 0xFFE4E6EB.toInt()
    val EDITOR_DIM = 0xFF6B7280.toInt().toInt()

    /** 次要文字：再淡一档，只用于说明。 */
    val MUTED = 0xFFA0A5B0.toInt()
    val MUTED_SOFT = 0x0D000000

    // ── 圆角与间距（dp） ─────────────────────────────────────────
    const val R_SM = 8
    const val R_MD = 12
    const val R_LG = 16
    const val R_XL = 22
    const val R_PILL = 100

    const val GAP_XS = 4
    const val GAP_S = 8
    const val GAP_M = 12
    const val GAP_L = 16
    const val GAP_XL = 24

    const val PAGE_PAD = 16

    // ── 字号（sp） ───────────────────────────────────────────────
    const val T_HERO = 30f
    const val T_TITLE = 19f
    const val T_SECTION = 13f
    const val T_BODY = 15f
    const val T_SUB = 13f
    const val T_TINY = 11f
    const val T_MONO = 13f

    // ── 图标（VSCode codicon，随模块打包，离线可用） ─────────────────
    object Icon {
        const val FIRE = "\ueaf2"
        const val TEMPLATE = "\ueaf0"
        const val GEAR = "\ueb51"
        const val BACK = "\uea9b"
        const val CHEVRON = "\ueab6"
        const val ADD = "\uea60"
        const val GRAPH = "\ueb03"
        const val WAND = "\uebcf"
        const val DISCARD = "\ueae2"
        const val CLEAR = "\ueabf"
        const val FOLD = "\ueaf5"
        const val UNFOLD = "\ueb73"
        const val CLIPPY = "\ueac0"
        const val CLONE = "\uea78"
        const val SELECT = "\ueb85"
        const val VARS = "\uebb8"
        const val PREVIEW = "\ueb2f"
        const val BOOK = "\ueaa4"
        const val SERVER = "\ueba2"
        const val INFO = "\uea74"
        const val CHECK = "\ueab2"
        const val CLOSE = "\uea76"
        const val SEARCH = "\uea6d"
        const val REFRESH = "\ueb37"
        const val PLAY = "\ueb2c"
        const val COPY = "\uebcc"
        const val DOWNLOAD = "\ueac2"
        const val IMPORT = "\ueac3"
        const val EDIT = "\uea73"
        const val TRASH = "\uea81"
        const val LINK = "\ueb15"
        const val MENU = "\ueb94"
        const val MORE = "\uea7c"
        const val LOCK = "\uea75"
        const val SHIELD = "\uebe0"
        const val OLD = "\ueb28"
    }

    /** 图标字体懒加载；取不到就退回默认字体（界面不崩）。 */
    private var iconFace: Typeface? = null

    fun iconFont(context: Context): Typeface =
        iconFace ?: runCatching { Typeface.createFromAsset(context.assets, "fonts/codicon.ttf") }
            .getOrDefault(Typeface.DEFAULT)
            .also { iconFace = it }

    private var monoFace: Typeface? = null

    /** 等宽：脚本编辑用，保证中英混排对齐。 */
    fun monoFont(): Typeface =
        monoFace ?: runCatching { Typeface.create("monospace", Typeface.NORMAL) }
            .getOrDefault(Typeface.MONOSPACE)
            .also { monoFace = it }
}
