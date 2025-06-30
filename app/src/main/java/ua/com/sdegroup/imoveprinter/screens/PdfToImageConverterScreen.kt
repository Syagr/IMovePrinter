package ua.com.sdegroup.imoveprinter.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import ua.com.sdegroup.imoveprinter.R
import androidx.compose.ui.res.stringResource

suspend fun convertPdfPageToBitmap(
  context: Context,
  pdfUri: Uri,
  pageNumber: Int,
  width: Int = 1024,
  height: Int = (width * 1.414).toInt()
): Bitmap? = withContext(Dispatchers.IO) {
  var fileDescriptor: ParcelFileDescriptor? = null
  var pdfRenderer: PdfRenderer? = null
  var currentPage: PdfRenderer.Page? = null

  try {
    fileDescriptor = context.contentResolver.openFileDescriptor(pdfUri, "r")
    if (fileDescriptor == null) {
      return@withContext null
    }

    pdfRenderer = PdfRenderer(fileDescriptor)

    if (pageNumber < 0 || pageNumber >= pdfRenderer.pageCount) {
      return@withContext null
    }

    currentPage = pdfRenderer.openPage(pageNumber)

    val bitmap = Bitmap.createBitmap(
      width,
      height,
      Bitmap.Config.ARGB_8888
    )

    currentPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

    return@withContext bitmap
  } catch (e: IOException) {
    e.printStackTrace()
    return@withContext null
  } finally {
    currentPage?.close()
    pdfRenderer?.close()
    fileDescriptor?.close()
  }
}

fun saveBitmapToFile(bitmap: Bitmap, file: File, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG, quality: Int = 90): Boolean {
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

@Composable
fun PdfToImageConverterScreen() {
    val context = LocalContext.current
    var selectedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        selectedPdfUri = uri
        pageBitmap = null
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(onClick = { pickPdfLauncher.launch(arrayOf("application/pdf")) }) {
            Text(stringResource(id = R.string.select_pdf).toString())
        }

        Spacer(Modifier.height(16.dp))

        selectedPdfUri?.let { uri ->
            Text("Selected PDF: ${uri.lastPathSegment ?: uri.toString()}")
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    if (!isLoading) {
                        isLoading = true
                        CoroutineScope(Dispatchers.Main).launch {
                            pageBitmap = convertPdfPageToBitmap(context, uri, 0) // Convert first page
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading
            ) {
                Text(if (isLoading) "Converting..." else "Convert Page 1 to Image")
            }

            Spacer(Modifier.height(16.dp))

            pageBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "PDF Page Image",
                    modifier = Modifier
                        .size(200.dp)
                        .clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Fit
                )
            } ?: run {
                if (selectedPdfUri != null && !isLoading) {
                    Text("No image generated yet or failed.")
                }
            }
        } ?: Text("No PDF selected.")
    }
}
