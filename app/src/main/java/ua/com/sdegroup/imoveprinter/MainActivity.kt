package ua.com.sdegroup.imoveprinter

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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

  private fun handleShareIntent(i: Intent): Boolean {
    if (i.action != Intent.ACTION_SEND) return false
    val errorDownloadPdfPreview = getString(R.string.error_download_pdf_preview)
    val pdfUri = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: i.data
    if (pdfUri != null) {
      directPrint(pdfUri)
      return true
    }

    if (i.type == "text/plain") {
      i.getStringExtra(Intent.EXTRA_TEXT)?.let { url ->
        lifecycleScope.launch {
          val uri = downloadToCache(url)
          if (uri != null) {
            directPrint(uri)
          } else {
            withContext(Dispatchers.Main) {
              Toast.makeText(
                this@MainActivity,
                errorDownloadPdfPreview,
                Toast.LENGTH_SHORT
              ).show()
            }
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

  private suspend fun downloadToCache(url: String): Uri? = withContext(Dispatchers.IO) {
    return@withContext try {
      val conn = URL(url).openConnection() as HttpURLConnection
      conn.connect()
      val f = File(cacheDir, "shared.pdf")
      conn.inputStream.use { inp ->
        FileOutputStream(f).use { out -> inp.copyTo(out) }
      }
      conn.disconnect()
      Uri.fromFile(f)
    } catch (e: Exception) {
      e.printStackTrace()
      null
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