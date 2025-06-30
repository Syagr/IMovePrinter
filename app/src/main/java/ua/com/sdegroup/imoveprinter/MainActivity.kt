package ua.com.sdegroup.imoveprinter

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.nestedscroll.NestedScrollSource.Companion.SideEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
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

  private lateinit var currentLanguage: MutableState<String>

  override fun attachBaseContext(newBase: Context) {
    val prefs = newBase.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val savedLang = prefs.getString("language", null)
    val lang = savedLang ?: Locale.getDefault().language.takeIf { it == "en" } ?: "uk"

    val locale = Locale(lang)
    Locale.setDefault(locale)

    val config = Configuration(newBase.resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)

    super.attachBaseContext(newBase.createConfigurationContext(config))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    WindowCompat.setDecorFitsSystemWindows(window, false)

    val savedLanguage = getSavedLanguagePreference()
    val defaultLang = savedLanguage ?: Locale.getDefault().language.takeIf { it == "en" } ?: "uk"
    currentLanguage = mutableStateOf(defaultLang)

    setContent {
      IMovePrinterTheme {
        AppNavigation(currentLanguage.value) { newLang ->
          if (newLang != currentLanguage.value) {
            saveLanguagePreference(newLang)
            currentLanguage.value = newLang
            recreate()
          }
        }
      }
    }
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