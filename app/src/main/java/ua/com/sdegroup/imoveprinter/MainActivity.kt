package ua.com.sdegroup.imoveprinter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ua.com.sdegroup.imoveprinter.screens.PrinterSetup
import ua.com.sdegroup.imoveprinter.screens.DeviceList
import ua.com.sdegroup.imoveprinter.screens.BluetoothDiscoveryScreen
import ua.com.sdegroup.imoveprinter.screens.WifiDiscoveryScreen
import ua.com.sdegroup.imoveprinter.ui.theme.IMovePrinterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IMovePrinterTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "printer_setup"
    ) {
        composable("printer_setup") { backStackEntry ->
            PrinterSetup(navController = navController, backStackEntry = backStackEntry)
        }
        composable("device_list") {
            DeviceList(navController = navController)
        }
        composable("bluetooth_discovery") {
            BluetoothDiscoveryScreen(navController = navController)
        }
        composable("wifi_discovery") {
            WifiDiscoveryScreen(navController = navController)
        }
    }
}