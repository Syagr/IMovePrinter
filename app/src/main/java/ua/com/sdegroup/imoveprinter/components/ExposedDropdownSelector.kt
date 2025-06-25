package ua.com.sdegroup.imoveprinter.components

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class) // ExposedDropdownMenuBox is often experimental
@Composable
fun ExposedDropdownSelector(
  title: String,
  options: List<String>,
  selectedOption: String,
  onOptionSelected: (String) -> Unit
) {
  var expanded by remember { mutableStateOf(false) }

  Text(title)
  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = !expanded },
  ) {
    TextField(
      value = selectedOption,
      onValueChange = { /* Read-only, no direct typing */ },
      readOnly = true,
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor(), // This is important for the menu's positioning
      //textStyle = TextStyle(fontSize = 12.sp, lineHeight = 0.5.em),
      //shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    )

    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { item ->
        DropdownMenuItem(
          text = { Text(item) },
          onClick = {
            onOptionSelected(item)
            expanded = false
          }
        )
      }
    }
  }
}