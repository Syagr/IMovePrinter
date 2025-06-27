package ua.com.sdegroup.imoveprinter

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import java.util.*
import ua.com.sdegroup.imoveprinter.screens.PrinterSetup
import ua.com.sdegroup.imoveprinter.screens.DeviceList
import ua.com.sdegroup.imoveprinter.screens.BluetoothDiscoveryScreen
import ua.com.sdegroup.imoveprinter.screens.WifiDiscoveryScreen
import ua.com.sdegroup.imoveprinter.ui.theme.IMovePrinterTheme

class MainActivity : ComponentActivity() {
    private var currentLanguage = mutableStateOf(getSystemLocale())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val savedLanguage = getSavedLanguagePreference()
        if (savedLanguage != null) {
            setAppLocale(savedLanguage)
            currentLanguage.value = savedLanguage
        } else {
            setAppLocale(currentLanguage.value)
        }

        enableEdgeToEdge()
        setContent {
            IMovePrinterTheme {
                AppNavigation(currentLanguage.value) { newLanguage ->
                    saveLanguagePreference(newLanguage)
                    setAppLocale(newLanguage)
                    currentLanguage.value = newLanguage
                }
            }
        }
    }

    fun setAppLocale(language: String) {
        val locale = Locale(language)
        Locale.setDefault(locale)
        val config = Configuration()
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun getSystemLocale(): String {
        return Locale.getDefault().language
    }

    private fun saveLanguagePreference(language: String) {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("language", language).apply()
    }

    private fun getSavedLanguagePreference(): String? {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("language", null)
    }
}

@Composable
fun AppNavigation(
    currentLanguage: String,
    onLanguageChange: (String) -> Unit
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "printer_setup"
    ) {
        composable("printer_setup") { backStackEntry ->
            PrinterSetup(
                navController = navController,
                backStackEntry = backStackEntry,
                currentLanguage = currentLanguage,
                onLanguageChange = onLanguageChange
            )
        }
        composable("device_list") {
            DeviceList(
                navController = navController,
                currentLanguage = currentLanguage,
                onLanguageChange = onLanguageChange
            )
        }
        composable("bluetooth_discovery") {
            BluetoothDiscoveryScreen(
                navController = navController,
                currentLanguage = currentLanguage,
                onLanguageChange = onLanguageChange
            )
        }
        composable("wifi_discovery") {
            WifiDiscoveryScreen(
                navController = navController,
                currentLanguage = currentLanguage,
                onLanguageChange = onLanguageChange
            )
        }
    }
}