package ua.com.sdegroup.imoveprinter.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.material3.ButtonDefaults

@Composable
fun DeviceList(navController: NavController) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(
      text = "You are on the Second Screen!",
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(bottom = 24.dp)
    )
    Button(
      onClick = {
        navController.popBackStack()
      },
      colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
      )
    ) {
      Text("Go Back", style = MaterialTheme.typography.bodyMedium)
    }
  }
}