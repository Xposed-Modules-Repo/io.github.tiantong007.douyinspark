package com.tiantong.spark.app.ui.v2.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.tiantong.spark.app.ui.common.Markdown
import com.tiantong.spark.app.ui.v2.comp.MATCH
import com.tiantong.spark.app.ui.v2.comp.WRAP
import com.tiantong.spark.app.ui.v2.comp.column
import com.tiantong.spark.app.ui.v2.comp.divider
import com.tiantong.spark.app.ui.v2.comp.dp
import com.tiantong.spark.app.ui.v2.comp.emptyState
import com.tiantong.spark.app.ui.v2.comp.lp
import com.tiantong.spark.app.ui.v2.comp.row
import com.tiantong.spark.app.ui.v2.comp.tbtn
import com.tiantong.spark.app.ui.v2.comp.tip
import com.tiantong.spark.app.ui.v2.nav.PageView
import com.tiantong.spark.app.ui.v2.nav.Shell
import com.tiantong.spark.app.ui.v2.theme.Tokens

/**
 * 使用文档。README 在构建时被复制成 assets/docs/manual.md，这里把它转成 HTML 显示。
 *
 * 用 WebView 而不用 TextView：文档里有表格、代码块、锚点跳转，纯文本控件排不出来。
 * JS 一律关掉，页面里只有我们自己的 Markdown 产物。
 */
class DocsScreen(shell: Shell) : PageView {

    private val context = shell.context
    private var host: FrameLayout? = null
    private var web: WebView? = null
    private var rawText: String = ""

    override fun root(): View = context.column().apply {
        setBackgroundColor(Tokens.BG)
        addView(
            context.row().apply {
                setPadding(context.dp(16), context.dp(4), context.dp(16), context.dp(10))
                addView(
                    context.tbtn(Tokens.Icon.COPY, "复制全文") {
                        val clip = ClipData.newPlainText("使用文档", rawText)
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                        context.tip("已复制全文")
                    },
                    context.lp(WRAP, WRAP),
                )
                addView(
                    context.tbtn(Tokens.Icon.LINK, "分享") {
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, rawText)
                                    },
                                    "分享使用文档",
                                ),
                            )
                        }
                    },
                    context.lp(WRAP, WRAP, left = 7),
                )
            },
            context.lp(MATCH, WRAP),
        )
        addView(View(context).also { it.setBackgroundColor(Tokens.LINE) }, context.lp(MATCH, 1))
        val frame = FrameLayout(context).apply { setBackgroundColor(Tokens.BG) }
        addView(frame, context.lp(MATCH, MATCH, 1f))
        host = frame
        load()
    }

    override fun refresh() {
        if (web == null) load()
    }

    private fun load() {
        val frame = host ?: return
        val text = runCatching {
            context.assets.open("docs/manual.md").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            frame.removeAllViews()
            frame.addView(
                context.emptyState(Tokens.Icon.BOOK, "读不到文档", "安装包里的 assets/docs/manual.md 缺失，可能是构建时没跑 syncManual"),
                FrameLayout.LayoutParams(MATCH, WRAP),
            )
            return
        }
        // HTML 注释（版本锚点之类）Markdown 会原样透传，先剔掉
        rawText = text.replace(Regex("<!--[\\s\\S]*?-->"), "").trim()
        val body = runCatching { Markdown.toHtml(rawText) }.getOrElse { "<pre>${escape(rawText)}</pre>" }
        val view = web ?: newWebView().also {
            web = it
            frame.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        view.loadDataWithBaseURL(null, wrap(body), "text/html", "utf-8", null)
    }

    private fun newWebView(): WebView = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        settings.javaScriptEnabled = false
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** 文档样式跟着应用走：白卡底色、强调色链接、深色代码块，和界面其他部分一致。 */
    private fun wrap(body: String): String = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>
  :root { color-scheme: light; }
  html { background: #F4F5F7; min-height: 100%; }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body {
    margin: 12px 12px 24px; padding: 18px 16px 28px;
    background: #FFFFFF; color: #14161C;
    border-radius: 18px; min-height: calc(100% - 36px);
    font: 15px/1.78 -apple-system, "Noto Sans CJK SC", "PingFang SC", sans-serif;
    word-break: break-word;
  }
  h1 { font-size: 23px; line-height: 1.3; font-weight: 750; margin: 18px 0 10px; }
  h2 { font-size: 19px; line-height: 1.35; font-weight: 750; margin: 26px 0 10px; padding-top: 6px; }
  h3 { font-size: 16px; font-weight: 650; margin: 20px 0 8px; }
  h4 { font-size: 15px; font-weight: 650; margin: 16px 0 6px; color: #4A4E58; }
  p { margin: 10px 0; }
  a { color: #FE2C55; text-decoration: none; }
  strong { font-weight: 650; }
  ul, ol { margin: 10px 0; padding-left: 22px; }
  li { margin: 5px 0; }
  hr { border: none; border-top: 1px solid rgba(0,0,0,.08); margin: 22px 0; }
  code {
    background: #EDEFF3; border-radius: 6px; padding: 1.5px 5px;
    font: 13px/1.5 "JetBrains Mono", monospace; color: #B3123A;
  }
  pre {
    background: #17191F; border-radius: 14px; padding: 13px 14px;
    overflow-x: auto; margin: 12px 0;
  }
  pre code { background: none; color: #E4E6EB; padding: 0; font-size: 12.5px; line-height: 1.75; }
  blockquote {
    margin: 12px 0; padding: 11px 14px; background: #FEF0F3;
    border-left: 3px solid #FE2C55; border-radius: 0 10px 10px 0; color: #8B1F35;
  }
  blockquote p { margin: 4px 0; }
  table { border-collapse: collapse; width: 100%; margin: 12px 0; font-size: 13px; }
  th, td { border: 1px solid #E7E9EE; padding: 7px 9px; text-align: left; vertical-align: top; }
  th { background: #F4F5F7; font-weight: 650; }
  img { max-width: 100%; height: auto; border-radius: 12px; }
</style></head><body>$body</body></html>
""".trimIndent()
}
