package sk.cartracker.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.launch
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.ui.screens.HelpScreen
import sk.cartracker.ui.screens.HomeScreen
import sk.cartracker.ui.screens.LockScreen
import sk.cartracker.ui.screens.LoginScreen
import sk.cartracker.ui.screens.PermissionsScreen
import sk.cartracker.ui.screens.PlacesScreen
import sk.cartracker.ui.screens.RefuelsScreen
import sk.cartracker.ui.screens.RemindersScreen
import sk.cartracker.ui.screens.RegisterScreen
import sk.cartracker.ui.screens.SettingsScreen
import sk.cartracker.ui.screens.StatsScreen
import sk.cartracker.ui.screens.TripDetailScreen
import sk.cartracker.ui.screens.TripsScreen
import sk.cartracker.ui.screens.VehiclesScreen
import sk.cartracker.ui.theme.CarTrackerTheme

/** FragmentActivity – potrebné pre BiometricPrompt. Zatvorenie okna sledovanie NEzastaví (beží v službe). */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = container
        setContent {
            val settings by c.settings.flow.collectAsStateWithLifecycle(initialValue = null as AppSettings?)
            val s = settings
            if (s != null) {
                val start = remember {
                    when {
                        !c.session.isLoggedIn -> "login"
                        s.biometricEnabled && c.session.hasBiometricToken() -> "lock"
                        else -> "main"
                    }
                }
                CarTrackerTheme(s.themeMode) {
                    Surface { AppNavigation(start) }
                }
            }
        }
    }
}

private fun NavHostController.goClean(route: String) = navigate(route) { popUpTo(0) { inclusive = true } }

@Composable
private fun AppNavigation(start: String) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = start) {
        composable("login") { LoginScreen(onLoggedIn = { nav.goClean("main") }, onRegister = { nav.navigate("register") }) }
        composable("register") {
            RegisterScreen(onRegistered = { nav.goClean("permissions") }, onBack = { nav.popBackStack() })
        }
        composable("lock") { LockScreen(onUnlocked = { nav.goClean("main") }, onUsePassword = { nav.goClean("login") }) }
        composable("main") {
            MainTabs(
                onOpenTrip = { nav.navigate("trip/$it") },
                onOpenPermissions = { nav.navigate("permissions") },
                onLoggedOut = { nav.goClean("login") },
                onOpenHelp = { nav.navigate("help") },
                onOpenVehicles = { nav.navigate("vehicles") },
                onNavigate = { nav.navigate(it) },
            )
        }
        composable("vehicles") { VehiclesScreen(onBack = { nav.popBackStack() }, onRefuels = { nav.navigate("refuels/$it") }) }
        composable("places") { PlacesScreen(onBack = { nav.popBackStack() }) }
        composable("reminders") { RemindersScreen(onBack = { nav.popBackStack() }) }
        composable("refuels/{vehicle}") { RefuelsScreen(it.arguments?.getString("vehicle") ?: "", onBack = { nav.popBackStack() }) }
        composable("help") { HelpScreen(onBack = { nav.popBackStack() }) }
        composable("offline") { sk.cartracker.ui.screens.OfflineMapScreen(onBack = { nav.popBackStack() }) }
        composable("diagnostics") { sk.cartracker.ui.screens.DiagnosticsScreen(onBack = { nav.popBackStack() }) }
        composable("timeline") { sk.cartracker.ui.screens.TimelineScreen(onBack = { nav.popBackStack() }, onOpenTrip = { nav.navigate("trip/$it") }) }
        composable("routes") { sk.cartracker.ui.screens.RoutesScreen(onBack = { nav.popBackStack() }, onOpenTrip = { nav.navigate("trip/$it") }) }
        composable("trip/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            TripDetailScreen(it.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
        }
        composable("permissions") {
            PermissionsScreen(onDone = { if (nav.previousBackStackEntry != null) nav.popBackStack() else nav.goClean("main") })
        }
    }
}

@Composable
private fun MainTabs(
    onOpenTrip: (Long) -> Unit, onOpenPermissions: () -> Unit, onLoggedOut: () -> Unit, onOpenHelp: () -> Unit,
    onOpenVehicles: () -> Unit, onNavigate: (String) -> Unit,
) {
    // Záložky sa dajú prepínať aj potiahnutím doľava / doprava
    var saved by rememberSaveable { mutableIntStateOf(0) }
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = saved) { 4 }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(pager.currentPage) { saved = pager.currentPage }
    fun go(i: Int) { scope.launch { pager.animateScrollToPage(i) } }
    Scaffold(bottomBar = {
        NavigationBar {
            val tab = pager.targetPage
            NavigationBarItem(tab == 0, { go(0) }, { Icon(Icons.Filled.Dashboard, null) }, label = { Text("Prehľad") })
            NavigationBarItem(tab == 1, { go(1) }, { Icon(Icons.Filled.Route, null) }, label = { Text("Jazdy") })
            NavigationBarItem(tab == 2, { go(2) }, { Icon(Icons.Filled.BarChart, null) }, label = { Text("Štatistiky") })
            NavigationBarItem(tab == 3, { go(3) }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Nastavenia") })
        }
    }) { pad ->
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager, modifier = Modifier.padding(pad), beyondViewportPageCount = 0, key = { it },
        ) { page ->
            Surface(Modifier.fillMaxSize()) {
                when (page) {
                    0 -> HomeScreen(onOpenPermissions, onOpenVehicles, onNavigate)
                    1 -> TripsScreen(onOpenTrip, onNavigate)
                    2 -> StatsScreen(onNavigate)
                    else -> SettingsScreen(onOpenPermissions, onLoggedOut, onOpenHelp, onOpenVehicles, onNavigate)
                }
            }
        }
    }
}
