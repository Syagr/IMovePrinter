package ua.com.sdegroup.imoveprinter

import android.util.Log
import android.net.ConnectivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.com.sdegroup.imoveprinter.service.ThermalPrintService
import ua.com.sdegroup.imoveprinter.screens.*
import ua.com.sdegroup.imoveprinter.ui.theme.IMovePrinterTheme
import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class MainActivity : ComponentActivity() {

  private lateinit var currentLanguage: MutableState<String>
  private val TAG = "MainActivity"
  override fun attachBaseContext(newBase: Context) {
    val prefs = newBase.getSharedPreferences("app_prefs", MODE_PRIVATE)
    val lang = prefs.getString(
      "language",
      Locale.getDefault().language.takeIf { it == "en" } ?: "uk"
    )!!
    val locale = Locale(lang)
    Locale.setDefault(locale)
    val cfg = Configuration(newBase.resources.configuration).apply {
      setLocale(locale)
      setLayoutDirection(locale)
    }
    super.attachBaseContext(newBase.createConfigurationContext(cfg))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    if (handleShareIntent(intent)) return

    val defaultLang = getSavedLanguage()
      ?: Locale.getDefault().language.takeIf { it == "en" } ?: "uk"
    currentLanguage = mutableStateOf(defaultLang)

    setContent {
      IMovePrinterTheme {
        val navController = rememberNavController()
        NavHost(navController, startDestination = "printer_setup") {
          composable("printer_setup") { back ->
            PrinterSetup(navController, back, currentLanguage.value) { new ->
              if (new != currentLanguage.value) {
                saveLanguage(new)
                currentLanguage.value = new
                recreate()
              }
            }
          }
          composable("device_list") {
            DeviceList(navController, currentLanguage.value) { new ->
              saveLanguage(new); currentLanguage.value = new; recreate()
            }
          }
          composable("bluetooth_discovery") {
            BluetoothDiscoveryScreen(navController, currentLanguage.value) { new ->
              saveLanguage(new); currentLanguage.value = new; recreate()
            }
          }
          composable("wifi_discovery") {
            WifiDiscoveryScreen(navController, currentLanguage.value) { new ->
              saveLanguage(new); currentLanguage.value = new; recreate()
            }
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleShareIntent(intent)
  }

  private fun handleShareIntent(intent: Intent): Boolean {
    if (intent.action != Intent.ACTION_SEND) return false

    val cm = getSystemService(ConnectivityManager::class.java) as ConnectivityManager

    cm.bindProcessToNetwork(null)

    val mobileOrWifiNet = cm.allNetworks.firstOrNull { network ->
      cm.getNetworkCapabilities(network)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    if (mobileOrWifiNet != null) {
      cm.bindProcessToNetwork(mobileOrWifiNet)
    }

    val pdfUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.data
    if (pdfUri != null) {
      directPrint(pdfUri)
      return true
    }

    if (intent.type == "text/plain") {
      intent.getStringExtra(Intent.EXTRA_TEXT)
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?.let { url ->
          val errorDownload = getString(R.string.error_download_pdf_preview)
          val noInternet = getString(R.string.no_internet)

          lifecycleScope.launch {
            val cachedUri = downloadToCache(url)
            if (cachedUri != null) {
              directPrint(cachedUri)
            } else {
              Toast.makeText(
                this@MainActivity, getString(R.string.error_download_pdf_preview),
                Toast.LENGTH_SHORT
              ).show()
              finish()
            }
          }
          return true
        }
    }

    return false
  }

  private fun directPrint(uri: Uri) {
    val printError = getString(R.string.print_error)
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        ThermalPrintService.printDirect(this@MainActivity, uri)
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          Toast.makeText(
            this@MainActivity,
            "$printError: ${e.message}",
            Toast.LENGTH_LONG
          ).show()
        }
      } finally {
        finish()
      }
    }
  }

  private suspend fun downloadToCache(rawUrl: String): Uri? = withContext(Dispatchers.IO) {
    val url = rawUrl.trim()
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val internetNet = cm.allNetworks.firstOrNull { net ->
      cm.getNetworkCapabilities(net)?.run {
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
      } == true
    }
    if (internetNet == null) {
      Log.e(TAG, "No validated internet network available")
      return@withContext null
    }

    if (!cm.bindProcessToNetwork(internetNet)) {
      Log.e(TAG, "Failed to bind process to network $internetNet")
      return@withContext null
    }

    try {
      val urlObj = URL(url)
      val conn = (internetNet.openConnection(urlObj) as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 10_000
        requestMethod = "GET"
        connect()
      }

      if (conn.responseCode != HttpURLConnection.HTTP_OK) {
        Log.e(TAG, "Server returned HTTP ${conn.responseCode} ${conn.responseMessage}")
        return@withContext null
      }

      val f = File(cacheDir, "shared.pdf")
      conn.inputStream.use { inp ->
        FileOutputStream(f).use { out ->
          inp.copyTo(out)
        }
      }
      return@withContext Uri.fromFile(f)

    } catch (e: Exception) {
      Log.e(TAG, "downloadToCache($url) failed: ${e.javaClass.simpleName}: ${e.message}")
      return@withContext null

    } finally {
      cm.bindProcessToNetwork(null)
    }
  }

  private fun saveLanguage(lang: String) {
    getSharedPreferences("app_prefs", MODE_PRIVATE)
      .edit().putString("language", lang).apply()
  }

  private fun getSavedLanguage(): String? =
    getSharedPreferences("app_prefs", MODE_PRIVATE)
      .getString("language", null)
}