package ua.com.sdegroup.imoveprinter.model

import android.content.Context
import kotlinx.coroutines.delay
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import cpcl.PrinterHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ua.com.sdegroup.imoveprinter.util.PrinterNetworkHolder

class PrinterModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val TAG = "PrinterModel"
    private val _receivedAddress = MutableStateFlow<String?>("No address received yet")
    val receivedAddress: StateFlow<String?> = _receivedAddress.asStateFlow()

    init {
        savedStateHandle.getLiveData<String>("address").observeForever { value ->
            _receivedAddress.value = value
            Log.d(TAG, "ViewModel received address: $value")
            savedStateHandle.remove<String>("address")
        }
    }

    fun getPdfUriFromAssets(context: Context, assetFileName: String): Uri? {
        val tempFile = File(context.cacheDir, assetFileName)
        return try {
            context.assets.open(assetFileName).use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Uri.fromFile(tempFile)
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    fun printPDF(context: Context) {
        val pdfUri = getPdfUriFromAssets(context, "report.pdf")
        if (pdfUri != null) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                val result = printPdfOnBluetooth(context, pdfUri, 0)
                Log.d(TAG, "PDF print result: $result")
            }
        } else {
            Log.e(TAG, "PDF file not found in assets")
        }
    }

    fun addLeftMargin(originalBitmap: Bitmap, offset: Int): Bitmap {
        val width = originalBitmap.width + offset
        val height = originalBitmap.height
        val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(originalBitmap, offset.toFloat(), 0f, null)
        return newBitmap
    }

    suspend fun convertPdfPageToBitmap(
        context: Context,
        pdfUri: Uri?,
        pageNumber: Int,
        addLeftMarginPx: Int = 90
    ): Bitmap? = withContext(Dispatchers.IO) {
        var fileDescriptor: ParcelFileDescriptor? = null
        var pdfRenderer: PdfRenderer? = null
        var currentPage: PdfRenderer.Page? = null
        try {
            if (pdfUri == null) return@withContext null
            fileDescriptor = context.contentResolver.openFileDescriptor(pdfUri, "r")
            if (fileDescriptor == null) return@withContext null
            pdfRenderer = PdfRenderer(fileDescriptor)
            if (pageNumber < 0 || pageNumber >= pdfRenderer.pageCount) return@withContext null
            currentPage = pdfRenderer.openPage(pageNumber)
            val scaleFactor = 203f / 72f
            val bitmap = Bitmap.createBitmap(
                (currentPage.width * scaleFactor).toInt(),
                (currentPage.height * scaleFactor).toInt(),
                Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(Color.WHITE)
            currentPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return@withContext if (addLeftMarginPx > 0) addLeftMargin(
                bitmap,
                addLeftMarginPx
            ) else bitmap
        } catch (e: IOException) {
            e.printStackTrace()
            null
        } finally {
            currentPage?.close()
            pdfRenderer?.close()
            fileDescriptor?.close()
        }
    }

    fun setAddress(address: String) {
        _receivedAddress.value = address
        Log.d(TAG, "Set Bluetooth address: $address")
    }

    fun saveBitmapToInternalStorage(
        context: Context,
        bitmap: Bitmap,
        filename: String,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 90
    ): Boolean {
        val file = File(context.filesDir, filename)
        Log.d(TAG, file.path)
        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(format, quality, out)
            }
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    fun connect(context: Context, mode: Int) {
        val address = receivedAddress.value
        if (mode == 0 && !address.isNullOrBlank()) {
            if (!PrinterHelper.IsOpened()) {
                try {
                    val result = PrinterHelper.portOpenBT(context, address)
                    Log.d(TAG, "portOpenBT result: $result")
                    if (result != 0) {
                        Log.e(TAG, "Failed to connect to printer. Error code: $result")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception during Bluetooth connection", e)
                }
            }
        } else {
            Log.e(TAG, "Bluetooth address is null or blank")
        }
    }

    fun connectToPrinter(context: Context, type: String, addressOrIp: String): Boolean {
        try {
            if (PrinterHelper.IsOpened()) {
                PrinterHelper.portClose()
                Thread.sleep(200)
                Log.d(TAG, "Previous connection closed")
            }

            if (addressOrIp.isBlank()) {
                Log.e(TAG, "$type address is missing or invalid")
                return false
            }

            val result = when (type) {
                "Bluetooth" -> PrinterHelper.portOpenBT(context, addressOrIp)
                "WiFi" -> PrinterHelper.portOpenWIFI(context, addressOrIp)
                else -> -99
            }

            Log.d(TAG, "Connection result ($type): $result")
            if (result != 0) {
                Log.e(TAG, "Failed to connect to $type printer. Error code: $result")
            }
            return result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Exception during $type connection", e)
            return false
        }
    }


    suspend fun getStatus(type: String): String {
        delay(300)
        return getPrinterStatus(type)
    }

    fun disconnect(context: Context) {
        Log.d(TAG, "Disconnecting printer...")
        try {
            if (PrinterHelper.IsOpened()) {
                PrinterHelper.portClose()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing port", e)
        }

        PrinterNetworkHolder.networkCallback?.let { callback ->
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager
            cm.bindProcessToNetwork(null)
            cm.unregisterNetworkCallback(callback)
        }
        PrinterNetworkHolder.wifiNetwork = null
        PrinterNetworkHolder.networkCallback = null
    }

    fun getPrinterStatus(type: String): String {
        return try {
            Log.d(TAG, "getPrinterStatus called")
            if (!PrinterHelper.IsOpened()) return "Не підключено"

            if (type == "Bluetooth") {
                when (PrinterHelper.getstatus()) {
                    0 -> "Готовий"
                    2 -> "Закінчився папір"
                    6 -> "Кришка відкрита"
                    -1 -> "Помилка зв'язку"
                    else -> "Невідома помилка"
                }
            } else {
                "Wi-Fi підключено"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in getPrinterStatus", e)
            "Status error: ${e.message}"
        }
    }

    fun getVersion() {
        try {
            val model = cpcl.PrinterHelper.getPrintModel()
            Log.e(TAG, "Model: $model")
            val id = cpcl.PrinterHelper.getPrintID()
            Log.e(TAG, "ID: $id")
            val name = cpcl.PrinterHelper.getPrintName()
            Log.e(TAG, "Name: $name")
            val sn = cpcl.PrinterHelper.getPrintSN()
            Log.e(TAG, "SN: $sn")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun printTestReceipt() {
        try {
            val lines = arrayOf("Line1", "Line2", "Line3", "Line4")
            cpcl.PrinterHelper.LanguageEncode = "GBK"
            cpcl.PrinterHelper.RowSetX("200")
            cpcl.PrinterHelper.Setlp("5", "2", "32")
            cpcl.PrinterHelper.RowSetBold("2")
            cpcl.PrinterHelper.PrintData(lines[0] + "\r\n")
            cpcl.PrinterHelper.RowSetBold("1")
            cpcl.PrinterHelper.RowSetX("100")
            cpcl.PrinterHelper.Setlp("5", "2", "32")
            cpcl.PrinterHelper.RowSetBold("2")
            cpcl.PrinterHelper.PrintData(lines[1] + "\r\n")
            cpcl.PrinterHelper.RowSetBold("1")
            cpcl.PrinterHelper.RowSetX("100")
            for (i in 2 until lines.size) {
                cpcl.PrinterHelper.Setlp("5", "0", "32")
                cpcl.PrinterHelper.PrintData(lines[i] + "\r\n")
            }
            cpcl.PrinterHelper.RowSetX("0")
            cpcl.PrinterHelper.Form()
            cpcl.PrinterHelper.Print()
        } catch (e: Exception) {
            Log.e(TAG, "PrintSampleReceipt: ${e.message}")
        } finally {
            if (PrinterHelper.IsOpened()) {
                Log.d(TAG, "Closing port after test receipt")
                PrinterHelper.portClose()
            }
        }
    }

    suspend fun printPdfOnBluetooth(
        context: Context,
        pdfUri: Uri?,
        pageNumber: Int = 0
    ): Boolean = withContext(Dispatchers.IO) {
val pageBitmap = convertPdfPageToBitmap(context, pdfUri, pageNumber)
if (pageBitmap != null) {
    Log.d(TAG, "Bitmap width: ${pageBitmap.width}, height: ${pageBitmap.height}, config: ${pageBitmap.config}")
    try {
        val result = PrinterHelper.printBitmap(0, 0, 0, pageBitmap, 0, false, 1)
        PrinterHelper.Form()
        PrinterHelper.Print()
        Log.d(TAG, "PDF bitmap sent: result=$result")
        return@withContext result == 0
    } catch (e: Exception) {
        Log.e(TAG, "Error during PDF printing: ${e.message}", e)
        return@withContext false
    } finally {
                if (PrinterHelper.IsOpened()) {
                    Log.d(TAG, "Closing port after PDF print")
                    PrinterHelper.portClose()
                }
            }
        }
        false
    }

suspend fun sendPdfToPrinter(context: Context, type: String, pdfBitmap: Bitmap): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            if (!PrinterHelper.IsOpened()) {
                Log.e(TAG, "$type printer is not connected")
                return@withContext false
            }

            // Check printer status
            val status = getPrinterStatus(type)
            if (status != "Готовий" && status != "Wi-Fi підключено") {
                Log.e(TAG, "Printer is not ready: $status")
                return@withContext false
            }

            // Ensure bitmap is formatted correctly
            val formattedBitmap = pdfBitmap.copy(Bitmap.Config.ARGB_8888, true)

            // Call printBitmap with correct parameters
            val result = PrinterHelper.printBitmap(0, 0, 0, formattedBitmap, 0, false, 1)
            Log.d(TAG, "$type PDF bitmap sent: result=$result")
            if (result != 0) {
                Log.e(TAG, "Failed to send PDF bitmap to $type printer. Error code: $result")
            }
            return@withContext result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Exception during $type PDF printing", e)
            return@withContext false
        }
    }
}

    override fun onCleared() {
        super.onCleared()
        try {
            cpcl.PrinterHelper.portClose()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}