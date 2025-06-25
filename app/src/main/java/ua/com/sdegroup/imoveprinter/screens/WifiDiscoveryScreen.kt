package ua.com.sdegroup.imoveprinter.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.net.Socket
import java.net.InetSocketAddress
import java.io.OutputStream
import androidx.navigation.NavController
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

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

@Composable
fun WifiDiscoveryScreen(navController: NavController) {
    val apIp = "192.168.1.1"
    val apPort = 9100
    var connectionStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Wi-Fi Printer AP Mode (No Password)", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Text("Connect your device to the printer's Wi-Fi network (e.g., T3PRO_AP-XXXX).", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Text("Printer IP: $apIp", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                scope.launch {
                    val cpclTest = """
                        ! 0 200 200 210 1
                        TEXT 4 0 30 40 CPCL TEST
                        FORM
                        PRINT
                    """.trimIndent()
                    val result = withContext(Dispatchers.IO) {
                        sendCpclCommand(apIp, apPort, cpclTest)
                    }
                    connectionStatus = if (result) "Test sent!" else "Send error!"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Print Test")
        }
        Spacer(Modifier.height(16.dp))
        Text(connectionStatus)
    }
}