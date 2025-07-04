package ua.com.sdegroup.imoveprinter.screens

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.com.sdegroup.imoveprinter.components.DropdownList
import ua.com.sdegroup.imoveprinter.factory.PrinterModelFactory
import ua.com.sdegroup.imoveprinter.model.PrinterModel
import ua.com.sdegroup.imoveprinter.ui.theme.IMovePrinterTheme
import ua.com.sdegroup.imoveprinter.R
import androidx.compose.ui.res.stringResource

@SuppressLint("ServiceCast")
fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
  val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
  return bluetoothManager.adapter
}

fun unpairDevice(device: BluetoothDevice): Boolean {
  return try {
    val method = device.javaClass.getMethod("removeBond")
    method.invoke(device) as Boolean
  } catch (e: Exception) {
    Log.e("Unpair", "Failed to unpair", e)
    false
  }
}

@Composable
fun EditablePrinterNameFieldStyled(
  printerName: String,
  onNameChange: (String) -> Unit,
  isEditing: Boolean,
  onToggleEdit: () -> Unit
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth()
  ) {
    OutlinedTextField(
      value = printerName,
      onValueChange = onNameChange,
      enabled = isEditing,
      label = { Text(stringResource(id = R.string.printer_name)) },
      modifier = Modifier.weight(1f),
      singleLine = true
    )

    IconButton(onClick = onToggleEdit) {
      Icon(
        imageVector = if (isEditing) Icons.Default.Check else Icons.Default.Edit,
        contentDescription = if (isEditing) "Save Name" else "Edit Name"
      )
    }
  }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterSetup(
  navController: NavHostController,
  backStackEntry: NavBackStackEntry,
  currentLanguage: String,
  onLanguageChange: (String) -> Unit
) {
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current
  val prefs = remember {
    context.getSharedPreferences("printer_prefs", Context.MODE_PRIVATE)
  }
  val viewModel: PrinterModel =
    viewModel(factory = PrinterModelFactory(backStackEntry.savedStateHandle))

  val snackbarHostState = remember { SnackbarHostState() }

  var hasConnect by remember { mutableStateOf(false) }
  var hasScan by remember { mutableStateOf(false) }

  val printerSetupLabel = stringResource(id = R.string.printer_setup)
  val connectionTypeLabel = stringResource(id = R.string.connection_type)
  val printerStatusLabel = stringResource(id = R.string.printer_status)
  val disconnectedLabel = stringResource(id = R.string.disconnected_from_printer)
  val receiptSentLabel = stringResource(id = R.string.test_receipt_sent)
  val pdfSentLabel = stringResource(id = R.string.pdf_sent_to_print)
  val connectedToLabel = stringResource(R.string.connected_to)
  val connectionFailedLabel = stringResource(R.string.connection_failed)

  val wifiIpFlow = backStackEntry.savedStateHandle.getStateFlow("wifi_ip", "")
  val wifiPortFlow = backStackEntry.savedStateHandle.getStateFlow("wifi_port", 9100)
  val wifiIp by wifiIpFlow.collectAsState()

  val permsLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { results ->
    hasConnect = results[Manifest.permission.BLUETOOTH_CONNECT] == true
    hasScan = results[Manifest.permission.BLUETOOTH_SCAN] == true
  }

  LaunchedEffect(Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val toReq = listOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
      ).filter { perm ->
        ContextCompat.checkSelfPermission(
          context,
          perm
        ) != PackageManager.PERMISSION_GRANTED
      }
      if (toReq.isNotEmpty()) permsLauncher.launch(toReq.toTypedArray())
      else {
        hasConnect = true; hasScan = true
      }
    } else {
      hasConnect = true; hasScan = true
    }
  }

  val scope = rememberCoroutineScope()
  val printerNameState = remember { mutableStateOf("") }
  var isEditingPrinterName by remember { mutableStateOf(false) }

  LaunchedEffect(true) {
    val saved = prefs.getString("printer_name", null)
    if (!saved.isNullOrBlank()) {
      printerNameState.value = saved
    } else {
      printerNameState.value = "My Thermal Printer"
      prefs.edit().putString("printer_name", printerNameState.value).apply()
    }
  }

  var statusText by remember { mutableStateOf("") }
  val selectedAddressFlow = backStackEntry.savedStateHandle.getStateFlow<String?>("address", null)
  val selectedAddress by selectedAddressFlow.collectAsState()
  LaunchedEffect(selectedAddress) {
    selectedAddress?.let {
      viewModel.setAddress(it)
      scope.launch { viewModel.connect(context, 0) }
      statusText = "$connectedToLabel $it"
    }
  }
  var refreshKey by remember { mutableStateOf(0) }

  val adapter = BluetoothAdapter.getDefaultAdapter()
  val pairedDevices: List<BluetoothDevice> = remember(hasConnect, refreshKey) {
    if (hasConnect && adapter != null && adapter.isEnabled) adapter.bondedDevices.toList()
    else emptyList()
  }

  var selectedIndex by rememberSaveable { mutableStateOf(0) }
  var printerStatus by remember { mutableStateOf("") }
  var menuExpanded by remember { mutableStateOf(false) }
  val connTypes = listOf("Bluetooth", "WiFi")
  var selType by rememberSaveable { mutableStateOf(0) }
  val wifiMgr = context.applicationContext
    .getSystemService(Context.WIFI_SERVICE) as WifiManager

  fun resolvePrinterIp(): String? {
    if (wifiIp.isNotBlank()) return wifiIp
    val dhcp = wifiMgr.dhcpInfo ?: return null
    return Formatter.formatIpAddress(dhcp.gateway)
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        title = { Text(printerSetupLabel) },
        actions = {

          IconButton(onClick = { menuExpanded = true }) {
            Text(
              text = when (currentLanguage) {
                "en" -> "\uD83C\uDDEC\uD83C\uDDE7"
                "uk" -> "\uD83C\uDDFA\uD83C\uDDE6"
                else -> "\uD83C\uDDFA\uD83C\uDDE6"
              },
              style = MaterialTheme.typography.titleLarge
            )
          }
          DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
          ) {
            val languages = listOf(
              stringResource(id = R.string.ukrainian),
              stringResource(id = R.string.english)
            )
            val languageCodes = listOf("uk", "en")
            languages.forEachIndexed { index, language ->
              DropdownMenuItem(
                text = { Text(language) },
                onClick = {
                  onLanguageChange(languageCodes[index])
                  menuExpanded = false
                }
              )
            }
          }
        }
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) }
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .clickable(
          indication = null,
          interactionSource = remember { MutableInteractionSource() }
        ) {
          focusManager.clearFocus()
        }
        .padding(16.dp)
    ) {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(connectionTypeLabel)
        DropdownList(
          itemList = connTypes,
          selectedIndex = selType,
          modifier = Modifier.width(160.dp),
          onItemClick = { selType = it }
        )
      }

      Spacer(Modifier.height(16.dp))

      Text(
        text = statusText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground
      )
      Spacer(Modifier.height(8.dp))
      val connectionText = when (connTypes[selType]) {
        "Bluetooth" -> pairedDevices.getOrNull(selectedIndex)?.name
          ?: stringResource(id = R.string.no_paired_devices)

        "WiFi" -> resolvePrinterIp() ?: stringResource(R.string.ip_not_defined)

        else -> stringResource(R.string.connection_type_not_supported)
      }

      Text(
        text = connectionText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground
      )

      Spacer(Modifier.height(16.dp))

      PrinterActionsGrid(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth(),
        onConnect = {
          when (connTypes[selType]) {
            "Bluetooth" -> {
              navController.navigate("bluetooth_discovery")
            }

            "WiFi" -> {
              navController.navigate("wifi_discovery")
            }
          }
        },
        onStatus = {
          scope.launch {
            val printerReady = withContext(Dispatchers.IO) {
              if (!cpcl.PrinterHelper.IsOpened()) {
                when (connTypes[selType]) {
                  "Bluetooth" -> {
                    val device = pairedDevices.getOrNull(selectedIndex)
                    device?.address?.let { mac ->
                      viewModel.connectToPrinter(context, "Bluetooth", mac)
                    } ?: false
                  }

                  "WiFi" -> {
                    resolvePrinterIp()?.let { ip ->
                      viewModel.connectToPrinter(context, "WiFi", ip)
                    } ?: false
                  }

                  else -> false
                }
              } else true
            }

            printerStatus = if (printerReady) {
              withContext(Dispatchers.IO) { viewModel.getStatus(connTypes[selType]) }
            } else {
              connectionFailedLabel
            }
            statusText = "$printerStatusLabel $printerStatus"
          }
        },
        onDisconnect = {
          scope.launch {
            withContext(Dispatchers.IO) {
              viewModel.disconnect(context)
              val device = pairedDevices.getOrNull(selectedIndex)
              if (device != null) {
                val success = unpairDevice(device)
                Log.d("PrinterSetup", "Результат розпарювання: $success")
              }
            }
            refreshKey++
            statusText = disconnectedLabel
          }
        },
        onPrintReceipt = {
          scope.launch {
            val type = connTypes[selType]
            val connected = withContext(Dispatchers.IO) {
              if (!cpcl.PrinterHelper.IsOpened()) {
                when (type) {
                  "Bluetooth" -> pairedDevices.getOrNull(selectedIndex)
                    ?.address
                    ?.let { mac -> viewModel.connectToPrinter(context, type, mac) }
                    ?: false
                  "WiFi"      -> resolvePrinterIp()
                    ?.let { ip -> viewModel.connectToPrinter(context, type, ip) }
                    ?: false
                  else        -> false
                }
              } else true
            }

            if (connected) {
              withContext(Dispatchers.IO) {
                viewModel.printTestReceipt()
              }
              statusText = receiptSentLabel
            } else {
              statusText = connectionFailedLabel
            }
          }
        },
        onPrintPDF = {
          scope.launch {
            val type = connTypes[selType]
            val success = withContext(Dispatchers.IO) {
              val connected = if (!cpcl.PrinterHelper.IsOpened()) {
                when (type) {
                  "Bluetooth" -> pairedDevices.getOrNull(selectedIndex)
                    ?.address
                    ?.let { mac -> viewModel.connectToPrinter(context, type, mac) }
                    ?: false
                  "WiFi"      -> resolvePrinterIp()
                    ?.let { ip -> viewModel.connectToPrinter(context, type, ip) }
                    ?: false
                  else        -> false
                }
              } else true

              if (!connected) return@withContext false

              val pdfUri = viewModel.getPdfUriFromAssets(context, "report.pdf")
              val bitmap = viewModel.convertPdfPageToBitmap(context, pdfUri, 0)

              if (bitmap != null) {
                viewModel.sendPdfToPrinter(context, type, bitmap)
              } else {
                false
              }
            }

            statusText = if (success) pdfSentLabel else connectionFailedLabel
          }
        }
      )

      Spacer(Modifier.height(16.dp))

      EditablePrinterNameFieldStyled(
        printerName = printerNameState.value,
        onNameChange = { printerNameState.value = it },
        isEditing = isEditingPrinterName,
        onToggleEdit = {
          if (isEditingPrinterName) {
            scope.launch {
              prefs.edit().putString("printer_name", printerNameState.value).apply()
            }
          }
          isEditingPrinterName = !isEditingPrinterName
        }
      )

      Spacer(Modifier.height(32.dp))
    }
  }
}

@Preview(showBackground = true)
@Composable
fun PrinterSetupPreview() {
  IMovePrinterTheme {
    PrinterSetup(
      navController = rememberNavController(),
      backStackEntry = rememberNavController().currentBackStackEntry!!,
      currentLanguage = "uk",
      onLanguageChange = {}
    )
  }
}