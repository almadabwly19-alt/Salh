package com.bahith.pdf

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val SHAMELA_HOME = "https://shamela.ws/"

/** بوابة داخل التطبيق إلى موقع المكتبة الشاملة الرسمي. */
@Composable
fun ShamelaScreen() {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("المكتبة الشاملة", style = MaterialTheme.typography.titleLarge)
                Text(
                    "بحث وتصفح الكتب من الموقع الرسمي. سجّل الدخول فقط إذا طلب الموقع ذلك، وتحقق من بيانات الطبعة والصفحة عند النقل أو الاقتباس.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { webView?.loadUrl(SHAMELA_HOME) }) { Text("الرئيسية") }
                    TextButton(onClick = { webView?.reload() }) { Text("تحديث") }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHAMELA_HOME)))
                        }
                    }) { Text("فتح بالمتصفح") }
                }
            }
        }
        if (loadFailed) {
            Text(
                "تعذّر تحميل الموقع. تحقق من اتصال الإنترنت ثم اضغط تحديث.",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.error
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loadFailed = false
                        }
                        override fun onReceivedError(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) loadFailed = true
                        }
                    }
                    webView = this
                    loadUrl(SHAMELA_HOME)
                }
            },
            update = { view -> if (webView !== view) webView = view }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            webView = null
        }
    }
}
