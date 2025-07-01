package ua.com.sdegroup.imoveprinter.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import ua.com.sdegroup.imoveprinter.model.PrinterModel
import ua.com.sdegroup.imoveprinter.ui.theme.IMovePrinterTheme
import ua.com.sdegroup.imoveprinter.viewmodel.BluetoothViewModel
import ua.com.sdegroup.imoveprinter.viewmodel.BluetoothState
import androidx.compose.ui.res.stringResource
import ua.com.sdegroup.imoveprinter.R


@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun BluetoothDiscoveryScreen(
  navController: NavController,
  currentLanguage: String,
  onLanguageChange: (String) -> Unit
) {
  val context = LocalContext.current
  val viewModel: BluetoothViewModel = viewModel()
  val printerModel: PrinterModel = viewModel()
  val bluetoothDevices by viewModel.bluetoothDevices.collectAsState()
  val isRefreshing by viewModel.isRefreshing.collectAsState()
  val bluetoothState by viewModel.bluetoothState.collectAsState()

  val bluetoothEnabled = stringResource(id = R.string.bluetooth_enabled)
  val bluetoothDisabled = stringResource(R.string.bluetooth_disabled)
  val bluetoothPermissionDenied = stringResource(R.string.bluetooth_permission_denied)
  val bluetoothPermissionRequired = stringResource(R.string.bluetooth_permission_required)
  val locationEnabled = stringResource(R.string.location_enabled)
  val locationDisabled = stringResource(R.string.location_disabled)
  val deviceBonded = stringResource(R.string.device_bonded)

  // State for dialogs/toasts
  var showProgressDialog by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  // SharedPreferences initialization
  val sharedPreferences =
    LocalContext.current.getSharedPreferences("printer_prefs", Context.MODE_PRIVATE)

  // ActivityResultLauncher for Bluetooth enable request
  val enableBtLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      Toast.makeText(context, bluetoothEnabled, Toast.LENGTH_SHORT).show()
      viewModel.startDiscovery(context)
    } else {
      Toast.makeText(context, bluetoothDisabled, Toast.LENGTH_SHORT).show()
      errorMessage = bluetoothPermissionDenied
    }
  }

  // ActivityResultLauncher for Bluetooth permissions
  val requestPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions()
  ) { permissions ->
    val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
              permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
    } else {
      permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    if (granted) {
      // Permissions granted, proceed with initialization
      viewModel.onPermissionsGranted(context)
      viewModel.startDiscovery(context) // Ensure discovery starts immediately
    } else {
      errorMessage = bluetoothPermissionRequired
      Toast.makeText(context, bluetoothPermissionDenied, Toast.LENGTH_LONG).show()
    }
  }

  // ActivityResultLauncher for Location enable request
  val enableLocationLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (isLocationEnabled(context)) {
      Toast.makeText(context, locationEnabled, Toast.LENGTH_SHORT).show()
      viewModel.startDiscovery(context)
    } else {
      Toast.makeText(context, locationDisabled, Toast.LENGTH_SHORT).show()
      errorMessage = locationDisabled
    }
  }

  // --- Lifecycle and Initialization ---
  LaunchedEffect(Unit) {
    // Request permissions when the screen first appears
    if (!isLocationEnabled(context)) {
      val enableLocationIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
      enableLocationLauncher.launch(enableLocationIntent)
    } else {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        requestPermissionLauncher.launch(
          arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
          )
        )
      } else {
        requestPermissionLauncher.launch(
          arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        )
      }
    }
  }

  // Observe Bluetooth state changes from ViewModel
  LaunchedEffect(bluetoothState) {
    when (val state = bluetoothState) {
      is BluetoothState.Error -> {
        // При ошибке показываем сообщение и скрываем диалог
        showProgressDialog = false
        Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
      }

      BluetoothState.Pairing -> {
        showProgressDialog = true
      }

      is BluetoothState.Bonded -> {
        showProgressDialog = false
        Toast.makeText(
          context,
          "$deviceBonded: ${state.deviceAddress}",
          Toast.LENGTH_SHORT
        ).show()

        sharedPreferences.edit()
          .putString("printer_connection_type", "Bluetooth")
          .putString("printer_address", state.deviceAddress)
          .apply()

        printerModel.connectToPrinter(context, "Bluetooth", state.deviceAddress)

        navController.previousBackStackEntry
          ?.savedStateHandle
          ?.set("address", state.deviceAddress)
        navController.popBackStack()
      }

      BluetoothState.Discovering -> {
        showProgressDialog = false
      }

      BluetoothState.Idle -> {
        showProgressDialog = false
      }
    }
  }

  // Check if Bluetooth is enabled after permissions are granted
  LaunchedEffect(bluetoothAdapterInitialized(context)) { // Custom check for initialization status
    if (bluetoothAdapterInitialized(context) && !isBluetoothEnabled(context)) {
      val enableBtIntent =
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS) // Direct to settings for enablement
      enableBtLauncher.launch(enableBtIntent)
    } else if (bluetoothAdapterInitialized(context) && isBluetoothEnabled(context) && !isRefreshing && bluetoothDevices.isEmpty()) {
      // Auto-start discovery if adapter is ready and no devices found yet
      viewModel.startDiscovery(context)
    }
  }

  // Ensure discovery starts if in Idle state and no devices are found
  LaunchedEffect(bluetoothState, bluetoothDevices) {
    if (bluetoothState is BluetoothState.Idle && bluetoothDevices.isEmpty()) {
      viewModel.startDiscovery(context)
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(id = R.string.bluetooth_discovery).toString()) },
        navigationIcon = {
          IconButton(onClick = { navController.popBackStack() }) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(id = R.string.back).toString()
            )
          }
        }
      )
    }
  ) { paddingValues -> // Important: Apply paddingValues to your content!
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues) // Apply padding from Scaffold
        .padding(16.dp), // Add extra padding for content if needed
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {

      Box(
        modifier = Modifier
          .fillMaxSize()
        //.pullRefresh(pullRefreshState) // Apply pull-to-refresh modifier
      ) {
        if (bluetoothDevices.isEmpty() && !isRefreshing && bluetoothState !is BluetoothState.Error) {
          Text(
            text = stringResource(id = R.string.no_bluetooth_devices),
            modifier = Modifier.align(Alignment.Center)
          )
        }

        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.Top
        ) {
          itemsIndexed(bluetoothDevices) { index, device ->
            val itemShape = when (index) {
              0 -> RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 0.dp,
                bottomEnd = 0.dp
              )

              bluetoothDevices.lastIndex -> RoundedCornerShape(
                topStart = 0.dp,
                topEnd = 0.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
              )

              else -> RoundedCornerShape(0.dp)
            }

            Column {
              if (index != 0) {
                Divider(
                  thickness = 1.dp,
                  color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
              }
              BluetoothDeviceItem(
                device = device,
                onClick = {
                  if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    Log.d("BluetoothDiscoveryScreen1", device.address)

                    sharedPreferences.edit()
                      .putString("printer_connection_type", "Bluetooth")
                      .putString("printer_address", device.address)
                      .apply()
                    navController.previousBackStackEntry
                      ?.savedStateHandle
                      ?.set("address", device.address)
                    navController.popBackStack()
                  } else {
                    viewModel.pairDevice(device) // Initiate pairing
                  }
                },
                shape = itemShape
              )
            }
          }
        }

        if (showProgressDialog) {
          AlertDialog(
            onDismissRequest = { /* Cannot dismiss during pairing */ },
            title = { Text(text = stringResource(id = R.string.pairing_title)) },
            text = {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
              ) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                Text(stringResource(id = R.string.pairing_message))
              }
            },
            confirmButton = {}
          )
        }
      }
      errorMessage?.let { message ->
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        errorMessage = null
      }
    }
  }
}

//@SuppressLint("MissingPermission") // Permissions handled in parent composable
@SuppressLint("MissingPermission")
@Composable
fun BluetoothDeviceItem(
  device: BluetoothDevice,
  onClick: () -> Unit,
  shape: RoundedCornerShape
) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = shape)
      .padding(vertical = 12.dp, horizontal = 16.dp)
  ) {
    Column {
      Text(
        text = device.name ?: stringResource(id = R.string.unknown_device),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Text(
        text = device.address,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
      )
      if (device.bondState == BluetoothDevice.BOND_BONDED) {
        Text(
          text = "Bonded",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary // Use a highlight color
        )
      }
    }
    // Icon for bonded devices (optional)
    if (device.bondState == BluetoothDevice.BOND_BONDED) {
      Icon(
        imageVector = Icons.Filled.Settings,
        contentDescription = "Bonded Device",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
          .align(Alignment.CenterEnd)
          .size(24.dp)
      )
    }
  }
}

// Helper functions for checking Bluetooth and Location status
private fun isBluetoothEnabled(context: Context): Boolean {
  val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
  return bluetoothManager.adapter?.isEnabled == true
}

private fun bluetoothAdapterInitialized(context: Context): Boolean {
  val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
  return bluetoothManager.adapter != null
}

private fun isLocationEnabled(context: Context): Boolean {
  val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
  return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
          locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
}


/*@Preview(showBackground = true)
@Composable
fun PreviewBluetoothDiscoveryScreen() {
  IMovePrinterTheme {
    BluetoothDiscoveryScreen(
      navController = null
      //onDeviceSelected = { address -> println("Selected: $address") },
      //tag = Activity.RESULT_OK
    )
  }
}*/