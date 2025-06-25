package ua.com.sdegroup.imoveprinter.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable
fun DropdownList(
  itemList: List<String>,
  selectedIndex: Int,
  modifier: Modifier,
  onItemClick: (Int) -> Unit,
  color: Color = MaterialTheme.colorScheme.onPrimary,
  backgroundColor: Color = MaterialTheme.colorScheme.primary,
) {
  var showDropdown by remember { mutableStateOf(false) }
  //val scrollState = rememberScrollState()

  Column(
    modifier = Modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Box(
      modifier = modifier
        .background(color = backgroundColor, shape = RoundedCornerShape(20.dp))
        .padding(vertical = 4.dp).clickable { showDropdown = true },
      contentAlignment = Alignment.Center,
    ) {
      TextWithTrailingIcon(
        text = itemList[selectedIndex],
        modifier = Modifier.padding(3.dp),
        icon = Icons.Filled.ArrowDropDown,
        color = color
      )
    }

    Box { // dropdown list
      if (showDropdown) {
        Popup(
          alignment = Alignment.TopCenter,
          properties = PopupProperties(excludeFromSystemGesture = true),
          onDismissRequest = { showDropdown = false }, // to dismiss on click outside
        ) {
          Column(
            modifier = modifier.heightIn(max = 100.dp).padding(top = 2.dp),//.border(width = 1.dp, color = color),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            itemList.onEachIndexed { index, item ->
              val cornerRadius = 4.dp;
              val itemShape = when (index) {
                0 -> RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius, bottomStart = 0.dp, bottomEnd = 0.dp)
                itemList.lastIndex -> RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = cornerRadius, bottomEnd = cornerRadius)
                else -> RoundedCornerShape(0.dp)
              }
              if (index != 0) {
                Divider(thickness = 1.dp, color = color)
              }
              Box(
                modifier = Modifier.background(color = backgroundColor, shape = itemShape)
                  .fillMaxWidth().clickable {
                  onItemClick(index)
                  showDropdown = !showDropdown
                },
                contentAlignment = Alignment.Center
              ) { Text(text = item, color = color) }
            }
          }
        }
      }
    }
  }
}