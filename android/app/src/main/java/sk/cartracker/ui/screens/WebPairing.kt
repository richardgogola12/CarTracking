package sk.cartracker.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import sk.cartracker.container
import sk.cartracker.data.remote.ApiProvider
import sk.cartracker.data.remote.PairInfo
import sk.cartracker.data.remote.PairRequest
import sk.cartracker.ui.formatDateTime

/** Výsledok rozboru odkazu z QR kódu na webe. */
sealed interface PairLink {
    data class Ok(val code: String) : PairLink
    data class Error(val message: String) : PairLink
}

object WebPair {
    /** trasnik://pair?c=<64 hex>&api=<adresa API>; api sa musí zhodovať so serverom aplikácie. */
    fun parse(raw: String, appServer: String): PairLink {
        val u = runCatching { Uri.parse(raw.trim()) }.getOrNull()
        if (u == null || u.scheme != "trasnik" || u.host != "pair") return PairLink.Error("Toto nie je QR kód prihlásenia Trasníka. Otvorte webovú stránku Trasníka na počítači.")
        val code = u.getQueryParameter("c").orEmpty()
        if (!Regex("^[a-f0-9]{64}$").matches(code)) return PairLink.Error("QR kód je poškodený – obnovte stránku na počítači.")
        val api = u.getQueryParameter("api")
        if (api != null && !sameServer(api, appServer))
            return PairLink.Error("Web je nastavený na iný server (${host(api)}) ako aplikácia (${host(appServer)}). Na webe v „Nastavenie servera“ zadajte rovnakú adresu.")
        return PairLink.Ok(code)
    }
    fun sameServer(a: String, b: String) = ApiProvider.normalize(a).lowercase() == ApiProvider.normalize(b).lowercase()
    private fun host(u: String) = u.removePrefix("https://").removePrefix("http://").trimEnd('/')

    fun scan(ctx: Context, onText: (String) -> Unit, onError: (String) -> Unit) {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        GmsBarcodeScanning.getClient(ctx, opts).startScan()
            .addOnSuccessListener { b -> b.rawValue?.let(onText) ?: onError("QR kód sa nepodarilo prečítať") }
            .addOnFailureListener { onError("Čítačka QR kódov nie je dostupná (${it.message ?: "Služby Google Play"}). Použite 6-miestny kód.") }
    }
}

/** Tlačidlá v Nastaveniach → Účet: načítať QR kód alebo zadať 6-miestny kód. */
@Composable
fun WebPairButtons() {
    val ctx = LocalContext.current
    var link by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { WebPair.scan(ctx, { link = it }, { error = it }) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(18.dp)); Text("  Prihlásiť web (QR kód)")
        }
        OutlinedButton(onClick = { manual = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Dialpad, null, Modifier.size(18.dp)); Text("  Prihlásiť web kódom")
        }
        Text("Na počítači otvorte web Trasníka – zobrazí QR kód a 6-miestny kód. Netreba písať e-mail ani heslo.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    link?.let { WebPairDialog(it) { link = null } }
    if (manual) WebPairDialog(null) { manual = false }
    error?.let { e -> AlertDialog(onDismissRequest = { error = null }, title = { Text("QR kód") }, text = { Text(e) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
}

/**
 * Potvrdenie prihlásenia webu. [raw] = odkaz z QR kódu; null = ručné zadanie 6-miestneho kódu.
 * Pred schválením ukáže, ktorý prehliadač (a z akej IP) o prihlásenie žiada.
 */
@Composable
fun WebPairDialog(raw: String?, onClose: () -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var short by remember { mutableStateOf("") }
    var req by remember { mutableStateOf<PairRequest?>(null) }
    var info by remember { mutableStateOf<PairInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    fun load(r: PairRequest) {
        busy = true; error = null
        scope.launch {
            try { info = c.api.get().pairInfo(r); req = r } catch (e: Exception) { error = c.api.errorMessage(e) }
            busy = false
        }
    }
    LaunchedEffect(raw) {
        if (raw != null) when (val p = WebPair.parse(raw, c.settings.current().serverUrl)) {
            is PairLink.Ok -> load(PairRequest(code = p.code))
            is PairLink.Error -> error = p.message
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        icon = { Icon(if (done) Icons.Filled.CheckCircle else Icons.Filled.Computer, null) },
        title = { Text(if (done) "Web je prihlásený" else "Prihlásiť web Trasníka") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    done -> Text("Stránka na počítači sa o chvíľu sama otvorí. Prístup zrušíte odhlásením na webe.")
                    info != null -> {
                        val i = info!!
                        Text("Prihlásiť tento prehliadač do vášho účtu?")
                        Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(i.browser ?: "Prehliadač", fontWeight = FontWeight.SemiBold)
                            Text("IP ${i.ip ?: "–"}" + (i.createdAt?.let { " · kód z ${formatDateTime(it)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Potvrďte iba vtedy, ak QR kód / kód vidíte na vlastnej obrazovke.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    raw == null -> {
                        Text("Zadajte 6-miestny kód zo stránky na počítači.")
                        OutlinedTextField(short, { v -> short = v.filter(Char::isDigit).take(6); error = null }, singleLine = true,
                            label = { Text("Kód") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("  Overujem…") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                done -> TextButton(onClick = onClose) { Text("Hotovo") }
                info != null -> Button(enabled = !busy, onClick = {
                    busy = true; error = null
                    scope.launch {
                        try { c.api.get().pairApprove(req!!); done = true } catch (e: Exception) { error = c.api.errorMessage(e) }
                        busy = false
                    }
                }) { Text("Prihlásiť") }
                raw == null -> Button(enabled = !busy && short.length == 6, onClick = { load(PairRequest(short = short)) }) { Text("Ďalej") }
                else -> TextButton(onClick = onClose) { Text("Zavrieť") }
            }
        },
        dismissButton = { if (!done && (info != null || raw == null)) TextButton(enabled = !busy, onClick = onClose) { Text("Zrušiť") } },
    )
}
