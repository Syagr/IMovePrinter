package ua.com.sdegroup.imoveprinter.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.ConnectivityManager
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
import ua.com.sdegroup.imoveprinter.util.PrinterNetworkHolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.roundToInt
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri

class ThermalPrintService : PrintService() {

  companion object {
    private const val TAG = "ThermalPrintService"
    private const val PRINTER_WIDTH_MM = 58f
    private const val PRINTER_DPI = 203

    fun printDirect(context: Context, uri: Uri) {
      val tmp = File(context.cacheDir, "direct_print.pdf")
      context.contentResolver.openInputStream(uri)!!.use { inp ->
        FileOutputStream(tmp).use { out -> inp.copyTo(out) }
      }

      runBlocking {
        val renderer = PdfRenderer(
          ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        )
        for (i in 0 until renderer.pageCount) {
          val bmp = convertPdfPageToBitmap(context, tmp, i, 90, 90)
          if (bmp != null) {
            if (!sendBitmapToPrinter(context, bmp)) {
              Log.e(TAG, "directPrint: page $i failed")
            }
            delay(200)
          }
        }
        renderer.close()
      }
    }

    @SuppressLint("ServiceCast")
    private fun sendBitmapToPrinter(context: Context, bitmap: Bitmap): Boolean {
      val prefs = context.getSharedPreferences("printer_prefs", Context.MODE_PRIVATE)
      val type = prefs.getString("printer_connection_type", "") ?: ""
      val addr = prefs.getString("printer_address", "") ?: ""
      if (type.isBlank() || addr.isBlank()) return false

      if (type == "WiFi") {
        (context.getSystemService(ConnectivityManager::class.java))
          ?.bindProcessToNetwork(PrinterNetworkHolder.wifiNetwork)
      }
      if (!PrinterHelper.IsOpened()) {
        val rc = if (type == "Bluetooth")
          PrinterHelper.portOpenBT(context, addr)
        else
          PrinterHelper.portOpenWIFI(context, addr)
        if (rc != 0) return false
      }

      return try {
        PrinterHelper.papertype_CPCL(0)
        val widthDots = (PRINTER_WIDTH_MM / 25.4f * PRINTER_DPI).roundToInt()
        PrinterHelper.printAreaSize(
          "0", "0",
          widthDots.toString(),
          bitmap.height.toString(),
          "0"
        )
        PrinterHelper.printBitmap(0, 0, 0, bitmap, 0, false, 1)
        PrinterHelper.openEndStatic(true)
        val status = PrinterHelper.getEndStatus(16)
        PrinterHelper.openEndStatic(false)
        status == 0
      } catch (e: Exception) {
        Log.e(TAG, "directPrint error", e)
        false
      }
    }

    private suspend fun convertPdfPageToBitmap(
      context: Context,
      pdfFile: File,
      pageNumber: Int,
      addLeftMarginPx: Int,
      addTopMarginPx: Int
    ): Bitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      var fd: ParcelFileDescriptor? = null
      var renderer: PdfRenderer? = null
      try {
        fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(fd)
        if (pageNumber !in 0 until renderer.pageCount) return@withContext null

        renderer.openPage(pageNumber).use { page ->
          val fullW = (PRINTER_WIDTH_MM / 25.4f * PRINTER_DPI).roundToInt()
          val w = (fullW * 0.85f).roundToInt()
          val h = (w * page.height / page.width.toFloat()).roundToInt()

          val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
          }
          page.render(bmp, Rect(0, 0, w, h), null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

          val outW = w + addLeftMarginPx
          val outH = h + addTopMarginPx
          val outBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
          val canvas = Canvas(outBmp)
          canvas.drawColor(Color.WHITE)
          canvas.drawBitmap(bmp, addLeftMarginPx.toFloat(), addTopMarginPx.toFloat(), null)

          val pixels = IntArray(outW * outH).also {
            outBmp.getPixels(it, 0, outW, 0, 0, outW, outH)
          }
          for (i in pixels.indices) {
            val c = pixels[i]
            val gray = ((c ushr 16 and 0xFF) +
                    (c ushr 8 and 0xFF) +
                    (c and 0xFF)) / 3
            pixels[i] = if (gray > 127) Color.WHITE else Color.BLACK
          }
          outBmp.setPixels(pixels, 0, outW, 0, 0, outW, outH)
          outBmp
        }
      } catch (e: Exception) {
        Log.e(TAG, "convertPdfStatic error", e)
        null
      } finally {
        renderer?.close()
        fd?.close()
      }
    }
  }

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
      var fd: ParcelFileDescriptor? = null
      var renderer: PdfRenderer? = null
      try {
        pfd.use { fdRaw ->
          FileInputStream(fdRaw.fileDescriptor).use { fis ->
            FileOutputStream(tempPdf).use { fos ->
              fis.copyTo(fos)
            }
          }
        }

        fd = ParcelFileDescriptor.open(tempPdf, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(fd)

        for (pageIndex in 0 until renderer.pageCount) {
          val bitmap = convertPdfPageToBitmap(
            applicationContext,
            tempPdf,
            pageIndex,
            addLeftMarginPx = 90,
            addTopMarginPx = 90
          ) ?: throw IllegalStateException("Failed to render page $pageIndex")

          val ok = sendBitmapToPrinter(applicationContext, bitmap)
          if (!ok) {
            throw IllegalStateException("Print error on page $pageIndex")
          }
          delay(200)
        }

        withContext(Dispatchers.Main) { job.complete() }
      } catch (e: Exception) {
        Log.e(TAG, "Printing failed", e)
        withContext(Dispatchers.Main) { job.fail("Error: ${e.message}") }
      } finally {
        renderer?.close()
        fd?.close()
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

}
