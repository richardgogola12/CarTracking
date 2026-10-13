package sk.cartracker.ui.screens

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlin.math.roundToInt

fun Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            content()
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * Plynulý posuvník od 0 – hodnota sa uloží po pustení prsta.
 * Ťuknutím na číslo sa dá zapísať vlastná hodnota (aj väčšia než koniec posuvníka).
 * [range] určuje už len horný koniec posuvníka; parameter step sa nepoužíva.
 */
@Composable
fun SliderRow(title: String, value: Int, range: IntRange, step: Int = 1, unit: String, subtitle: String? = null, format: ((Int) -> String)? = null, onChange: (Int) -> Unit) {
    val max = range.last.coerceAtLeast(1)
    var temp by remember(value) { mutableFloatStateOf(value.toFloat()) }
    var editing by remember { mutableStateOf(false) }
    fun label(v: Int) = format?.invoke(v) ?: "$v $unit".trim()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Surface(
                onClick = { editing = true },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label(temp.roundToInt()), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Icon(Icons.Filled.Edit, "Zadať hodnotu", Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = temp.coerceIn(0f, max.toFloat()),
            onValueChange = { temp = it.coerceIn(0f, max.toFloat()) },
            onValueChangeFinished = { onChange(temp.roundToInt()) },
            valueRange = 0f..max.toFloat(),
        )
    }
    if (editing) {
        val timeInput = format != null && unit.isBlank()   // napr. pracovný čas „7:30“
        var text by remember { mutableStateOf(if (timeInput) label(value) else value.toString()) }
        var error by remember { mutableStateOf<String?>(null) }
        fun parse(): Int? {
            val t = text.trim().replace(" ", "").replace('\u00A0'.toString(), "")
            if (timeInput && t.contains(':')) {
                val (h, m) = t.split(':', limit = 2).map { it.toIntOrNull() ?: return null }
                return if (h in 0..24 && m in 0..59) h * 60 + m else null
            }
            return t.replace(',', '.').toDoubleOrNull()?.roundToInt()
        }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        text, { text = it; error = null }, singleLine = true,
                        label = { Text(if (timeInput) "Čas (hh:mm)" else "Hodnota" + if (unit.isNotBlank()) " ($unit)" else "") },
                        keyboardOptions = KeyboardOptions(keyboardType = if (timeInput) KeyboardType.Text else KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Posuvník ide od 0 do ${label(max)}; zapísať môžete aj väčšiu hodnotu.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = parse()
                    if (v == null || v < 0) { error = "Zadajte číslo 0 alebo väčšie"; return@TextButton }
                    temp = v.toFloat(); onChange(v); editing = false
                }) { Text("Uložiť") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Zrušiť") } },
        )
    }
}

@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Zobraziť heslo")
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
