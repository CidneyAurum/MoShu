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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    OnboardingPage(Icons.Rounded.AutoAwesome, "欢迎来到墨枢", "只管记录，整理交给我", "一句话、一张图，都能成为清晰的记忆。墨枢会在后台补全概括、标签、情绪和行动项。"),
    OnboardingPage(Icons.Rounded.Lock, "本地优先", "你的记忆，先留在你的设备", "没有账号，也没有墨枢服务器。正文、图片和待办默认只保存在本机。"),
    OnboardingPage(Icons.Rounded.Image, "图片边界", "是否理解图片，由你决定", "图片默认不会发给 AI。只有配置视觉模型并手动开启后，才会参与智能整理。"),
)

@Composable
fun OnboardingScreen(onComplete: () -> Unit, modifier: Modifier = Modifier) {
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
        Button(
            onClick = { if (page < pages.lastIndex) page++ else onComplete() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (page == pages.lastIndex) "开始记录" else "继续", modifier = Modifier.padding(vertical = 6.dp)) }
    }
}
