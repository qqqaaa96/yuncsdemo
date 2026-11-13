package sy.great.toolbox.ui.calculator

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import sy.great.toolbox.ui.theme.LocalPalette

/**
 * 函数图像计算器（WebView + math.js）。
 *
 * 【为什么用 WebView】
 *   本地实现（CalcEngine）只能覆盖手写的几十个函数，
 *   而 math.js 内置 60+ 函数（含复数、矩阵、单位、统计），
 *   并且解析器是工业级的，边界情况已被大量验证。
 *
 * 需求是“支持所有函数、最大扩展性与准确性”，
 * 纯 Kotlin 做不到，必须用 math.js。
 *
 * 【资源】
 *   assets/graph/index.html   页面骨架 + 样式
 *   assets/graph/graph.js     绘图与解析逻辑（照搬数学教学.html）
 *
 * math.js 从 CDN 加载（与 HTML 一致）：
 *   https://cdn.jsdelivr.net/npm/mathjs@12.4.2/lib/browser/math.js
 *
 * 所以本页需要联网。断网时会显示“正在加载 math.js…”。
 */
@Composable
fun GraphCalc() {
    val pal = LocalPalette.current

    // 加载状态：true = math.js 未就绪
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf("") }

    val holder = remember { androidx.compose.runtime.mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                holder.value?.let { w ->
                    (w.parent as? ViewGroup)?.removeView(w)
                    w.stopLoading()
                    w.loadUrl("about:blank")
                    w.destroy()
                }
            } catch (_: Throwable) {
            }
            holder.value = null
        }
    }

    Column(Modifier.fillMaxSize().background(pal.paper)) {
        // 顶部提示条（仅加载/错误时显示）
        if (loading || errorMsg.isNotBlank()) {
            Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (errorMsg.isNotBlank()) errorMsg
                    else "正在加载 math.js…\n首次打开需要联网",
                    fontSize = 13.sp,
                    color = pal.inkSoft,
                    lineHeight = 20.sp,
                )
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    @SuppressLint("SetJavaScriptEnabled")
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // 允许 file:// 页面加载 https 资源（math.js CDN）
                        allowFileAccess = true
                        allowContentAccess = true
                        // 不要缩放整个页面（canvas 自己处理手势）
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        // 允许混合内容（file:// 里请求 https）
                        @Suppress("DEPRECATION")
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        // 使用宽视口，页面自己控制布局
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        databaseEnabled = true
                    }

                    // 给页面一个原生桥：用于上报“math.js 已就绪”
                    addJavascriptInterface(
                        object {
                            @android.webkit.JavascriptInterface
                            fun ready() {
                                post { loading = false }
                            }

                            @android.webkit.JavascriptInterface
                            fun failed(msg: String) {
                                post {
                                    loading = false
                                    errorMsg = msg
                                }
                            }
                        },
                        "AndroidBridge",
                    )

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 页面加载完毕后轮询 math.js 是否可用
                            view?.evaluateJavascript(
                                "(function(){try{return !!window.math}catch(e){return false}})()"
                            ) { r ->
                                val ok = r?.contains("true") == true
                                loading = !ok
                                if (!ok) {
                                    // 再等一会儿重试（CDN 可能慢）
                                    view.postDelayed({
                                        view.evaluateJavascript(
                                            "(function(){try{return !!window.math}catch(e){return false}})()"
                                        ) { r2 ->
                                            loading = r2?.contains("true") != true
                                        }
                                    }, 2500)
                                }
                            }
                        }

                        @Suppress("DEPRECATION")
                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            super.onReceivedError(view, request, error)
                            // 只关心主文档失败
                            if (request?.isForMainFrame == true) {
                                loading = false
                                errorMsg = "页面加载失败：" + (error?.description ?: "未知错误")
                            }
                        }
                    }

                    setBackgroundColor(AndroidColor.WHITE)
                    loadUrl("file:///android_asset/graph/index.html")
                    holder.value = this
                }
            },
            // 覆盖加载中/错误的遮罩：正常时让 WebView 显示在最上层
            onRelease = {
                try {
                    it.destroy()
                } catch (_: Throwable) {
                }
            },
        )
    }
}
