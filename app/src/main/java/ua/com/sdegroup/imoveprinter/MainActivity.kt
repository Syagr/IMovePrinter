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
    Log.d("LANG_LOG", "Selected app lang: $lang, system lang: ${Locale.getDefault().language}")
    val contextWithLocale = newBase.updateLocale(lang)
    super.attachBaseContext(contextWithLocale)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    WindowCompat.setDecorFitsSystemWindows(window, false)

    val savedLanguage = getSavedLanguagePreference()
    val defaultLang = savedLanguage ?: Locale.getDefault().language.takeIf { it == "en" } ?: "uk"
    currentLanguage = mutableStateOf(defaultLang)

    setAppLocale(defaultLang)
    saveLanguagePreference(defaultLang)

    setContent {
      val isDarkTheme = isSystemInDarkTheme()
      SideEffect {
        WindowInsetsControllerCompat(window, window.decorView)
          .isAppearanceLightStatusBars = !isDarkTheme
      }

      IMovePrinterTheme {
        AppNavigation(currentLanguage.value) { newLanguage ->
          saveLanguagePreference(newLanguage)
          setAppLocale(newLanguage, restart = true)
        }
      }
    }
  }

  fun setAppLocale(language: String, restart: Boolean = false) {
    val locale = Locale(language)
    Locale.setDefault(locale)
    val config = Configuration()
    config.setLocale(locale)
    resources.updateConfiguration(config, resources.displayMetrics)

    if (restart) {
      recreate()
    }
  }


  private fun getSystemLocale(): String {
    val systemLang = Locale.getDefault().language
    Log.d("LANG_CHECK", "System language: $systemLang")
    return if (systemLang in listOf("uk", "en")) systemLang else "uk"
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

fun Context.updateLocale(language: String): Context {
  val locale = Locale(language)
  Locale.setDefault(locale)
  val config = Configuration(resources.configuration)
  config.setLocale(locale)
  return createConfigurationContext(config)
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