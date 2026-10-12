package sk.cartracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sk.cartracker.container
import sk.cartracker.security.BiometricAuth

@Composable
private fun AuthHeader(subtitle: String) {
    Icon(Icons.Filled.DirectionsCar, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
    Text("Car Tracking", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
}

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onRegister: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val c = ctx.container
    val scope = rememberCoroutineScope()

    var email by rememberSaveable { mutableStateOf(c.session.lastEmail()) }
    var password by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var offerBiometric by remember { mutableStateOf(false) }
    val bioReady = remember { c.session.hasBiometricToken() && BiometricAuth.isAvailable(ctx) }

    fun biometricLogin() {
        val act = activity ?: return
        error = null
        BiometricAuth.unlock(act, c.session,
            onToken = { token ->
                scope.launch {
                    loading = true
                    c.auth.loginWithToken(token).onSuccess { onLoggedIn() }.onFailure { error = it.message }
                    loading = false
                }
            },
            onError = { msg, _ -> error = msg })
    }

    LaunchedEffect(Unit) { if (bioReady) biometricLogin() }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        AuthHeader("Prihlásenie")
        OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        PasswordField(password, { password = it }, "Heslo")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                scope.launch {
                    loading = true; error = null
                    c.auth.login(email, password)
                        .onSuccess {
                            if (BiometricAuth.isAvailable(ctx) && !c.session.hasBiometricToken()) offerBiometric = true
                            else onLoggedIn()
                        }
                        .onFailure { error = it.message }
                    loading = false
                }
            },
            enabled = !loading && email.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Prihlásiť sa") }

        if (bioReady) {
            FilledTonalButton(onClick = { biometricLogin() }, enabled = !loading, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Fingerprint, null); Spacer(Modifier.size(8.dp)); Text("Prihlásiť odtlačkom prsta")
            }
        }
        TextButton(onClick = onRegister) { Text("Nemáte účet? Zaregistrujte sa") }
    }

    if (offerBiometric) {
        AlertDialog(
            onDismissRequest = { offerBiometric = false; onLoggedIn() },
            icon = { Icon(Icons.Filled.Fingerprint, null) },
            title = { Text("Prihlasovať sa odtlačkom?") },
            text = { Text("Nabudúce sa prihlásite iba priložením prsta. Zmeniť to môžete v Nastaveniach.") },
            confirmButton = {
                TextButton(onClick = {
                    val act = activity ?: return@TextButton
                    BiometricAuth.enroll(act, c.session) { ok, _ ->
                        scope.launch {
                            if (ok) c.settings.update { it.copy(biometricEnabled = true) }
                            offerBiometric = false
                            onLoggedIn()
                        }
                    }
                }) { Text("Zapnúť") }
            },
            dismissButton = { TextButton(onClick = { offerBiometric = false; onLoggedIn() }) { Text("Teraz nie") } },
        )
    }
}

@Composable
fun RegisterScreen(onRegistered: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        AuthHeader("Nový účet")
        OutlinedTextField(name, { name = it }, label = { Text("Meno") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        PasswordField(password, { password = it }, "Heslo (min. 8 znakov)")
        PasswordField(confirm, { confirm = it }, "Zopakujte heslo")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                error = when {
                    name.isBlank() -> "Zadajte meno"
                    !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> "Neplatný e-mail"
                    password.length < 8 -> "Heslo musí mať aspoň 8 znakov"
                    password != confirm -> "Heslá sa nezhodujú"
                    else -> null
                }
                if (error == null) scope.launch {
                    loading = true
                    c.auth.register(name, email, password).onSuccess { onRegistered() }.onFailure { error = it.message }
                    loading = false
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Zaregistrovať") }
        TextButton(onClick = onBack) { Text("Už mám účet – prihlásiť sa") }
    }
}

/** Zámok pri otvorení appky, ak je zapnuté prihlásenie odtlačkom. */
@Composable
fun LockScreen(onUnlocked: () -> Unit, onUsePassword: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val c = ctx.container
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }

    fun unlock() {
        val act = activity ?: return
        error = null
        BiometricAuth.unlock(act, c.session,
            onToken = { onUnlocked() },
            onError = { msg, permanent ->
                error = msg
                if (permanent) scope.launch { c.auth.logout(forget = false); onUsePassword() }
            })
    }
    LaunchedEffect(Unit) { unlock() }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        AuthHeader("Aplikácia je zamknutá")
        Button(onClick = { unlock() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Filled.Fingerprint, null); Spacer(Modifier.size(8.dp)); Text("Odomknúť odtlačkom")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { scope.launch { c.auth.logout(forget = false); onUsePassword() } }) {
            Text("Prihlásiť sa heslom")
        }
        Text("Sledovanie jázd na pozadí funguje aj pri zamknutej aplikácii.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
