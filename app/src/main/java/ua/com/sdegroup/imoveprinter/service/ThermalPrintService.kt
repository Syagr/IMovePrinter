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
import kotlin.math.min
import android.graphics.Canvas
import android.graphics.Rect

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
        // копируем pdf во временный файл
        pfd.use { fd ->
          FileInputStream(fd.fileDescriptor).use { fis ->
            FileOutputStream(tempPdf).use { fos ->
              fis.copyTo(fos)
            }
          }
        }
        // конвертим первую страницу
        val bitmap = convertPdfPageToBitmap(
          applicationContext, tempPdf, 0,
          addLeftMarginPx = 90, addTopMarginPx = 90
        )
        val ok = bitmap?.let { printBitmap(it) } ?: false
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
        // 1) жёсткий DPI-преобразователь PDF-пунктов в точки принтера
        val scale = PRINTER_DPI / 72f
        val fullW = (page.width  * scale).roundToInt()
        val fullH = (page.height * scale).roundToInt()

        // 2) рендерим страницу с этим масштабом
        val bmpFull = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888)
        bmpFull.eraseColor(Color.WHITE)
        page.render(bmpFull,
          Rect(0, 0, fullW, fullH),
          null,
          PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

        // 3) обрезаем по ширине принтера (центрируем горизонтально)
        val printerW = (PRINTER_WIDTH_MM / 25.4f * PRINTER_DPI).roundToInt()
        val cropX = ((fullW - printerW) / 2).coerceAtLeast(0)
        val cropW = min(printerW, fullW - cropX)
        val cropped = Bitmap.createBitmap(bmpFull, cropX, 0, cropW, fullH)

        // 4) добавляем поля
        val outW = cropped.width + addLeftMarginPx
        val outH = cropped.height + addTopMarginPx
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(cropped, addLeftMarginPx.toFloat(), addTopMarginPx.toFloat(), null)

        // 5) бинаризация в ч/б, как у вас было
        val pixels = IntArray(outW * outH).also {
          out.getPixels(it, 0, outW, 0, 0, outW, outH)
        }
        for (i in pixels.indices) {
          val gray = ((pixels[i] ushr 16 and 0xFF) +
                  (pixels[i] ushr  8 and 0xFF) +
                  (pixels[i]       and 0xFF)) / 3
          pixels[i] = if (gray > 127) Color.WHITE else Color.BLACK
        }
        out.setPixels(pixels, 0, outW, 0, 0, outW, outH)

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

  @SuppressLint("ServiceCast")
  private suspend fun printBitmap(bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
    val prefs = applicationContext
      .getSharedPreferences("printer_prefs", Context.MODE_PRIVATE)
    val type = prefs.getString("printer_connection_type", "") ?: ""
    val addr = prefs.getString("printer_address", "") ?: ""
    if (type.isBlank() || addr.isBlank()) return@withContext false

    val cm = applicationContext
      .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    if (type == "WiFi") {
      PrinterNetworkHolder.wifiNetwork?.let { net ->
        cm.bindProcessToNetwork(net)
      }
    }

    // Открываем порт
    val openResult = when (type) {
      "Bluetooth" -> PrinterHelper.portOpenBT(applicationContext, addr)
      "WiFi" -> PrinterHelper.portOpenWIFI(applicationContext, addr)
      else -> -1
    }
    if (openResult != 0) {
      PrinterHelper.portClose()
      if (type == "WiFi") cm.bindProcessToNetwork(null)
      return@withContext false
    }

    try {
      PrinterHelper.papertype_CPCL(0)
      val widthDots = (PRINTER_WIDTH_MM / 25.4f * PRINTER_DPI).roundToInt()
      PrinterHelper.printAreaSize(
        "0",                  // x
        "0",                  // y
        widthDots.toString(), // ширина в точках
        bitmap.height.toString(),
        "0"                   // ориентация
      )
      PrinterHelper.printBitmap(0, 0, 0, bitmap, 0, false, 1)
      PrinterHelper.openEndStatic(true)
      val status = PrinterHelper.getEndStatus(16)
      PrinterHelper.openEndStatic(false)
      return@withContext status == 0
    } finally {
      delay(500)
      PrinterHelper.portClose()
      if (type == "WiFi") {
        cm.bindProcessToNetwork(null)
        PrinterNetworkHolder.networkCallback?.let { cm.unregisterNetworkCallback(it) }
        PrinterNetworkHolder.wifiNetwork = null
        PrinterNetworkHolder.networkCallback = null
      }
    }
  }
}
