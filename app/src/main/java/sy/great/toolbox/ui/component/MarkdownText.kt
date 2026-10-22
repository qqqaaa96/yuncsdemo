package sy.great.toolbox.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Ink
import sy.great.toolbox.ui.theme.InkSoft
import sy.great.toolbox.ui.theme.LocalPalette

/**
 * 极简 Markdown 渲染。
 *
 * 只支持更新日志里实际会出现的语法：
 *   `# ` / `## ` / `### `  标题
 *   `- ` / `* ` / `1. `   列表
 *   `**粗体**`             粗体
 *   `*斜体*`               斜体
 *   `` `代码` ``           行内代码
 *   ``` 代码块 ```          代码块
 *   `---`                  分隔线
 *   空行                  段落间距
 *
 * 为什么不引第三方库：
 *   一个 changelog 用不到完整 CommonMark，
 *   引库会加几百 KB 体积，而这里 100 行就能覆盖。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val pal = LocalPalette.current
    val lines = markdown.replace("\r\n", "\n").split("\n")

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()

            // ---- 代码块 ----
            if (line.trimStart().startsWith("```")) {
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                i++ // 跳过结束的 ```
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(pal.paperSoft, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        code.toString().trimEnd(),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Ink,
                        lineHeight = 18.sp
                    )
                }
                continue
            }

            // ---- 分隔线 ----
            if (line.trim() == "---" || line.trim() == "***") {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(pal.line)
                )
                Spacer(Modifier.height(6.dp))
                i++
                continue
            }

            // ---- 空行 ----
            if (line.isBlank()) {
                Spacer(Modifier.height(6.dp))
                i++
                continue
            }

            // ---- 标题 ----
            val h = headingLevel(line)
            if (h > 0) {
                val text = line.trimStart().substring(h).trimStart()
                // h1 用于版本更新弹窗里的版本号，需要显著大于正文
                val size = when (h) {
                    1 -> 26.sp
                    2 -> 18.sp
                    3 -> 15.sp
                    else -> 13.sp
                }
                Spacer(Modifier.height(if (h <= 2) 10.dp else 4.dp))
                Text(
                    inline(text),
                    fontSize = size,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    lineHeight = (size.value * 1.25f).sp
                )
                i++
                continue
            }

            // ---- 有序列表 ----
            val ordered = Regex("^\\s*(\\d+)\\.\\s+(.*)$").find(line)
            if (ordered != null) {
                val num = ordered.groupValues[1]
                val body = ordered.groupValues[2]
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        "$num.",
                        fontSize = 13.sp,
                        color = InkSoft,
                        modifier = Modifier.width(22.dp),
                        lineHeight = 20.sp
                    )
                    Text(inline(body), fontSize = 13.sp, color = Ink, lineHeight = 20.sp)
                }
                i++
                continue
            }

            // ---- 无序列表 ----
            val bullet = Regex("^\\s*[-*+]\\s+(.*)$").find(line)
            if (bullet != null) {
                val body = bullet.groupValues[1]
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        "•",
                        fontSize = 13.sp,
                        color = Accent,
                        modifier = Modifier.width(16.dp),
                        lineHeight = 20.sp
                    )
                    Text(inline(body), fontSize = 13.sp, color = Ink, lineHeight = 20.sp)
                }
                i++
                continue
            }

            // ---- 普通段落 ----
            Text(inline(line), fontSize = 13.sp, color = Ink, lineHeight = 20.sp)
            i++
        }
    }
}

/** 标题级别：返回 # 的数量，不是标题返回 0 */
private fun headingLevel(line: String): Int {
    val t = line.trimStart()
    var n = 0
    while (n < t.length && t[n] == '#') n++
    if (n == 0 || n > 6) return 0
    // 必须是 # 后跟空格
    if (n >= t.length || t[n] != ' ') return 0
    return n
}

/**
 * 行内样式：**粗体**、*斜体*、`代码`
 */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        // ** 粗体
        if (i + 1 < n && text[i] == '*' && text[i + 1] == '*') {
            val end = text.indexOf("**", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        // ` 代码
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = androidx.compose.ui.graphics.Color(0x14000000)
                    )
                ) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        // * 斜体
        if (text[i] == '*') {
            val end = text.indexOf('*', i + 1)
            if (end > i + 1) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}

/** 可滚动的 Markdown 内容（供弹窗使用） */
@Composable
fun MarkdownScroll(markdown: String, maxHeightDp: Int = 320) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(maxHeightDp.dp)
            .verticalScroll(rememberScrollState())
    ) {
        MarkdownText(markdown)
    }
}
