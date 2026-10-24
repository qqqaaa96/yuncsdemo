package sy.great.toolbox.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sy.great.toolbox.tools.AuthApi
import sy.great.toolbox.tools.RankApi
import sy.great.toolbox.ui.PageHeader
import sy.great.toolbox.ui.pressable
import sy.great.toolbox.ui.theme.Accent
import sy.great.toolbox.ui.theme.Bad
import sy.great.toolbox.ui.theme.Good
import sy.great.toolbox.ui.theme.LocalPalette
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 隐私设置。
 *
 * 作用：控制自己在<b>排行榜</b>上暴露哪些信息。
 *
 * 默认值（与服务端一致）：
 *   头像 ✓ / 称号 ✓ / UID ✓ / 邮箱 ✗ / 连续签到 ✓ / 积分 ✓
 *
 * 注意：
 *   无论怎么设置，你自己总能看到自己的全部信息。
 *   这里只影响<b>别人</b>在排行榜看到的内容。
 */
@Composable
fun PrivacySettingsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var msgBad by remember { mutableStateOf(false) }

    // 与服务端 PRIVACY_DEFAULTS 对应
    var showAvatar by remember { mutableStateOf(true) }
    var showTag by remember { mutableStateOf(true) }
    var showUid by remember { mutableStateOf(true) }
    var showEmail by remember { mutableStateOf(false) }
    var showStreak by remember { mutableStateOf(true) }
    var showPoints by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { AuthApi.me(ctx) }
        loading = false
        if (!r.ok) {
            msg = r.error.ifBlank { "加载失败" }
            msgBad = true
            return@LaunchedEffect
        }
        val p = r.obj("user")?.optJSONObject("privacy")
        if (p != null) {
            showAvatar = p.optBoolean("showAvatar", true)
            showTag = p.optBoolean("showTag", true)
            showUid = p.optBoolean("showUid", true)
            showEmail = p.optBoolean("showEmail", false)
            showStreak = p.optBoolean("showStreak", true)
            showPoints = p.optBoolean("showPoints", true)
        }
    }

    fun save() {
        if (saving) return
        saving = true
        msg = ""
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                RankApi.savePrivacy(
                    ctx,
                    mapOf(
                        "showAvatar" to showAvatar,
                        "showTag" to showTag,
                        "showUid" to showUid,
                        "showEmail" to showEmail,
                        "showStreak" to showStreak,
                        "showPoints" to showPoints,
                    )
                )
            }
            saving = false
            if (ok) {
                msg = "已保存"
                msgBad = false
            } else {
                msg = "保存失败，请重试"
                msgBad = true
            }
        }
    }

    Column(Modifier.fillMaxSize().background(pal.paper)) {
        PageHeader("隐私设置", onBack)

        Column(Modifier.padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(8.dp))

            // 说明
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text(
                    "以下设置控制你在排行榜里对外展示的信息。",
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    color = pal.inkSoft,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "你自己始终能看到自己的全部信息。",
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    color = pal.inkSoft,
                )
            }

            Spacer(Modifier.height(14.dp))

            Card {
                SwitchPreference(
                    title = "显示头像",
                    summary = "关闭后别人在排行榜看到首字母",
                    checked = showAvatar,
                    onCheckedChange = { showAvatar = it },
                )
                SwitchPreference(
                    title = "显示称号",
                    summary = "含炫彩流光效果",
                    checked = showTag,
                    onCheckedChange = { showTag = it },
                )
                SwitchPreference(
                    title = "显示用户名",
                    summary = "关闭后显示为「匿名用户」",
                    checked = showUid,
                    onCheckedChange = { showUid = it },
                )
                SwitchPreference(
                    title = "显示连续签到",
                    summary = "连续签到天数",
                    checked = showStreak,
                    onCheckedChange = { showStreak = it },
                )
                SwitchPreference(
                    title = "显示积分",
                    summary = "关闭后别人看到「已隐藏」",
                    checked = showPoints,
                    onCheckedChange = { showPoints = it },
                )
                SwitchPreference(
                    title = "显示邮箱",
                    summary = "默认关闭，不建议开启",
                    checked = showEmail,
                    onCheckedChange = { showEmail = it },
                )
            }

            Spacer(Modifier.height(14.dp))

            // 保存按钮
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(
                        if (saving) pal.paperSoft else Accent,
                        androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    )
                    .pressable(enabled = !saving, pressedScale = 0.97f, onClick = { save() }),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(
                    if (saving) "保存中..." else "保存",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (saving) pal.inkSoft else androidx.compose.ui.graphics.Color.White,
                )
            }

            if (msg.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    msg,
                    fontSize = 12.5.sp,
                    color = if (msgBad) Bad else Good,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
