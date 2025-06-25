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

        // --- Пропорційний ресайз без згладжування ---
        val targetWidthPx = PRINTER_WIDTH_PX
        val aspectRatio = bitmap.height.toFloat() / bitmap.width
        val targetHeightPx = (targetWidthPx * aspectRatio).toInt()
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, targetWidthPx, targetHeightPx, false)

        // --- Обрізаємо поля ---
        val croppedBitmap = autoCropBitmap(resizedBitmap)

        // --- Додаємо лівий марджин 12px ---
        val printBitmap = addMarginsToBitmap(croppedBitmap, left = 12, top = 0)

        // --- Конвертуємо в ч/б ---
        val monoBitmap = toMonoBitmap(printBitmap)

        // --- Центруємо (опціонально, якщо треба) ---
        val centeredBitmap = centerBitmapHorizontally(monoBitmap, PRINTER_WIDTH_PX)

        // --- Конвертуємо в RGB_565 ---
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

        // --- Друкуємо тільки один раз ---
        val result = cpcl.PrinterHelper.printBitmap(0, 0, 0, finalBitmap, 0, false, 0)
        Log.d(TAG, "Pic result: $result")

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