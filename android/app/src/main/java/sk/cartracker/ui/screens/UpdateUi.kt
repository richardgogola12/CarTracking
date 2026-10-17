package sk.cartracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sk.cartracker.BuildConfig
import sk.cartracker.sync.AppUpdate

/** Kontrola, stiahnutie a inštalácia novej verzie z GitHubu. */
@Composable
fun UpdateDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf("check") }   // check | none | found | download | error
    var rel by remember { mutableStateOf<AppUpdate.Release?>(null) }
    var error by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        runCatching { AppUpdate.check() }
            .onSuccess { rel = it; state = if (it == null) "none" else "found" }
            .onFailure { error = it.message ?: "bez internetu"; state = "error" }
    }
    fun start() {
        val r = rel ?: return
        if (!AppUpdate.canInstall(ctx)) { AppUpdate.openInstallPermission(ctx); return }
        state = "download"
        scope.launch {
            runCatching { AppUpdate.download(ctx, r) { progress = it } }
                .onSuccess { AppUpdate.install(ctx, it); onClose() }
                .onFailure { error = it.message ?: "chyba"; state = "error" }
        }
    }
    AlertDialog(
        onDismissRequest = { if (state != "download") onClose() },
        title = { Text("Aktualizácia aplikácie") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    "check" -> { Text("Hľadám novú verziu…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    "none" -> Text("Máte najnovšiu verziu ${BuildConfig.VERSION_NAME} ✅")
                    "found" -> {
                        val r = rel!!
                        Text("Nová verzia ${r.versionName} (máte ${BuildConfig.VERSION_NAME}).")
                        Text("Veľkosť ${r.sizeBytes / 1_048_576} MB. Stiahne sa z GitHubu a Android ju nainštaluje ako aktualizáciu – jazdy a nastavenia ostanú.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!AppUpdate.canInstall(ctx)) Text("Najprv povoľte Trasníku „Inštalovať neznáme aplikácie“ – po ťuknutí na Povoliť sa otvorí nastavenie, zapnite ho a vráťte sa.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    "download" -> { Text("Sťahujem… $progress %"); LinearProgressIndicator({ progress / 100f }, Modifier.fillMaxWidth()) }
                    else -> Text("Nepodarilo sa: $error", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (state) {
                "found" -> TextButton(onClick = { start() }) { Text(if (AppUpdate.canInstall(ctx)) "Stiahnuť a nainštalovať" else "Povoliť") }
                "download" -> {}
                else -> TextButton(onClick = onClose) { Text("OK") }
            }
        },
        dismissButton = { if (state == "found") TextButton(onClick = onClose) { Text("Neskôr") } },
    )
}
