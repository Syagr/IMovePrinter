package ua.com.sdegroup.imoveprinter.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.*
import android.net.NetworkSpecifier
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import ua.com.sdegroup.imoveprinter.R
import android.text.format.Formatter
import ua.com.sdegroup.imoveprinter.util.PrinterNetworkHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiDiscoveryScreen(
  navController: NavController, currentLanguage: String, onLanguageChange: (String) -> Unit
) {
  val context = LocalContext.current
  val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
  var scanResults by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
  var selectedSsid by remember { mutableStateOf<String?>(null) }
  var password by remember { mutableStateOf("") }
  var connectionStatus by remember { mutableStateOf("") }
  val scope = rememberCoroutineScope()

  val connectionSuccessful = stringResource(R.string.connection_successful)
  val connectionFailed = stringResource(R.string.connection_failed)

  // Request location permission for Wi-Fi scanning
  val permsLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { }
  LaunchedEffect(Unit) {
    val perms = mutableListOf<String>()
    if (ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (perms.isNotEmpty()) permsLauncher.launch(perms.toTypedArray())
  }

  // Scan for available Wi-Fi networks
  fun startScan() {
    wifiManager.startScan()
    scanResults = wifiManager.scanResults.filter { it.SSID.isNotBlank() }
  }

  Scaffold(
    topBar = {
      TopAppBar(title = { Text(stringResource(R.string.wifi_discovery)) }, navigationIcon = {
        IconButton(onClick = { navController.popBackStack() }) {
          Icon(
            Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null
          )
        }
      })
    }) { padding ->
    Column(
      Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(16.dp)
    ) {
      Button(
        onClick = { startScan() }, modifier = Modifier.fillMaxWidth()
      ) {
        Text(text = stringResource(R.string.scan_networks))
      }

      Spacer(modifier = Modifier.height(16.dp))

      LazyColumn(modifier = Modifier.weight(1f)) {
        items(scanResults) { result ->
          Row(
            Modifier
              .fillMaxWidth()
              .clickable { selectedSsid = result.SSID }
              .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = result.SSID, modifier = Modifier.weight(1f)
            )
            Text(
              text = if (result.capabilities.contains("WEP") || result.capabilities.contains("WPA")) "🔒" else "🔓"
            )
          }
          Divider()
        }
      }

      selectedSsid?.let { ssid ->
        Text(text = "${stringResource(R.string.selected_network)}: $ssid")
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          label = { Text(text = stringResource(R.string.enter_password)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ConnectAndPrintButton(
            ssid = ssid, password = password, onResult = { success ->
              connectionStatus = if (success) connectionSuccessful
              else connectionFailed
            })
        } else {
          Text(
            text = stringResource(R.string.wifi_multinet_not_supported),
            color = MaterialTheme.colorScheme.error
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
      Text(text = connectionStatus)
    }
  }
}

@RequiresApi(Build.VERSION_CODES.Q)
@Composable
private fun ConnectAndPrintButton(
  ssid: String, password: String, onResult: (Boolean) -> Unit
) {
  val context = LocalContext.current
  val cm = remember {
    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  }

  val callback = remember {
    object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        // 1. Запоминаем сеть и колбэк
        PrinterNetworkHolder.wifiNetwork = network
        PrinterNetworkHolder.networkCallback = this

        // 2. Биндим на неё процесс сразу
        cm.bindProcessToNetwork(network)

        // 3. Получаем gateway и формируем IP принтера
        val wifiMgr =
          context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val gw = wifiMgr.dhcpInfo?.gateway ?: 0
        val printerIp = if (gw != 0) Formatter.formatIpAddress(gw)
        else "192.168.1.1"

        // 4. Сохраняем в prefs только тип и адрес
        context.getSharedPreferences("printer_prefs", Context.MODE_PRIVATE).edit()
          .putString("printer_connection_type", "WiFi").putString("printer_address", printerIp)
          .apply()

        onResult(true)
        // НЕ отвязываемся и НЕ отписываемся:
        // PrintService потом сам достанет PrinterNetworkHolder и повторно привяжется.
      }

      override fun onUnavailable() {
        onResult(false)
        PrinterNetworkHolder.wifiNetwork = null
        PrinterNetworkHolder.networkCallback = null
      }
    }
  }

  Button(
    onClick = {
      val spec = WifiNetworkSpecifier.Builder().setSsid(ssid)
        .apply { if (password.isNotEmpty()) setWpa2Passphrase(password) }.build()
      val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .setNetworkSpecifier(spec).build()
      cm.requestNetwork(req, callback)
    }, modifier = Modifier.fillMaxWidth()
  ) {
    Text(text = stringResource(R.string.connect_and_print))
  }
}