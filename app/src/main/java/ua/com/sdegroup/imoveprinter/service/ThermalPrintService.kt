package ua.com.sdegroup.imoveprinter.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterInfo
import android.print.PrinterId
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import cpcl.PrinterHelper
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class ThermalPrintService : PrintService() {

    private val TAG = "ThermalPrintService"
    private val PRINTER_WIDTH_MM = 58f
    private val PRINTER_DPI = 203
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<String, Job>()

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        Log.d(TAG, "onCreatePrinterDiscoverySession")
        return object : PrinterDiscoverySession() {
            override fun onStartPrinterDiscovery(printerIds: MutableList<PrinterId>) {
                Log.d(TAG, "onStartPrinterDiscovery")
                val prefs = applicationContext
                    .getSharedPreferences("printer_prefs", Context.MODE_PRIVATE)
                val id = generatePrinterId("MY_THERMAL_PRINTER")
                val label = prefs.getString("printer_name", "My Thermal Printer")
                    ?: "My Thermal Printer"
                val widthMils = (PRINTER_WIDTH_MM / 25.4f * 1000).toInt()
                val heightMils = (200f / 25.4f * 1000).toInt()
                val mediaSize = PrintAttributes.MediaSize(
                    "THERMAL_58MM", "58 mm", widthMils, heightMils
                )
                val caps = PrinterCapabilitiesInfo.Builder(id)
                    .addMediaSize(mediaSize, true)
                    .addResolution(
                        PrintAttributes.Resolution("default", "203×203", PRINTER_DPI, PRINTER_DPI),
                        true
                    )
                    .setColorModes(
                        PrintAttributes.COLOR_MODE_MONOCHROME,
                        PrintAttributes.COLOR_MODE_MONOCHROME
                    )
                    .build()
                val info = PrinterInfo.Builder(id, label, PrinterInfo.STATUS_IDLE)
                    .setCapabilities(caps)
                    .build()
                addPrinters(listOf(info))
                Log.d(TAG, "Printer added: $info")
            }

            override fun onStopPrinterDiscovery() = Unit
            override fun onValidatePrinters(printerIds: MutableList<PrinterId>) = Unit
            override fun onStartPrinterStateTracking(printerId: PrinterId) = Unit
            override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit
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
            job.fail("No data"); return
        }
        val tempPdf = File(cacheDir, "print_$jobId.pdf")
        serviceScope.launch {
            try {
                pfd.use { fd ->
                    FileInputStream(fd.fileDescriptor).use { fis ->
                        FileOutputStream(tempPdf).use { fos ->
                            fis.copyTo(fos)
                        }
                    }
                }
                val bitmap =
                    convertPdfPageToBitmap(
                        applicationContext,
                        tempPdf,
                        0,
                        addLeftMarginPx = 90,
                        addTopMarginPx = 90
                    )
                val ok = bitmap?.let { printBitmapOverBluetooth(it) } ?: false
                withContext(Dispatchers.Main) {
                    if (ok) job.complete() else job.fail("Print error")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { job.fail("Error: ${e.message}") }
            }
        }.also { activeJobs[jobId.toString()] = it }
    }

    override fun onRequestCancelPrintJob(job: PrintJob) {
        job.info.id?.toString()?.let { id ->
            activeJobs.remove(id)?.cancel()
            CoroutineScope(Dispatchers.Main).launch { job.cancel() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    private suspend fun convertPdfPageToBitmap(
        context: Context,
        pdfFile: File,
        pageNumber: Int,
        addLeftMarginPx: Int = 0,
        addTopMarginPx: Int = 0
    ): Bitmap? = withContext(Dispatchers.IO) {
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fd)
            if (pageNumber !in 0 until renderer.pageCount) return@withContext null
            renderer.openPage(pageNumber).use { page ->
                val scale = PRINTER_DPI / 72f
                val bmp = Bitmap.createBitmap(
                    (page.width * scale).toInt(),
                    (page.height * scale).toInt(),
                    Bitmap.Config.ARGB_8888
                )
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val w = bmp.width + addLeftMarginPx
                val h = bmp.height + addTopMarginPx
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(out)
                c.drawColor(Color.WHITE)
                c.drawBitmap(bmp, addLeftMarginPx.toFloat(), addTopMarginPx.toFloat(), null)
                val pixels = IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
                for (i in pixels.indices) {
                    val gray = ((pixels[i] ushr 16 and 0xFF) +
                            (pixels[i] ushr 8 and 0xFF) +
                            (pixels[i] and 0xFF)) / 3
                    pixels[i] = if (gray > 127) Color.WHITE else Color.BLACK
                }
                out.setPixels(pixels, 0, w, 0, 0, w, h)
                out
            }
        } catch (e: Exception) {
            Log.e(TAG, "convertPdf error", e)
            null
        } finally {
            renderer?.close()
            fd?.close()
        }
    }

    private suspend fun printBitmapOverBluetooth(bitmap: Bitmap): Boolean =
        withContext(Dispatchers.IO) {
            val prefs = applicationContext
                .getSharedPreferences("printer_prefs", Context.MODE_PRIVATE)
            val addr = prefs.getString("printer_address", "") ?: ""
            if (addr.isBlank()) return@withContext false
            if (PrinterHelper.portOpenBT(applicationContext, addr) != 0) {
                PrinterHelper.portClose()
                return@withContext false
            }

            try {
                PrinterHelper.papertype_CPCL(0)
                PrinterHelper.printAreaSize(
                    "0",
                    PRINTER_DPI.toString(),
                    PRINTER_DPI.toString(),
                    bitmap.height.toString(),
                    "1"
                )

                val res = PrinterHelper.printBitmap(0, 0, 0, bitmap, 0, false, 1)

                PrinterHelper.openEndStatic(true)
                val status = PrinterHelper.getEndStatus(16)
                PrinterHelper.openEndStatic(false)

                return@withContext (status == 0)
            } finally {
                delay(500)
                PrinterHelper.portClose()
            }
        }
}