package app.moshu.journal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import app.moshu.journal.ui.detail.EntryDetailScreen
import app.moshu.journal.ui.detail.EntryDetailViewModel
import app.moshu.journal.ui.insights.InsightsScreen
import app.moshu.journal.ui.insights.InsightsViewModel
import app.moshu.journal.ui.onboarding.OnboardingScreen
import app.moshu.journal.ui.record.MemoryScreen
import app.moshu.journal.ui.record.RecordViewModel
import app.moshu.journal.ui.settings.SettingsScreen
import app.moshu.journal.ui.settings.SettingsViewModel
import app.moshu.journal.ui.theme.MoShuTheme
import app.moshu.journal.ui.today.TodayScreen
import app.moshu.journal.ui.today.TodayViewModel
import app.moshu.journal.ui.todo.TodosScreen
import app.moshu.journal.ui.todo.TodosViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val openActions = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openActions.value = intent.getBooleanExtra("open_actions", false)
        enableEdgeToEdge()
        setContent {
            val app = MoShuApp.instance
            val startupFlow = remember(app) {
                combine(app.settings.themeMode, app.settings.onboardingDone) { theme, done -> theme to done }
            }
            val startup by startupFlow.collectAsStateWithLifecycle(initialValue = null)
            val scope = rememberCoroutineScope()
            MoShuTheme(startup?.first ?: "system") {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (startup?.second == true) {
                        MoShuRoot(startAtActions = openActions.value)
                    } else if (startup != null) {
                        OnboardingScreen(onComplete = { scope.launch { app.settings.completeOnboarding() } })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("open_actions", false)) openActions.value = true
    }
}

private object Routes {
    const val TODAY = "today"
    const val MEMORY = "memory"
    const val ACTIONS = "actions"
    const val REVIEW = "review"
    const val SETTINGS = "settings"
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
private fun MoShuRoot(startAtActions: Boolean) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val activeTodos by MoShuApp.instance.database.todoDao().observeActiveCount().collectAsStateWithLifecycle(0)
    val topLevel = destinations.any { it.route == currentRoute }

    androidx.compose.runtime.LaunchedEffect(startAtActions) {
        if (startAtActions) navigateTop(navController, Routes.ACTIONS)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 840.dp
        if (useRail) {
            Row(Modifier.fillMaxSize()) {
                if (topLevel) NavigationItems(currentRoute, activeTodos, vertical = true) { navigateTop(navController, it) }
                AppNavHost(navController, Modifier.weight(1f), startAtActions)
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (topLevel) NavigationItems(currentRoute, activeTodos, vertical = false) { navigateTop(navController, it) }
                },
            ) { padding -> AppNavHost(navController, Modifier.padding(padding), startAtActions) }
        }
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
private fun AppNavHost(navController: androidx.navigation.NavHostController, modifier: Modifier, startAtActions: Boolean) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        NavHost(
            navController = navController,
            startDestination = if (startAtActions) Routes.ACTIONS else Routes.TODAY,
            modifier = Modifier.fillMaxSize().widthIn(max = 960.dp),
        ) {
            composable(Routes.TODAY) {
                TodayScreen(
                    viewModel = viewModel<TodayViewModel>(),
                    onOpenEntry = { navController.navigate(Routes.entry(it)) },
                    onOpenMemory = { navigateTop(navController, Routes.MEMORY) },
                    onOpenActions = { navigateTop(navController, Routes.ACTIONS) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.MEMORY) {
                MemoryScreen(
                    viewModel = viewModel<RecordViewModel>(),
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
                SettingsScreen(viewModel<SettingsViewModel>(), onBack = { navController.popBackStack() })
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
