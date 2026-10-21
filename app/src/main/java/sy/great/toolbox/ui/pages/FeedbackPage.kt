package sy.great.toolbox.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sy.great.toolbox.tools.AuthApi
import sy.great.toolbox.ui.PageHeader
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.Ink
import sy.great.toolbox.ui.theme.InkSoft
import sy.great.toolbox.ui.theme.LocalPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 时间戳 → yyyy-MM-dd HH:mm */
private fun fmtTs(ts: Long): String {
    if (ts <= 0L) return ""
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts * 1000))
}

/**
 * 意见反馈页。
 *
 * 上半部分：写反馈（内容 + 可选联系方式）
 * 下半部分：我的历史反馈 + 管理员回复
 */
@Composable
fun FeedbackPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    var content by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }

    var items by remember { mutableStateOf<List<AuthApi.FeedbackItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    suspend fun reload() {
        items = withContext(Dispatchers.IO) { AuthApi.listFeedback(ctx) }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    fun submit() {
        val c = content.trim()
        if (c.length < 5) {
            msg = "请至少写 5 个字"
            bad = true
            return
        }
        busy = true
        msg = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                AuthApi.sendFeedback(ctx, c, contact.trim())
            }
            busy = false
            if (r.ok) {
                content = ""
                contact = ""
                msg = "已提交，感谢反馈"
                bad = false
                reload()
            } else {
                msg = if (r.error.isNotEmpty()) r.error else "提交失败"
                bad = true
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(pal.paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        PageHeader(title = "意见反馈", onBack = onBack)
        Spacer(Modifier.height(16.dp))

        Text("反馈内容", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = content,
            onValueChange = { if (it.length <= 2000) content = it },
            placeholder = { Text("描述你遇到的问题或建议") },
            minLines = 5,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            content.length.toString() + " / 2000",
            fontSize = 11.sp,
            color = InkSoft,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )

        Spacer(Modifier.height(12.dp))
        Text("联系方式（可选）", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = contact,
            onValueChange = { if (it.length <= 100) contact = it },
            placeholder = { Text("邮箱 / QQ，方便回复你") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(
                    if (busy) pal.line else Accent,
                    RoundedCornerShape(12.dp)
                )
                .then(if (busy) Modifier else Modifier.clickable { submit() }),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (busy) "提交中..." else "提交反馈",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (busy) InkSoft else Color.White
            )
        }

        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(msg, fontSize = 12.sp, color = if (bad) Bad else Good)
        }

        Spacer(Modifier.height(28.dp))
        Text("我的反馈", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
        Spacer(Modifier.height(10.dp))

        if (loading) {
            Text("加载中...", fontSize = 12.sp, color = InkSoft)
        } else if (items.isEmpty()) {
            Text("还没有提交过反馈", fontSize = 12.sp, color = InkSoft)
        } else {
            for (it in items) {
                FeedbackCard(it, pal)
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun FeedbackCard(item: AuthApi.FeedbackItem, pal: sy.great.toolbox.ui.theme.Palette) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(pal.paperSoft, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(fmtTs(item.createdAt), fontSize = 11.sp, color = InkSoft)
            if (item.replied) {
                Text("已回复", fontSize = 11.sp, color = Good)
            } else {
                Text("待处理", fontSize = 11.sp, color = InkSoft)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.content, fontSize = 13.sp, color = Ink, lineHeight = 20.sp)

        if (item.contact.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("联系方式：" + item.contact, fontSize = 11.sp, color = InkSoft)
        }

        if (item.replied) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(pal.paper, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text("官方回复", fontSize = 11.sp, color = Accent, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(item.reply, fontSize = 13.sp, color = Ink, lineHeight = 19.sp)
                if (item.repliedAt > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(fmtTs(item.repliedAt), fontSize = 10.sp, color = InkSoft)
                }
            }
        }
    }
}
