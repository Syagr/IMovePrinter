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
    private val PRINTER_WIDTH_MM = 58f // стандартная ширина рулона, мм
    private val PRINTER_DPI = 203     // разрешение принтера
    private val PRINTER_WIDTH_PX = ((PRINTER_WIDTH_MM / 25.4f) * PRINTER_DPI).toInt() // ≈384px
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<String, Job>()
    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        Log.d(TAG, "onCreatePrinterDiscoverySession")
        return object : PrinterDiscoverySession() {
            override fun onStartPrinterDiscovery(printerIds: MutableList<PrinterId>) {
                Log.d(TAG, "onStartPrinterDiscovery")
                val id: PrinterId = generatePrinterId("MY_THERMAL_PRINTER")
                val widthMils  = (PRINTER_WIDTH_MM / 25.4f * 1000).toInt()
                val heightMils = (200f / 25.4f * 1000).toInt()
                val mediaSize = PrintAttributes.MediaSize(
                    "THERMAL_58MM", "58 mm",
                    widthMils, heightMils
                )

                val caps = PrinterCapabilitiesInfo.Builder(id)
                    .addMediaSize(mediaSize, true)
                    .addResolution(
                        PrintAttributes.Resolution("default", "203×203 dpi", 203, 203),
                        true
                    )
                    .setColorModes(
                        PrintAttributes.COLOR_MODE_MONOCHROME,
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
        val jobId = job.info.id ?: run {
            job.fail("Invalid job id"); return
        }
        job.start()

        val pfd = job.document?.data ?: run {
            job.fail("No document data"); return
        }
        val tempPdf = File(cacheDir, "print_$jobId.pdf")

        // Сохраняем PDF и запускаем печать в IO-диспетчере
        val co = serviceScope.launch {
            try {
                pfd.use { fd ->
                    FileInputStream(fd.fileDescriptor).use { fis ->
                        FileOutputStream(tempPdf).use { fos ->
                            fis.copyTo(fos)
                        }
                    }                }            } catch (e: Exception) {
                withContext(Dispatchers.Main) { job.fail("File copy error") }
                return@launch
            }

            val ok = sendPdfToThermalPrinterAsync(tempPdf, addLeftMarginPx = 90, addTopMarginPx = 50)
            withContext(Dispatchers.Main) {
                if (ok) job.complete() else job.fail("Send error")
            }
        }
        activeJobs[jobId.toString()] = co
    }

    override fun onRequestCancelPrintJob(job: PrintJob) {
        val id = job.info.id?.toString()
            ?: return
        serviceScope.launch {
            activeJobs.remove(id)?.cancel()
            withContext(Dispatchers.Main) { job.cancel() }
        }    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    private suspend fun sendPdfToThermalPrinterAsync(
        pdfFile: File,
        addLeftMarginPx: Int,
        addTopMarginPx: Int
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "sendPdfToThermalPrinter: ${pdfFile.path}")
        try {
            // 1) Рендерим первую страницу PDF в Bitmap
            val uri = Uri.fromFile(pdfFile)
            val bitmap = convertA4PdfToBitmap(applicationContext, uri, 0)
                ?: return@withContext false

            // 2) Обрезаем белые поля
            val cropped = autoCropBitmap(bitmap)

            // 3) Добавляем визуальные отступы
            val withLeft = addLeftMargin(cropped, addLeftMarginPx)
            val finalBmp  = addTopMargin(withLeft, addTopMarginPx)
                .let { toMonoBitmap(it) }
                .let { resizeBitmapToWidth(it, PRINTER_WIDTH_PX) }
                .let { centerBitmapHorizontally(it, PRINTER_WIDTH_PX) }
                .copy(Bitmap.Config.RGB_565, false)

            // 4) Подключаемся к принтеру
            val prefs   = applicationContext.getSharedPreferences("printer_prefs", Context.MODE_PRIVATE)
            val address = prefs.getString("printer_address", "") ?: ""
            if (address.isBlank()) return@withContext false
            if (PrinterHelper.portOpenBT(applicationContext, address) != 0) {
                PrinterHelper.portClose()
                return@withContext false
            }

            // 5) Печатаем один раз, с нужными отступами
            val result = PrinterHelper.printBitmap(
                /* x = */ addLeftMarginPx,
                /* y = */ addTopMarginPx,
                /* type = */ 0,
                /* bitmap = */ finalBmp,
                /* compressType = */ 0,
                /* isForm = */ false,
                /* segments = */ 1
            )
            if (result != 0) {
                PrinterHelper.portClose()
                return@withContext false
            }

            // 6) Завершаем
            PrinterHelper.Form()
            PrinterHelper.Print()
            PrinterHelper.portClose()
            true

        } catch (e: Exception) {
            Log.e(TAG, "Error printing PDF", e)
            false
        }
    }

    // --- Вспомогательные методы ---

    private fun addLeftMargin(src: Bitmap, marginPx: Int): Bitmap {
        val w = src.width + marginPx
        val h = src.height
        val out = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, marginPx.toFloat(), 0f, null)
        return out
    }

    private fun addTopMargin(src: Bitmap, topPx: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height + topPx, src.config ?: Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, topPx.toFloat(), null)
        }
        return out
    }

    private fun autoCropBitmap(bitmap: Bitmap): Bitmap {
        var left = bitmap.width
        var right = 0
        for (x in 0 until bitmap.width)
            for (y in 0 until bitmap.height)
                if (bitmap.getPixel(x, y) != Color.WHITE) {
                    left  = minOf(left, x)
                    right = maxOf(right, x)
                }
        return if (left < right)
            Bitmap.createBitmap(bitmap, left, 0, right - left + 1, bitmap.height)
        else bitmap
    }

    private fun centerBitmapHorizontally(src: Bitmap, targetWidth: Int): Bitmap {
        if (src.width >= targetWidth) return src
        val left = (targetWidth - src.width) / 2
        val out = Bitmap.createBitmap(targetWidth, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, left.toFloat(), 0f, null)
        }
        return out
    }

    private fun resizeBitmapToWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
        if (bitmap.width == targetWidth) return bitmap
        val h = (bitmap.height.toFloat() / bitmap.width * targetWidth).toInt()
        return Bitmap.createScaledBitmap(bitmap, targetWidth, h, true)
    }

    private fun toMonoBitmap(src: Bitmap): Bitmap {
        val bw = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(src.width * src.height).also { src.getPixels(it, 0, src.width, 0, 0, src.width, src.height) }
        for (i in pixels.indices) {
            val gray = ((pixels[i] ushr 16 and 0xFF) +
                    (pixels[i] ushr 8  and 0xFF) +
                    (pixels[i]        and 0xFF)) / 3
            pixels[i] = if (gray > 127) Color.WHITE else Color.BLACK
        }
        bw.setPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        return bw
    }

    private fun adjustBitmapHeight(bitmap: Bitmap): Bitmap {
        val maxHeight = bitmap.height / 2
        return Bitmap.createScaledBitmap(bitmap, bitmap.width, maxHeight, true)
    }

    private suspend fun convertA4PdfToBitmap(
        context: Context,
        pdfUri: Uri,
        page: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            fd = context.contentResolver.openFileDescriptor(pdfUri, "r") ?: return@withContext null
            renderer = PdfRenderer(fd)
            if (page !in 0 until renderer.pageCount) return@withContext null
            renderer.openPage(page).use { p ->
                val w = ((210f / 25.4f) * PRINTER_DPI).toInt()
                val h = ((297f / 25.4f) * PRINTER_DPI).toInt()
                return@withContext Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }        } catch (e: Exception) {
            Log.e(TAG, "convertA4PdfToBitmap error", e)
            null
        } finally {
            renderer?.close()
            fd?.close()
        }
    }
}