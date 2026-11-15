package com.bahith.pdf

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** نتيجة من فهرس الكتب المفتوحة في Internet Archive. */
data class CatalogItem(
    val identifier: String,
    val title: String,
    val creator: String,
    val year: String,
    val downloads: String
)

/**
 * موصل أولي لفهرس Internet Archive العام. لا يفترض أن كل عنصر مجاني لإعادة النشر؛
 * يجب على المستخدم مراجعة صفحة المصدر وبيانات الحقوق قبل الاستخدام أو إعادة التوزيع.
 */
object ArchiveCatalog {
    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "BahithPDF/1.6 (Android; public catalog search)")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return body
        } finally {
            connection.disconnect()
        }
    }

    suspend fun search(query: String): List<CatalogItem> = withContext(Dispatchers.IO) {
        val encoded = Uri.encode(query)
        val url = "https://archive.org/advancedsearch.php?q=$encoded%20AND%20mediatype%3Atexts&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=year&fl%5B%5D=downloads&rows=30&page=1&output=json"
        val root = JSONObject(get(url))
        val docs = root.optJSONObject("response")?.optJSONArray("docs") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until docs.length()) {
                val d = docs.optJSONObject(i) ?: continue
                val id = d.optString("identifier").trim()
                val title = d.optString("title").trim()
                if (id.isBlank() || title.isBlank()) continue
                add(CatalogItem(
                    identifier = id,
                    title = title,
                    creator = d.optString("creator").takeUnless { it == "null" } ?: "",
                    year = d.optString("year").takeUnless { it == "null" } ?: "",
                    downloads = d.optString("downloads").takeUnless { it == "null" } ?: ""
                ))
            }
        }
    }

    suspend fun downloadPdf(item: CatalogItem, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val metadata = JSONObject(get("https://archive.org/metadata/${Uri.encode(item.identifier)}"))
        val files = metadata.optJSONArray("files") ?: throw IllegalStateException("لا توجد قائمة ملفات للمصدر")
        var pdfName: String? = null
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name")
            val format = f.optString("format")
            if (name.endsWith(".pdf", true) || format.equals("pdf", true)) {
                pdfName = name
                break
            }
        }
        val chosen = pdfName ?: throw IllegalStateException("هذا السجل لا يوفر ملف PDF قابلاً للتنزيل")
        val encodedName = chosen.split("/").joinToString("/") { Uri.encode(it) }
        val downloadUrl = "https://archive.org/download/${Uri.encode(item.identifier)}/$encodedName"
        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            setRequestProperty("User-Agent", "BahithPDF/1.6 (Android)")
        }
        val out = File.createTempFile("bahith_catalog_", ".part")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("تعذّر جلب ملف PDF: HTTP $code")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                out.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var count: Int
                    var received = 0L
                    while (input.read(buffer).also { count = it } >= 0) {
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        received += count
                        if (total > 0) onProgress(((received * 100) / total).toInt().coerceIn(0, 99))
                    }
                }
            }
            if (out.length() < 1000L) throw IllegalStateException("الملف المستلم صغير أو غير صالح")
            onProgress(100)
            out
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }
}

@Composable
fun PublicLibraryScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("المكتبة العامة", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "هذا البحث يستعلم عن بيانات الكتب العامة (العنوان والمؤلف والبيانات الوصفية)، وليس نص صفحات جميع الكتب. بعد تنزيل كتاب PDF وفهرسته يصبح البحث في نصه متاحاً محلياً، مع تحديد مواضع الكلمات على صفحاته عند توفر إحداثيات النص أو OCR.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = vm.catalogQuery,
            onValueChange = { vm.catalogQuery = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("عنوان الكتاب أو المؤلف أو الموضوع") },
            singleLine = true
        )
        Button(
            onClick = { vm.searchCatalog() },
            enabled = !vm.catalogLoading && vm.catalogQuery.trim().length >= 2,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text("البحث في المكتبة العامة") }
        if (vm.catalogLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        if (vm.downloadingId != null) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("جارٍ تنزيل الكتاب… ${vm.downloadProgress}%")
                LinearProgressIndicator(
                    progress = { vm.downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        vm.catalogError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(vm.catalogResults, key = { it.identifier }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        if (item.creator.isNotBlank()) Text("المؤلف: ${item.creator}", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (item.year.isNotBlank()) Text("السنة: ${item.year}  ", style = MaterialTheme.typography.labelSmall)
                            if (item.downloads.isNotBlank()) Text("التنزيلات: ${item.downloads}", style = MaterialTheme.typography.labelSmall)
                        }
                        Text("المصدر: Internet Archive • راجع حقوق الكتاب قبل إعادة نشره.", style = MaterialTheme.typography.labelSmall)
                        TextButton(
                            onClick = { vm.downloadCatalogBook(item) },
                            enabled = vm.downloadingId == null
                        ) { Text("تنزيل PDF وإضافته إلى مكتبتي") }
                    }
                }
            }
        }
        Text(
            "تنبيه: توفر الكتاب في الفهرس لا يعني بالضرورة أن حقوق إعادة نشره متاحة. بعد التنزيل يمكنك البحث فيه دون إنترنت، أما تصفح الفهرس والتنزيل فيحتاجان إلى اتصال.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
