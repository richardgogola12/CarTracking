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
            )
        }
        composable("vehicles") { VehiclesScreen(onBack = { nav.popBackStack() }) }
        composable("help") { HelpScreen(onBack = { nav.popBackStack() }) }
        composable("trip/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            TripDetailScreen(it.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
        }
        composable("permissions") {
            PermissionsScreen(onDone = { if (nav.previousBackStackEntry != null) nav.popBackStack() else nav.goClean("main") })
        }
    }
}

@Composable
private fun MainTabs(onOpenTrip: (Long) -> Unit, onOpenPermissions: () -> Unit, onLoggedOut: () -> Unit, onOpenHelp: () -> Unit, onOpenVehicles: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Dashboard, null) }, label = { Text("Prehľad") })
            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Route, null) }, label = { Text("Jazdy") })
            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.BarChart, null) }, label = { Text("Štatistiky") })
            NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Nastavenia") })
        }
    }) { pad ->
        val m = Modifier.padding(pad)
        Surface(m) {
            when (tab) {
                0 -> HomeScreen(onOpenPermissions, onOpenVehicles)
                1 -> TripsScreen(onOpenTrip)
                2 -> StatsScreen()
                else -> SettingsScreen(onOpenPermissions, onLoggedOut, onOpenHelp, onOpenVehicles)
            }
        }
    }
}
