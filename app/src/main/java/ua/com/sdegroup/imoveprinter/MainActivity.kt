package ua.com.sdegroup.imoveprinter

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.print.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.com.sdegroup.imoveprinter.screens.*
import ua.com.sdegroup.imoveprinter.ui.theme.IMovePrinterTheme
import java.io.FileOutputStream
import java.util.Locale
import android.print.pdf.PrintedPdfDocument
import android.util.Log

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

        val intent = intent

        if (intent?.action == Intent.ACTION_SEND && intent.type == "application/pdf") {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                lifecycleScope.launch {
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            printPdfFromUri(uri)
                        }
                    }
                }
            }
            return
        } else if (intent?.action == Intent.ACTION_VIEW && intent.type == "application/pdf") {
            intent.data?.let { uri ->
                lifecycleScope.launch {
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            printPdfFromUri(uri)
                        }
                    }
                }
            }
            return
        }

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

    private fun printPdfFromUri(uri: Uri) {
        lifecycleScope.launchWhenResumed {
            if (isFinishing || isDestroyed) {
                Log.e("PrintDebug", "Activity is not in a valid state: isFinishing=$isFinishing, isDestroyed=$isDestroyed")
                return@launchWhenResumed
            }

            try {
                Log.d("PrintDebug", "Attempting to start print job. Activity state: isFinishing=$isFinishing, isDestroyed=$isDestroyed")
                val printManager = this@MainActivity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val printAdapter = object : PrintDocumentAdapter() {
                    override fun onLayout(
                        oldAttributes: PrintAttributes?,
                        newAttributes: PrintAttributes?,
                        cancellationSignal: android.os.CancellationSignal?,
                        callback: LayoutResultCallback?,
                        extras: android.os.Bundle?
                    ) {
                        callback?.onLayoutFinished(
                            PrintDocumentInfo.Builder("document.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(),
                            true
                        )
                    }

                    override fun onWrite(
                        pages: Array<PageRange>?,
                        destination: ParcelFileDescriptor?,
                        cancellationSignal: android.os.CancellationSignal?,
                        callback: WriteResultCallback?
                    ) {
                        try {
                            contentResolver.openInputStream(uri)?.use { input ->
                                FileOutputStream(destination?.fileDescriptor).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                        } catch (e: Exception) {
                            Log.e("PrintDebug", "Error writing PDF: ${e.message}", e)
                            callback?.onWriteFailed(e.message)
                        }
                    }
                }

                Log.d("PrintDebug", "Starting print job with valid Activity context")
                printManager.print("PDF Document", printAdapter, null)
            } catch (e: Exception) {
                Log.e("PrintDebug", "Error initializing PrintManager: ${e.message}", e)
            }
        }
    }

    private fun printBitmap(bitmap: Bitmap, printManager: PrintManager, jobName: String) {
        lifecycleScope.launch {
            val printAdapter = object : PrintDocumentAdapter() {
                private var pdfDocument: PrintedPdfDocument? = null
                private var printAttributes: PrintAttributes? = null

                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes,
                    cancellationSignal: android.os.CancellationSignal,
                    callback: LayoutResultCallback,
                    extras: Bundle?
                ) {
                    printAttributes = newAttributes
                    pdfDocument = PrintedPdfDocument(this@MainActivity, newAttributes)

                    if (cancellationSignal.isCanceled) {
                        callback.onLayoutCancelled()
                        return
                    }

                    val info = PrintDocumentInfo.Builder(jobName)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO)
                        .setPageCount(1)
                        .build()

                    callback.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<PageRange>,
                    destination: ParcelFileDescriptor,
                    cancellationSignal: android.os.CancellationSignal,
                    callback: WriteResultCallback
                ) {
                    val page = pdfDocument?.startPage(0)

                    if (cancellationSignal.isCanceled) {
                        callback.onWriteCancelled()
                        pdfDocument?.close()
                        pdfDocument = null
                        return
                    }

                    page?.canvas?.drawBitmap(bitmap, 0f, 0f, null)
                    pdfDocument?.finishPage(page)

                    try {
                        pdfDocument?.writeTo(FileOutputStream(destination.fileDescriptor))
                    } catch (e: Exception) {
                        callback.onWriteFailed(e.message)
                        return
                    } finally {
                        pdfDocument?.close()
                        pdfDocument = null
                    }

                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                }
            }

            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    printManager.print(jobName, printAdapter, null)
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
