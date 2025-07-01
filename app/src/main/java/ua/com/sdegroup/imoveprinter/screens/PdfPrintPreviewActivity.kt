package ua.com.sdegroup.imoveprinter.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.com.sdegroup.imoveprinter.R
import ua.com.sdegroup.imoveprinter.ui.theme.IMovePrinterTheme

private const val TAG = "PdfPrintPreviewAct"
private const val PRINTER_DPI = 203

@OptIn(ExperimentalMaterial3Api::class)
class PdfPrintPreviewActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val pdfUri = intent?.data ?: run {
      finish()
      return
    }

    setContent {
      IMovePrinterTheme {
        Scaffold(
          topBar = {
            TopAppBar(
              title = { Text(stringResource(id = R.string.pdf_preview)) },
              actions = {
                IconButton(onClick = { launchPrint(pdfUri) }) {
                  Icon(Icons.Default.Print, contentDescription = "Print")
                }
              }
            )
          }
        ) { padding ->
          PdfListViewer(
            uri = pdfUri,
            modifier = Modifier
              .fillMaxSize()
              .padding(padding)
          )
        }
      }
    }
  }

  private fun launchPrint(uri: Uri) {
    val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
    val jobName = "ThermalPrintJob_${System.currentTimeMillis()}"
    val adapter = object : PrintDocumentAdapter() {
      override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: android.os.CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?
      ) {
        callback.onLayoutFinished(
          PrintDocumentInfo.Builder("pdf_print")
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build(),
          true
        )
      }

      override fun onWrite(
        pages: Array<android.print.PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: android.os.CancellationSignal,
        callback: WriteResultCallback
      ) {
        contentResolver.openInputStream(uri)?.use { inp ->
          java.io.FileOutputStream(destination.fileDescriptor).use { out ->
            inp.copyTo(out)
          }
        }
        callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
      }
    }

    val attrs = PrintAttributes.Builder()
      .setMediaSize(
        PrintAttributes.MediaSize(
          "THERMAL_58MM", "58 мм",
          (58f / 25.4f * 1000).toInt(),
          (200f / 25.4f * 1000).toInt()
        )
      )
      .setResolution(
        PrintAttributes.Resolution("default", "203×203", PRINTER_DPI, PRINTER_DPI)
      )
      .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
      .build()

    printManager.print(jobName, adapter, attrs)
  }
}

@Composable
fun PdfListViewer(
  uri: Uri,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current

  val descriptor = remember(uri) {
    context.contentResolver.openFileDescriptor(uri, "r")
  }
  val renderer = remember(descriptor) {
    descriptor?.let { PdfRenderer(it) }
  }
  DisposableEffect(renderer) {
    onDispose {
      renderer?.close()
      descriptor?.close()
    }
  }

  if (renderer == null) {
    Box(modifier, contentAlignment = Alignment.Center) {
      Text(stringResource(id = R.string.error_open_pdf_preview))
    }
    return
  }

  val pageCount = renderer.pageCount

  LazyColumn(modifier = modifier) {
    itemsIndexed(List(pageCount) { it }) { index, _ ->
      PdfPage(renderer, index)
    }
  }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, pageIndex: Int) {
  val context = LocalContext.current
  var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }

  LaunchedEffect(renderer, pageIndex) {
    withContext(Dispatchers.IO) {
      renderer.openPage(pageIndex).use { page ->
        val scale = 3f
        val width = (page.width * scale).toInt()
        val height = (page.height * scale).toInt()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        val rect = android.graphics.Rect(0, 0, width, height)
        page.render(bmp, rect, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap = bmp
      }
    }
  }

  bitmap?.let { bmp ->
    Image(
      bitmap = bmp.asImageBitmap(),
      contentDescription = null,
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(bmp.width / bmp.height.toFloat())
        .padding(horizontal = 8.dp)
    )
    Spacer(Modifier.height(16.dp))
  }
}