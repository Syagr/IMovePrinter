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
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

@SuppressLint("ServiceCast")
fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    return bluetoothManager.adapter
}

@Composable
fun OpenDeviceList() {
    val context = LocalContext.current
    val bluetoothAdapter = remember { getBluetoothAdapter(context) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterSetup(
    navController: NavHostController = rememberNavController(),
    backStackEntry: NavBackStackEntry
) {
    val context = LocalContext.current
    val viewModel: PrinterModel = viewModel(factory = PrinterModelFactory(backStackEntry.savedStateHandle))

    val snackbarHostState = remember { SnackbarHostState() }

    var hasConnect by remember { mutableStateOf(false) }
    var hasScan by remember { mutableStateOf(false) }

    val wifiIpFlow = backStackEntry.savedStateHandle.getStateFlow("wifi_ip", "")
    val wifiPortFlow = backStackEntry.savedStateHandle.getStateFlow("wifi_port", 9100)
    val wifiIp by wifiIpFlow.collectAsState()
    val wifiPort by wifiPortFlow.collectAsState()

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
    var expanded by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    val adapter = BluetoothAdapter.getDefaultAdapter()
    val pairedDevices: List<BluetoothDevice> = remember(hasConnect, refreshKey) {
        if (hasConnect && adapter != null && adapter.isEnabled) adapter.bondedDevices.toList()
        else emptyList()
    }
    val pairedNames = pairedDevices.map { it.name ?: it.address }

    var selectedIndex by rememberSaveable { mutableStateOf(0) }
    var printerStatus by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    val connTypes = listOf("Bluetooth", "WiFi", "USB")
    var selType by rememberSaveable { mutableStateOf(0) }
    var statusText by remember { mutableStateOf("") }
    var selectedAddress by remember { mutableStateOf<String?>(null) }
    val wifiMgr = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun resolvePrinterIp(): String? {
        if (wifiIp.isNotBlank()) return wifiIp
        val dhcp = wifiMgr.dhcpInfo ?: return null
        return Formatter.formatIpAddress(dhcp.gateway)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Налаштування принтера") },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Вибрати принтер")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        if (pairedNames.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Немає запарених пристроїв") },
                                onClick = { menuExpanded = false }
                            )
                        } else {
                            pairedNames.forEachIndexed { idx, name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        selectedIndex = idx
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {}) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Тип підключення")
                DropdownList(
                    itemList = connTypes,
                    selectedIndex = selType,
                    modifier = Modifier.width(160.dp),
                    onItemClick = { selType = it }
                )
            }
            Spacer(Modifier.height(16.dp))

            PrinterActionsGrid(
                onConnect = {
                    when (connTypes[selType]) {
                        "Bluetooth" -> {
                            navController.navigate("bluetooth_discovery")
                            pairedDevices.getOrNull(selectedIndex)?.address?.let {
                                viewModel.setAddress(pairedDevices[selectedIndex].address)
                                scope.launch { viewModel.connect(context, 0) }
                            }
                        }
                        "WiFi" -> {
                            navController.navigate("wifi_discovery")
                        }
                        "USB" -> {}
                    }
                },
                onStatus = {
                    val selectedDevice = pairedDevices.getOrNull(selectedIndex)
                    selectedDevice?.address?.let {
                        viewModel.setAddress(it)
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                viewModel.connect(context, 0)
                            }
                            printerStatus = withContext(Dispatchers.IO) {
                                viewModel.getStatus()
                            }
                            statusText = "Статус принтера: $printerStatus"
                        }
                    }
                },
                onDisconnect = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            viewModel.disconnect()
                            val device = pairedDevices.getOrNull(selectedIndex)
                            if (device != null) {
                                val success = unpairDevice(device)
                                Log.d("PrinterSetup", "Результат розпарювання: $success")
                            }
                        }
                        refreshKey++
                        statusText = "Вимкнено від принтера"
                    }
                },
                onPrintReceipt = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            viewModel.connect(context, 0)
                            viewModel.printTestReceipt()
                        }
                        statusText = "Тестову квитанцію відправлено"
                    }
                },
                onPrintPDF = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            viewModel.connect(context, 0)
                            viewModel.printPDF(context)
                        }
                        statusText = "PDF надіслано на друк"
                    }
                },
                onVersion = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            viewModel.getVersion()
                        }
                        statusText = "Версія запиту виконана"
                    }
                }

            )

            Spacer(Modifier.height(16.dp))
            Text(statusText)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PrinterSetupPreview() {
    IMovePrinterTheme {
        PrinterSetup(
            navController = rememberNavController(),
            backStackEntry = rememberNavController().currentBackStackEntry!!
        )
    }
}