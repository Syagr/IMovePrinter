package ua.com.sdegroup.imoveprinter.service

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterInfo
import android.print.PrinterId
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import cpcl.PrinterHelper
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import ua.com.sdegroup.imoveprinter.model.PrinterModel

class ThermalPrintService : PrintService() {

    private val TAG = "ThermalPrintService"
    private val PRINTER_WIDTH_MM = 58 // стандартная ширина рулона, мм
    private val PRINTER_DPI = 203     // разрешение принтера
    private val PRINTER_WIDTH_PX = ((PRINTER_WIDTH_MM / 25.4) * PRINTER_DPI).toInt() // ≈384px

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        Log.d(TAG, "onCreatePrinterDiscoverySession")
        return object : PrinterDiscoverySession() {
            override fun onStartPrinterDiscovery(printerIds: MutableList<PrinterId>) {
                Log.d(TAG, "onStartPrinterDiscovery")
                val id: PrinterId = generatePrinterId("MY_THERMAL_PRINTER")

                val mediaSize = PrintAttributes.MediaSize(
                    "THERMAL_58MM", "58mm", PRINTER_WIDTH_PX, 1000
                )

                val caps = PrinterCapabilitiesInfo.Builder(id)
                    .addMediaSize(mediaSize, true)
                    .addResolution(
                        PrintAttributes.Resolution("default", "203×203 dpi", 203, 203),
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

    private suspend fun sendPdfToThermalPrinterAsync(pdfFile: File, addLeftMarginPx: Int = 90): Boolean = withContext(Dispatchers.IO) {
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

            // --- Добавляем отступы ---
            val printBitmap = PrinterModel(SavedStateHandle()).addLeftMargin(croppedBitmap, addLeftMarginPx)

            // --- Конвертация в ч/б ---
            val monoBitmap = toMonoBitmap(printBitmap)

            // --- Ресайз до ширины принтера ---
            val resizedBitmap = resizeBitmapToWidth(monoBitmap, PRINTER_WIDTH_PX)

            // --- Уменьшаем длину, если изображение слишком длинное ---
            val adjustedBitmap = adjustBitmapHeight(resizedBitmap)

            // --- Центрирование по ширине рулона ---
            val centeredBitmap = centerBitmapHorizontally(adjustedBitmap, PRINTER_WIDTH_PX)

            // --- Конвертация в RGB_565 ---
            val finalBitmap = centeredBitmap.copy(Bitmap.Config.RGB_565, false)

            // --- Подключение к принтеру ---
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

            // --- Тестируем повороты ---
            var successfulRotation: Int? = null
            for (rotation in 0..3) {
                Log.d(TAG, "Testing printBitmap with rotation=$rotation")
                val testResult = cpcl.PrinterHelper.printBitmap(0, 0, 0, finalBitmap, 0, true, rotation) // true для теста
                Log.d(TAG, "Test result (rotation=$rotation): $testResult")
                if (testResult == 0) {
                    successfulRotation = rotation
                    break // Успешный поворот найден
                }
            }

            if (successfulRotation == null) {
                Log.e(TAG, "No successful rotation found for printing")
                cpcl.PrinterHelper.portClose()
                return@withContext false
            }

            // --- Печать с успешным поворотом ---
            Log.d(TAG, "Printing with successful rotation=$successfulRotation")
            val printResult = cpcl.PrinterHelper.printBitmap(0, 0, 0, finalBitmap, 0, false, successfulRotation)
            if (printResult != 0) {
                Log.e(TAG, "Ошибка при печати изображения, код ошибки: $printResult")
                cpcl.PrinterHelper.portClose()
                return@withContext false
            }

            // --- Завершение печати ---
            cpcl.PrinterHelper.Form()
            cpcl.PrinterHelper.Print()

            cpcl.PrinterHelper.portClose()
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при отправке PDF на принтер", e)
            return@withContext false
        }
    }

    // --- Вспомогательные методы ---
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
        if (left >= right) return bitmap
        return Bitmap.createBitmap(bitmap, left, 0, right - left + 1, height)
    }

    private fun centerBitmapHorizontally(original: Bitmap, targetWidth: Int): Bitmap {
        if (original.width >= targetWidth) return original
        val left = (targetWidth - original.width) / 2
        val newBitmap = Bitmap.createBitmap(targetWidth, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(original, left.toFloat(), 0f, null)
        return newBitmap
    }

    private fun resizeBitmapToWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
        if (bitmap.width == targetWidth) return bitmap
        val aspectRatio = bitmap.height.toFloat() / bitmap.width
        val targetHeight = (targetWidth * aspectRatio).toInt()
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

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
            pixels[i] = if (gray > threshold) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        bw.setPixels(pixels, 0, width, 0, 0, width, height)
        return bw
    }

    private fun adjustBitmapHeight(bitmap: Bitmap): Bitmap {
        val maxHeight = bitmap.height / 2
        return Bitmap.createScaledBitmap(bitmap, bitmap.width, maxHeight, true)
    }

    private suspend fun convertA4PdfToThermalBitmap(
        context: Context,
        pdfUri: Uri,
        pageNumber: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        var fileDescriptor: ParcelFileDescriptor? = null
        var pdfRenderer: PdfRenderer? = null
        var currentPage: PdfRenderer.Page? = null

        try {
            fileDescriptor = context.contentResolver.openFileDescriptor(pdfUri, "r")
            if (fileDescriptor == null) return@withContext null

            pdfRenderer = PdfRenderer(fileDescriptor)
            if (pageNumber < 0 || pageNumber >= pdfRenderer.pageCount) return@withContext null

            currentPage = pdfRenderer.openPage(pageNumber)

            // Рассчитываем размеры для A4
            val widthPx = ((210 / 25.4) * PRINTER_DPI).toInt() // 210 мм ширина A4
            val heightPx = ((297 / 25.4) * PRINTER_DPI).toInt() // 297 мм высота A4

            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            currentPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            return@withContext bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error converting PDF to Bitmap", e)
            return@withContext null
        } finally {
            currentPage?.close()
            pdfRenderer?.close()
            fileDescriptor?.close()
        }
    }
}