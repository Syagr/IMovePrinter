package ua.com.sdegroup.imoveprinter.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
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

    /**
     * Копирует PDF из assets во временный файл и возвращает Uri.
     */
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
    // Пример: печать первой страницы report.pdf из assets
    val pdfUri = getPdfUriFromAssets(context, "report.pdf")
    if (pdfUri != null) {
        // Запускаем корутину для асинхронной печати
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            val result = printPdfOnBluetooth(context, pdfUri, 0)
            Log.d(TAG, "PDF print result: $result")
        }
    } else {
        Log.e(TAG, "PDF file not found in assets")
    }
}
    /**
     * Добавляет левое поле к bitmap (например, для термопринтера).
     */
    fun addLeftMargin(originalBitmap: Bitmap, offset: Int): Bitmap {
        val width = originalBitmap.width + offset
        val height = originalBitmap.height
        val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(originalBitmap, offset.toFloat(), 0f, null)
        return newBitmap
    }

    /**
     * Конвертирует страницу PDF в Bitmap (асинхронно).
     */
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
            val scaleFactor = 203f / 72f // 200 dpi
            val bitmap = Bitmap.createBitmap(
                (currentPage.width * scaleFactor).toInt(),
                (currentPage.height * scaleFactor).toInt(),
                Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(Color.WHITE)
            currentPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return@withContext if (addLeftMarginPx > 0) addLeftMargin(bitmap, addLeftMarginPx) else bitmap
        } catch (e: IOException) {
            e.printStackTrace()
            null
        } finally {
            currentPage?.close()
            pdfRenderer?.close()
            fileDescriptor?.close()
        }
    }

    /**
     * Сохраняет bitmap во внутреннее хранилище.
     */
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

    // --- Методы для работы с Bluetooth-принтером через PrinterHelper (если используется) ---

    suspend fun connect(context: Context) = withContext(Dispatchers.IO) {
        val address = receivedAddress.value
        if (!address.isNullOrEmpty()) {
            val isOpened = cpcl.PrinterHelper.IsOpened()
            Log.d(TAG, "Connecting: $address, isOpened: $isOpened")
            if (!isOpened) {
                val state = cpcl.PrinterHelper.portOpenBT(context, address)
                if (state == 0) {
                    Log.d(TAG, "Connected successfully")
                } else {
                    Log.e(TAG, "Failed to connect, state: $state")
                    // Попробуйте повторное подключение или покажите сообщение об ошибке
                }
            }
        } else {
            Log.e(TAG, "No address found for connection")
        }
    }
    
    suspend fun getStatus(): String = withContext(Dispatchers.IO) {
        getBluetoothPrinterStatus()
    }

    fun disconnect() {
        try {
            if (cpcl.PrinterHelper.IsOpened()) {
                cpcl.PrinterHelper.portClose()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


fun getBluetoothPrinterStatus(): String {
    return try {
        Log.d(TAG, "getBluetoothPrinterStatus called")
        if (!cpcl.PrinterHelper.IsOpened()) {
            Log.e(TAG, "Printer port is not opened")
            return "Не підключено"
        }
        val getStatus: Int = cpcl.PrinterHelper.getstatus()
        Log.d(TAG, "Status: $getStatus")
        when (getStatus) {
            0 -> "Готовий"
            2 -> "Закінчився папір"
            6 -> "Кришка відкрита"
            else -> "Помилка"
        }
    } catch (e: Exception) {
        Log.e(TAG, "Exception in getBluetoothPrinterStatus", e)
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
        } catch (e: Exception) {
            Log.e(TAG, "PrintSampleReceipt: ${e.message}")
        }
    }

    /**
     * Асинхронная печать PDF-файла на Bluetooth-принтере через PrinterHelper.
     */
    suspend fun printPdfOnBluetooth(
        context: Context,
        pdfUri: Uri?,
        pageNumber: Int = 0
    ): Boolean = withContext(Dispatchers.IO) {
        val pageBitmap = convertPdfPageToBitmap(context, pdfUri, pageNumber)
        if (pageBitmap != null) {
            val result = cpcl.PrinterHelper.printBitmap(0, 0, 0, pageBitmap, 0, false, 1)
            Log.e(TAG, "Pic result: $result")
            return@withContext result == 0
        }
        false
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