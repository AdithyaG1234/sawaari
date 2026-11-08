package app.sawaari.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Palette and light/dark choices. Changes apply straight away. */
@Composable
fun ThemeOptions(
    palette: AppPalette,
    mode: DarkMode,
    onPalette: (AppPalette) -> Unit,
    onMode: (DarkMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppPalette.entries.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .border(
                        if (p == palette) 2.dp else 1.dp,
                        if (p == palette) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onPalette(p) }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = p == palette, onClick = { onPalette(p) })
                p.swatch.forEach { c ->
                    Box(Modifier.padding(end = 4.dp).size(24.dp).clip(CircleShape).background(c))
                }
                Spacer(Modifier.width(8.dp))
                Text(p.label, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.size(4.dp))
        }
        Text("Light or dark", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DarkMode.entries.forEach { m ->
                FilterChip(selected = m == mode, onClick = { onMode(m) }, label = { Text(m.label) })
            }
        }
    }
}

/** The same choices in a dialog, opened from the last tour step. */
@Composable
fun ThemePickerDialog(
    palette: AppPalette,
    mode: DarkMode,
    onPalette: (AppPalette) -> Unit,
    onMode: (DarkMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App colours") },
        text = { ThemeOptions(palette, mode, onPalette, onMode) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
