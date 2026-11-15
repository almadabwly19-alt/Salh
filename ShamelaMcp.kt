package com.bahith.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

private const val DEFAULT_SHAMELA_MCP = "https://mcp.shamela.ws"

/** Minimal JSON-RPC MCP client for the official Shamela Streamable HTTP endpoint. */
object ShamelaMcpClient {
    private val ids = AtomicInteger(1)

    private fun post(context: Context, endpoint: String, payload: JSONObject, timeoutMs: Int): JSONObject {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("User-Agent", "BahithPDFProMax/2.1 (Android)")
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("خادم الشاملة أعاد HTTP $code: ${raw.take(500)}")
            val jsonText = if (raw.contains("data:")) {
                raw.lineSequence().filter { it.trimStart().startsWith("data:") }
                    .map { it.substringAfter("data:").trim() }
                    .lastOrNull { it.startsWith("{") }.orEmpty()
            } else raw
            if (jsonText.isBlank()) throw IllegalStateException("استجابة الخادم فارغة")
            return JSONObject(jsonText)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun rpc(context: Context, endpoint: String, method: String, params: JSONObject = JSONObject(), timeoutMs: Int = 25000): JSONObject =
        withContext(Dispatchers.IO) {
            val id = ids.getAndIncrement()
            val payload = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
            val response = post(context, endpoint, payload, timeoutMs)
            response.optJSONObject("error")?.let { err ->
                throw IllegalStateException(err.optString("message", "خطأ MCP") + " (" + err.opt("code") + ")")
            }
            response.optJSONObject("result") ?: throw IllegalStateException("استجابة MCP لا تحتوي result")
        }

    suspend fun tools(context: Context, endpoint: String, timeoutMs: Int): List<JSONObject> {
        val result = rpc(context, endpoint, "tools/list", timeoutMs = timeoutMs)
        val arr = result.optJSONArray("tools") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    suspend fun callTool(context: Context, endpoint: String, name: String, args: JSONObject, timeoutMs: Int): JSONObject {
        val params = JSONObject().put("name", name).put("arguments", args)
        val result = rpc(context, endpoint, "tools/call", params, timeoutMs)
        if (result.optBoolean("isError")) {
            val content = result.optJSONArray("content")
            val detail = if (content != null && content.length() > 0) content.optJSONObject(0)?.optString("text") else null
            throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "أبلغ خادم MCP عن فشل الأداة $name")
        }
        return result
    }
}

object ShamelaOfflineStore {
    private fun folder(context: Context) = File(context.filesDir, "shamela-offline").apply { mkdirs() }
    fun save(context: Context, title: String, json: JSONObject): File {
        val safe = title.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(55).ifBlank { "page" }
        val f = File(folder(context), "${System.currentTimeMillis()}_$safe.json")
        f.writeText(json.toString(2), Charsets.UTF_8)
        list(context).drop(300).forEach { it.delete() }
        return f
    }
    fun list(context: Context): List<File> = folder(context).listFiles()?.filter { it.isFile && it.extension == "json" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    fun read(file: File): String = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
    fun deleteAll(context: Context) { folder(context).deleteRecursively() }
    fun size(context: Context): Long = list(context).sumOf { it.length() }
}

private fun compactJson(value: Any?): String = when (value) {
    is JSONObject -> value.toString(2)
    is JSONArray -> value.toString(2)
    null -> ""
    else -> value.toString()
}

private fun extractDisplayPayload(result: JSONObject): JSONObject {
    result.optJSONObject("structuredContent")?.let { return it }
    val content = result.optJSONArray("content") ?: return result
    for (i in 0 until content.length()) {
        val item = content.optJSONObject(i) ?: continue
        val text = item.optString("text")
        if (text.isNotBlank()) {
            val parsed = runCatching { JSONObject(text) }.getOrNull()
            if (parsed != null) return parsed
            return JSONObject().put("text", text)
        }
    }
    return result
}

private fun findArray(value: JSONObject): JSONArray? {
    val preferred = listOf("results", "items", "matches", "books", "pages", "hits", "passages")
    for (key in preferred) value.optJSONArray(key)?.let { return it }
    val keys = value.keys()
    while (keys.hasNext()) {
        val v = value.opt(keys.next())
        if (v is JSONArray && v.length() > 0 && v.opt(0) is JSONObject) return v
    }
    return null
}

private fun resultTitle(o: JSONObject): String = listOf("title", "book", "name", "work", "book_title")
    .firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() && it != "null" } }
    ?: "نتيجة من المكتبة الشاملة"

private fun flattenTextFields(value: JSONObject, prefix: String = ""): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    val keys = value.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        when (val v = value.opt(key)) {
            is String -> if (v.isNotBlank() && v != "null") out.add((if (prefix.isBlank()) key else "$prefix • $key") to v)
            is JSONObject -> out.addAll(flattenTextFields(v, if (prefix.isBlank()) key else "$prefix • $key"))
            is JSONArray -> {
                for (i in 0 until v.length()) {
                    val child = v.optJSONObject(i)
                    if (child != null) out.addAll(flattenTextFields(child, "$key ${i + 1}"))
                    else v.optString(i).takeIf { it.isNotBlank() && it != "null" }?.let { out.add("$key" to it) }
                }
            }
        }
    }
    return out
}

private fun schemaArguments(tool: JSONObject, query: String, limit: Int): JSONObject {
    val schema = tool.optJSONObject("inputSchema") ?: JSONObject()
    val props = schema.optJSONObject("properties") ?: JSONObject()
    val args = JSONObject()
    val keys = props.keys().asSequence().toList()
    for (key in keys) {
        val p = props.optJSONObject(key) ?: continue
        val normalized = key.lowercase().replace("_", "").replace("-", "")
        when {
            normalized in listOf("query", "q", "search", "term", "text", "keyword", "phrase", "keywords") -> args.put(key, query)
            normalized in listOf("limit", "maxresults", "count", "topk", "page_size", "pagesize") -> args.put(key, limit)
            normalized in listOf("offset", "page") -> if (p.optString("type") == "integer") args.put(key, 0)
            p.optJSONArray("enum")?.let { it.length() > 0 } == true -> args.put(key, p.optJSONArray("enum")!!.opt(0))
        }
    }
    if (args.length() == 0) {
        val fallback = keys.firstOrNull { key -> props.optJSONObject(key)?.optString("type") == "string" }
        if (fallback != null) args.put(fallback, query)
    }
    val required = schema.optJSONArray("required") ?: JSONArray()
    for (i in 0 until required.length()) {
        val key = required.optString(i)
        if (!args.has(key)) {
            val p = props.optJSONObject(key) ?: continue
            val enum = p.optJSONArray("enum")
            when {
                enum != null && enum.length() > 0 -> args.put(key, enum.opt(0))
                p.optString("type") == "string" -> args.put(key, query)
                p.optString("type") == "integer" || p.optString("type") == "number" -> args.put(key, 10)
                p.optString("type") == "boolean" -> args.put(key, false)
            }
        }
    }
    return args
}

@Composable
fun ShamelaMcpScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("shamela_mcp", Context.MODE_PRIVATE) }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", DEFAULT_SHAMELA_MCP) ?: DEFAULT_SHAMELA_MCP) }
    var query by remember { mutableStateOf("") }
    var tools by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var payload by remember { mutableStateOf<JSONObject?>(null) }
    var selectedTool by remember { mutableStateOf<JSONObject?>(null) }
    var selectedRaw by remember { mutableStateOf("") }
    var activeTab by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("لم يتم الاتصال بعد") }
    var cacheCount by remember { mutableIntStateOf(ShamelaOfflineStore.list(context).size) }
    var showSettings by remember { mutableStateOf(false) }
    var showToolCall by remember { mutableStateOf(false) }
    var timeout by remember { mutableIntStateOf(prefs.getInt("timeout", 25000)) }
    var resultLimit by remember { mutableIntStateOf(prefs.getInt("limit", 20)) }
    var autoCache by remember { mutableStateOf(prefs.getBoolean("autoCache", true)) }
    var saveSearches by remember { mutableStateOf(prefs.getBoolean("saveSearches", true)) }
    var offlineOnly by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun persistSettings() {
        prefs.edit().putString("endpoint", endpoint.trim()).putInt("timeout", timeout)
            .putInt("limit", resultLimit).putBoolean("autoCache", autoCache)
            .putBoolean("saveSearches", saveSearches).apply()
    }

    fun connect() {
        scope.launch {
            busy = true; error = null; status = "جارٍ الاتصال بخادم الشاملة…"
            try {
                tools = ShamelaMcpClient.tools(context, endpoint.trim(), timeout)
                status = "متصل • ${tools.size} أدوات متاحة"
            } catch (e: Exception) {
                status = "غير متصل"
                error = e.message ?: "تعذّر الاتصال"
            } finally { busy = false }
        }
    }

    fun runSearch() {
        if (offlineOnly) { error = "وضع دون إنترنت مفعّل؛ افتح تبويب المحفوظات للبحث فيما حفظته."; return }
        if (query.trim().length < 2) { error = "اكتب عبارة بحث من حرفين على الأقل."; return }
        scope.launch {
            busy = true; error = null; activeTab = 0
            try {
                if (tools.isEmpty()) tools = ShamelaMcpClient.tools(context, endpoint.trim(), timeout)
                val tool = tools.firstOrNull { it.optString("name") == "shamela_find" }
                    ?: tools.firstOrNull { it.optString("name").contains("find", true) }
                    ?: throw IllegalStateException("لم يعرض الخادم أداة بحث باسم shamela_find")
                val args = schemaArguments(tool, query.trim(), resultLimit)
                val raw = ShamelaMcpClient.callTool(context, endpoint.trim(), tool.optString("name"), args, timeout)
                val parsed = extractDisplayPayload(raw)
                payload = parsed
                selectedTool = null
                if (saveSearches) ShamelaOfflineStore.save(context, "بحث_${query.take(24)}", parsed)
                cacheCount = ShamelaOfflineStore.list(context).size
                status = "اكتمل البحث عبر ${tool.optString("name")}"
            } catch (e: Exception) { error = e.message ?: "فشل البحث" }
            finally { busy = false }
        }
    }

    fun openResult(item: JSONObject) {
        val tool = tools.firstOrNull { it.optString("name") == "shamela_open" }
        if (tool == null) {
            selectedRaw = item.toString(2); showToolCall = true; return
        }
        // Fill only parameters declared by the server schema; unknown fields are never invented.
        val schema = tool.optJSONObject("inputSchema") ?: JSONObject()
        val props = schema.optJSONObject("properties") ?: JSONObject()
        val args = JSONObject()
        val keys = props.keys().asSequence().toList()
        for (key in keys) {
            val n = key.lowercase().replace("_", "").replace("-", "")
            val aliases = when (n) {
                "id", "resultid", "ref" -> listOf("id", "resultId", "ref")
                "bookid", "workid", "book" -> listOf("bookId", "book_id", "workId", "book")
                "pageid", "page", "pagekey", "pageno", "pagenumber" -> listOf("pageId", "page_id", "page", "pageNo", "page_no", "pageNumber")
                "url", "href", "sourceurl" -> listOf("url", "href", "sourceUrl")
                else -> listOf(key)
            }
            for (a in aliases) if (item.has(a)) { args.put(key, item.opt(a)); break }
        }
        val required = schema.optJSONArray("required") ?: JSONArray()
        var missing = false
        for (i in 0 until required.length()) if (!args.has(required.optString(i))) missing = true
        if (missing) {
            selectedTool = tool
            selectedRaw = args.toString(2)
            showToolCall = true
            return
        }
        scope.launch {
            busy = true; error = null
            try {
                val raw = ShamelaMcpClient.callTool(context, endpoint.trim(), tool.optString("name"), args, timeout)
                val parsed = extractDisplayPayload(raw)
                payload = parsed
                if (autoCache) ShamelaOfflineStore.save(context, resultTitle(item), parsed)
                cacheCount = ShamelaOfflineStore.list(context).size
                status = "تم فتح النص من الشاملة"
            } catch (e: Exception) { error = e.message ?: "تعذرت قراءة الصفحة" }
            finally { busy = false }
        }
    }

    LaunchedEffect(Unit) { connect() }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("المكتبة الشاملة — MCP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { showSettings = true }) { Text("⚙") }
            IconButton(onClick = { connect() }, enabled = !busy) { Text("↻") }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("ابحث في نصوص كتب الشاملة") }, placeholder = { Text("اكتب عبارة عربية…") }, singleLine = true
        )
        Button(onClick = { runSearch() }, enabled = !busy && query.trim().length >= 2, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "جارٍ التنفيذ…" else "بحث في نصوص الكتب")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = activeTab == 0, onClick = { activeTab = 0 }, label = { Text("النتائج") })
            FilterChip(selected = activeTab == 1, onClick = { activeTab = 1; cacheCount = ShamelaOfflineStore.list(context).size }, label = { Text("المحفوظ دون إنترنت ($cacheCount)") })
            FilterChip(selected = activeTab == 2, onClick = { activeTab = 2 }, label = { Text("أدوات MCP (${tools.size})") })
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        when (activeTab) {
            0 -> {
                val array = payload?.let { findArray(it) }
                if (payload == null) {
                    Text("اتصل بالخادم الرسمي ثم ابحث. ستظهر هنا النصوص وبيانات الكتب التي يعيدها الخادم.", style = MaterialTheme.typography.bodyMedium)
                } else if (array != null && array.length() > 0) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items((0 until array.length()).mapNotNull { array.optJSONObject(it) }, key = { it.toString().hashCode() }) { item ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(resultTitle(item), fontWeight = FontWeight.Bold)
                                    val snippet = listOf("snippet", "preview", "text", "excerpt", "content").firstNotNullOfOrNull { k -> item.optString(k).takeIf { it.isNotBlank() && it != "null" } }
                                    if (!snippet.isNullOrBlank()) Text(snippet.take(1200), style = MaterialTheme.typography.bodyMedium)
                                    val meta = item.toString(2)
                                    Text(meta.take(1200), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { openResult(item) }, enabled = !busy) { Text("فتح النص / الصفحة") }
                                        TextButton(onClick = {
                                            ShamelaOfflineStore.save(context, resultTitle(item), item); cacheCount = ShamelaOfflineStore.list(context).size
                                        }) { Text("حفظ دون إنترنت") }
                                        val url = listOf("url", "sourceUrl", "href", "link").firstNotNullOfOrNull { k -> item.optString(k).takeIf { it.startsWith("http") } }
                                        if (url != null) TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) { Text("المصدر") }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("نص الكتاب وبيانات الصفحة", fontWeight = FontWeight.Bold)
                        val fields = payload?.let { flattenTextFields(it) }.orEmpty()
                        if (fields.isEmpty()) Text(payload?.toString(2) ?: "", style = MaterialTheme.typography.bodySmall)
                        else fields.forEach { (label, value) ->
                            Text(label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                            HorizontalDivider()
                        }
                        TextButton(onClick = { payload?.let { ShamelaOfflineStore.save(context, "صفحة_الشاملة", it); cacheCount = ShamelaOfflineStore.list(context).size } }) { Text("حفظ الصفحة ومراجعها دون إنترنت") }
                        TextButton(onClick = { payload?.let { selectedRaw = it.toString(2); selectedTool = tools.firstOrNull { t -> t.optString("name") == "shamela_open_many" }; showToolCall = selectedTool != null } }) { Text("فتح أداة قراءة صفحات متعددة") }
                    }
                }
            }
            1 -> {
                val files = remember(cacheCount) { ShamelaOfflineStore.list(context) }
                if (files.isEmpty()) Text("لا توجد صفحات محفوظة بعد. ابحث عن كتاب ثم احفظ النتيجة أو افتح الصفحة واحفظها.")
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files, key = { it.name }) { f ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(f.name.substringAfter('_').removeSuffix(".json"), fontWeight = FontWeight.Bold)
                                Text("${f.length() / 1024} كيلوبايت • محفوظ محلياً", style = MaterialTheme.typography.labelSmall)
                                Row {
                                    TextButton(onClick = { payload = runCatching { JSONObject(ShamelaOfflineStore.read(f)) }.getOrNull(); activeTab = 0 }) { Text("عرض") }
                                    TextButton(onClick = { f.delete(); cacheCount = ShamelaOfflineStore.list(context).size }) { Text("حذف") }
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(tools, key = { it.optString("name") }) { t ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(t.optString("name"), fontWeight = FontWeight.Bold)
                                Text(t.optString("description"), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    selectedTool = t
                                    selectedRaw = "{}"
                                    showToolCall = true
                                }) { Text("استدعاء الأداة يدوياً") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) AlertDialog(
        onDismissRequest = { showSettings = false },
        title = { Text("إعدادات الشاملة المتقدمة") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("عنوان خادم MCP") }, singleLine = true)
                Text("المعتمد افتراضياً: $DEFAULT_SHAMELA_MCP", style = MaterialTheme.typography.labelSmall)
                Text("مهلة الاتصال: ${timeout / 1000} ثانية")
                Slider(value = timeout.toFloat(), onValueChange = { timeout = it.toInt() }, valueRange = 10000f..60000f, steps = 9)
                Text("عدد النتائج المطلوب: $resultLimit")
                Slider(value = resultLimit.toFloat(), onValueChange = { resultLimit = it.toInt() }, valueRange = 5f..50f, steps = 8)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("حفظ الصفحات المفتوحة تلقائياً", Modifier.weight(1f)); Switch(autoCache, { autoCache = it }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("حفظ عمليات البحث محلياً", Modifier.weight(1f)); Switch(saveSearches, { saveSearches = it }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("وضع دون إنترنت (منع طلبات الشبكة)", Modifier.weight(1f)); Switch(offlineOnly, { offlineOnly = it }) }
                Text("المحفوظ محلياً: ${ShamelaOfflineStore.list(context).size} عنصر • ${ShamelaOfflineStore.size(context) / 1024} كيلوبايت")
                TextButton(onClick = { ShamelaOfflineStore.deleteAll(context); cacheCount = 0 }) { Text("حذف جميع المحفوظات المحلية") }
            }
        },
        confirmButton = { TextButton(onClick = { persistSettings(); showSettings = false; if (!offlineOnly) connect() }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = { showSettings = false }) { Text("إلغاء") } }
    )

    if (showToolCall) AlertDialog(
        onDismissRequest = { showToolCall = false },
        title = { Text("استدعاء أداة MCP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(selectedTool?.optString("name") ?: "اختر أداة", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = selectedRaw, onValueChange = { selectedRaw = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 300.dp),
                    label = { Text("وسائط JSON حسب مخطط الأداة") }
                )
                Text("يمكنك تعديل الوسائط وفق المخطط الذي يعلنه الخادم. لا يضيف التطبيق أسماء حقول غير معروفة.", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val tool = selectedTool
                val args = runCatching { JSONObject(selectedRaw) }.getOrNull()
                if (tool == null || args == null) { error = "تحقق من صيغة JSON"; return@TextButton }
                showToolCall = false
                scope.launch {
                    busy = true; error = null
                    try {
                        val raw = ShamelaMcpClient.callTool(context, endpoint.trim(), tool.optString("name"), args, timeout)
                        payload = extractDisplayPayload(raw); activeTab = 0
                        if (autoCache) ShamelaOfflineStore.save(context, tool.optString("name"), payload!!)
                        cacheCount = ShamelaOfflineStore.list(context).size
                    } catch (e: Exception) { error = e.message ?: "فشل استدعاء الأداة" }
                    finally { busy = false }
                }
            }) { Text("تنفيذ") }
        },
        dismissButton = { TextButton(onClick = { showToolCall = false }) { Text("إلغاء") } }
    )
}
