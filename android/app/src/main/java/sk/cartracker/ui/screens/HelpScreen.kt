package sk.cartracker.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import sk.cartracker.ui.Reports

const val MANUAL_URL = "file:///android_asset/manual.html"

/** Návod v aplikácii – rovnaký dokument sa dá vytlačiť alebo uložiť ako PDF. */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Návod") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Späť") } },
            actions = {
                IconButton(enabled = activity != null, onClick = {
                    activity?.let { Reports.printHtml(it, "Car Tracking – návod", url = MANUAL_URL) }
                }) { Icon(Icons.Filled.Print, "Vytlačiť / uložiť PDF") }
            },
        )
    }) { pad ->
        AndroidView(
            modifier = Modifier.padding(pad).fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = false
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    webViewClient = object : WebViewClient() {
                        // Odkazy mimo návodu otvoriť v prehliadači, kotvy (#…) v návode
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url.toString()
                            if (url.startsWith("file:///android_asset/")) return false
                            runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            return true
                        }
                    }
                    loadUrl(MANUAL_URL)
                }
            },
        )
    }
}
