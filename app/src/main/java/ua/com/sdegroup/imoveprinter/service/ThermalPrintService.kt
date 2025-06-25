// package ua.com.sdegroup.imoveprinter

// import android.bluetooth.BluetoothAdapter
// import android.bluetooth.BluetoothDevice
// import android.content.BroadcastReceiver
// import android.content.Context
// import android.content.Intent
// import android.content.IntentFilter
// import android.graphics.Bitmap
// import android.graphics.pdf.PdfRenderer
// import android.net.Uri
// import android.os.ParcelFileDescriptor
// import android.util.Log
// import androidx.lifecycle.SavedStateHandle
// import androidx.lifecycle.ViewModel
// import cpcl.PrinterHelper
// import kotlinx.coroutines.*
// import kotlinx.coroutines.flow.MutableStateFlow
// import kotlinx.coroutines.flow.StateFlow
// import kotlinx.coroutines.flow.asStateFlow
// import java.io.File
// import java.io.FileOutputStream
// import java.io.IOException

// class ThermalPrintService(
//     private val savedStateHandle: SavedStateHandle
// ) : ViewModel() {

//     private val TAG = "ThermalPrintService"
//     private val PRINTER_DPI = 200
//     private val DEFAULT_PAPER_WIDTH_MM = 210 // ISO_A4 ширина в мм
//     private val DEFAULT_PAPER_HEIGHT_MM = 297 // ISO_A4 высота в мм

//     private val _receivedAddress = MutableStateFlow<String?>("No address received yet")
//     val receivedAddress: StateFlow<String?> = _receivedAddress.asStateFlow()

//     init {
//         savedStateHandle.getLiveData<String>("address").observeForever { value ->
//             _receivedAddress.value = value
//             Log.d(TAG, "ViewModel received address: $value")
//             savedStateHandle.remove<String>("address")
//         }
//     }

//     private fun getPdfUriFromAssets(context: Context, assetFileName: String): Uri? {
//         val tempFile = File(context.cacheDir, assetFileName)
//         return try {
//             context.assets.open(assetFileName).use { inputStream ->
//                 FileOutputStream(tempFile).use { outputStream ->
//                     inputStream.copyTo(outputStream)
//                 }
//             }
//             Uri.fromFile(tempFile)
//         } catch (e: IOException) {
//             Log.e(TAG, "Error loading PDF from assets", e)
//             null
//         }
//     }

//     private suspend fun convertPdfPageToBitmap(
//         context: Context,
//         pdfUri: Uri?,
//         pageNumber: Int
//     ): Bitmap? = withContext(Dispatchers.IO) {
//         var fileDescriptor: ParcelFileDescriptor? = null
//         var pdfRenderer: PdfRenderer? = null
//         var currentPage: PdfRenderer.Page? = null

//         try {
//             if (pdfUri == null) return@withContext null
//             fileDescriptor = context.contentResolver.openFileDescriptor(pdfUri, "r")
//             if (fileDescriptor == null) return@withContext null

//             pdfRenderer = PdfRenderer(fileDescriptor)
//             if (pageNumber < 0 || pageNumber >= pdfRenderer.pageCount) return@withContext null

//             currentPage = pdfRenderer.openPage(pageNumber)

//             // Создаем Bitmap с размерами ISO_A4
//             val widthPx = (DEFAULT_PAPER_WIDTH_MM / 25.4 * PRINTER_DPI).toInt()
//             val heightPx = (DEFAULT_PAPER_HEIGHT_MM / 25.4 * PRINTER_DPI).toInt()

//             val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
//             currentPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

//             return@withContext bitmap
//         } catch (e: IOException) {
//             Log.e(TAG, "Error converting PDF to Bitmap", e)
//             return@withContext null
//         } finally {
//             currentPage?.close()
//             pdfRenderer?.close()
//             fileDescriptor?.close()
//         }
//     }

//     fun discoverPrinters(context: Context) {
//         val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

//         if (bluetoothAdapter == null) {
//             Log.e(TAG, "Bluetooth не поддерживается на этом устройстве")
//             return
//         }

//         if (!bluetoothAdapter.isEnabled) {
//             Log.e(TAG, "Bluetooth выключен")
//             return
//         }

//         // Получение списка сопряженных устройств
//         val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
//         pairedDevices?.forEach { device ->
//             Log.d(TAG, "Сопряженное устройство: ${device.name} - ${device.address}")
//         }

//         // Регистрация BroadcastReceiver для обнаружения новых устройств
//         val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
//         context.registerReceiver(object : BroadcastReceiver() {
//             override fun onReceive(context: Context, intent: Intent) {
//                 val action: String? = intent.action
//                 if (BluetoothDevice.ACTION_FOUND == action) {
//                     val device: BluetoothDevice? =
//                         intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
//                     device?.let {
//                         Log.d(TAG, "Найдено устройство: ${it.name} - ${it.address}")
//                     }
//                 }
//             }
//         }, filter)

//         // Запуск обнаружения устройств
//         if (bluetoothAdapter.startDiscovery()) {
//             Log.d(TAG, "Запущено обнаружение устройств")
//         } else {
//             Log.e(TAG, "Не удалось запустить обнаружение устройств")
//         }
//     }

//     fun connect(context: Context) {
//         val address = receivedAddress.value
//         if (address.isNullOrEmpty()) {
//             Log.e(TAG, "Printer address is not set")
//             return
//         }

//         if (!PrinterHelper.IsOpened()) {
//             val state = PrinterHelper.portOpenBT(context, address)
//             if (state == 0) {
//                 Log.d(TAG, "Successfully connected to printer: $address")
//             } else {
//                 Log.e(TAG, "Failed to connect to printer: $address, error code: $state")
//             }
//         } else {
//             Log.d(TAG, "Printer is already connected")
//         }
//     }

//     fun disconnect() {
//         try {
//             if (PrinterHelper.IsOpened()) {
//                 PrinterHelper.portClose()
//             }
//         } catch (e: Exception) {
//             Log.e(TAG, "Error disconnecting printer", e)
//         }
//     }

//     fun printPDF(context: Context) {
//         CoroutineScope(Dispatchers.Main).launch {
//             val pdfUri = getPdfUriFromAssets(context, "report.pdf")
//             val pageBitmap = convertPdfPageToBitmap(context, pdfUri, 0)
//             if (pageBitmap != null) {
//                 val result = PrinterHelper.printBitmap(0, 0, 0, pageBitmap, 0, false, 0)
//                 if (result == 0) {
//                     Log.d(TAG, "PDF printed successfully")
//                 } else {
//                     Log.e(TAG, "Failed to print PDF, error code: $result")
//                 }
//             } else {
//                 Log.e(TAG, "Failed to convert PDF to Bitmap")
//             }
//         }
//     }

//     fun getStatus(): String {
//         return try {
//             val status = PrinterHelper.getstatus()
//             when (status) {
//                 0 -> "Ready"
//                 2 -> "Out of paper"
//                 6 -> "Cover open"
//                 else -> "Error"
//             }
//         } catch (e: Exception) {
//             Log.e(TAG, "Error getting printer status", e)
//             "Error"
//         }
//     }

//     override fun onCleared() {
//         super.onCleared()
//         disconnect()
//     }
// }
// 1 v

// package ua.com.sdegroup.imoveprinter.service

// import android.content.Context
// import android.graphics.*
// import android.graphics.pdf.PdfRenderer
// import android.net.Uri
// import android.os.ParcelFileDescriptor
// import android.print.PrintAttributes
// import android.print.PrinterCapabilitiesInfo
// import android.print.PrinterInfo
// import android.print.PrinterId
// import android.printservice.PrintJob
// import android.printservice.PrintService
// import android.printservice.PrinterDiscoverySession
// import android.util.Log
// import cpcl.PrinterHelper
// import kotlinx.coroutines.*
// import java.io.File
// import java.io.FileInputStream
// import java.io.FileOutputStream

// class ThermalPrintService : PrintService() {

//     private val TAG = "ThermalPrintService"
//     private val PRINTER_DPI = 200
//     private val DEFAULT_PAPER_WIDTH_MM = 58
//     private val PRINTER_WIDTH_PX = ((DEFAULT_PAPER_WIDTH_MM / 25.4) * PRINTER_DPI).toInt()

//     override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
//         Log.d(TAG, "onCreatePrinterDiscoverySession called")
//         return object : PrinterDiscoverySession() {
//             override fun onStartPrinterDiscovery(printerIds: MutableList<PrinterId>) {
//                 Log.d(TAG, "onStartPrinterDiscovery called")
//                 val id = generatePrinterId("MY_THERMAL_PRINTER")
//                 val mediaSize = PrintAttributes.MediaSize("THERMAL_58MM", "58mm", PRINTER_WIDTH_PX, 100)
//                 val caps = PrinterCapabilitiesInfo.Builder(id)
//                     .addMediaSize(mediaSize, true)
//                     .addResolution(PrintAttributes.Resolution("R200x200", "200x200dpi", 200, 200), true)
//                     .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
//                     .build()
//                 val printerInfo = PrinterInfo.Builder(id, "Thermal Printer", PrinterInfo.STATUS_IDLE)
//                     .setCapabilities(caps)
//                     .build()
//                 addPrinters(listOf(printerInfo))
//             }

//             override fun onStopPrinterDiscovery() {
//                 Log.d(TAG, "onStopPrinterDiscovery")
//             }

//             override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
//                 Log.d(TAG, "onValidatePrinters: $printerIds")
//             }

//             override fun onStartPrinterStateTracking(printerId: PrinterId) {
//                 Log.d(TAG, "onStartPrinterStateTracking: $printerId")
//             }

//             override fun onStopPrinterStateTracking(printerId: PrinterId) {
//                 Log.d(TAG, "onStopPrinterStateTracking: $printerId")
//             }

//             override fun onDestroy() {
//                 Log.d(TAG, "PrinterDiscoverySession destroyed")
//             }
//         }
//     }

//     override fun onPrintJobQueued(printJob: PrintJob) {
//         Log.d(TAG, "onPrintJobQueued: ${printJob.info.id}")
//         printJob.start()

//         val pfd = printJob.document?.data
//         if (pfd == null) {
//             Log.e(TAG, "No document data for job")
//             printJob.fail("No document data")
//             return
//         }

//         val tempFile = File(cacheDir, "print_${printJob.info.id}.pdf")
//         try {
//             FileInputStream(pfd.fileDescriptor).use { input ->
//                 FileOutputStream(tempFile).use { output ->
//                     input.copyTo(output)
//                 }
//             }
//         } catch (e: Exception) {
//             Log.e(TAG, "Error copying PDF", e)
//             printJob.fail("Copy error")
//             return
//         }

//         CoroutineScope(Dispatchers.Main).launch {
//             val success = sendPdfToPrinterAsync(tempFile)
//             if (success) {
//                 printJob.complete()
//             } else {
//                 printJob.fail("Print failed")
//             }
//         }
//     }

//     override fun onRequestCancelPrintJob(printJob: PrintJob) {
//         Log.d(TAG, "onRequestCancelPrintJob: ${printJob.info.id}")
//         printJob.cancel()
//     }

//     /** Печать PDF */
//     private suspend fun sendPdfToPrinterAsync(pdfFile: File): Boolean = withContext(Dispatchers.IO) {
//         Log.d(TAG, "sendPdfToPrinterAsync: ${pdfFile.absolutePath}")
//         try {
//             val uri = Uri.fromFile(pdfFile)
//             val bitmap = convertPdfToBitmap(applicationContext, uri, 0)
//             if (bitmap == null) {
//                 Log.e(TAG, "Bitmap conversion failed")
//                 return@withContext false
//             }

//             val prefs = applicationContext.getSharedPreferences("printer_prefs", Context.MODE_PRIVATE)
//             val address = prefs.getString("printer_address", null)
//             if (address.isNullOrEmpty()) {
//                 Log.e(TAG, "No printer address")
//                 return@withContext false
//             }

//             val connected = PrinterHelper.portOpenBT(applicationContext, address)
//             if (connected != 0) {
//                 Log.e(TAG, "Failed to connect to printer: $connected")
//                 return@withContext false
//             }

//             PrinterHelper.printBitmap(0, 0, 0, bitmap, 0, false, 0)
//             PrinterHelper.Form()
//             PrinterHelper.Print()
//             PrinterHelper.portClose()

//             Log.d(TAG, "Printed successfully")
//             return@withContext true
//         } catch (e: Exception) {
//             Log.e(TAG, "Error during print", e)
//             return@withContext false
//         }
//     }

//     /** Конвертація PDF у Bitmap */
//     private suspend fun convertPdfToBitmap(context: Context, uri: Uri, page: Int): Bitmap? = withContext(Dispatchers.IO) {
//         var pdfRenderer: PdfRenderer? = null
//         var pageRenderer: PdfRenderer.Page? = null
//         var fileDescriptor: ParcelFileDescriptor? = null

//         try {
//             fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
//             if (fileDescriptor == null) return@withContext null
//             pdfRenderer = PdfRenderer(fileDescriptor)
//             if (page < 0 || page >= pdfRenderer.pageCount) return@withContext null

//             pageRenderer = pdfRenderer.openPage(page)
//             val targetWidth = PRINTER_WIDTH_PX
//             val ratio = pageRenderer.height.toFloat() / pageRenderer.width
//             val targetHeight = (targetWidth * ratio).toInt()

//             val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
//             bitmap.eraseColor(Color.WHITE)
//             pageRenderer.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

//             return@withContext bitmap
//         } catch (e: Exception) {
//             Log.e(TAG, "PDF to Bitmap error", e)
//             return@withContext null
//         } finally {
//             pageRenderer?.close()
//             pdfRenderer?.close()
//             fileDescriptor?.close()
//         }
//     }
// }
//  2 version 

package ua.com.sdegroup.imoveprinter

import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterInfo
import android.print.PrinterId
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Environment
import kotlinx.coroutines.*
import ua.com.sdegroup.imoveprinter.model.PrinterModel

class ThermalPrintService : PrintService() {

    private val TAG = "ThermalPrintService"
    private val PRINTER_WIDTH_MM = 58 // стандартна ширина рулону, мм (змінюй під свій принтер)
    private val PRINTER_DPI = 200     // роздільна здатність принтера
    private val PRINTER_WIDTH_PX = ((PRINTER_WIDTH_MM / 25.4) * PRINTER_DPI).toInt() // ≈384px

    /** Повертає стандартні розміри паперу у мм */
    fun getDefaultPaperSizeMm(): Pair<Int, Int> {
        val widthMm = PRINTER_WIDTH_MM
        val heightMm = 100 // наприклад, 100 мм (довжина чека, можна змінювати)
        return widthMm to heightMm
    }

    /** Повертає стандартні розміри паперу у пікселях */
    fun getDefaultPaperSizePx(): Pair<Int, Int> {
        val (widthMm, heightMm) = getDefaultPaperSizeMm()
        val widthPx = ((widthMm / 25.4) * PRINTER_DPI).toInt()
        val heightPx = ((heightMm / 25.4) * PRINTER_DPI).toInt()
        return widthPx to heightPx
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        Log.d(TAG, "onCreatePrinterDiscoverySession")
        return object : PrinterDiscoverySession() {
            override fun onStartPrinterDiscovery(printerIds: MutableList<PrinterId>) {
                Log.d(TAG, "onStartPrinterDiscovery")
                val id: PrinterId = generatePrinterId("MY_THERMAL_PRINTER")

                val caps = PrinterCapabilitiesInfo.Builder(id)
                    .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                    .addResolution(
                        PrintAttributes.Resolution("R200x200", "200×200 dpi", 200, 200),
                        true
                    )
                    .setColorModes(
                        PrintAttributes.COLOR_MODE_MONOCHROME or PrintAttributes.COLOR_MODE_COLOR,
                        PrintAttributes.COLOR_MODE_MONOCHROME
                    )
                    .build()

                val info = PrinterInfo.Builder(id, "My Thermal Printer", PrinterInfo.STATUS_IDLE)
                    .setCapabilities(caps)
                    .build()

                addPrinters(listOf(info))
                Log.d(TAG, "Printer added: $info")
            }

            override fun onStopPrinterDiscovery() {
                Log.d(TAG, "onStopPrinterDiscovery")
            }
            override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
                Log.d(TAG, "onValidatePrinters: $printerIds")
            }
            override fun onStartPrinterStateTracking(printerId: PrinterId) {
                Log.d(TAG, "onStartPrinterStateTracking: $printerId")
            }
            override fun onStopPrinterStateTracking(printerId: PrinterId) {
                Log.d(TAG, "onStopPrinterStateTracking: $printerId")
            }
            override fun onDestroy() {
                Log.d(TAG, "PrinterDiscoverySession destroyed")
            }
        }
    }

    override fun onPrintJobQueued(job: PrintJob) {
        Log.d(TAG, "PrintJob queued: ${job.info.id}")
        job.start()

        job.document?.data?.let { pfd ->
            Log.d(TAG, "Received document for print job: ${job.info.id}")
            val tempFile = File(cacheDir, "print_${job.info.id}.pdf")
            try {
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    FileOutputStream(tempFile).use { fos ->
                        fis.copyTo(fos)
                    }
                }
                Log.d(TAG, "PDF file copied to cache: ${tempFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Error copying PDF to cache", e)
                job.fail("File copy error")
                return
            }

            CoroutineScope(Dispatchers.Main).launch {
                val success = sendPdfToThermalPrinterAsync(tempFile)
                if (success) {
                    Log.d(TAG, "Print job ${job.info.id} completed successfully")
                    job.complete()
                } else {
                    Log.e(TAG, "Print job ${job.info.id} failed to send to printer")
                    job.fail("Send error")
                }
            }
        } ?: run {
            Log.e(TAG, "No document data for print job: ${job.info.id}")
            job.fail("No document data")
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        Log.d(TAG, "Print job cancelled: ${printJob.info.id}")
        printJob.cancel()
    }

    private suspend fun sendPdfToThermalPrinterAsync(pdfFile: File): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "sendPdfToThermalPrinter started for file: ${pdfFile.absolutePath}")
        try {
            val context: Context = applicationContext
            val uri = Uri.fromFile(pdfFile)
            val bitmap = convertA4PdfToThermalBitmap(context, uri, 0)
            if (bitmap == null) {
                Log.e(TAG, "Failed to render PDF page to bitmap")
                return@withContext false
            }

            // --- Обрезаем белые поля по краям ---
            val croppedBitmap = autoCropBitmap(bitmap)

            // --- Додаємо відступи (якщо треба) ---
            val printBitmap = addMarginsToBitmap(croppedBitmap, left = 0, top = 0)

            // --- Конвертація в ч/б ---
            val monoBitmap = toMonoBitmap(printBitmap)

            // --- Ресайз до ширини принтера (чек буде максимально широкий) ---
            val resizedBitmap = resizeBitmapToWidth(monoBitmap, PRINTER_WIDTH_PX)

            // --- Центрування по ширині рулона ---
            val centeredBitmap = centerBitmapHorizontally(resizedBitmap, PRINTER_WIDTH_PX)

            // --- Конвертація в RGB_565 ---
            val finalBitmap = centeredBitmap.copy(Bitmap.Config.RGB_565, false)

            // --- Збереження bitmap для дебагу ---
            val debugFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "debug_print_bitmap.png")
            try {
                FileOutputStream(debugFile).use {
                    finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                Log.d(TAG, "Bitmap saved to: ${debugFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving debug bitmap", e)
            }

            // --- Підключення до принтера ---
            val prefs = applicationContext.getSharedPreferences("printer_prefs", Context.MODE_PRIVATE)
            val printerAddress = prefs.getString("printer_address", null)
            if (printerAddress.isNullOrEmpty()) {
                Log.e(TAG, "Printer address not set in SharedPreferences")
                return@withContext false
            }
            Log.d(TAG, "Connecting to printer: $printerAddress")
            val connected = cpcl.PrinterHelper.portOpenBT(context, printerAddress)
            if (connected != 0) {
                Log.e(TAG, "Не удалось подключиться к принтеру: $printerAddress, код ошибки: $connected")
                return@withContext false
            }

            Log.d(TAG, "Printer is opened: ${cpcl.PrinterHelper.IsOpened()}")

            // --- Друк з різними rotation ---
            var result = -1
            for (rotation in 0..3) {
                Log.d(TAG, "Trying printBitmap with rotation=$rotation")
                result = cpcl.PrinterHelper.printBitmap(0, 0, 0, finalBitmap, 0, false, rotation)
                Log.d(TAG, "Pic result (rotation=$rotation): $result")
                if (result == 0) break
            }

            // --- Обов'язково Form() і Print() ---
            cpcl.PrinterHelper.Form()
            cpcl.PrinterHelper.Print()

            cpcl.PrinterHelper.portClose()
            return@withContext result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при отправке PDF на принтер", e)
            return@withContext false
        }
    }

    // Автообрезка белых полей по краям bitmap
    private fun autoCropBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        var left = width
        var right = 0

        for (x in 0 until width) {
            for (y in 0 until height) {
                if (bitmap.getPixel(x, y) != Color.WHITE) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        // Если весь bitmap белый — не обрезаем
        if (left >= right) return bitmap
        return Bitmap.createBitmap(bitmap, left, 0, right - left + 1, height)
    }

    // Центрування bitmap по ширині рулона
    private fun centerBitmapHorizontally(original: Bitmap, targetWidth: Int): Bitmap {
        if (original.width >= targetWidth) return original
        val left = (targetWidth - original.width) / 2
        val newBitmap = Bitmap.createBitmap(targetWidth, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(original, left.toFloat(), 0f, null)
        return newBitmap
    }

    private suspend fun convertPdfPageToBitmap(
        context: Context,
        pdfUri: Uri?,
        pageNumber: Int // 0-indexed page number
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
            val scaleFactor = PRINTER_DPI / 72f // 200 dpi, як у PrinterModel
            val bitmap = Bitmap.createBitmap(
                (currentPage.width * scaleFactor).toInt(),
                (currentPage.height * scaleFactor).toInt(),
                Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(Color.WHITE)
            currentPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return@withContext bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при конвертации PDF в Bitmap", e)
            return@withContext null
        } finally {
            currentPage?.close()
            pdfRenderer?.close()
            fileDescriptor?.close()
        }
    }

    private fun addMarginsToBitmap(original: Bitmap, left: Int = 0, top: Int = 0): Bitmap {
        val newBitmap = Bitmap.createBitmap(original.width + left, original.height + top, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(original, left.toFloat(), top.toFloat(), null)
        return newBitmap
    }

    // Ресайз до ширини принтера (зберігає пропорції)
    private fun resizeBitmapToWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
        if (bitmap.width == targetWidth) return bitmap
        val aspectRatio = bitmap.height.toFloat() / bitmap.width
        val targetHeight = (targetWidth * aspectRatio).toInt()
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    // Преобразование bitmap в чёрно-белый (dithered)
    private fun toMonoBitmap(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val bw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val threshold = 127
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val gray = (r + g + b) / 3
            // Только два цвета, без прозрачности!
            pixels[i] = if (gray > threshold) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        bw.setPixels(pixels, 0, width, 0, 0, width, height)
        return bw
    }

    private suspend fun convertA4PdfToThermalBitmap(
        context: Context,
        pdfUri: Uri?,
        pageNumber: Int
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

            // A4 в мм: 210×297 мм → в px при 200 dpi
            val a4WidthPx = (210f / 25.4f * PRINTER_DPI).toInt()  // ≈1653
            val a4HeightPx = (297f / 25.4f * PRINTER_DPI).toInt() // ≈2339

            val a4Bitmap = Bitmap.createBitmap(a4WidthPx, a4HeightPx, Bitmap.Config.ARGB_8888)
            a4Bitmap.eraseColor(Color.WHITE)

            currentPage.render(a4Bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            // Пропорційний ресайз під ширину принтера (384px)
            val targetWidthPx = PRINTER_WIDTH_PX
            val aspectRatio = a4Bitmap.height.toFloat() / a4Bitmap.width
            val targetHeightPx = (targetWidthPx * aspectRatio).toInt()

            val resizedBitmap = Bitmap.createScaledBitmap(a4Bitmap, targetWidthPx, targetHeightPx, true)

            return@withContext resizedBitmap

        } catch (e: Exception) {
            Log.e(TAG, "Error converting A4 PDF to thermal bitmap", e)
            return@withContext null
        } finally {
            currentPage?.close()
            pdfRenderer?.close()
            fileDescriptor?.close()
        }
    }
}