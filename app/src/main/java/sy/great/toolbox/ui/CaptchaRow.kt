package sy.great.toolbox.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sy.great.toolbox.tools.AuthApi
import sy.great.toolbox.ui.theme.InkSoft
import sy.great.toolbox.ui.theme.Line
import sy.great.toolbox.ui.theme.PaperSoft

/**
 * 图形验证码输入行。
 *
 * 服务端出题（PNG + X-Captcha-Id），客户端只负责展示与回传，
 * 本地不保存正确答案，所以脚本无法绕过。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun CaptchaRow(
    // 当前绑定的 captcha_id：由 onLoaded 回传后由调用方保存，本组件内部不直接使用
    captchaId: String,
    captchaCode: String,
    onCodeChange: (String) -> Unit,
    onLoaded: (id: String) -> Unit,
    enabled: Boolean = true
) {
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var err by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        err = ""
        val r = withContext(Dispatchers.IO) { AuthApi.captcha() }
        if (!r.ok) {
            bitmap = null
            err = r.error
        } else {
            bitmap = try {
                BitmapFactory.decodeByteArray(r.bytes, 0, r.bytes.size)
            } catch (_: Throwable) {
                null
            }
            if (bitmap == null) {
                err = "图片解析失败"
            } else {
                onLoaded(r.id)
            }
        }
    }

    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = captchaCode,
                onValueChange = { onCodeChange(it.filter { c -> c.isLetterOrDigit() }.take(5)) },
                label = { Text("图形验证码") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.weight(1f)
            )

            Box(
                Modifier
                    .size(width = 118.dp, height = 58.dp)
                    .background(PaperSoft, RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) { tick++ },
                contentAlignment = Alignment.Center
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "图形验证码，点击刷新",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(width = 112.dp, height = 52.dp)
                    )
                } else {
                    Text("点击刷新", fontSize = 11.sp, color = InkSoft)
                }
            }
        }

        if (err.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(err, fontSize = 11.sp, color = sy.great.toolbox.ui.theme.Bad, lineHeight = 16.sp)
        } else {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.height(1.dp).weight(1f).background(Line))
                Text(
                    "看不清？点图片换一张",
                    fontSize = 10.sp,
                    color = InkSoft,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Box(Modifier.height(1.dp).weight(1f).background(Line))
            }
        }
    }
}
