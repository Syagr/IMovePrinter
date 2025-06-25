package ua.com.sdegroup.imoveprinter.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun TextWithTrailingIcon(
  text: String,
  modifier: Modifier = Modifier,
  icon: ImageVector,
  iconModifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.primary,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Text(
      text = text,
      color = color,
      style = MaterialTheme.typography.bodyMedium
    )
    Icon(
      imageVector = icon,
      contentDescription = "Icon",
      modifier = iconModifier.size(16.dp),
      tint = color
    )
  }
}