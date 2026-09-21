package app.moshu.journal

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.moshu.journal.data.imports.TextImport
import app.moshu.journal.ui.detail.EntryDetailScreen
import app.moshu.journal.ui.detail.EntryDetailViewModel
import app.moshu.journal.ui.insights.InsightsScreen
import app.moshu.journal.ui.insights.InsightsViewModel
import app.moshu.journal.ui.onboarding.OnboardingScreen
import app.moshu.journal.ui.record.MemoryScreen
import app.moshu.journal.ui.record.RecordViewModel
import app.moshu.journal.ui.settings.SettingsScreen
import app.moshu.journal.ui.settings.SettingsViewModel
import app.moshu.journal.ui.tags.TagsScreen
import app.moshu.journal.ui.tags.TagsViewModel
import app.moshu.journal.ui.theme.MoShuTheme
import app.moshu.journal.ui.today.TodayScreen
import app.moshu.journal.ui.today.TodayViewModel
import app.moshu.journal.ui.todo.TodosScreen
import app.moshu.journal.ui.todo.TodosViewModel
import app.moshu.journal.ui.trash.TrashScreen
import app.moshu.journal.ui.trash.TrashViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** 递增的请求令牌：从待办通知进来时 +1，用令牌而不是布尔值做 key，
     *  这样连续点第二条通知也能重新导航（布尔值第二次不会变化，看起来像没反应）。 */
    private val actionsRequest = mutableIntStateOf(0)
    /** 引导页请求跳转到设置页（配置 AI）时的令牌。 */
    private val settingsRequest = mutableIntStateOf(0)
    /** 分享进来时请求跳回「今天」的令牌。 */
    private val todayRequest = mutableIntStateOf(0)
    /** AI 整理失败通知请求跳到「记忆」页失败筛选的令牌。 */
    private val failedRequest = mutableIntStateOf(0)
    /** 桌面快捷方式「搜一搜」请求直接打开搜索框的令牌。 */
    private val searchRequest = mutableIntStateOf(0)

    /**
     * 从外部分享进来的文字（ACTION_SEND / PROCESS_TEXT）。
     * 只作为「今天」输入框的预填内容，不直接入库——用户应当有机会先改再存。
     */
    private val sharedDraft = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 只在首次创建时消费这个 extra。旋转屏幕/换主题会用同一个 Intent 重建 Activity，
        // 每次都读会把用户从当前页面拽回「行动」。
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_ACTIONS, false)) {
            actionsRequest.intValue = 1
        }
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_AI_FAILURES, false)) {
            failedRequest.intValue = 1
        }
        // 快捷方式「搜一搜」：直接落到搜索态，而不是先到列表再让用户点放大镜。
        if (savedInstanceState == null && wantsSearch(intent)) {
            searchRequest.intValue = 1
        }
        if (savedInstanceState == null) consumeSharedText(intent)
        enableEdgeToEdge()
        setContent {
            val app = MoShuApp.instance
            val startupFlow = remember(app) {
                combine(
                    app.settings.themeMode,
                    app.settings.onboardingDone,
                    app.settings.dynamicColor,
                ) { theme, done, dynamic -> Triple(theme, done, dynamic) }
            }
            val startup by startupFlow.collectAsStateWithLifecycle(initialValue = null)
            val scope = rememberCoroutineScope()
            val themeMode = startup?.first ?: "system"
            val dynamicColor = startup?.third ?: false
            val dark = when (themeMode) {
                "dark" -> true
                "light" -> false
                // 「按时间」用本地小时粗算：19:00–7:00 视为夜间。
                // 精确到日出日落需要定位权限，为换主题索要位置不合理。
                "auto" -> java.time.LocalTime.now().let { it.hour >= 19 || it.hour < 7 }
                else -> isSystemInDarkTheme()
            }
            // 系统栏图标必须跟随应用内主题，而不是系统主题：强制深色时若图标仍是深色，
            // 在深色背景上就完全看不见了。
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MoShuTheme(themeMode, dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when {
                        startup?.second == true -> MoShuRoot(
                            actionsToken = actionsRequest.intValue,
                            openSettingsToken = settingsRequest.intValue,
                            todayToken = todayRequest.intValue,
                            failedToken = failedRequest.intValue,
                            searchToken = searchRequest.intValue,
                            sharedDraft = sharedDraft.value,
                            onDraftConsumed = { sharedDraft.value = "" },
                        )
                        startup != null -> OnboardingScreen(
                            onComplete = { scope.launch { app.settings.completeOnboarding() } },
                            // 引导页的「现在配置 AI」要真的能到设置页：先标记引导完成，
                            // 再请求导航，否则按钮点了没有任何反应。
                            onConfigureAi = {
                                scope.launch { app.settings.completeOnboarding() }
                                settingsRequest.intValue += 1
                            },
                        )
                        // 读取 DataStore 期间给一个明确的加载态，而不是一片空白。
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }

    /**
     * 外接键盘快捷键。
     *
     * 只在按下 Ctrl（或 Meta）时介入，避免抢走输入框里的普通按键——
     * 用户正在写正文时按 n 应该打出字母 n，而不是新建。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val accel = event.isCtrlPressed || event.isMetaPressed
        if (accel) {
            when (keyCode) {
                KeyEvent.KEYCODE_F -> { searchRequest.intValue += 1; return true }
                KeyEvent.KEYCODE_N -> { todayRequest.intValue += 1; return true }
            }
        }
        // Esc 返回上一层；编辑态由详情页自己的确认流程接管（它比这里更清楚有没有改动）。
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_ACTIONS, false)) actionsRequest.intValue += 1
        if (intent.getBooleanExtra(EXTRA_OPEN_AI_FAILURES, false)) failedRequest.intValue += 1
        if (wantsSearch(intent)) searchRequest.intValue += 1
        consumeSharedText(intent)
    }

    /** 桌面快捷方式「搜一搜」有两种到达方式：布尔 extra（通知/内部）或 `moshu://search`（快捷方式）。 */
    private fun wantsSearch(intent: Intent): Boolean =
        intent.getBooleanExtra(EXTRA_OPEN_SEARCH, false) ||
            (intent.data?.scheme == "moshu" && intent.data?.host == "search")

    /**
     * 处理 ACTION_SEND / ACTION_PROCESS_TEXT。
     * 分享进来的文本经编码清洗后作为草稿，并请求导航到「今天」。
     */
    private fun consumeSharedText(intent: Intent) {
        val action = intent.action ?: return
        val raw = when (action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        val text = TextImport.normalize(raw.orEmpty())
        if (text.isEmpty()) return
        sharedDraft.value = text
        todayRequest.intValue += 1
    }

    /** 通知与外部 Intent 使用的 extra 名。通知构造方引用同一常量，避免字符串拼写漂移。 */
    companion object {
        const val EXTRA_OPEN_SEARCH = "app.moshu.journal.OPEN_SEARCH"
        const val EXTRA_OPEN_ACTIONS = "open_actions"
        const val EXTRA_OPEN_AI_FAILURES = "open_ai_failures"
    }
}

private object Routes {
    const val TODAY = "today"
    const val MEMORY = "memory"
    const val ACTIONS = "actions"
    const val REVIEW = "review"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
    const val TAGS = "tags"
    const val TRASH = "trash"
    const val ENTRY = "entry/{entryId}"
    fun entry(id: Long) = "entry/$id"
}

private data class Destination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val destinations = listOf(
    Destination(Routes.TODAY, "今日", Icons.Rounded.WbSunny),
    Destination(Routes.MEMORY, "记忆", Icons.Rounded.AutoStories),
    Destination(Routes.ACTIONS, "行动", Icons.Rounded.CheckCircle),
    Destination(Routes.REVIEW, "回顾", Icons.Rounded.Insights),
)

@Composable
private fun MoShuRoot(
    actionsToken: Int,
    openSettingsToken: Int = 0,
    todayToken: Int = 0,
    failedToken: Int = 0,
    searchToken: Int = 0,
    sharedDraft: String = "",
    onDraftConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // 必须在 remember 里创建：collectAsStateWithLifecycle 以 Flow 实例为 key，
    // 每次重组都新建 Flow 会重新订阅并重跑 COUNT 查询（角标还会瞬间归零）。
    val todoCountFlow = remember { MoShuApp.instance.database.todoDao().observeActiveCount() }
    val activeTodos by todoCountFlow.collectAsStateWithLifecycle(0)
    val topLevel = destinations.any { it.route == currentRoute }

    // 全局提示宿主：撤销、AI 整理完成等提示都从这里出，各页面不必各写一套 SnackbarHost。
    val snackbar = remember { SnackbarHostState() }
    val noticeBus = remember { MoShuApp.instance.notices }
    LaunchedEffect(noticeBus) {
        noticeBus.events.collect { notice ->
            val result = snackbar.showSnackbar(
                message = notice.message,
                actionLabel = notice.actionLabel,
                withDismissAction = notice.actionLabel == null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) notice.action?.invoke()
        }
    }

    LaunchedEffect(actionsToken) {
        if (actionsToken > 0) navigateTop(navController, Routes.ACTIONS)
    }
    LaunchedEffect(openSettingsToken) {
        if (openSettingsToken > 0) navController.navigate(Routes.SETTINGS)
    }
    // 从外部分享进来时跳回「今天」，让用户看到预填的草稿
    LaunchedEffect(todayToken) {
        if (todayToken > 0) navigateTop(navController, Routes.TODAY)
    }
    // 整理失败通知：直接进「记忆」并打开失败筛选，而不是把用户丢到待办列表。
    LaunchedEffect(failedToken) {
        if (failedToken > 0) navigateTop(navController, Routes.MEMORY)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 840.dp
        if (useRail) {
            Row(Modifier.fillMaxSize()) {
                if (topLevel) NavigationItems(currentRoute, activeTodos, vertical = true) { navigateTop(navController, it) }
                AppNavHost(navController, Modifier.weight(1f), actionsToken, failedToken, searchToken, sharedDraft, onDraftConsumed)
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (topLevel) NavigationItems(currentRoute, activeTodos, vertical = false) { navigateTop(navController, it) }
                },
            ) { padding -> AppNavHost(navController, Modifier.padding(padding), actionsToken, failedToken, searchToken, sharedDraft, onDraftConsumed) }
        }
        // 底栏之上留出空间，避免 snackbar 压住导航项。
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (useRail) 16.dp else 88.dp))
    }
}

@Composable
private fun NavigationItems(currentRoute: String?, activeTodos: Int, vertical: Boolean, onNavigate: (String) -> Unit) {
    if (vertical) {
        NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
            destinations.forEach { destination ->
                NavigationRailItem(
                    selected = currentRoute == destination.route,
                    onClick = { onNavigate(destination.route) },
                    icon = { DestinationIcon(destination, activeTodos) },
                    label = { Text(destination.label) },
                )
            }
        }
    } else {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            destinations.forEach { destination ->
                NavigationBarItem(
                    selected = currentRoute == destination.route,
                    onClick = { onNavigate(destination.route) },
                    icon = { DestinationIcon(destination, activeTodos) },
                    label = { Text(destination.label) },
                )
            }
        }
    }
}

@Composable
private fun DestinationIcon(destination: Destination, activeTodos: Int) {
    if (destination.route == Routes.ACTIONS && activeTodos > 0) {
        BadgedBox(badge = { Badge { Text(activeTodos.coerceAtMost(99).toString()) } }) {
            Icon(destination.icon, contentDescription = destination.label)
        }
    } else Icon(destination.icon, contentDescription = destination.label)
}

@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    modifier: Modifier,
    actionsToken: Int,
    failedToken: Int = 0,
    searchToken: Int = 0,
    sharedDraft: String = "",
    onDraftConsumed: () -> Unit = {},
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        NavHost(
            navController = navController,
            startDestination = if (actionsToken > 0) Routes.ACTIONS else Routes.TODAY,
            modifier = Modifier.fillMaxSize().widthIn(max = 960.dp),
        ) {
            composable(Routes.TODAY) {
                TodayScreen(
                    viewModel = viewModel<TodayViewModel>(),
                    onOpenEntry = { navController.navigate(Routes.entry(it)) },
                    onOpenMemory = { navigateTop(navController, Routes.MEMORY) },
                    onOpenActions = { navigateTop(navController, Routes.ACTIONS) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    initialDraft = sharedDraft,
                    onDraftConsumed = onDraftConsumed,
                )
            }
            composable(Routes.MEMORY) {
                val memoryViewModel = viewModel<RecordViewModel>()
                // 失败通知 deep link：进入时默认打开「整理失败」筛选。
                LaunchedEffect(failedToken) { if (failedToken > 0) memoryViewModel.setFailedOnly(true) }
                // 快捷方式「搜一搜」：进入记忆页即展开搜索框。
                LaunchedEffect(searchToken) { if (searchToken > 0) memoryViewModel.openSearch() }
                MemoryScreen(
                    viewModel = memoryViewModel,
                    onOpenEntry = { navController.navigate(Routes.entry(it)) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.ACTIONS) {
                TodosScreen(
                    viewModel = viewModel<TodosViewModel>(),
                    onOpenSource = { navController.navigate(Routes.entry(it)) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.REVIEW) {
                InsightsScreen(
                    viewModel = viewModel<InsightsViewModel>(),
                    onOpenEntry = { navController.navigate(Routes.entry(it)) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel<SettingsViewModel>(),
                    onBack = { navController.popBackStack() },
                    // 引导页可以随时重看，返回即回到设置，不会重置 onboardingDone。
                    onOpenOnboarding = { navController.navigate(Routes.ONBOARDING) },
                    onOpenTags = { navController.navigate(Routes.TAGS) },
                    onOpenTrash = { navController.navigate(Routes.TRASH) },
                )
            }
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onComplete = { navController.popBackStack() },
                    onConfigureAi = {
                        navController.popBackStack()
                        navController.navigate(Routes.SETTINGS)
                    },
                )
            }
            composable(Routes.TAGS) {
                TagsScreen(viewModel<TagsViewModel>(), onBack = { navController.popBackStack() })
            }
            composable(Routes.TRASH) {
                TrashScreen(viewModel<TrashViewModel>(), onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.ENTRY,
                arguments = listOf(navArgument("entryId") { type = NavType.LongType }),
            ) {
                EntryDetailScreen(viewModel<EntryDetailViewModel>(), onBack = { navController.popBackStack() })
            }
        }
    }
}

private fun navigateTop(navController: NavController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
