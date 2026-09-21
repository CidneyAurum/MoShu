package app.moshu.journal.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.moshu.journal.MoShuApp

/**
 * 应用级依赖的注入点。
 *
 * 此前 Composable 里直接写 `MoShuApp.instance`，既无法 Preview，也无法在测试里换成假数据。
 * 由 [app.moshu.journal.ui.theme.MoShuTheme] 在根部 provide，页面只从 local 取。
 * 默认值仍指向真实单例，保证没有显式 provide 的场景（例如预览里的部分页面）行为不变。
 */
val LocalMoShuApp = staticCompositionLocalOf { MoShuApp.instance }