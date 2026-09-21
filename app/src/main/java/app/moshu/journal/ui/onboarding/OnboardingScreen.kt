package app.moshu.journal.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class OnboardingPage(val icon: ImageVector, val eyebrow: String, val title: String, val body: String)

private val pages = listOf(
    OnboardingPage(Icons.Rounded.AutoAwesome, "欢迎来到墨枢", "只管记录，整理交给我", "不配置也能完整记录；配置后墨枢会自动补全概括、标签与行动项。"),
    OnboardingPage(Icons.Rounded.Lock, "本地优先", "你的记忆，先留在你的设备", "没有账号，也没有墨枢服务器。正文、图片和待办默认只保存在本机。"),
    OnboardingPage(Icons.Rounded.Image, "图片边界", "是否理解图片，由你决定", "记录正文在开启 AI 后会被发送到你配置的服务商；图片默认不发送，需额外开启。"),
)

@Composable
fun OnboardingScreen(onComplete: () -> Unit, modifier: Modifier = Modifier, onConfigureAi: () -> Unit = {}) {
    var page by remember { mutableIntStateOf(0) }
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("墨枢", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.weight(0.7f))
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                (slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, spring()) + fadeIn()) togetherWith
                    (slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, spring()) + fadeOut())
            },
            label = "onboarding",
        ) { index ->
            val item = pages[index]
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Surface(modifier = Modifier.size(92.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(contentAlignment = Alignment.Center) { Icon(item.icon, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary) }
                }
                Text(item.eyebrow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(item.title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                Text(item.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            pages.indices.forEach { index -> Box(Modifier.size(if (index == page) 22.dp else 7.dp, 7.dp).background(if (index == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)) }
        }
        Spacer(Modifier.size(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (page > 0) {
                OutlinedButton(onClick = { page-- }, modifier = Modifier.weight(1f)) {
                    Text("上一步", modifier = Modifier.padding(vertical = 6.dp))
                }
            }
            Button(
                onClick = { if (page < pages.lastIndex) page++ else onComplete() },
                modifier = Modifier.weight(1f),
            ) { Text(if (page == pages.lastIndex) "开始记录" else "继续", modifier = Modifier.padding(vertical = 6.dp)) }
        }
        // 在介绍 AI 的那一页直接给出配置入口，而不是让用户自己去找设置页；配置始终是可选的。
        if (page == 0) {
            TextButton(onClick = onConfigureAi, modifier = Modifier.fillMaxWidth()) { Text("现在配置 AI（可选）") }
        }
        // 三步都是说明性的，不该强制用户翻完才能进应用。
        TextButton(onClick = onComplete, modifier = Modifier.fillMaxWidth()) { Text("跳过") }
    }
}
