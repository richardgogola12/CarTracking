package sk.cartracker.ui.screens

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sk.cartracker.data.AppSettings
import sk.cartracker.service.SoundBank
import sk.cartracker.service.SoundEvent
import sk.cartracker.service.SoundPlayer

private fun AppSettings.withSound(e: SoundEvent, id: String) = when (e) {
    SoundEvent.DRIVE_START -> copy(soundDriveStart = id)
    SoundEvent.DRIVE_END -> copy(soundDriveEnd = id)
    SoundEvent.WALK_START -> copy(soundWalkStart = id)
    SoundEvent.WALK_END -> copy(soundWalkEnd = id)
    SoundEvent.SPEEDING -> copy(soundSpeeding = id)
    SoundEvent.PARKING -> copy(soundParking = id)
}

/** Obsah nastavení „Zvuky“ – zapnutie, tichý režim, hlasitosť a zvuk pre každú udalosť. */
@Composable
fun SoundSettingsBody(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf<SoundEvent?>(null) }
    // na skúšku sa prehrá aj keď sú zvuky vypnuté
    fun preview(id: String) = SoundPlayer.play(ctx, id, s.copy(sound = true, soundVolume = s.soundVolume.coerceAtLeast(30)))

    SwitchRow("Zvuky zapnuté", "Zvuk pri začiatku a konci jazdy a chôdze, pri prekročení rýchlosti a pri konci parkovacieho lístka", s.sound) { on ->
        update { it.copy(sound = on) }
    }
    SwitchRow("Prehrať aj v tichom režime", "Zvuk ide cez kanál budíka – znie aj pri stlmenom zvonení či vibráciách. Ak je hlasitosť budíka na nule, na chvíľu sa zvýši.",
        s.soundIgnoreSilent, enabled = s.sound) { on -> update { it.copy(soundIgnoreSilent = on) } }
    SliderRow("Hlasitosť", s.soundVolume, 0..100, 5, "%", "Vzhľadom na hlasitosť budíka (v tichom režime) alebo upozornení.") { v -> update { it.copy(soundVolume = v) } }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    SoundEvent.entries.forEach { e ->
        val id = e.of(s)
        Row(Modifier.fillMaxWidth().clickable(enabled = s.sound) { picking = e }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.label, fontWeight = FontWeight.SemiBold)
                Text(SoundBank.label(ctx, id), style = MaterialTheme.typography.bodySmall,
                    color = if (id == SoundBank.NONE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { preview(id) }, enabled = id != SoundBank.NONE) { Icon(Icons.Filled.PlayArrow, "Prehrať") }
        }
    }
    Text("Prekročenie rýchlosti zaznie len pri zapnutom upozornení (Štýl jazdy a rýchlosť), najviac raz za minútu. Zvuky chôdze sú predvolene vypnuté.",
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    picking?.let { e ->
        SoundPickerDialog(e.label, e.of(s), onPreview = { preview(it) }, onDismiss = { picking = null }) { id ->
            update { it.withSound(e, id) }
            picking = null
        }
    }
}

@Composable
private fun SoundPickerDialog(title: String, current: String, onPreview: (String) -> Unit, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var sel by remember { mutableStateOf(current) }
    val options = remember { listOf(SoundBank.NONE, SoundBank.SYSTEM) + SoundBank.all(ctx).map { it.id } +
        (if (current.startsWith(SoundBank.URI_PREFIX)) listOf(current) else emptyList()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = r.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) { sel = SoundBank.URI_PREFIX + uri; onPreview(sel) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(options + listOfNotNull(sel.takeIf { it.startsWith(SoundBank.URI_PREFIX) && it !in options })) { id ->
                    Row(Modifier.fillMaxWidth().clickable { sel = id; onPreview(id) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(sel == id, onClick = { sel = id; onPreview(id) })
                        Text(SoundBank.label(ctx, id), Modifier.weight(1f))
                        if (id != SoundBank.NONE) IconButton(onClick = { onPreview(id) }) { Icon(Icons.Filled.PlayArrow, "Prehrať") }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().clickable {
                        picker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Vlastný zvuk – $title"))
                    }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.FolderOpen, null, Modifier.padding(horizontal = 12.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Vlastný zvuk z telefónu…", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(sel) }) { Text("Použiť") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušiť") } },
    )
}
