package sy.great.toolbox.ui.component

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import sy.great.toolbox.ui.theme.LocalPalette
import java.nio.charset.StandardCharsets

/**
 * Markdown 渲染（WebView）。
 *
 * 照搬 KernelSU 的 GithubMarkdown 方案：
 *   CommonMark 解析 → HTML → WebView 显示。
 *
 * 为什么用 WebView 而不是纯 Compose：
 *   更新日志/公告的排版靠 GitHub 的 markdown.css，
 *   里面有大量细碎样式（hr 粗条、列表缩进、段落间距、表格），
 *   纯 Compose 重写不仅工作量大，而且很难做到逐像素一致。
 *
 * 与 KernelSU 原版的差异（全部是与本项目相关的最小改写）：
 *   · 去掉 okhttp 网络拦截 → 直接禁用网络加载（日志不含图片）
 *   · 去掉 WebViewAssetLoader → 直接把 CSS 内联进 HTML
 *     （assets 用 file:// 加载会被 WebView 安全策略拦，内联最稳）
 *   · 去掉 RegistryUrls / LocalUiMode / ksuApp 等 KernelSU 内部类
 *   · 颜色从 LocalPalette 取，跟随本项目主题
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownWebView(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val dark = isSystemInDarkTheme()

    // 1) Markdown → HTML（与 KernelSU 同一套扩展）
    val rendered = remember(markdown) {
        val extensions = listOf(
            TablesExtension.create(),
            StrikethroughExtension.builder().requireTwoTildes(true).build(),
            AutolinkExtension.create(),
            TaskListItemsExtension.create(),
        )
        val parser = Parser.builder().extensions(extensions).build()
        val renderer = HtmlRenderer.builder()
            .extensions(extensions)
            .escapeHtml(true)   // 禁止内联 HTML，防注入
            .sanitizeUrls(true) // 禁止 javascript: 等危险链接
            .build()
        renderer.render(parser.parse(markdown))
    }

    // 2) 拼 HTML：CSS 直接从 assets 读出来内联
    val html = remember(rendered, dark, pal) {
        val cssFiles = listOf(
            if (dark) "colors_dark.css" else "colors_light.css",
            "markdown.css",
            if (dark) "syntax_dark.css" else "syntax_light.css",
            "font.css",
        )
        val css = buildString {
            cssFiles.forEach { name ->
                try {
                    ctx.assets.open("webview/$name")
                        .bufferedReader(StandardCharsets.UTF_8)
                        .use { append(it.readText()).append('\n') }
                } catch (_: Throwable) {
                    // 缺文件不影响主流程，最多少一段样式
                }
            }
        }

        // 主题变量覆盖：让 GitHub 风格的颜色跟随本项目 Palette
        val override = """
            :root {
                --hr-background: ${pal.line.toCss()};
                --textPrimary: ${pal.ink.toCss()};
                --link: ${pal.accent.toCss()};
            }
            html, body {
                margin: 0; padding: 0;
                background: transparent;
                color: ${pal.ink.toCss()};
            }
            .markdown-body { padding: 0; }
            .markdown-body h1 {
                font-size: 28px;
                font-weight: 700;
                border-bottom: 1px solid ${pal.line.toCss()};
                padding-bottom: 6px;
                margin: 0 0 16px;
            }
            .markdown-body hr {
                height: 4px;
                margin: 20px 0;
                background-color: ${pal.line.toCss()};
                border: 0;
                border-radius: 2px;
            }
        """.trimIndent()

        buildString {
            append("<!DOCTYPE html><html")
            append(if (dark) " data-color-mode=\"dark\"" else " data-color-mode=\"light\"")
            append(" dir=\"ltr\"><head>")
            append("<meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=0\">")
            append("<style>").append(css).append("</style>")
            append("<style>").append(override).append("</style>")
            append("</head><body>")
            append("<main class=\"markdown-body\">").append(rendered).append("</main>")
            append("</body></html>")
        }
    }

    // 完全照搬 KernelSU GithubMarkdown 的 AndroidView 结构：
    //   · 外层 FrameLayout，不是直接返回 WebView
    //   · WebView 显式设 layoutParams
    //
    // 原因：WindowDialog 的 content 会被自定义 Layout 测量，
    // 直接返回一个没有 layoutParams 的 WebView，
    // 在 measure 阶段会因 layoutParams 为 null 抛异常（闪退）。
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val frameLayout = FrameLayout(context)
            val webView = try {
                WebView(context)
            } catch (t: Throwable) {
                // WebView 创建失败（如 context 不是 Activity）时返回空容器，不崩溃
                crashLog(context, "WebView 创建失败", t)
                null
            }
            if (webView == null) return@AndroidView frameLayout
            webView.apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false

                settings.apply {
                    // 更新日志没有交互，全部关掉，只保留排版
                    javaScriptEnabled = false
                    domStorageEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkImage = true
                    blockNetworkLoads = true
                    setSupportZoom(false)
                    textZoom = 100
                }

                // 与 KernelSU 一致：宽度撑满、高度随内容
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }
            frameLayout.addView(webView)
            frameLayout
        },
        update = { frameLayout ->
            val webView = frameLayout.getChildAt(0) as? WebView ?: return@AndroidView
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(
                    null, html, "text/html", StandardCharsets.UTF_8.name(), null
                )
            }
        },
    )
}

/** Compose 颜色 → CSS rgba 字符串 */
private fun androidx.compose.ui.graphics.Color.toCss(): String {
    val r = (red * 255).toInt()
    val g = (green * 255).toInt()
    val b = (blue * 255).toInt()
    val a = alpha
    return "rgba($r,$g,$b,$a)"
}
