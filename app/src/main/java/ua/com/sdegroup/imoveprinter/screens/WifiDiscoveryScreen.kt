package ua.com.sdegroup.imoveprinter.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import ua.com.sdegroup.imoveprinter.R
import ua.com.sdegroup.imoveprinter.util.PrinterNetworkHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiDiscoveryScreen(
  navController: NavController,
  currentLanguage: String,
  onLanguageChange: (String) -> Unit
) {
  val context = LocalContext.current
  val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
  var scanResults by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
  var selectedResult by remember { mutableStateOf<ScanResult?>(null) }
  var password by remember { mutableStateOf("") }
  var connectionStatus by remember { mutableStateOf<String?>(null) }
  var printerIp by remember { mutableStateOf("192.168.1.1") }
  var ipEditable by remember { mutableStateOf(false) }
  var shouldNavigateBack by remember { mutableStateOf(false) }

  val connectionSuccessful = stringResource(R.string.connection_successful)
  val connectionFailed = stringResource(R.string.connection_failed)

  val permsLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) {
    startScan(wifiManager).also {
      scanResults = wifiManager.scanResults.filter { it.SSID.isNotBlank() }
    }
  }

  LaunchedEffect(Unit) {
    val perms = mutableListOf<String>()
    if (ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
      ) != PackageManager.PERMISSION_GRANTED
    ) {
      perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (perms.isNotEmpty()) permsLauncher.launch(perms.toTypedArray())
    else startScan(wifiManager).also {
      scanResults = wifiManager.scanResults.filter { it.SSID.isNotBlank() }
    }
  }

  LaunchedEffect(shouldNavigateBack) {
    if (shouldNavigateBack) {
      navController.popBackStack()
      shouldNavigateBack = false
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.wifi_discovery)) },
        navigationIcon = {
          IconButton(onClick = { navController.navigateUp() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
          }
        },
        actions = {
          IconButton(onClick = {
            startScan(wifiManager)
            scanResults = wifiManager.scanResults.filter { it.SSID.isNotBlank() }
          }) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh Wi-Fi")
          }
        }
      )
    }
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(16.dp),
      verticalArrangement = Arrangement.Top
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
      ) {
        OutlinedTextField(
          value = printerIp,
          onValueChange = { printerIp = it },
          enabled = ipEditable,
          label = { Text(stringResource(id = R.string.printer_ip)) },
          modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { ipEditable = !ipEditable }) {
          Icon(
            imageVector = if (ipEditable) Icons.Default.Check else Icons.Default.Edit,
            contentDescription = if (ipEditable) "Save IP" else "Edit IP"
          )
        }
      }

      Spacer(Modifier.height(8.dp))

      LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Top) {
        itemsIndexed(scanResults) { index, result ->
          val shape = when (index) {
            0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            scanResults.lastIndex -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
            else -> RoundedCornerShape(0.dp)
          }

          Column {
            if (index != 0) {
              Divider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
              )
            }
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  selectedResult = result
                  password = ""
                }
                .background(MaterialTheme.colorScheme.surfaceVariant, shape = shape)
                .padding(vertical = 12.dp, horizontal = 16.dp)
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = result.SSID,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                Icon(
                  imageVector = Icons.Filled.SignalWifi4Bar,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.padding(end = 4.dp)
                )
                if (result.capabilities.contains("WEP") || result.capabilities.contains("WPA")) {
                  Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Secured",
                    tint = MaterialTheme.colorScheme.primary
                  )
                }
              }
            }
          }
        }
      }

      selectedResult?.let { result ->
        Spacer(Modifier.height(16.dp))
        Text(text = "${stringResource(R.string.selected_network)}: ${result.SSID}")
        if (result.capabilities.contains("WEP") || result.capabilities.contains("WPA")) {
          Spacer(Modifier.height(8.dp))
          OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.enter_password)) },
            modifier = Modifier.fillMaxWidth()
          )
        }
        Spacer(Modifier.height(16.dp))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ConnectAndPrintButton(
            ssid = result.SSID,
            password = password,
            printerIp = printerIp,
            onResult = { success ->
              connectionStatus = if (success) connectionSuccessful else connectionFailed
              if (success) shouldNavigateBack = true
            }
          )
        } else {
          Text(
            stringResource(R.string.wifi_multinet_not_supported),
            color = MaterialTheme.colorScheme.error
          )
        }
      }

      connectionStatus?.let {
        Spacer(Modifier.height(16.dp))
        Text(
          text = it,
          color = if (it.contains(stringResource(id = R.string.successfully))) Color.Green else Color.Red
        )
      }
    }
  }
}

fun startScan(wifiManager: WifiManager) {
  wifiManager.startScan()
}

@RequiresApi(Build.VERSION_CODES.Q)
@Composable
fun ConnectAndPrintButton(
  ssid: String,
  password: String,
  printerIp: String,
  onResult: (Boolean) -> Unit
) {
  val context = LocalContext.current
  val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  val callback = remember {
    object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        PrinterNetworkHolder.wifiNetwork = network
        PrinterNetworkHolder.networkCallback = this
        cm.bindProcessToNetwork(network)

        context.getSharedPreferences("printer_prefs", Context.MODE_PRIVATE).edit()
          .putString("printer_connection_type", "WiFi")
          .putString("printer_address", printerIp)
          .apply()

        onResult(true)
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
      val spec = WifiNetworkSpecifier.Builder()
        .setSsid(ssid)
        .apply { if (password.isNotEmpty()) setWpa2Passphrase(password) }
        .build()
      val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .setNetworkSpecifier(spec)
        .build()
      cm.requestNetwork(request, callback)
    },
    modifier = Modifier.fillMaxWidth()
  ) {
    Text(text = stringResource(R.string.connect))
  }
}