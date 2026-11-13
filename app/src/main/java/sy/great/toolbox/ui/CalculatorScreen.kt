package sy.great.toolbox.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import sy.great.toolbox.ui.theme.LocalPalette

/**
 * 计算器页面（9 合 1）。
 *
 * 为什么用 WebView：
 *   函数图像需要表达式解析 + Canvas 曲线绘制，这套实现已存在于 assets/calc，
 *   直接在 WebView 里跑跟原 HTML 行为完全一致；用 Kotlin 重写得重写
 *   词法分析、语法树、绘图（约 800 行）且容易出偏差。
 *
 * 资源位置：app/src/main/assets/calc/
 *   index.html  页面骨架 + 样式
 *   engine.js   表达式引擎（从数学教学.html 提取）
 *   plot.js     坐标系 / 曲线绘制（同上）
 *   calc.js     9 个计算器的业务逻辑
 *
 * 全部本地加载，不联网。
 */
@Composable
fun CalculatorScreen(onBack: () -> Unit = {}) {
    val pal = LocalPalette.current

    // WebView 必须持有引用，在 onDispose 里销毁，否则会泄漏。
    //
    // 用 remember 包一层：不然每次重组都会重置为 null，
    // 导致 onDispose 时拿不到实例、WebView 泄漏。
    val webHolder = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<WebView?>(null)
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webHolder.value?.let { w ->
                    (w.parent as? ViewGroup)?.removeView(w)
                    w.stopLoading()
                    w.loadUrl("about:blank")
                    w.destroy()
                }
            } catch (_: Throwable) {
            }
            webHolder.value = null
        }
    }

    Column(Modifier.fillMaxSize().background(pal.paper)) {
        // 顶部返回栏（与其它子页一致）
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            PageHeader(title = "计算器", onBack = onBack)
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    @SuppressLint("SetJavaScriptEnabled")
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // 禁止缩放页面（内部 canvas 自己处理手势）
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        // 允许 file:// 加载同目录的 js
                        allowFileAccess = true
                        allowContentAccess = true
                        mediaPlaybackRequiresUserGesture = false
                    }
                    webViewClient = WebViewClient()
                    setBackgroundColor(0xFFFFFFFF.toInt())
                    // 本地 assets 加载：file:///android_asset/calc/index.html
                    loadUrl("file:///android_asset/calc/index.html")
                    webHolder.value = this
                }
            },
        )
    }
}
