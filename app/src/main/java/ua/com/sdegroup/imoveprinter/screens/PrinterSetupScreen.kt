package ua.com.sdegroup.imoveprinter.screens

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import ua.com.sdegroup.imoveprinter.components.DropdownList
import ua.com.sdegroup.imoveprinter.ui.theme.IMovePrinterTheme
import ua.com.sdegroup.imoveprinter.model.PrinterModel
import ua.com.sdegroup.imoveprinter.factory.PrinterModelFactory
import androidx.compose.material3.MaterialTheme

fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    return bluetoothManager.adapter
}

@Composable
fun OpenDeviceList() {
    val context = LocalContext.current
    val bluetoothAdapter = remember { getBluetoothAdapter(context) }
    //if (bluetoothAdapter)
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
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    val menuItems = listOf("Menu1", "Menu2")
    val connectionTypes = listOf("Bluetooth", "WiFi", "USB")
    var selectedIndex by rememberSaveable { mutableStateOf(0) }
    var printerStatus by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Printer Setup") },
                actions = {
                    Box {
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            menuItems.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item) },
                                    onClick = { expanded = false }
                                )
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "Hello from Snackbar!",
                        actionLabel = "Dismiss"
                    )
                    when (result) {
                        SnackbarResult.Dismissed -> println("Snackbar dismissed")
                        SnackbarResult.ActionPerformed -> println("Snackbar action performed")
                    }
                }
            }) {
                Icon(Icons.Filled.Add, "Add FAB")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Connection type")
                  DropdownList(
                      itemList = connectionTypes,
                      selectedIndex = selectedIndex,
                      modifier = Modifier.width(160.dp),
                      onItemClick = { selectedIndex = it },
                      color = MaterialTheme.colorScheme.primary,
                      backgroundColor = MaterialTheme.colorScheme.onPrimary
                  )
            }
            Button(onClick = {
                when (connectionTypes[selectedIndex]) {
                    "Bluetooth" -> navController.navigate("bluetooth_discovery")
                    "WiFi" -> navController.navigate("wifi_discovery")
                }
            }) {
                Text("Connect")
            }
            val scope = rememberCoroutineScope()
            
            Button(onClick = {
                scope.launch {
                    viewModel.connect(context)
                    printerStatus = viewModel.getStatus()
                }
            }) {
                Text("Get Printer Status")
            }
            Text(printerStatus)
            Button(onClick = {
                viewModel.disconnect()
            }) {
                Text("Disconnect")
            }
            Button(onClick = {
                viewModel.printTestReceipt()
            }) {
                Text("Print Test Receipt")
            }
            Button(onClick = {
                viewModel.printPDF(context)
            }) {
                Text("Print Test Receipt")
            }
            Button(onClick = {
                viewModel.getVersion()
            }) {
                Text("Print Version")
            }
        }
    }
}

/*@Preview(showBackground = true)
@Composable
fun MyScreenPreview() {
    IMovePrinterTheme {
        PrinterSetup()
    }
}*/