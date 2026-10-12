package sk.cartracker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sk.cartracker.container
import sk.cartracker.data.AppSettings
import sk.cartracker.detection.ActivityDetectionManager
import sk.cartracker.ui.Perm
import sk.cartracker.ui.appSettingsIntent
import sk.cartracker.ui.batteryOptimizationIntent
import sk.cartracker.ui.permissionItems
import sk.cartracker.ui.permissionString

@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val s by c.settings.flow.collectAsStateWithLifecycle(AppSettings())
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val items = remember(tick, s) { permissionItems(ctx, s) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    // Po udelení "Fyzická aktivita" hneď zaregistruj automatickú detekciu
    val activityGranted = items.firstOrNull { it.perm == Perm.ACTIVITY }?.granted == true
    LaunchedEffect(activityGranted, s.autoDetection) {
        if (activityGranted && s.autoDetection && c.session.isLoggedIn) ActivityDetectionManager.register(ctx)
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Povolenia", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Aby sa jazda autom zaznamenávala úplne automaticky (aj pri zatvorenej aplikácii), " +
            "povoľte postupne všetky položky. Chôdza ani bicykel sa zaznamenávať nebudú.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        val fineGranted = items.first { it.perm == Perm.LOCATION }.granted
        items.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (item.granted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                        tint = if (item.granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.SemiBold)
                        Text(item.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!item.granted) {
                        // Polohu na pozadí možno žiadať až po presnej polohe
                        val enabled = item.perm != Perm.BACKGROUND_LOCATION || fineGranted
                        FilledTonalButton(enabled = enabled, onClick = {
                            if (item.perm == Perm.BATTERY) ctx.startActivity(batteryOptimizationIntent(ctx))
                            else permissionString(item.perm)?.let { launcher.launch(it) }
                        }) { Text("Povoliť") }
                    }
                }
            }
        }

        TextButton(onClick = { ctx.startActivity(appSettingsIntent(ctx)) }) {
            Text("Otvoriť systémové nastavenia aplikácie")
        }
        Text("Tip: na telefónoch Xiaomi, Huawei, Samsung a pod. povoľte v systéme aj „Automatické spustenie“ " +
            "a nastavte batériu na „Bez obmedzení“, inak môže výrobca službu na pozadí ukončiť.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val allOk = items.all { it.granted || !it.required }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(if (allOk) "Hotovo" else "Pokračovať (dokončím neskôr)") }
    }
}
