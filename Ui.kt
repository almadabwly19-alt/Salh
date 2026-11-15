@file:OptIn(ExperimentalMaterial3Api::class)

package com.bahith.pdf

import android.graphics.Bitmap
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

val MARK_COLORS = listOf(Color(0xFFFFD54F), Color(0xFFA5D6A7), Color(0xFFF48FB1), Color(0xFF90CAF9))

fun markColor(i: Int): Color = MARK_COLORS[i.coerceIn(0, MARK_COLORS.size - 1)]

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    // الهوية البصرية الموحدة: أزرق داكن مع لمسات ذهبية.
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFFD5A84B),
            onPrimary = Color(0xFF10233D),
            primaryContainer = Color(0xFF1B3555),
            onPrimaryContainer = Color(0xFFF4D99A),
            secondary = Color(0xFF9DBCE0),
            onSecondary = Color(0xFF10233D),
            secondaryContainer = Color(0xFF203B5D),
            onSecondaryContainer = Color(0xFFDCE9F8),
            tertiary = Color(0xFFD5A84B),
            onTertiary = Color(0xFF10233D),
            background = Color(0xFF081421),
            onBackground = Color(0xFFE8EFF8),
            surface = Color(0xFF0D1B2A),
            onSurface = Color(0xFFE8EFF8),
            surfaceVariant = Color(0xFF1A2B40),
            onSurfaceVariant = Color(0xFFC0CDDC),
            outline = Color(0xFF647A94)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0B1F3A),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDCE7F4),
            onPrimaryContainer = Color(0xFF102B4C),
            secondary = Color(0xFF31577F),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE5EEF8),
            onSecondaryContainer = Color(0xFF173653),
            tertiary = Color(0xFF9A7025),
            onTertiary = Color.White,
            background = Color(0xFFF4F7FB),
            onBackground = Color(0xFF172638),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF172638),
            surfaceVariant = Color(0xFFEAF0F7),
            onSurfaceVariant = Color(0xFF46596F),
            outline = Color(0xFF8799AD)
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val dark = when (vm.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    AppTheme(dark) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            val books by vm.books.collectAsStateWithLifecycle()
            val view = LocalView.current
            val busy = books.any {
                it.status == ST_QUEUED || it.status == ST_INDEXING || it.status == ST_REFINING
            }
            val reading = vm.reader != null
            LaunchedEffect(busy, vm.keepAwake, reading, vm.keepAwakeRead) {
                view.keepScreenOn = (busy && vm.keepAwake) || (reading && vm.keepAwakeRead)
            }

            val notifLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33) {
                    notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments()
            ) { uris -> vm.importUris(uris) }
            val treePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri -> if (uri != null) vm.importTree(uri) }
            val target = vm.reader
            BackHandler(enabled = target != null) { vm.reader = null }

            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                if (target != null) {
                    ReaderScreen(vm, target)
                } else {
                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = vm.tab == 0,
                                    onClick = { vm.tab = 0 },
                                    icon = { Icon(Icons.Default.Home, null) },
                                    label = { Text("المكتبة") }
                                )
                                NavigationBarItem(
                                    selected = vm.tab == 1,
                                    onClick = { vm.tab = 1 },
                                    icon = { Icon(Icons.Default.Search, null) },
                                    label = { Text("البحث") }
                                )
                                NavigationBarItem(
                                    selected = vm.tab == 4,
                                    onClick = { vm.tab = 4 },
                                    icon = { Text("ش", fontWeight = FontWeight.Bold) },
                                    label = { Text("الشاملة") }
                                )
                                NavigationBarItem(
                                    selected = vm.tab == 3,
                                    onClick = { vm.tab = 3 },
                                    icon = { Icon(Icons.Default.Share, null) },
                                    label = { Text("العامة") }
                                )
                                NavigationBarItem(
                                    selected = vm.tab == 2,
                                    onClick = { vm.tab = 2 },
                                    icon = { Icon(Icons.Default.Settings, null) },
                                    label = { Text("الإعدادات") }
                                )
                            }
                        }
                    ) { pad ->
                        Box(Modifier.padding(pad)) {
                            when (vm.tab) {
                                0 -> LibraryScreen(
                                    vm,
                                    onAdd = { picker.launch(arrayOf("*/*")) },
                                    onAddFolder = { treePicker.launch(null) }
                                )
                                1 -> SearchScreen(vm)
                                3 -> PublicLibraryScreen(vm)
                                4 -> ShamelaMcpScreen()
                                else -> SettingsScreen(vm)
                            }
                        }
                    }
                }
            }

            vm.message?.let { m ->
                AlertDialog(
                    onDismissRequest = { vm.message = null },
                    confirmButton = { TextButton(onClick = { vm.message = null }) { Text("حسناً") } },
                    text = { Text(m) }
                )
            }
        }
    }
}

// ======================= المكتبة =======================

@Composable
fun LibraryScreen(vm: AppViewModel, onAdd: () -> Unit, onAddFolder: () -> Unit) {
    val books by vm.books.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<BookEntity?>(null) }
    var deleting by remember { mutableStateOf<BookEntity?>(null) }
    val shown = remember(books, vm.libraryFilter, vm.sortOrder, vm.libFavOnly) {
        arrangeBooks(books, vm.libraryFilter, vm.sortOrder).filter { !vm.libFavOnly || it.favorite }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("باحث PDF") },
                actions = { TextButton(onClick = onAddFolder) { Text("إضافة مجلد") } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("إضافة كتاب") }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.importTotal > 0) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LinearProgressIndicator(
                        progress = { vm.importDone.toFloat() / vm.importTotal },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "جارٍ إضافة الكتب… ${vm.importDone} / ${vm.importTotal}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            val busyNow = books.any {
                it.status == ST_QUEUED || it.status == ST_INDEXING || it.status == ST_REFINING
            }
            if (busyNow) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (Work.paused) "الفهرسة متوقفة مؤقتاً" else "الفهرسة جارية…",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium
                    )
                    TextButton(onClick = { vm.togglePause() }) {
                        Text(if (Work.paused) "استئناف" else "إيقاف مؤقت")
                    }
                }
            }
            if (books.isNotEmpty()) {
                OutlinedTextField(
                    value = vm.libraryFilter,
                    onValueChange = { vm.libraryFilter = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text("ابحث في عناوين الكتب والمؤلفين") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (vm.libraryFilter.isNotEmpty()) {
                            IconButton(onClick = { vm.libraryFilter = "" }) {
                                Icon(Icons.Default.Close, "مسح")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp)
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${books.size} كتاب", style = MaterialTheme.typography.labelLarge)
                    FilterChip(
                        selected = vm.libFavOnly,
                        onClick = { vm.libFavOnly = !vm.libFavOnly },
                        label = { Text("المفضلة فقط") }
                    )
                    listOf("الأحدث", "الاسم", "المفضلة أولاً").forEachIndexed { i, s ->
                        FilterChip(
                            selected = vm.sortOrder == i,
                            onClick = { vm.updateSort(i) },
                            label = { Text(s) }
                        )
                    }
                }
            }
            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "مكتبتك فارغة.\nاضغط «إضافة كتاب» لاختيار ملفات PDF من هاتفك.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("لا توجد كتب مطابقة", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(shown, key = { it.id }) { b ->
                        BookCard(b, vm, onEdit = { editing = b }, onDelete = { deleting = b })
                    }
                }
            }
        }
    }

    editing?.let { b ->
        EditBookDialog(
            book = b,
            onDismiss = { editing = null },
            onOk = { t, a -> vm.editBook(b.id, t, a); editing = null }
        )
    }
    deleting?.let { b ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("حذف الكتاب") },
            text = { Text("سيُحذف «${b.title}» وفهرسته من التطبيق نهائياً.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(b.id); deleting = null }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun BookCard(b: BookEntity, vm: AppViewModel, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = {
            if (b.pageCount > 0) {
                vm.reader = ReaderTarget(b.id, b.lastPage.coerceIn(1, b.pageCount), emptyList())
            }
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    b.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (b.author.isNotBlank()) {
                    Text(
                        b.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                val er = Work.errors[b.id]
                if (er != null && b.status != ST_QUEUED && b.status != ST_INDEXING) {
                    Text(
                        er,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3
                    )
                }
                when (b.status) {
                    ST_DONE -> {
                        val resume = if (b.lastPage > 1) " • آخر قراءة: صفحة ${b.lastPage}" else ""
                        Text(
                            "${b.pageCount} صفحة • جاهز للبحث$resume",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    ST_REFINING -> {
                        val left = Work.refineLeft[b.id]
                        val sl = Work.refineStartLeft[b.id]
                        val st = Work.refineStartMs[b.id]
                        val eta = if (left != null && sl != null && st != null && sl - left > 0) {
                            val per = (System.currentTimeMillis() - st).toDouble() / (sl - left)
                            " • نحو ${(per * left / 60000.0).toInt() + 1} د"
                        } else ""
                        val frac = if (left != null && b.pageCount > 0) 1f - left.toFloat() / b.pageCount else 0f
                        Text(
                            "${b.pageCount} صفحة • جاهز للبحث • يُحسَّن النص بالـ OCR" +
                                (if (left != null) " (متبقي $left$eta)" else ""),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                    }
                    ST_ERROR -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "تعذّرت الفهرسة",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                        TextButton(onClick = { vm.startIndex(b.id) }) { Text("إعادة المحاولة") }
                    }
                    else -> {
                        val frac = if (b.pageCount > 0) b.indexedPages.toFloat() / b.pageCount else 0f
                        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "جارٍ الفهرسة ${b.indexedPages} / ${b.pageCount}",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            IconButton(onClick = { vm.toggleFavorite(b) }) {
                Icon(
                    if (b.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    "مفضلة",
                    tint = if (b.favorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "خيارات") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("تعديل العنوان والمؤلف") },
                        onClick = { menu = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("إعادة الفهرسة") },
                        onClick = { menu = false; vm.startIndex(b.id, restart = true) }
                    )
                    DropdownMenuItem(
                        text = { Text("إعادة الفهرسة بالـ OCR") },
                        onClick = { menu = false; vm.startIndex(b.id, forceOcr = true) }
                    )
                    DropdownMenuItem(
                        text = { Text("حذف") },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
fun EditBookDialog(book: BookEntity, onDismiss: () -> Unit, onOk: (String, String) -> Unit) {
    var title by remember { mutableStateOf(book.title) }
    var author by remember { mutableStateOf(book.author) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل بيانات الكتاب") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("العنوان") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("المؤلف (اختياري)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (title.isNotBlank()) onOk(title.trim(), author) }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

// ======================= البحث =======================

@Composable
fun SearchScreen(vm: AppViewModel) {
    val books by vm.books.collectAsStateWithLifecycle()
    var showScope by remember { mutableStateOf(false) }

    LaunchedEffect(vm.query) {
        if (vm.liveSearch || vm.query.isBlank()) {
            delay(250)
            vm.search()
        }
    }
    LaunchedEffect(vm.mode, vm.scopeBookId, vm.resultLimit, vm.snippetLen) {
        if (vm.query.isNotBlank()) vm.search()
    }

    val scopeName = if (vm.scopeBookId == 0L) "كل الكتب"
    else books.firstOrNull { it.id == vm.scopeBookId }?.title ?: "كل الكتب"

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("البحث في المكتبة") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("اكتب كلمة أو جملة أو فقرة…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.query = ""; vm.search() }) { Icon(Icons.Default.Close, "مسح") }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    vm.search()
                    vm.addHistory(vm.query)
                })
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchMode.values().forEach { m ->
                    FilterChip(
                        selected = vm.mode == m,
                        onClick = { vm.mode = m },
                        label = { Text(m.label) }
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("النطاق:", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { showScope = true }) {
                    Text(scopeName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            val res = vm.results
            when {
                vm.searching && res.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                res.isEmpty() && vm.searchedOnce -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("لا توجد نتائج", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                res.isEmpty() && vm.history.isNotEmpty() && vm.query.isBlank() -> HistoryPane(vm)
                res.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                    Text(
                        "ابحث بكلمة أو عبارة. للنص الطويل اختر «عبارة».\nالبحث يتجاهل التشكيل ويوحّد الهمزات.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    val grouped = res.groupBy { it.bookId }
                    var collapsed by remember(res) { mutableStateOf(setOf<Long>()) }
                    val cap = if (res.size >= vm.resultLimit) "+" else ""
                    Text(
                        "${res.size}$cap نتيجة في ${grouped.size} كتاب",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        grouped.forEach { (bid, hits) ->
                            val open = !collapsed.contains(bid)
                            item {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { collapsed = if (open) collapsed + bid else collapsed - bid }
                                        .padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        hits.first().title + "  (" + hits.size + ")",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(if (open) "▾" else "◂", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (open) {
                                items(hits) { h ->
                                    if (vm.resultStyle == 0) {
                                        HitCardImage(vm, h) { vm.openHit(h) }
                                    } else {
                                        HitCard(h, markColor(vm.highlightIdx)) { vm.openHit(h) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showScope) {
        AlertDialog(
            onDismissRequest = { showScope = false },
            title = { Text("نطاق البحث") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ScopeRow("كل الكتب") { vm.scopeBookId = 0L; showScope = false }
                    books.filter { it.indexedPages > 0 }.forEach { b ->
                        ScopeRow(b.title) { vm.scopeBookId = b.id; showScope = false }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showScope = false }) { Text("إغلاق") } }
        )
    }
}

@Composable
fun HistoryPane(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("عمليات بحث سابقة", fontWeight = FontWeight.Bold)
            TextButton(onClick = { vm.clearHistory() }) { Text("مسح") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.history) { q -> ScopeRow(q) { vm.query = q } }
        }
    }
}

@Composable
fun ScopeRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

fun highlighted(text: String, ranges: List<IntRange>, color: Color = MARK_COLORS[0]): AnnotatedString = buildAnnotatedString {
    append(text)
    for (r in ranges) {
        val s = r.first.coerceIn(0, text.length)
        val e = (r.last + 1).coerceIn(s, text.length)
        if (e > s) addStyle(SpanStyle(background = color, color = Color.Black), s, e)
    }
}

@Composable
fun HitCard(h: Hit, color: Color, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "صفحة ${h.pageNo}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                highlighted(h.snippet, h.ranges, color),
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 24.sp
            )
        }
    }
}

class Crop(val bmp: Bitmap, val rects: List<WordBox>)

/** مقطع من صفحة الكتاب نفسه حول أول موضع للنتيجة، مع مستطيلات التظليل. */
suspend fun makeCrop(ctx: Context, h: Hit, terms: List<String>, band: Int): Crop? {
    val boxes = WordStore.load(ctx, h.bookId, h.pageNo)
    if (boxes.isEmpty()) return null
    val hits = matchBoxes(boxes, terms)
    if (hits.isEmpty()) return null
    val bmp = PageCache.page(ctx, h.bookId, h.pageNo - 1, 1100) ?: return null
    val first = hits[0]
    val i = band.coerceIn(0, 2)
    val before = floatArrayOf(0.035f, 0.06f, 0.10f)[i]
    val after = floatArrayOf(0.05f, 0.09f, 0.15f)[i]
    val top = (first.y - before).coerceAtLeast(0f)
    val bottom = (first.y + first.hh + after).coerceAtMost(1f)
    val span = (bottom - top).coerceAtLeast(0.05f)
    val t = (top * bmp.height).toInt().coerceIn(0, bmp.height - 2)
    val hpx = (span * bmp.height).toInt().coerceIn(2, bmp.height - t)
    val crop = Bitmap.createBitmap(bmp, 0, t, bmp.width, hpx)
    val real = hpx.toFloat() / bmp.height
    val rects = hits.filter { it.y + it.hh >= top && it.y <= top + real }
        .map { WordBox(it.w, it.x, (it.y - top) / real, it.ww, it.hh / real) }
    return Crop(crop, rects)
}

@Composable
fun HitCardImage(vm: AppViewModel, h: Hit, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val terms = vm.hlTerms
    val crop by produceState<Crop?>(null, h.bookId, h.pageNo, terms, vm.cropBand) {
        value = withContext(Dispatchers.IO) { makeCrop(ctx, h, terms, vm.cropBand) }
    }
    val color = markColor(vm.highlightIdx)
    val c = crop
    if (c == null) {
        HitCard(h, color, onClick)
        return
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "صفحة ${h.pageNo}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(c.bmp.width.toFloat() / c.bmp.height)) {
                Image(
                    bitmap = c.bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
                Canvas(Modifier.fillMaxSize()) {
                    for (r in c.rects) {
                        drawRect(
                            color = color.copy(alpha = vm.hlAlpha),
                            topLeft = Offset(r.x * size.width, r.y * size.height),
                            size = Size(r.ww * size.width, r.hh * size.height)
                        )
                    }
                }
            }
        }
    }
}

// ======================= الإعدادات =======================

@Composable
fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { i, s ->
            FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(s) })
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val books by vm.books.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmReindex by remember { mutableStateOf(false) }
    val storage by produceState("…", books.size) { value = formatSize(vm.storageBytes()) }
    val limits = listOf(100, 400, 1000)
    val minOpts = listOf(2, 3, 5, 8)
    val sugOpts = listOf(3, 8, 15, 30)
    val maxOpts = listOf(300_000, 600_000, 1_000_000, 1_500_000)
    val lexPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importLexicon(uri) }
    val refPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importReferenceText(uri) }

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("الإعدادات") }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsCard("المظهر") {
                ChipRow(listOf("حسب النظام", "فاتح", "داكن"), vm.themeMode) { vm.updateTheme(it) }
                Text("لون تظليل نتائج البحث")
                ChipRow(listOf("أصفر", "أخضر", "وردي", "أزرق"), vm.highlightIdx) { vm.updateHighlight(it) }
                Text("حجم خط القراءة (عرض النص): ${vm.readerFont.toInt()}")
                Slider(
                    value = vm.readerFont,
                    onValueChange = { vm.readerFont = it },
                    onValueChangeFinished = { vm.saveFont() },
                    valueRange = 16f..34f
                )
                Text("تباعد الأسطر: ${"%.1f".format(vm.lineSpacing)}")
                Slider(
                    value = vm.lineSpacing,
                    onValueChange = { vm.lineSpacing = it },
                    onValueChangeFinished = { vm.saveReader() },
                    valueRange = 1.2f..2.4f
                )
                Text("نوع الخط")
                ChipRow(listOf("نسخ", "عادي"), vm.fontStyle) { vm.updateFontStyle(it) }
                SwitchRow("إخفاء التشكيل في عرض النص", vm.hideTashkeel) { vm.updateHideTashkeel(it) }
                SwitchRow("ضبط الفقرات (محاذاة الجانبين)", vm.justify) { vm.updateJustify(it) }
                Text(
                    "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ",
                    fontSize = vm.readerFont.sp,
                    fontFamily = if (vm.fontStyle == 0) FontFamily.Serif else FontFamily.SansSerif,
                    textAlign = if (vm.justify) TextAlign.Justify else TextAlign.Start
                )
            }

            SettingsCard("القراءة") {
                Text("عند فتح نتيجة بحث")
                ChipRow(listOf("نص الصفحة", "صورة الصفحة"), vm.hitView) { vm.updateHitView(it) }
                SwitchRow("إبقاء الشاشة مفعّلة أثناء القراءة", vm.keepAwakeRead) { vm.updateKeepAwakeRead(it) }
                SwitchRow("تظليل نتيجة البحث فوق صورة الصفحة", vm.hlOnPage) { vm.updateHlOnPage(it) }
                Text("شفافية التظليل: ${(vm.hlAlpha * 100).toInt()}%")
                Slider(
                    value = vm.hlAlpha,
                    onValueChange = { vm.hlAlpha = it },
                    onValueChangeFinished = { vm.saveHlAlpha() },
                    valueRange = 0.15f..0.8f
                )
            }

            SettingsCard("البحث") {
                Text("وضع البحث الافتراضي")
                ChipRow(
                    SearchMode.values().map { it.label },
                    vm.defaultMode.ordinal
                ) { vm.updateDefaultMode(SearchMode.values()[it]) }
                Text("أقصى عدد للنتائج")
                ChipRow(
                    limits.map { "$it" },
                    limits.indexOf(vm.resultLimit).coerceAtLeast(0)
                ) { vm.updateLimit(limits[it]) }
                Text("شكل نتائج البحث")
                ChipRow(listOf("صورة من الصفحة مع التظليل", "نص فقط"), vm.resultStyle) { vm.updateResultStyle(it) }
                Text("حجم مقطع الصفحة في النتيجة")
                ChipRow(listOf("ضيق", "متوسط", "واسع"), vm.cropBand) { vm.updateCropBand(it) }
                Text("طول مقتطف النتيجة (عند عرض النص)")
                ChipRow(listOf("قصير", "متوسط", "طويل"), vm.snippetLen) { vm.updateSnippetLen(it) }
                SwitchRow("البحث أثناء الكتابة", vm.liveSearch) { vm.updateLiveSearch(it) }
                SwitchRow("حفظ سجل البحث", vm.saveHistory) { vm.updateSaveHistory(it) }
                Text("توحيد الحروف في البحث", fontWeight = FontWeight.Bold)
                SwitchRow("التاء المربوطة = الهاء (ة = ه)", vm.normTa) {
                    vm.updateNorm(it, vm.normAlef, vm.normHamza)
                }
                SwitchRow("الألف المقصورة = الياء (ى = ي)", vm.normAlef) {
                    vm.updateNorm(vm.normTa, it, vm.normHamza)
                }
                SwitchRow("توحيد الهمزات (أ إ آ = ا ، ؤ = و ، ئ = ي)", vm.normHamza) {
                    vm.updateNorm(vm.normTa, vm.normAlef, it)
                }
                Text(
                    "بعد تغيير خيارات توحيد الحروف اضغط «تطبيق على الفهرس» ليشمل الكتب المفهرسة.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { vm.renormalize() }, enabled = !vm.renorming) {
                    Text(if (vm.renorming) "جارٍ التطبيق…" else "تطبيق على الفهرس")
                }
                TextButton(onClick = { vm.clearHistory() }) { Text("مسح سجل البحث") }
            }

            SettingsCard("الفهرسة") {
                Text("طريقة استخراج النص")
                ChipRow(
                    listOf("تلقائي (موصى به)", "OCR لكل الصفحات", "نص PDF فقط"),
                    vm.extractMode
                ) { vm.updateExtractMode(it) }
                Text(
                    "يصبح البحث متاحاً بسرعة من نص الملف، ثم تُحسَّن الصفحات التالفة بالـ OCR في الخلفية.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("سرعة الفهرسة (عدد المعالجات)")
                ChipRow(listOf("1", "2", "3", "4", "5", "6"), vm.workers - 1) { vm.updateWorkers(it + 1) }
                Text("نموذج الـ OCR")
                ChipRow(listOf("سريع", "أدق (أبطأ)"), vm.ocrModel) { vm.updateOcrModel(it) }
                Text("دقة صورة الـ OCR: ${vm.ocrWidth} بكسل")
                Slider(
                    value = vm.ocrWidth.toFloat(),
                    onValueChange = { vm.ocrWidth = (it / 100f).toInt() * 100 },
                    onValueChangeFinished = { vm.saveOcrWidth() },
                    valueRange = 1200f..3000f
                )
                SwitchRow("حفظ مواضع الكلمات (لتظليل الصفحة)", vm.saveBoxes) { vm.updateSaveBoxes(it) }
                SwitchRow("إيقاف الفهرسة مؤقتاً", Work.paused) { Work.paused = it }
                SwitchRow("تجاهل التشكيل أثناء قراءة OCR (أدق للحروف)", vm.ocrIgnoreMarks) {
                    vm.updateOcrIgnoreMarks(it)
                }
                Text(
                    "عدد أكبر وجودة أعلى = دقة أفضل لكن استهلاك أكثر للذاكرة والبطارية ووقت أطول. " +
                        "تجاهل التشكيل يحسّن قراءة الحروف لكنه يزيل التشكيل من النص المقروء بالـ OCR.",
                    style = MaterialTheme.typography.bodySmall
                )
                SwitchRow("إبقاء الشاشة مفعّلة أثناء الفهرسة", vm.keepAwake) { vm.updateKeepAwake(it) }
                TextButton(onClick = { confirmReindex = true }) { Text("إعادة فهرسة كل الكتب") }
            }

            SettingsCard("المعجم اللغوي") {
                SwitchRow("استعمال المعجم لتحسين البحث في صفحات الـ OCR", vm.lexOn) { vm.updateLexOn(it) }
                Text("المعجم الكبير: ${Lexicon.baseSize} كلمة • المتعلَّمة من كتبك: ${Lexicon.learnedSize}")
                if (Lexicon.status.isNotEmpty()) {
                    Text(Lexicon.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                SwitchRow("تصحيح صيغ «صلى الله عليه وسلم» و«رضي الله عنه» المعكوسة", vm.formulaFix) { vm.updateFormulaFix(it) }
                SwitchRow("استنتاج رمز ﷺ بعد «رسول الله» في صفحات الـ OCR", vm.lexGuess) { vm.updateLexGuess(it) }
                SwitchRow("التعلّم من الصفحات السليمة أثناء الفهرسة", vm.lexLearn) { vm.updateLexLearn(it) }
                SwitchRow("التعلّم من كلمات الـ OCR عالية الثقة", vm.lexLearnOcr) { vm.updateLexLearnOcr(it) }
                Text("أقل تكرار لاعتبار الكلمة صحيحة")
                ChipRow(minOpts.map { "$it" }, minOpts.indexOf(vm.lexMin).coerceAtLeast(0)) { vm.updateLexMin(minOpts[it]) }
                Text("أقل تكرار للكلمة المقترحة (أعلى = تصحيح أحذر)")
                ChipRow(sugOpts.map { "$it" }, sugOpts.indexOf(vm.lexSug).coerceAtLeast(0)) { vm.updateLexSug(sugOpts[it]) }
                Text("أقصى عدد كلمات في المعجم")
                ChipRow(
                    listOf("300 ألف", "600 ألف", "مليون", "مليون ونصف"),
                    maxOpts.indexOf(vm.lexMax).coerceAtLeast(0)
                ) { vm.updateLexMax(maxOpts[it]) }
                Text(
                    "لا يغيّر المعجم نص الكتاب ولا صورته. يُستعمل لعمود البحث ومواضع التظليل في صفحات الـ OCR فقط. " +
                        "يصلح الأخطاء القريبة (نقطة أو حرف) ولا يصلح الكلمة المشوّهة كلياً. القوائم الكبيرة تستهلك ذاكرة أكثر.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { vm.buildLexiconFromBooks() }, enabled = !Lexicon.busy) {
                    Text(if (Lexicon.busy) "جارٍ العمل…" else "بناء المعجم من كتبي")
                }
                TextButton(onClick = { lexPicker.launch(arrayOf("text/plain", "*/*")) }, enabled = !Lexicon.busy) {
                    Text("استبدال المعجم الكبير بقائمة كلمات (txt)")
                }
                TextButton(onClick = { refPicker.launch(arrayOf("text/plain", "*/*")) }, enabled = !Lexicon.busy) {
                    Text("استيراد نص مرجعي (مثل المصحف) للمعجم")
                }
                TextButton(onClick = { vm.applyLexicon() }, enabled = !Lexicon.busy) {
                    Text("تطبيق المعجم على الكتب المفهرسة")
                }
                TextButton(onClick = { vm.clearLexicon(false) }, enabled = !Lexicon.busy) { Text("مسح المعجم المتعلَّم") }
                TextButton(onClick = { vm.clearLexicon(true) }, enabled = !Lexicon.busy) { Text("مسح المعجم الكبير") }
            }

            SettingsCard("المكتبة الشاملة — اتصال MCP") {
                Text("اتصال مباشر بخادم المكتبة الشاملة للبحث في الكتب وفتح النصوص والصفحات، مع إمكانية حفظ ما تفتحه محلياً للرجوع إليه دون إنترنت.", style = MaterialTheme.typography.bodySmall)
                Text("عنوان الخادم الافتراضي: https://mcp.shamela.ws", style = MaterialTheme.typography.labelSmall)
                Button(onClick = { vm.tab = 4 }, modifier = Modifier.fillMaxWidth()) {
                    Text("فتح أدوات الشاملة وإعداداتها المتقدمة")
                }
            }

            SettingsCard("التخزين") {
                Text("المساحة المستخدمة: $storage")
                Text("عدد الكتب: ${books.size}")
                TextButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("حذف جميع الكتب") }
            }

            SettingsCard("سجل التغييرات") {
                Text("الإصدار $APP_VERSION", fontWeight = FontWeight.Bold)
                Text("• اتصال MCP مباشر بخادم الشاملة الرسمي، مع اكتشاف أدوات البحث والفتح من الخادم")
                Text("• حفظ نتائج البحث والصفحات المسترجعة محلياً وعرضها دون اتصال")
                Text("• إعدادات اتصال متقدمة: عنوان الخادم، المهلة، عدد النتائج، الحفظ التلقائي، ووضع عدم الاتصال")
                Text("• استدعاء أدوات MCP المتاحة يدوياً مع تعديل وسائط JSON حسب مخطط الخادم")
                Spacer(Modifier.height(6.dp))
                Text("• معجم كبير بملايين الكلمات يُجهَّز مرة واحدة ويعمل دون إنترنت")
                Text("• إصلاح «ملسو هيلع هللا ىلص» وأمثالها إلى الصيغة الصحيحة")
                Text("• استنتاج رمز ﷺ بعد «رسول الله» في صفحات الـ OCR، واستيراد نص مرجعي كالمصحف")
                Spacer(Modifier.height(6.dp))
                Text("الإصدار 1.7", fontWeight = FontWeight.Bold)
                Text("• معجم لغوي يتعلّم من كتبك ويقبل قائمة كلمات مستوردة")
                Text("• تصحيح الكلمات المقروءة بالـ OCR في عمود البحث والتظليل فقط، دون تغيير النص")
                Text("• تطبيق المعجم على الكتب المفهرسة دون إعادة القراءة بالـ OCR")
                Spacer(Modifier.height(6.dp))
                Text("الإصدار 1.6", fontWeight = FontWeight.Bold)
                Text("• نتيجة البحث تعرض مقطعاً من صفحة الكتاب نفسه مع تظليل الكلمة")
                Text("• فتح النتيجة على صفحة الكتاب مع التظليل فوق الصورة")
                Text("• فهرسة أسرع: تمريرة واحدة لاستخراج النص ومواضع الكلمات، وإيقاف مؤقت")
                Text("• مدة تقديرية للتحسين، ورسالة خطأ واضحة لكل كتاب تعذّر فهرسته")
                Text("• إعدادات جديدة: دقة OCR، ونموذج OCR، وحفظ المواضع، وشفافية التظليل")
                Text("• تبويب «العامة»: بحث وتنزيل من فهرس Internet Archive")
                Spacer(Modifier.height(6.dp))
                Text("الإصدار 1.4", fontWeight = FontWeight.Bold)
                Text("• فهرسة بمرحلتين: بحث متاح بسرعة، وتحسين الـ OCR في الخلفية")
                Text("• نموذج OCR أدق وجودة أعلى، وخيار تجاهل التشكيل لدقة الحروف")
                Text("• تعديل نص أي صفحة يدوياً، وإعادة قراءة صفحة واحدة بالـ OCR الدقيق")
                Text("• خيارات توحيد الحروف في البحث، وطول المقتطف، ولون التظليل")
                Text("• البحث أثناء الكتابة أو عند الضغط، وحفظ السجل اختياري")
                Text("• إضافة كتاب واحد مباشرة من الملفات، و«إضافة مجلد» في الأعلى")
                Spacer(Modifier.height(6.dp))
                Text("الإصدار 1.3", fontWeight = FontWeight.Bold)
                Text("• إضافة من مجلد أو من «مشاركة» ملف PDF، وبحث داخل الكتاب، وعلامات")
                Spacer(Modifier.height(6.dp))
                Text("الإصدار 1.2", fontWeight = FontWeight.Bold)
                Text("• اكتشاف النص التالف وقراءته بالـ OCR، وفهرسة متوازية وفي الخلفية")
                Spacer(Modifier.height(6.dp))
                Text("الإصدار 1.1", fontWeight = FontWeight.Bold)
                Text("• الإعدادات، والمفضلة، وتعديل العنوان والمؤلف، وسجل البحث")
                Spacer(Modifier.height(6.dp))
                Text("الإصدار 1.0", fontWeight = FontWeight.Bold)
                Text("• النسخة الأولى: مكتبة، وبحث، وفهرسة، وOCR عربي")
            }

            SettingsCard("حول التطبيق") {
                Text("باحث PDF — الإصدار $APP_VERSION")
                Text(
                    "القراءة والبحث والفهرسة تعمل دون إنترنت. الإنترنت مطلوب فقط لتبويب «العامة» (تصفّح الفهرس وتنزيل الكتب).",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (confirmReindex) {
        AlertDialog(
            onDismissRequest = { confirmReindex = false },
            title = { Text("إعادة فهرسة كل الكتب") },
            text = { Text("ستُعاد فهرسة جميع الكتب من البداية، وقد يستغرق ذلك وقتاً طويلاً.") },
            confirmButton = {
                TextButton(onClick = { vm.reindexAll(); confirmReindex = false }) { Text("متابعة") }
            },
            dismissButton = { TextButton(onClick = { confirmReindex = false }) { Text("إلغاء") } }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("حذف جميع الكتب") },
            text = { Text("ستُحذف كل الكتب وفهارسها من التطبيق نهائياً. ملفاتك الأصلية على الهاتف لن تتأثر.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteAll(); confirmDelete = false }) { Text("حذف الكل") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } }
        )
    }
}

// ======================= القارئ =======================

@Composable
fun ReaderScreen(vm: AppViewModel, target: ReaderTarget) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val books by vm.books.collectAsStateWithLifecycle()
    val book = books.firstOrNull { it.id == target.bookId }
    if (book == null || book.pageCount <= 0) {
        LaunchedEffect(Unit) { vm.reader = null }
        return
    }
    val total = book.pageCount
    val source = remember(book.id) { PdfSource(File(ctx.filesDir, "books/${book.id}.pdf")) }
    DisposableEffect(book.id) { onDispose { source.close() } }

    val pagerState = rememberPagerState(
        initialPage = (target.page - 1).coerceIn(0, total - 1)
    ) { total }
    val scope = rememberCoroutineScope()
    var textMode by remember { mutableStateOf(target.terms.isNotEmpty() && vm.hitView == 0) }
    var zoomed by remember { mutableStateOf(false) }
    var showGo by remember { mutableStateOf(false) }
    var showMarks by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var terms by remember { mutableStateOf(target.terms) }
    var searchOpen by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var hitPages by remember { mutableStateOf<List<Int>>(emptyList()) }
    var hitIdx by remember { mutableIntStateOf(-1) }
    var speaking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }

    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var ref: TextToSpeech? = null
        ref = TextToSpeech(ctx) { st ->
            if (st == TextToSpeech.SUCCESS) ref?.language = Locale("ar")
        }
        tts.value = ref
        onDispose {
            ref?.stop()
            ref?.shutdown()
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        zoomed = false
        vm.saveLastPage(book.id, pagerState.currentPage + 1)
        if (speaking) {
            tts.value?.stop()
            speaking = false
        }
    }

    LaunchedEffect(q) {
        delay(300)
        val t = Arabic.terms(q)
        if (t.isEmpty() || t.sumOf { it.length } < 2) {
            hitPages = emptyList()
            hitIdx = -1
            terms = target.terms
        } else {
            val r = vm.searchInBook(book.id, q)
            hitPages = r.first
            terms = r.second
            textMode = true
            if (r.first.isEmpty()) {
                hitIdx = -1
            } else {
                val cur = pagerState.currentPage + 1
                val i = r.first.indexOfFirst { it >= cur }
                hitIdx = if (i < 0) 0 else i
                pagerState.scrollToPage(r.first[hitIdx] - 1)
            }
        }
    }

    fun goHit(delta: Int) {
        if (hitPages.isEmpty()) return
        hitIdx = (hitIdx + delta + hitPages.size) % hitPages.size
        scope.launch { pagerState.scrollToPage(hitPages[hitIdx] - 1) }
    }

    fun toast(m: String) {
        Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
    }

    val curPage = pagerState.currentPage + 1
    val marked = vm.isBookmarked(book.id, curPage)

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                book.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "صفحة $curPage من $total" + if (marked) "  ★" else "",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { vm.reader = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع")
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchOpen = !searchOpen }) {
                            Icon(Icons.Default.Search, "بحث في الكتاب")
                        }
                        TextButton(onClick = { textMode = !textMode }) {
                            Text(if (textMode) "الصفحة" else "النص")
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Default.MoreVert, "المزيد")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("الانتقال إلى صفحة") },
                                    onClick = { menu = false; showGo = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (marked) "إزالة العلامة من هذه الصفحة" else "إضافة علامة لهذه الصفحة") },
                                    onClick = { menu = false; vm.toggleBookmark(book.id, curPage) }
                                )
                                DropdownMenuItem(
                                    text = { Text("العلامات المحفوظة") },
                                    onClick = { menu = false; showMarks = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("تكبير الخط") },
                                    onClick = {
                                        vm.readerFont = (vm.readerFont + 2f).coerceAtMost(40f)
                                        vm.saveFont()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("تصغير الخط") },
                                    onClick = {
                                        vm.readerFont = (vm.readerFont - 2f).coerceAtLeast(14f)
                                        vm.saveFont()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("تعديل نص الصفحة") },
                                    onClick = {
                                        menu = false
                                        scope.launch { editing = vm.pageText(book.id, curPage) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("إعادة قراءة الصفحة بالـ OCR الدقيق") },
                                    onClick = {
                                        menu = false
                                        toast("جارٍ قراءة الصفحة…")
                                        vm.reOcrPage(book.id, curPage) { ok ->
                                            toast(if (ok) "تم تحديث نص الصفحة" else "تعذّرت القراءة")
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("نسخ نص الصفحة") },
                                    onClick = {
                                        menu = false
                                        scope.launch {
                                            val t = vm.pageText(book.id, curPage)
                                            clipboard.setText(AnnotatedString(t))
                                            toast("تم نسخ نص الصفحة")
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("مشاركة نص الصفحة") },
                                    onClick = {
                                        menu = false
                                        scope.launch {
                                            val t = vm.pageText(book.id, curPage)
                                            val send = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, t)
                                            }
                                            ctx.startActivity(Intent.createChooser(send, "مشاركة نص الصفحة"))
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (speaking) "إيقاف القراءة الصوتية" else "قراءة الصفحة صوتياً") },
                                    onClick = {
                                        menu = false
                                        scope.launch {
                                            if (speaking) {
                                                tts.value?.stop()
                                                speaking = false
                                            } else {
                                                val t = vm.pageText(book.id, curPage)
                                                if (t.isBlank()) {
                                                    toast("لا يوجد نص لهذه الصفحة")
                                                } else {
                                                    tts.value?.speak(t.take(3900), TextToSpeech.QUEUE_FLUSH, null, "page")
                                                    speaking = true
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                )
                if (searchOpen) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = q,
                            onValueChange = { q = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("ابحث في هذا الكتاب") },
                            singleLine = true,
                            shape = RoundedCornerShape(20.dp)
                        )
                        Text(
                            if (hitPages.isEmpty()) (if (q.isBlank()) "" else "0") else "${hitIdx + 1}/${hitPages.size}",
                            Modifier.padding(horizontal = 6.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                        TextButton(onClick = { goHit(-1) }) { Text("السابق") }
                        TextButton(onClick = { goHit(1) }) { Text("التالي") }
                    }
                }
            }
        }
    ) { pad ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !zoomed,
            modifier = Modifier.padding(pad).fillMaxSize()
        ) { idx ->
            if (textMode) {
                TextPage(vm, book.id, idx + 1, terms)
            } else {
                val ctxP = LocalContext.current
                val boxes by produceState<List<WordBox>>(emptyList(), book.id, idx, vm.textVersion) {
                    value = withContext(Dispatchers.IO) { WordStore.load(ctxP, book.id, idx + 1) }
                }
                val rects = remember(boxes, terms, vm.hlOnPage) {
                    if (vm.hlOnPage) matchBoxes(boxes, terms) else emptyList()
                }
                Box(Modifier.fillMaxSize()) {
                    ZoomBox(onZoomChange = { zoomed = it }) {
                        PdfPageImage(
                            source, idx,
                            (LocalConfiguration.current.screenWidthDp *
                                LocalDensity.current.density * 1.5f).toInt().coerceIn(800, 2000),
                            rects, markColor(vm.highlightIdx), vm.hlAlpha
                        )
                    }
                    if (terms.isNotEmpty() && boxes.isEmpty()) {
                        Text(
                            "لا توجد مواضع محفوظة لهذه الصفحة — أعد فهرسة الكتاب لتظليلها",
                            Modifier.align(Alignment.BottomCenter).padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    editing?.let { cur ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("تعديل نص الصفحة $curPage") },
            text = {
                OutlinedTextField(
                    value = cur,
                    onValueChange = { editing = it },
                    modifier = Modifier.fillMaxWidth().height(320.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.savePageText(book.id, curPage, cur)
                    editing = null
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("إلغاء") } }
        )
    }

    if (showMarks) {
        val marks = vm.bookmarksOf(book.id)
        AlertDialog(
            onDismissRequest = { showMarks = false },
            title = { Text("العلامات المحفوظة") },
            text = {
                if (marks.isEmpty()) {
                    Text("لا توجد علامات. أضف علامة من القائمة (⋮).")
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        marks.forEach { pg ->
                            ScopeRow("صفحة $pg") {
                                showMarks = false
                                scope.launch { pagerState.scrollToPage((pg - 1).coerceIn(0, total - 1)) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMarks = false }) { Text("إغلاق") } }
        )
    }

    if (showGo) {
        var num by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGo = false },
            title = { Text("الانتقال إلى صفحة") },
            text = {
                OutlinedTextField(
                    value = num,
                    onValueChange = { num = it.filter { c -> c.isDigit() } },
                    singleLine = true,
                    placeholder = { Text("1 - $total") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = num.toIntOrNull()
                    if (n != null) {
                        scope.launch { pagerState.scrollToPage((n - 1).coerceIn(0, total - 1)) }
                    }
                    showGo = false
                }) { Text("انتقال") }
            },
            dismissButton = { TextButton(onClick = { showGo = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun PdfPageImage(
    source: PdfSource,
    index: Int,
    widthPx: Int,
    rects: List<WordBox>,
    color: Color,
    alpha: Float
) {
    var bmp by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index) { bmp = source.render(index, widthPx) }
    val b = bmp
    if (b == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(b.width.toFloat() / b.height)) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
                if (rects.isNotEmpty()) {
                    Canvas(Modifier.fillMaxSize()) {
                        for (r in rects) {
                            drawRect(
                                color = color.copy(alpha = alpha),
                                topLeft = Offset(r.x * size.width, r.y * size.height),
                                size = Size(r.ww * size.width, r.hh * size.height)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TextPage(vm: AppViewModel, bookId: Long, pageNo: Int, terms: List<String>) {
    val text by produceState<String?>(null, bookId, pageNo, vm.textVersion) {
        value = vm.pageText(bookId, pageNo)
    }
    val t = text
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        when {
            t == null -> CircularProgressIndicator()
            t.isBlank() -> Text(
                "لا يوجد نص لهذه الصفحة (لم تُفهرس بعد أو أنها فارغة). جرّب «الصفحة».",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> {
                val shown = if (vm.hideTashkeel) Arabic.stripTashkeel(t) else t
                val ranges = remember(shown, terms) { Arabic.findRanges(shown, terms) }
                Text(
                    highlighted(shown, ranges, markColor(vm.highlightIdx)),
                    fontSize = vm.readerFont.sp,
                    lineHeight = (vm.readerFont * vm.lineSpacing).sp,
                    fontFamily = if (vm.fontStyle == 0) FontFamily.Serif else FontFamily.SansSerif,
                    textAlign = if (vm.justify) TextAlign.Justify else TextAlign.Start,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

private fun clampOffset(o: Offset, s: Float, sz: IntSize): Offset {
    val mx = sz.width * (s - 1f) / 2f
    val my = sz.height * (s - 1f) / 2f
    return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
}

@Composable
fun ZoomBox(onZoomChange: (Boolean) -> Unit, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    val gestureMod = if (scale > 1f) {
        Modifier.pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                val ns = (scale * zoom).coerceIn(1f, 5f)
                scale = ns
                offset = if (ns <= 1f) Offset.Zero else clampOffset(offset + pan, ns, size)
                onZoomChange(ns > 1f)
            }
        }
    } else {
        Modifier
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                    onZoomChange(scale > 1f)
                })
            }
            .then(gestureMod)
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
    ) {
        content()
    }
}
