package com.csdemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csdemo.ui.theme.Ink
import com.csdemo.ui.theme.InkSoft
import com.csdemo.ui.theme.Line
import com.csdemo.ui.theme.Paper
import com.csdemo.ui.theme.PaperSoft

@Composable
fun Card(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .background(Paper, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        if (title != null) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = InkSoft)
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

/**
 * 可点击的卡片，带按压回弹。
 */
@Composable
fun TapCard(
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(enabled = enabled, onClick = onClick)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .background(Paper, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        content()
    }
}

@Composable
fun KV(k: String, v: String, mono: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(k, fontSize = 13.sp, color = InkSoft, modifier = Modifier.width(96.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (v.isBlank()) "-" else v,
            fontSize = 13.sp,
            color = Ink,
            modifier = Modifier.weight(1f),
            fontFamily = if (mono) FontFamily.Monospace else null
        )
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Row(
        Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun RowItem(title: String, sub: String? = null, trailing: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.985f, onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null && sub.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(sub, fontSize = 11.sp, color = InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Text(trailing, fontSize = 12.sp, color = InkSoft)
        }
    }
}

@Composable
fun HLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

@Composable
fun Mono(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(PaperSoft, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            if (text.isBlank()) "等待执行..." else text,
            fontSize = 12.sp,
            color = Ink,
            fontFamily = FontFamily.Monospace,
            lineHeight = 17.sp
        )
    }
}
