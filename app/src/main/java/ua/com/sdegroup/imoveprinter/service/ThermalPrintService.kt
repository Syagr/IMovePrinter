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
import ua.com.sdegroup.imoveprinter.R
import ua.com.sdegroup.imoveprinter.util.getFileNameFromUri
import ua.com.sdegroup.imoveprinter.util.showSystemPrintError

class ThermalPrintService : PrintService() {

  companion object {
    private const val TAG = "ThermalPrintService"
    private const val PRINTER_WIDTH_MM = 58f
    private const val PRINTER_DPI = 203

    fun printDirect(context: Context, uri: Uri) {
      val prefs = context.getSharedPreferences("printer_prefs", Context.MODE_PRIVATE)
      val type = prefs.getString("printer_connection_type", "").orEmpty()
      val addr = prefs.getString("printer_address", "").orEmpty()

      val fileName = getFileNameFromUri(context, uri)

      if (type.isBlank() || addr.isBlank()) {
        val msg = when {
          type.isBlank() && addr.isBlank() ->
            context.getString(R.string.error_no_type_and_address)

          type.isBlank() ->
            context.getString(R.string.error_no_connection_type)

          else ->
            context.getString(R.string.error_no_printer_address)
        }
        Log.e(TAG, msg)
        showSystemPrintError(context, fileName, msg)
        return
      }

      val tmp = File(context.cacheDir, "direct_print.pdf")
      try {
        context.contentResolver.openInputStream(uri)?.use { inp ->
          FileOutputStream(tmp).use { out -> inp.copyTo(out) }
        } ?: run {
          val msg = context.getString(R.string.error_no_print_data)
          Log.e(TAG, msg)
          showSystemPrintError(context, fileName, msg)
          return
        }
      } catch (e: Exception) {
        val msg = context.getString(R.string.error_print_generic, e.message ?: "")
        Log.e(TAG, msg, e)
        showSystemPrintError(context, fileName, msg)
        return
      }

      runBlocking {
        var renderer: PdfRenderer? = null
        var fd: ParcelFileDescriptor? = null

        try {
          fd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
          renderer = PdfRenderer(fd)

          for (i in 0 until renderer.pageCount) {
            val bmp = convertPdfPageToBitmap(context, tmp, i, 90, 90)
            if (bmp == null) {
              val msg = context.getString(R.string.error_render_page, i + 1)
              Log.e(TAG, msg)
              showSystemPrintError(context, fileName, msg)
              break
            }

            val ok = sendBitmapToPrinter(context, bmp)
            if (!ok) {
              val msg = context.getString(R.string.error_print_page, i + 1)
              Log.e(TAG, msg)
              showSystemPrintError(context, fileName, msg)
              break
            }

            delay(200)
          }

        } catch (e: Exception) {
          val msg = context.getString(R.string.error_print_generic, e.message ?: "")
          Log.e(TAG, msg, e)
          showSystemPrintError(context, fileName, msg)

        } finally {
          renderer?.close()
          fd?.close()
        }
      }
    }


    @SuppressLint("ServiceCast")
    private fun sendBitmapToPrinter(context: Context, bitmap: Bitmap): Boolean {
      val prefs = context.getSharedPreferences("printer_prefs", Context.MODE_PRIVATE)
      val type = prefs.getString("printer_connection_type", "") ?: ""
      val addr = prefs.getString("printer_address", "") ?: ""
      if (type.isBlank() || addr.isBlank()) {
        Log.e(
          TAG,
          "Відсутні дані підключення: type='$type', addr='$addr' / Connection info missing: type='$type', addr='$addr'"
        )
        return false
      }

      if (type == "WiFi") {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        val net = PrinterNetworkHolder.wifiNetwork
        if (cm == null || net == null) {
          Log.e(
            TAG,
            "Не вдалося прив'язати до Wi-Fi: cm=$cm, net=$net / Cannot bind to Wi-Fi: cm=$cm, net=$net"
          )
          return false
        }
        cm.bindProcessToNetwork(net)
      }

      if (!PrinterHelper.IsOpened()) {
        val rc = when (type) {
          "Bluetooth" -> PrinterHelper.portOpenBT(context, addr)
          "WiFi" -> PrinterHelper.portOpenWIFI(context, addr)
          else -> -1
        }
        if (rc != 0) {
          Log.e(
            TAG,
            "Не вдалося відкрити порт $type за адресою $addr: код=$rc / Failed to open $type port at $addr: code=$rc"
          )
          return false
        }
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
        PrinterHelper.Form()
        PrinterHelper.Print()
        val status = PrinterHelper.getEndStatus(16)
        PrinterHelper.openEndStatic(false)

        when (status) {
          0 -> {
            Log.i(TAG, "Друк завершено успішно. / Print completed successfully.")
            true
          }

          1 -> {
            Log.e(TAG, "Помилка: закінчився папір. / Error: Out of paper.")
            false
          }

          2 -> {
            Log.e(TAG, "Помилка: кришка відкрита. / Error: Cover is open.")
            false
          }

          -1 -> {
            Log.e(TAG, "Помилка: тайм від принтера. / Error: Printer timeout.")
            false
          }

          else -> {
            Log.e(TAG, "Невідомий код статусу: $status. / Unknown status code: $status.")
            false
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Виняток під час друку: ${e.message} / Exception during print: ${e.message}", e)
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
      val msg = getString(R.string.error_invalid_job_id)
      Log.e(TAG, msg)
      job.fail(msg)
      showSystemPrintError(applicationContext, "unknown.pdf", msg)
      return
    }
    job.start()

    val fileName = job.info.label?.takeIf { it.isNotBlank() } ?: "document.pdf"

    val prefs = applicationContext
      .getSharedPreferences("printer_prefs", Context.MODE_PRIVATE)
    val type = prefs.getString("printer_connection_type", "").orEmpty()
    val addr = prefs.getString("printer_address", "").orEmpty()
    if (type.isBlank() || addr.isBlank()) {
      val msg = when {
        type.isBlank() && addr.isBlank() ->
          getString(R.string.error_no_type_and_address)

        type.isBlank() ->
          getString(R.string.error_no_connection_type)

        else ->
          getString(R.string.error_no_printer_address)
      }
      Log.e(TAG, msg)
      job.fail(msg)
      showSystemPrintError(applicationContext, fileName, msg)
      return
    }

    val tempPdf = File(cacheDir, "print_$jobId.pdf")
    job.document?.data?.use { pfd ->
      FileInputStream(pfd.fileDescriptor).use { fis ->
        FileOutputStream(tempPdf).use { fos ->
          fis.copyTo(fos)
        }
      }
    } ?: run {
      val msg = getString(R.string.error_no_print_data)
      Log.e(TAG, msg)
      job.fail(msg)
      showSystemPrintError(applicationContext, fileName, msg)
      return
    }

    serviceScope.launch {
      var fd: ParcelFileDescriptor? = null
      var renderer: PdfRenderer? = null

      try {
        fd = ParcelFileDescriptor.open(tempPdf, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(fd)

        for (pageIndex in 0 until renderer.pageCount) {
          val bitmap = convertPdfPageToBitmap(
            applicationContext,
            tempPdf,
            pageIndex,
            addLeftMarginPx = 90,
            addTopMarginPx = 90
          ) ?: run {
            val msg = getString(R.string.error_render_page, pageIndex + 1)
            Log.e(TAG, msg)
            showSystemPrintError(applicationContext, fileName, msg)
            withContext(Dispatchers.Main) { job.fail(msg) }
            return@launch
          }

          val ok = sendBitmapToPrinter(applicationContext, bitmap)
          if (!ok) {
            val msg = getString(R.string.error_print_page, pageIndex + 1)
            Log.e(TAG, msg)
            showSystemPrintError(applicationContext, fileName, msg)
            withContext(Dispatchers.Main) { job.fail(msg) }
            return@launch
          }

          delay(200)
        }

        withContext(Dispatchers.Main) { job.complete() }

      } catch (e: Exception) {
        val msg = getString(R.string.error_print_generic, e.message ?: "")
        Log.e(TAG, "Printing failed", e)
        showSystemPrintError(applicationContext, fileName, msg)
        withContext(Dispatchers.Main) { job.fail(msg) }

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
