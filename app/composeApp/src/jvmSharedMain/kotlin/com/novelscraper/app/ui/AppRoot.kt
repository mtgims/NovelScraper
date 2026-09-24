package com.novelscraper.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.novelscraper.app.platform.isDesktop
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.novelscraper.app.net.Account
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.savedstate.read
import com.novelscraper.app.data.LibraryPrefs
import com.novelscraper.app.platform.encodeRouteArg
import com.novelscraper.app.ui.browse.BrowseScreen
import com.novelscraper.app.ui.browse.ExtensionsScreen
import com.novelscraper.app.ui.browse.SourceScreen
import com.novelscraper.app.ui.components.PillNavBar
import com.novelscraper.app.ui.components.NavRail
import com.novelscraper.app.ui.components.SiteCheckPrompt
import com.novelscraper.app.ui.components.isTopLevelRoute
import com.novelscraper.app.ui.screen.BookScreen
import com.novelscraper.app.ui.screen.LibraryScreen
import com.novelscraper.app.ui.screen.LoginScreen
import com.novelscraper.app.ui.screen.ProgressScreen
import com.novelscraper.app.ui.screen.ReaderScreen
import com.novelscraper.app.ui.screen.RegisterScreen
import com.novelscraper.app.ui.screen.SettingsScreen
import com.novelscraper.app.ui.screen.StatsScreen

/** The whole app: the tabs and detail screens. A server account is optional;
 *  sign-in lives under Settings. */
@Composable
fun AppRoot() = MainApp()

/** Stands in for a screen that needs the server while signed out. */
@Composable
private fun NeedsServer(what: String, onSignIn: () -> Unit, content: @Composable () -> Unit) {
    val account by Account.state.collectAsState()
    if (account is Account.State.SignedIn) { content(); return }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(what, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onSignIn, modifier = Modifier.padding(top = 16.dp)) { Text("Sign in") }
    }
}

private val TAB_ORDER = listOf("library", "browse", "jobs", "stats", "settings", "login", "register")
// Tab rank drives slide direction; detail screens (book/reader) rank high so
// opening them slides forward (left), and back-navigation slides right.
private fun routeRank(route: String?): Int {
    val base = route?.substringBefore("/")
    val i = TAB_ORDER.indexOf(base)
    return if (i >= 0) i else 10
}

@Composable
private fun MainApp() {
    val nav = rememberNavController()
    val auth: AuthViewModel = viewModel { AuthViewModel() }
    val scope = rememberCoroutineScope()
    val signIn = { nav.navigate("login") { launchSingleTop = true } }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val slideSpec = tween<IntOffset>(260)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Which navigation is shown follows the window, as layout should: a rail
        // on anything desktop-sized, with labels when there is room for them.
        val wide = maxWidth >= RAIL_MIN_WIDTH
        val collapsed by LibraryPrefs.railCollapsed.collectAsState()
        val labelled = maxWidth >= RAIL_LABEL_MIN_WIDTH && !collapsed
        // The rail stays while you read a novel's page too. It used to go, and the
        // page jumped wider a moment after opening, which read as the app
        // stumbling. Only the reader takes the whole window, as it should.
        val onRail = wide && route?.startsWith("reader/") != true

        Row(Modifier.fillMaxSize()) {
            if (onRail) {
                NavRail(
                    current = route,
                    labelled = labelled,
                    onSelect = { dest -> go(nav, route, dest) },
                    onToggleWidth = { LibraryPrefs.toggleRail() },
                )
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
        NavHost(
            nav,
            startDestination = "library",
            enterTransition = {
                // A window doesn't slide from side to side when you change page:
                // that is a phone showing you which way it went. Here it fades.
                if (isDesktop) return@NavHost fadeIn(tween(120))
                val fwd = routeRank(targetState.destination.route) >= routeRank(initialState.destination.route)
                slideIntoContainer(
                    if (fwd) AnimatedContentTransitionScope.SlideDirection.Left
                    else AnimatedContentTransitionScope.SlideDirection.Right,
                    slideSpec,
                )
            },
            exitTransition = {
                if (isDesktop) return@NavHost fadeOut(tween(120))
                val fwd = routeRank(targetState.destination.route) >= routeRank(initialState.destination.route)
                slideOutOfContainer(
                    if (fwd) AnimatedContentTransitionScope.SlideDirection.Left
                    else AnimatedContentTransitionScope.SlideDirection.Right,
                    slideSpec,
                )
            },
            popEnterTransition = {
                if (isDesktop) return@NavHost fadeIn(tween(120))
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, slideSpec)
            },
            popExitTransition = {
                if (isDesktop) return@NavHost fadeOut(tween(120))
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, slideSpec)
            },
        ) {
            composable("library") {
                LibraryScreen(onOpenBook = { id -> nav.navigate("book/$id") })
            }
            composable("login") {
                val busy by auth.busy.collectAsState()
                val error by auth.error.collectAsState()
                LoginScreen(
                    baseUrl = auth.baseUrl, busy = busy, error = error,
                    onLogin = { u, p -> auth.login(u, p) { nav.popBackStack("login", inclusive = true) } },
                    onSetBaseUrl = auth::setBaseUrl,
                    onRegister = { auth.clearError(); auth.loadAuthConfig(); nav.navigate("register") },
                    onBack = { auth.clearError(); nav.popBackStack() },
                )
            }
            composable("register") {
                val busy by auth.busy.collectAsState()
                val error by auth.error.collectAsState()
                val openSignup by auth.openSignup.collectAsState()
                RegisterScreen(
                    busy = busy, error = error, openSignup = openSignup,
                    onRegister = { u, p, i -> auth.register(u, p, i) { nav.popBackStack("login", inclusive = true) } },
                    onBack = { auth.clearError(); nav.popBackStack() },
                )
            }
            composable("browse") {
                BrowseScreen(
                    onOpenSource = { id -> nav.navigate("source/${encodeRouteArg(id)}") },
                    onManage = { nav.navigate("extensions") },
                )
            }
            composable("extensions") { ExtensionsScreen(onBack = { nav.popBackStack() }) }
            composable("source/{plugin}") { e ->
                val plugin = e.arguments!!.read { getString("plugin") }
                SourceScreen(
                    pluginId = plugin,
                    onBack = { nav.popBackStack() },
                    onOpenNovel = { id -> nav.navigate("book/$id") },
                )
            }
            composable("jobs") {
                NeedsServer("Updates running on your NovelScraper server show here.", signIn) { ProgressScreen() }
            }
            // Worked out from the library on this device, so no account needed.
            composable("stats") { StatsScreen() }
            composable("settings") {
                SettingsScreen(onSignIn = signIn, onLogout = { scope.launch { Account.logout() } })
            }
            composable(
                "book/{id}",
                arguments = listOf(navArgument("id") { type = NavType.IntType }),
            ) { e ->
                val id = e.arguments!!.read { getInt("id") }
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
                    bookId = e.arguments!!.read { getInt("id") },
                    position = e.arguments!!.read { getInt("pos") },
                    onBack = { nav.popBackStack() },
                )
            }
        }

                // A source asking for a browser check can come from any screen.
                SiteCheckPrompt()

                if (!wide && isTopLevelRoute(route)) {
                    PillNavBar(
                        current = route,
                        onSelect = { dest -> go(nav, route, dest) },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

/** Go to a top-level destination, keeping each tab's own back stack. */
private fun go(nav: androidx.navigation.NavHostController, from: String?, dest: String) {
    if (dest == from) return
    // Already somewhere above it in the stack (a novel's page sits above the
    // library): come back to it. Navigating instead asks to pop up to the
    // library and then to go to the library, and the two cancel out, which is
    // why the library was the one destination that did nothing.
    if (nav.popBackStack(dest, inclusive = false)) return
    nav.navigate(dest) {
        launchSingleTop = true
        popUpTo("library") { saveState = true }
        restoreState = true
    }
}

/** A window wide enough for navigation down the side, and wide enough to name it. */
private val RAIL_MIN_WIDTH = 760.dp
private val RAIL_LABEL_MIN_WIDTH = 1000.dp
