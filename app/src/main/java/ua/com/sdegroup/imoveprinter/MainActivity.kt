package ua.com.sdegroup.imoveprinter

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.com.sdegroup.imoveprinter.service.ThermalPrintService
import ua.com.sdegroup.imoveprinter.screens.*
import ua.com.sdegroup.imoveprinter.ui.theme.IMovePrinterTheme
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class MainActivity : ComponentActivity() {

  companion object {
    private const val TAG = "MainActivity"
  }

  private val requestNotificationsLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (!granted) {
      Toast.makeText(
        this,
        getString(R.string.error_notifications_disabled),
        Toast.LENGTH_LONG
      ).show()
    }
  }

  private lateinit var currentLanguage: MutableState<String>

  override fun attachBaseContext(newBase: android.content.Context) {
    val prefs = newBase.getSharedPreferences("app_prefs", MODE_PRIVATE)
    val lang = prefs.getString(
      "language",
      Locale.getDefault().language.takeIf { it == "en" } ?: "uk"
    )!!
    val locale = Locale(lang)
    Locale.setDefault(locale)
    val cfg = android.content.res.Configuration(newBase.resources.configuration).apply {
      setLocale(locale)
      setLayoutDirection(locale)
    }
    super.attachBaseContext(newBase.createConfigurationContext(cfg))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Запросим разрешение на уведомления (Android 13+)
    requestNotificationPermissionIfNeeded()

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

  private fun requestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val perm = Manifest.permission.POST_NOTIFICATIONS
      if (ContextCompat.checkSelfPermission(this, perm)
        != PackageManager.PERMISSION_GRANTED
      ) {
        requestNotificationsLauncher.launch(perm)
      }
    }
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    handleShareIntent(intent)
  }

  private fun handleShareIntent(intent: android.content.Intent): Boolean {
    if (intent.action != android.content.Intent.ACTION_SEND) return false

    val cm = getSystemService(ConnectivityManager::class.java) as ConnectivityManager
    cm.bindProcessToNetwork(null)
    val mobileOrWifiNet = cm.allNetworks.firstOrNull { network ->
      cm.getNetworkCapabilities(network)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
    if (mobileOrWifiNet != null) cm.bindProcessToNetwork(mobileOrWifiNet)

    val pdfUri = intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM)
      ?: intent.data
    if (pdfUri != null) {
      directPrint(pdfUri)
      return true
    }

    if (intent.type == "text/plain") {
      intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?.let { url ->
          lifecycleScope.launch {
            val cachedUri = downloadToCache(url)
            if (cachedUri != null) {
              directPrint(cachedUri)
            } else {
              Toast.makeText(
                this@MainActivity,
                getString(R.string.error_download_pdf_preview),
                Toast.LENGTH_SHORT
              ).show()
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
      }
      withContext(Dispatchers.Main) {
        lifecycleScope.launch {
          kotlinx.coroutines.delay(1000)
          finish()
        }
      }
    }
  }

  private suspend fun downloadToCache(rawUrl: String): Uri? = withContext(Dispatchers.IO) {
    val url = rawUrl.trim()
    val cm = getSystemService(ConnectivityManager::class.java) as ConnectivityManager
    val internetNet = cm.allNetworks.firstOrNull { net ->
      cm.getNetworkCapabilities(net)?.run {
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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

    return@withContext try {
      val urlObj = URL(url)
      val conn = (internetNet.openConnection(urlObj) as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 10_000
        requestMethod = "GET"
        connect()
      }
      if (conn.responseCode != HttpURLConnection.HTTP_OK) {
        Log.e(TAG, "Server returned HTTP ${conn.responseCode}")
        null
      } else {
        val contentDisposition = conn.getHeaderField("Content-Disposition")
        val fileName = Regex("filename=\"?([^\";]+)\"?")
          .find(contentDisposition ?: "")
          ?.groupValues?.getOrNull(1)
          ?: urlObj.path.substringAfterLast('/').takeIf { it.isNotBlank() }
          ?: "downloaded.pdf"

        val f = File(cacheDir, fileName)
        conn.inputStream.use { inp ->
          FileOutputStream(f).use { out -> inp.copyTo(out) }
        }
        Uri.fromFile(f)
      }
    } catch (e: Exception) {
      Log.e(TAG, "downloadToCache failed: ${e.message}")
      null
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
