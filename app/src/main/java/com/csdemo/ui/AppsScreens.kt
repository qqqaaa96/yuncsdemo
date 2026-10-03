package com.csdemo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.tools.Apps
import com.csdemo.tools.Codec
import com.csdemo.ui.theme.Ink
import com.csdemo.ui.theme.InkSoft
import com.csdemo.ui.theme.LocalPalette

@Composable
fun AppsScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var onlyThird by remember { mutableStateOf(false) }
    val all = remember { Apps.scan(ctx) }
    val list = all.filter {
        (!onlyThird || !it.isSystem) &&
                (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
    }
    var selected by remember { mutableStateOf<Apps.AppItem?>(null) }

    val sel = selected
    if (sel != null) {
        AppDetail(sel) { selected = null }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        PageHeader("应用列表", onBack)
        Spacer(Modifier.height(4.dp))
        Text("共 " + all.size + " 个应用，显示 " + list.size, fontSize = 11.sp, color = LocalPalette.current.inkSoft)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索应用名 / 包名", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onlyThird = !onlyThird }) {
            Text(if (onlyThird) "仅看第三方 ✓" else "仅看第三方")
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(list, key = { it.pkg }) { app ->
                RowItem(
                    title = app.label,
                    sub = app.pkg,
                    trailing = app.versionName
                ) { selected = app }
                HLine()
            }
        }
    }
}

@Composable
private fun AppDetail(app: Apps.AppItem, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Button(onClick = onBack) { Text("返回") }
        Spacer(Modifier.height(12.dp))
        Text(app.label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LocalPalette.current.ink)
        Spacer(Modifier.height(12.dp))
        Card("基本信息") {
            KV("包名", app.pkg, mono = true)
            KV("版本", app.versionName + " (" + app.versionCode + ")")
            KV("类型", if (app.isSystem) "系统应用" else "第三方应用")
            KV("targetSdk", app.targetSdk.toString())
            KV("大小", String.format("%.2f MB", app.sizeBytes / 1024.0 / 1024.0))
            KV("首次安装", app.firstInstall)
            KV("最后更新", app.lastUpdate)
            KV("APK 路径", app.apkPath, mono = true)
        }
        Spacer(Modifier.height(12.dp))
        Card("签名指纹") {
            KV("MD5", app.signerMd5, mono = true)
            KV("SHA-256", app.signerSha256, mono = true)
        }
        Spacer(Modifier.height(12.dp))
        Card("组件") {
            KV("Activity", app.activities.toString())
            KV("Service", app.services.toString())
            KV("Receiver", app.receivers.toString())
            KV("Provider", app.providers.toString())
        }
        Spacer(Modifier.height(12.dp))
        Card("权限（" + app.permissions.size + "）") {
            if (app.permissions.isEmpty()) {
                Text("无", fontSize = 13.sp, color = LocalPalette.current.inkSoft)
            }
            app.permissions.forEach { p ->
                Text("• " + Apps.permissionLabel(p), fontSize = 12.sp, color = LocalPalette.current.ink,
                    modifier = Modifier.padding(vertical = 3.dp))
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
fun CodecScreen(onBack: () -> Unit = {}) {
    var input by remember { mutableStateOf("Hello 工具箱") }
    var output by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        PageHeader("编码 / 哈希", onBack)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("输入", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth().height(110.dp)
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                Button(onClick = { output = Codec.base64Encode(input) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Base64 编码", fontSize = 12.sp)
                }
            }
            Box(Modifier.weight(1f)) {
                Button(onClick = { output = Codec.base64Decode(input) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Base64 解码", fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                Button(onClick = { output = Codec.urlEncode(input) }, modifier = Modifier.fillMaxWidth()) {
                    Text("URL 编码", fontSize = 12.sp)
                }
            }
            Box(Modifier.weight(1f)) {
                Button(onClick = { output = Codec.urlDecode(input) }, modifier = Modifier.fillMaxWidth()) {
                    Text("URL 解码", fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                Button(onClick = { output = Codec.hexEncode(input) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Hex 编码", fontSize = 12.sp)
                }
            }
            Box(Modifier.weight(1f)) {
                Button(onClick = { output = Codec.hexDecode(input) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Hex 解码", fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                Button(onClick = { output = Codec.htmlEncode(input) }, modifier = Modifier.fillMaxWidth()) {
                    Text("HTML 转义", fontSize = 12.sp)
                }
            }
            Box(Modifier.weight(1f)) {
                Button(onClick = { output = Codec.jsonFormat(input) }, modifier = Modifier.fillMaxWidth()) {
                    Text("JSON 格式化", fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            output = Codec.allHashes(input).joinToString("\n\n") { it.first + "\n" + it.second }
        }) { Text("计算全部哈希") }
        Spacer(Modifier.height(14.dp))
        Mono(output)
        Spacer(Modifier.height(40.dp))
    }
}
