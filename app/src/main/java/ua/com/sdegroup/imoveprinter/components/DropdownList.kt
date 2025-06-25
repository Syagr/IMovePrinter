package ua.com.sdegroup.imoveprinter.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.CardDefaults

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
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
  modifier: Modifier = Modifier,
  onItemClick: (Int) -> Unit,
  color: Color = MaterialTheme.colorScheme.onPrimary,
  backgroundColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
  var showDropdown by remember { mutableStateOf(false) }

  val popupBackground = MaterialTheme.colorScheme.onPrimaryContainer

  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // Кнопка
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
            listOf(backgroundColor, backgroundColor.copy(alpha = 0.8f))
          ),
          shape = RoundedCornerShape(16.dp)
        )
        .clickable { showDropdown = !showDropdown }
        .padding(vertical = 14.dp, horizontal = 18.dp),
      contentAlignment = Alignment.Center
    ) {
      TextWithTrailingIcon(
        text = itemList[selectedIndex],
        icon = Icons.Filled.ArrowDropDown,
        color = color
      )
    }

    // Выпадающее меню
    AnimatedVisibility(
      visible = showDropdown,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically()
    ) {
      Popup(
        alignment = Alignment.TopCenter,
        onDismissRequest = { showDropdown = false },
        properties = PopupProperties(focusable = true)
      ) {
        ElevatedCard(
          modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth(0.6f)
            .heightIn(max = 240.dp),
          shape = RoundedCornerShape(14.dp),
          elevation = CardDefaults.elevatedCardElevation(12.dp),
          colors = CardDefaults.cardColors(containerColor = popupBackground)
        ) {
          Column {
            itemList.forEachIndexed { index, item ->
              val isSelected = index == selectedIndex
              val interactionSource = remember { MutableInteractionSource() }

              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(
                    if (isSelected) popupBackground.copy(alpha = 0.2f)
                    else Color.Transparent
                  )
                  .clickable(
                    interactionSource = interactionSource,
                    indication = null
                  ) {
                    onItemClick(index)
                    showDropdown = false
                  }
                  .padding(vertical = 12.dp, horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart
              ) {
                Text(
                  text = item,
                  color = color,
                  style = MaterialTheme.typography.bodyMedium
                )
              }

              if (index != itemList.lastIndex) {
                Divider(color = color.copy(alpha = 0.1f), thickness = 1.dp)
              }
            }
          }
        }
      }
    }
  }
}