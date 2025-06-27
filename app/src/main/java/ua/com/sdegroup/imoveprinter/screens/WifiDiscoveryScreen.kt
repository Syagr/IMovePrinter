package ua.com.sdegroup.imoveprinter.screens

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.net.Socket
import java.net.InetSocketAddress
import java.io.OutputStream
import androidx.navigation.NavController
import cpcl.PrinterHelper
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import ua.com.sdegroup.imoveprinter.model.PrinterModel

fun sendCpclCommand(ip: String, port: Int, cpcl: String): Boolean {
    return try {
        val socket = Socket()
        socket.connect(InetSocketAddress(ip, port), 3000)
        val out: OutputStream = socket.getOutputStream()
        out.write(cpcl.toByteArray(Charsets.UTF_8))
        out.flush()
        out.close()
        socket.close()
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiDiscoveryScreen(navController: NavController) {
    val context = LocalContext.current
    val printerModel: PrinterModel = viewModel()
    var ipInput by remember { mutableStateOf("192.168.1.1") }
    var connectionStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wi-Fi принтер") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = ipInput,
                onValueChange = { ipInput = it },
                label = { Text("Введіть IP принтер") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedLabelColor = MaterialTheme.colorScheme.primary
                ),
                textStyle = MaterialTheme.typography.bodyLarge
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    scope.launch {
                        val connected = withContext(Dispatchers.IO) {
                            printerModel.connectToPrinter(context, "WiFi", ipInput)
                        }

                        if (connected) {
                            connectionStatus = "Підключено до принтера ($ipInput)"
                            navController.previousBackStackEntry?.savedStateHandle?.set("wifi_ip", ipInput)
                            navController.popBackStack()
                        } else {
                            connectionStatus = "Не вдалося підключитися до принтера ($ipInput)"
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Підключитися та надрукувати")
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = connectionStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = if (connectionStatus.contains("Успішно")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
    }
}