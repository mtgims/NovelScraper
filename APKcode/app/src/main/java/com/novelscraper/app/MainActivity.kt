package com.novelscraper.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.novelscraper.app.ui.AuthState
import com.novelscraper.app.ui.AuthViewModel
import com.novelscraper.app.ui.components.PillNavBar
import com.novelscraper.app.ui.components.isTopLevelRoute
import com.novelscraper.app.ui.screen.BookScreen
import com.novelscraper.app.ui.screen.LibraryScreen
import com.novelscraper.app.ui.screen.LoginScreen
import com.novelscraper.app.ui.screen.NewScrapeScreen
import com.novelscraper.app.ui.screen.NuBrowserScreen
import com.novelscraper.app.ui.screen.ProgressScreen
import com.novelscraper.app.ui.screen.ReaderScreen
import com.novelscraper.app.ui.screen.RegisterScreen
import com.novelscraper.app.ui.screen.SettingsScreen
import com.novelscraper.app.ui.screen.StatsScreen
import com.novelscraper.app.ui.theme.NovelScraperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NovelScraperTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val vm: AuthViewModel = viewModel()
    val state by vm.state.collectAsState()

    when (val s = state) {
        is AuthState.Loading ->
            Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        is AuthState.SignedOut -> AuthFlow(vm)
        is AuthState.SignedIn -> AuthedApp(username = s.user.username, onLogout = vm::logout)
    }
}

@Composable
private fun AuthFlow(vm: AuthViewModel) {
    val nav = rememberNavController()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    NavHost(nav, startDestination = "login") {
        composable("login") {
            LoginScreen(
                baseUrl = vm.baseUrl, busy = busy, error = error,
                onLogin = vm::login, onSetBaseUrl = vm::setBaseUrl,
                onRegister = { vm.clearError(); vm.loadAuthConfig(); nav.navigate("register") },
            )
        }
        composable("register") {
            val openSignup by vm.openSignup.collectAsState()
            RegisterScreen(
                busy = busy, error = error, openSignup = openSignup,
                onRegister = vm::register,
                onBack = { vm.clearError(); nav.popBackStack() },
            )
        }
    }
}

private val TAB_ORDER = listOf("library", "new", "jobs", "stats", "settings")
// Tab rank drives slide direction; detail screens (book/reader) rank high so
// opening them slides forward (left), and back-navigation slides right.
private fun routeRank(route: String?): Int {
    val base = route?.substringBefore("/")
    val i = TAB_ORDER.indexOf(base)
    return if (i >= 0) i else 10
}

@Composable
private fun AuthedApp(username: String, onLogout: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val slideSpec = tween<IntOffset>(260)

    Box(Modifier.fillMaxSize()) {
        NavHost(
            nav,
            startDestination = "library",
            enterTransition = {
                val fwd = routeRank(targetState.destination.route) >= routeRank(initialState.destination.route)
                slideIntoContainer(
                    if (fwd) AnimatedContentTransitionScope.SlideDirection.Left
                    else AnimatedContentTransitionScope.SlideDirection.Right,
                    slideSpec,
                )
            },
            exitTransition = {
                val fwd = routeRank(targetState.destination.route) >= routeRank(initialState.destination.route)
                slideOutOfContainer(
                    if (fwd) AnimatedContentTransitionScope.SlideDirection.Left
                    else AnimatedContentTransitionScope.SlideDirection.Right,
                    slideSpec,
                )
            },
            popEnterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, slideSpec)
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, slideSpec)
            },
        ) {
            composable("library") {
                LibraryScreen(onOpenBook = { id -> nav.navigate("book/$id") })
            }
            composable("new") {
                NewScrapeScreen(
                    onScraped = {
                        nav.navigate("jobs") {
                            launchSingleTop = true
                            popUpTo("library") { saveState = true }
                            restoreState = true
                        }
                    },
                    onImported = {
                        nav.navigate("library") {
                            launchSingleTop = true
                            popUpTo("library") { inclusive = true }
                        }
                    },
                    onAddFromNu = { nav.navigate("nu") },
                )
            }
            composable("nu") {
                NuBrowserScreen(
                    onBack = { nav.popBackStack() },
                    onScraped = {
                        nav.navigate("jobs") {
                            launchSingleTop = true
                            popUpTo("library") { saveState = true }
                            restoreState = true
                        }
                    },
                )
            }
            composable("jobs") { ProgressScreen() }
            composable("stats") { StatsScreen() }
            composable("settings") { SettingsScreen(username = username, onLogout = onLogout) }
            composable(
                "book/{id}",
                arguments = listOf(navArgument("id") { type = NavType.IntType }),
            ) { e ->
                val id = e.arguments!!.getInt("id")
                BookScreen(
                    bookId = id,
                    onBack = { nav.popBackStack() },
                    onOpenReader = { pos -> nav.navigate("reader/$id/$pos") },
                )
            }
            composable(
                "reader/{id}/{pos}",
                arguments = listOf(
                    navArgument("id") { type = NavType.IntType },
                    navArgument("pos") { type = NavType.IntType },
                ),
            ) { e ->
                ReaderScreen(
                    bookId = e.arguments!!.getInt("id"),
                    position = e.arguments!!.getInt("pos"),
                    onBack = { nav.popBackStack() },
                )
            }
        }

        if (isTopLevelRoute(route)) {
            PillNavBar(
                current = route,
                onSelect = { dest ->
                    if (dest != route) nav.navigate(dest) {
                        launchSingleTop = true
                        popUpTo("library") { saveState = true }
                        restoreState = true
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
