package ua.com.sdegroup.imoveprinter.components

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.res.stringResource // Добавлен импорт
import ua.com.sdegroup.imoveprinter.R

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

@Composable
fun LanguageSelector(onLanguageSelected: (String) -> Unit) {
    val languages = listOf(
        stringResource(id = R.string.english),
        stringResource(id = R.string.ukrainian),
    )
    val languageCodes = listOf("en", "uk") // Соответствующие коды языков
    var selectedLanguage by remember { mutableStateOf(languages[0]) }
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.Language, // Используем иконку языка
                contentDescription = stringResource(id = R.string.select_language)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            languages.forEachIndexed { index, language ->
                DropdownMenuItem(
                    text = { Text(language) },
                    onClick = {
                        expanded = false // Закрываем модалку сразу
                        onLanguageSelected(languageCodes[index]) // Выполняем действие после закрытия
                    }
                )
            }
        }
    }
}