package com.bahith.pdf

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class Hit(
    val bookId: Long,
    val title: String,
    val pageNo: Int,
    val snippet: String,
    val ranges: List<IntRange>
)

data class ReaderTarget(val bookId: Long, val page: Int, val terms: List<String>)

const val APP_VERSION = "2.1 Pro Max"

fun arrangeBooks(list: List<BookEntity>, filter: String, sort: Int): List<BookEntity> {
    val f = Arabic.normalize(filter)
    val filtered = if (f.isEmpty()) list
    else list.filter { Arabic.normalize(it.title + " " + it.author).contains(f) }
    return when (sort) {
        1 -> filtered.sortedBy { Arabic.normalize(it.title) }
        2 -> filtered.sortedWith(
            compareByDescending<BookEntity> { it.favorite }.thenByDescending { it.addedAt }
        )
        else -> filtered.sortedByDescending { it.addedAt }
    }
}

fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.2f جيجابايت", mb / 1024.0)
    else String.format("%.1f ميجابايت", mb)
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDb.get(app).dao()
    private var searchJob: Job? = null
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val books = dao.observeBooks()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---------- الإعدادات ----------
    var themeMode by mutableIntStateOf(prefs.getInt("theme", 0))
    var readerFont by mutableFloatStateOf(prefs.getFloat("font", 20f))
    var defaultMode by mutableStateOf(
        SearchMode.values()[prefs.getInt("mode", 0).coerceIn(0, SearchMode.values().size - 1)]
    )
    var resultLimit by mutableIntStateOf(prefs.getInt("limit", 400))
    var extractMode by mutableIntStateOf(prefs.getInt("extract", 0))
    var workers by mutableIntStateOf(
        prefs.getInt("workers", minOf(4, maxOf(2, Runtime.getRuntime().availableProcessors() / 2)))
    )
    var ocrQuality by mutableIntStateOf(prefs.getInt("ocrq", 1))
    var hitView by mutableIntStateOf(prefs.getInt("hitview", 1))
    var lineSpacing by mutableFloatStateOf(prefs.getFloat("lineSp", 1.7f))
    var hideTashkeel by mutableStateOf(prefs.getBoolean("hideTash", false))
    var justify by mutableStateOf(prefs.getBoolean("justify", false))
    var fontStyle by mutableIntStateOf(prefs.getInt("fontStyle", 0))
    var importTotal by mutableIntStateOf(0)
    var importDone by mutableIntStateOf(0)
    var libFavOnly by mutableStateOf(false)
    var ocrIgnoreMarks by mutableStateOf(prefs.getBoolean("ocrNoMarks", false))
    var normTa by mutableStateOf(prefs.getBoolean("normTa", true))
    var normAlef by mutableStateOf(prefs.getBoolean("normAlef", true))
    var normHamza by mutableStateOf(prefs.getBoolean("normHamza", true))
    var snippetLen by mutableIntStateOf(prefs.getInt("snippet", 1))
    var liveSearch by mutableStateOf(prefs.getBoolean("live", true))
    var saveHistory by mutableStateOf(prefs.getBoolean("saveHist", true))
    var highlightIdx by mutableIntStateOf(prefs.getInt("hl", 0))
    var keepAwakeRead by mutableStateOf(prefs.getBoolean("awakeRead", false))
    var ocrModel by mutableIntStateOf(prefs.getInt("ocrModel", 0))
    var ocrWidth by mutableIntStateOf(prefs.getInt("ocrWidth", 1900))
    var saveBoxes by mutableStateOf(prefs.getBoolean("saveBoxes", true))
    var resultStyle by mutableIntStateOf(prefs.getInt("resStyle", 0))
    var cropBand by mutableIntStateOf(prefs.getInt("cropBand", 1))
    var hlAlpha by mutableFloatStateOf(prefs.getFloat("hlAlpha", 0.45f))
    var hlOnPage by mutableStateOf(prefs.getBoolean("hlOnPage", true))
    var hlTerms by mutableStateOf<List<String>>(emptyList())
    var lexOn by mutableStateOf(prefs.getBoolean("lexOn", true))
    var lexMin by mutableIntStateOf(prefs.getInt("lexMin", 3))
    var lexSug by mutableIntStateOf(prefs.getInt("lexSug", 8))
    var lexMax by mutableIntStateOf(prefs.getInt("lexMax", 600_000))
    var lexLearn by mutableStateOf(prefs.getBoolean("lexLearn", true))
    var lexLearnOcr by mutableStateOf(prefs.getBoolean("lexLearnOcr", true))
    var formulaFix by mutableStateOf(prefs.getBoolean("formulaFix", true))
    var lexGuess by mutableStateOf(prefs.getBoolean("lexGuess", true))
    var textVersion by mutableIntStateOf(0)

    // ---------- المكتبة العامة: فهرس Internet Archive المفتوح ----------
    var catalogQuery by mutableStateOf("")
    var catalogResults by mutableStateOf<List<CatalogItem>>(emptyList())
    var catalogLoading by mutableStateOf(false)
    var catalogError by mutableStateOf<String?>(null)
    var downloadingId by mutableStateOf<String?>(null)
    var downloadProgress by mutableIntStateOf(0)
    private var catalogJob: Job? = null
    var renorming by mutableStateOf(false)
    var bookmarks by mutableStateOf(
        (prefs.getString("bookmarks", "") ?: "").split("\n").filter { it.contains(":") }.toSet()
    )
    var keepAwake by mutableStateOf(prefs.getBoolean("keepAwake", true))
    var sortOrder by mutableIntStateOf(prefs.getInt("sort", 0))
    var history by mutableStateOf(
        (prefs.getString("history", "") ?: "").split("\n").filter { it.isNotBlank() }
    )

    fun updateTheme(v: Int) { themeMode = v; prefs.edit().putInt("theme", v).apply() }
    fun saveFont() { prefs.edit().putFloat("font", readerFont).apply() }
    fun updateDefaultMode(m: SearchMode) {
        defaultMode = m
        mode = m
        prefs.edit().putInt("mode", m.ordinal).apply()
    }
    fun updateLimit(v: Int) { resultLimit = v; prefs.edit().putInt("limit", v).apply() }
    fun updateExtractMode(v: Int) { extractMode = v; prefs.edit().putInt("extract", v).apply() }
    fun updateWorkers(v: Int) { workers = v; prefs.edit().putInt("workers", v).apply() }
    fun updateOcrQuality(v: Int) { ocrQuality = v; prefs.edit().putInt("ocrq", v).apply() }
    fun saveReader() { prefs.edit().putFloat("lineSp", lineSpacing).putFloat("font", readerFont).apply() }
    fun updateHideTashkeel(v: Boolean) { hideTashkeel = v; prefs.edit().putBoolean("hideTash", v).apply() }
    fun updateJustify(v: Boolean) { justify = v; prefs.edit().putBoolean("justify", v).apply() }
    fun updateFontStyle(v: Int) { fontStyle = v; prefs.edit().putInt("fontStyle", v).apply() }

    fun isBookmarked(bookId: Long, page: Int): Boolean = bookmarks.contains("$bookId:$page")

    fun toggleBookmark(bookId: Long, page: Int) {
        val k = "$bookId:$page"
        bookmarks = if (bookmarks.contains(k)) bookmarks - k else bookmarks + k
        prefs.edit().putString("bookmarks", bookmarks.joinToString("\n")).apply()
    }

    fun bookmarksOf(bookId: Long): List<Int> =
        bookmarks.filter { it.startsWith("$bookId:") }
            .mapNotNull { it.substringAfter(":").toIntOrNull() }.sorted()

    private fun dropBookmarks(bookId: Long?) {
        bookmarks = if (bookId == null) emptySet()
        else bookmarks.filterNot { it.startsWith("$bookId:") }.toSet()
        prefs.edit().putString("bookmarks", bookmarks.joinToString("\n")).apply()
    }

    fun updateHitView(v: Int) { hitView = v; prefs.edit().putInt("hitview", v).apply() }

    private fun options(): IndexOptions =
        IndexOptions(extractMode, workers, ocrWidth, ocrModel == 1, ocrIgnoreMarks, saveBoxes)

    private fun pushLexicon() {
        Lexicon.on = lexOn
        Lexicon.minCount = lexMin
        Lexicon.minSuggest = lexSug
        Lexicon.maxWords = lexMax
        Lexicon.learn = lexLearn
        Lexicon.learnOcr = lexLearnOcr
        Lexicon.guessSalawat = lexGuess
        Formulas.on = formulaFix
    }

    fun updateFormulaFix(v: Boolean) { formulaFix = v; prefs.edit().putBoolean("formulaFix", v).apply(); pushLexicon() }
    fun updateLexGuess(v: Boolean) { lexGuess = v; prefs.edit().putBoolean("lexGuess", v).apply(); pushLexicon() }

    fun updateLexOn(v: Boolean) { lexOn = v; prefs.edit().putBoolean("lexOn", v).apply(); pushLexicon() }
    fun updateLexMin(v: Int) { lexMin = v; prefs.edit().putInt("lexMin", v).apply(); pushLexicon() }
    fun updateLexSug(v: Int) { lexSug = v; prefs.edit().putInt("lexSug", v).apply(); pushLexicon() }
    fun updateLexMax(v: Int) { lexMax = v; prefs.edit().putInt("lexMax", v).apply(); pushLexicon() }
    fun updateLexLearn(v: Boolean) { lexLearn = v; prefs.edit().putBoolean("lexLearn", v).apply(); pushLexicon() }
    fun updateLexLearnOcr(v: Boolean) { lexLearnOcr = v; prefs.edit().putBoolean("lexLearnOcr", v).apply(); pushLexicon() }

    fun importLexicon(uri: Uri) {
        if (Lexicon.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            Lexicon.busy = true
            try {
                val n = Lexicon.importList(getApplication<Application>(), uri)
                message = "تم تجهيز المعجم الكبير: $n كلمة"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "تعذّر استيراد القائمة: ${e.message ?: "خطأ"}"
            } finally {
                Lexicon.busy = false
            }
        }
    }

    /** يستورد نصاً مرجعياً (مثل المصحف) فتُضاف كلماته إلى المعجم المتعلَّم بوزن قوي. */
    fun importReferenceText(uri: Uri) {
        if (Lexicon.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            Lexicon.busy = true
            try {
                val n = Lexicon.importText(getApplication<Application>(), uri)
                message = "تمت إضافة $n كلمة من النص المرجعي إلى المعجم"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "تعذّر استيراد النص: ${e.message ?: "خطأ"}"
            } finally {
                Lexicon.busy = false
            }
        }
    }

    /** يتعلم المعجم من الصفحات السليمة في الكتب المفهرسة. */
    fun buildLexiconFromBooks() {
        if (Lexicon.busy) return
        viewModelScope.launch(Dispatchers.Default) {
            Lexicon.busy = true
            try {
                Lexicon.ensureLoaded(getApplication<Application>())
                var last = 0L
                var pages = 0
                while (true) {
                    val b = dao.textBatch(last, 300)
                    if (b.isEmpty()) break
                    for (row in b) {
                        val t = row.text
                        if (Arabic.letterCount(t) >= 40 && !Arabic.looksCorrupted(t)) {
                            Lexicon.learnNorm(Arabic.normalize(t), true)
                            pages++
                        }
                    }
                    last = b.last().id
                }
                Lexicon.saveLearned()
                message = "تعلّم المعجم من $pages صفحة. الكلمات المتعلَّمة: ${Lexicon.learnedSize}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "تعذّر بناء المعجم: ${e.message ?: "خطأ"}"
            } finally {
                Lexicon.busy = false
            }
        }
    }

    private suspend fun applyLexiconNow(): Int {
        val app = getApplication<Application>()
        Lexicon.ensureLoaded(app)
        var fixed = 0
        for (b in dao.allBooks()) {
            val f = File(booksDir(), "${b.id}.ocr")
            if (!f.exists()) continue
            val pages = f.readText().split(",").mapNotNull { it.trim().toIntOrNull() }.distinct()
            for (p in pages) {
                val t = dao.pageText(b.id, p) ?: continue
                dao.setNormForPage(b.id, p, Lexicon.fixNorm(Arabic.normalize(t)))
                val boxes = WordStore.load(app, b.id, p)
                if (boxes.isNotEmpty()) {
                    WordStore.save(app, b.id, p, Lexicon.fixBoxes(boxes))
                }
                fixed++
            }
        }
        return fixed
    }

    /** يطبّق المعجم على صفحات الـ OCR المفهرسة سابقاً دون إعادة قراءتها. */
    fun applyLexicon() {
        if (Lexicon.busy) return
        viewModelScope.launch(Dispatchers.Default) {
            Lexicon.busy = true
            try {
                val n = applyLexiconNow()
                message = "طُبّق المعجم على $n صفحة من صفحات الـ OCR"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "تعذّر تطبيق المعجم: ${e.message ?: "خطأ"}"
            } finally {
                Lexicon.busy = false
            }
        }
    }

    fun clearLexicon(importedList: Boolean) {
        if (importedList) Lexicon.clearBase() else Lexicon.clearLearned()
        message = if (importedList) "مُسح المعجم الكبير" else "مُسح المعجم المتعلَّم"
    }

    fun updateOcrModel(v: Int) { ocrModel = v; prefs.edit().putInt("ocrModel", v).apply() }
    fun saveOcrWidth() { prefs.edit().putInt("ocrWidth", ocrWidth).apply() }
    fun updateSaveBoxes(v: Boolean) { saveBoxes = v; prefs.edit().putBoolean("saveBoxes", v).apply() }
    fun updateResultStyle(v: Int) { resultStyle = v; prefs.edit().putInt("resStyle", v).apply() }
    fun updateCropBand(v: Int) { cropBand = v; prefs.edit().putInt("cropBand", v).apply() }
    fun saveHlAlpha() { prefs.edit().putFloat("hlAlpha", hlAlpha).apply() }
    fun updateHlOnPage(v: Boolean) { hlOnPage = v; prefs.edit().putBoolean("hlOnPage", v).apply() }
    fun togglePause() { Work.paused = !Work.paused }

    fun updateOcrIgnoreMarks(v: Boolean) { ocrIgnoreMarks = v; prefs.edit().putBoolean("ocrNoMarks", v).apply() }
    fun updateSnippetLen(v: Int) { snippetLen = v; prefs.edit().putInt("snippet", v).apply() }
    fun updateLiveSearch(v: Boolean) { liveSearch = v; prefs.edit().putBoolean("live", v).apply() }
    fun updateSaveHistory(v: Boolean) { saveHistory = v; prefs.edit().putBoolean("saveHist", v).apply() }
    fun updateHighlight(v: Int) { highlightIdx = v; prefs.edit().putInt("hl", v).apply() }
    fun updateKeepAwakeRead(v: Boolean) { keepAwakeRead = v; prefs.edit().putBoolean("awakeRead", v).apply() }

    fun updateNorm(ta: Boolean, alef: Boolean, hamza: Boolean) {
        normTa = ta; normAlef = alef; normHamza = hamza
        ArabicOpts.taMarbuta = ta
        ArabicOpts.alefMaksura = alef
        ArabicOpts.hamza = hamza
        prefs.edit().putBoolean("normTa", ta).putBoolean("normAlef", alef).putBoolean("normHamza", hamza).apply()
    }

    /** إعادة بناء عمود البحث لكل الصفحات بعد تغيير خيارات توحيد الحروف (سريع، دون OCR). */
    fun renormalize() {
        if (renorming) return
        viewModelScope.launch(Dispatchers.Default) {
            renorming = true
            try {
                var last = 0L
                while (true) {
                    val b = dao.textBatch(last, 300)
                    if (b.isEmpty()) break
                    dao.renormBatch(b)
                    last = b.last().id
                }
                if (Lexicon.on) applyLexiconNow()
                message = "تم تطبيق خيارات البحث على الفهرس"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "تعذّر تحديث الفهرس"
            } finally {
                renorming = false
            }
        }
    }

    // ---------- تعديل نص الصفحة ----------
    fun savePageText(bookId: Long, pageNo: Int, text: String) {
        viewModelScope.launch {
            val t = text.trim()
            dao.updatePage(bookId, pageNo, t, Arabic.normalize(t))
            textVersion++
        }
    }

    /** يعيد قراءة صفحة واحدة بالـ OCR الأدق ويحفظ النتيجة. */
    fun reOcrPage(bookId: Long, pageNo: Int, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val r = withContext(Dispatchers.Default) {
                Lexicon.ensureBase(app)
                Work.indexer(app).ocrOne(bookId, pageNo, ocrIgnoreMarks)
            }
            val t = Formulas.fix(r.first)
            if (t.isBlank()) {
                done(false)
            } else {
                Lexicon.learnBoxes(r.second)
                dao.updatePage(bookId, pageNo, t.trim(), Lexicon.fixNorm(Arabic.normalize(t)))
                if (saveBoxes) WordStore.save(app, bookId, pageNo, Lexicon.fixBoxes(r.second))
                textVersion++
                done(true)
            }
        }
    }
    fun updateKeepAwake(v: Boolean) { keepAwake = v; prefs.edit().putBoolean("keepAwake", v).apply() }
    fun updateSort(v: Int) { sortOrder = v; prefs.edit().putInt("sort", v).apply() }

    fun addHistory(q: String) {
        val t = q.trim()
        if (t.length < 2 || !saveHistory) return
        history = (listOf(t) + history.filter { it != t }).take(12)
        prefs.edit().putString("history", history.joinToString("\n")).apply()
    }

    fun clearHistory() {
        history = emptyList()
        prefs.edit().putString("history", "").apply()
    }

    // ---------- الحالة العامة ----------
    var tab by mutableIntStateOf(0)
    var reader by mutableStateOf<ReaderTarget?>(null)
    var message by mutableStateOf<String?>(null)
    var libraryFilter by mutableStateOf("")

    var query by mutableStateOf("")
    var mode by mutableStateOf(defaultMode)
    var scopeBookId by mutableLongStateOf(0L)
    var results by mutableStateOf<List<Hit>>(emptyList())
    var searching by mutableStateOf(false)
    var searchedOnce by mutableStateOf(false)
    private var lastTerms: List<String> = emptyList()

    init {
        pushLexicon()
        viewModelScope.launch(Dispatchers.IO) { Lexicon.ensureBase(getApplication<Application>()) }
        ArabicOpts.taMarbuta = normTa
        ArabicOpts.alefMaksura = normAlef
        ArabicOpts.hamza = normHamza
        viewModelScope.launch {
            dao.unfinished().forEach {
                if (Work.jobs[it.id]?.isActive != true) startIndex(it.id)
            }
        }
    }

    private fun booksDir(): File =
        File(getApplication<Application>().filesDir, "books").apply { mkdirs() }

    // ---------- إضافة الكتب ----------
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            importTotal += uris.size
            var added = 0
            var dup = 0
            for (uri in uris) {
                val id = withContext(Dispatchers.IO) { copyIn(uri) }
                importDone += 1
                if (id == null) {
                    // فشل نسخ الملف وظهرت رسالة
                } else if (id < 0) {
                    dup++
                } else {
                    added++
                    startIndex(id)
                }
            }
            if (importDone >= importTotal) {
                importTotal = 0
                importDone = 0
            }
            if (uris.size > 1 || dup > 0) {
                message = "تمت إضافة $added كتاب" + if (dup > 0) " وتخطي $dup مكرّر" else ""
            }
        }
    }

    fun importTree(tree: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val uris = withContext(Dispatchers.IO) { collectPdfs(DocumentFile.fromTreeUri(app, tree)) }
            if (uris.isEmpty()) message = "لم أجد ملفات PDF في هذا المجلد" else importUris(uris)
        }
    }

    private fun collectPdfs(dir: DocumentFile?): List<Uri> {
        val out = ArrayList<Uri>()
        dir?.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                out.addAll(collectPdfs(f))
            } else if (f.type == "application/pdf" || f.name?.endsWith(".pdf", true) == true) {
                out.add(f.uri)
            }
        }
        return out
    }

    private suspend fun copyIn(uri: Uri): Long? {
        val ctx = getApplication<Application>()
        val found: String? = try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
        val name = found ?: "كتاب"
        val title = name.removeSuffix(".pdf").removeSuffix(".PDF")
        val id = dao.insertBook(BookEntity(title = title))
        val f = File(booksDir(), "$id.pdf")
        return try {
            ctx.contentResolver.openInputStream(uri)!!.use { i ->
                f.outputStream().use { o -> i.copyTo(o) }
            }
            val count = PdfSource(f).use { it.pageCount }
            val dup = dao.allBooks().any { it.id != id && it.title == title && it.pageCount == count }
            if (dup) {
                f.delete()
                dao.deleteBookFully(id)
                -1L
            } else {
                dao.setPageCount(id, count)
                id
            }
        } catch (e: Exception) {
            f.delete()
            dao.deleteBookFully(id)
            message = "تعذّر فتح الملف: $title"
            null
        }
    }

    fun startIndex(id: Long, forceOcr: Boolean = false, restart: Boolean = false) {
        Work.jobs[id]?.cancel()
        val opts = options()
        val app = getApplication<Application>()
        Work.jobs[id] = Work.scope.launch {
            if (restart || forceOcr) dao.setStatus(id, ST_QUEUED)
            IndexService.start(app)
            Work.lock.withLock { Work.indexer(app).index(id, forceOcr, restart, opts) }
        }
    }

    fun reindexAll() {
        viewModelScope.launch {
            dao.allBooks().forEach { startIndex(it.id, forceOcr = false, restart = true) }
        }
    }

    // ---------- إدارة الكتب ----------
    fun editBook(id: Long, title: String, author: String) {
        viewModelScope.launch { dao.updateInfo(id, title, author.trim()) }
    }

    fun toggleFavorite(b: BookEntity) {
        viewModelScope.launch { dao.setFavorite(b.id, !b.favorite) }
    }

    fun saveLastPage(id: Long, page: Int) {
        viewModelScope.launch { dao.setLastPage(id, page) }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            Work.jobs.remove(id)?.cancelAndJoin()
            PageCache.drop(id)
            WordStore.deleteBook(getApplication<Application>(), id)
            withContext(Dispatchers.IO) {
                dao.deleteBookFully(id)
                File(booksDir(), "$id.pdf").delete()
                File(booksDir(), "$id.pending").delete()
                File(booksDir(), "$id.ocr").delete()
            }
            dropBookmarks(id)
            if (scopeBookId == id) scopeBookId = 0L
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            Work.jobs.values.toList().forEach { it.cancelAndJoin() }
            Work.jobs.clear()
            PageCache.dropAll()
            WordStore.deleteAll(getApplication<Application>())
            withContext(Dispatchers.IO) {
                dao.deleteAllPages()
                dao.deleteAllBooks()
                booksDir().listFiles()?.forEach { it.delete() }
            }
            dropBookmarks(null)
            scopeBookId = 0L
            results = emptyList()
            searchedOnce = false
        }
    }

    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) {
        val files = booksDir().listFiles()?.sumOf { it.length() } ?: 0L
        val db = getApplication<Application>().getDatabasePath("bahith.db").length()
        files + db
    }

    // ---------- البحث والتنزيل من المكتبة العامة ----------
    fun searchCatalog() {
        val q = catalogQuery.trim()
        if (q.length < 2) {
            catalogError = "اكتب كلمتين على الأقل للبحث في فهرس الكتب."
            catalogResults = emptyList()
            return
        }
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            catalogLoading = true
            catalogError = null
            try {
                catalogResults = withContext(Dispatchers.IO) { ArchiveCatalog.search(q) }
                if (catalogResults.isEmpty()) catalogError = "لم يُعثر على كتب PDF مطابقة. جرّب عبارة أخرى."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                catalogError = "تعذّر الاتصال بفهرس المكتبة العامة: ${e.message ?: "خطأ اتصال"}"
            } finally {
                catalogLoading = false
            }
        }
    }

    fun downloadCatalogBook(item: CatalogItem) {
        if (downloadingId != null) {
            message = "يوجد تنزيل جارٍ. انتظر حتى ينتهي قبل بدء تنزيل آخر."
            return
        }
        viewModelScope.launch {
            downloadingId = item.identifier
            downloadProgress = 0
            var temp: File? = null
            try {
                val result = withContext(Dispatchers.IO) { ArchiveCatalog.downloadPdf(item) { p -> downloadProgress = p } }
                temp = result
                val pageCount = withContext(Dispatchers.IO) { PdfSource(result).use { it.pageCount } }
                if (pageCount <= 0) throw IllegalStateException("الملف لا يحتوي على صفحات PDF صالحة")
                val id = dao.insertBook(BookEntity(title = item.title, author = item.creator))
                val destination = File(booksDir(), "$id.pdf")
                withContext(Dispatchers.IO) {
                    if (!result.renameTo(destination)) result.copyTo(destination, overwrite = true)
                    if (result.exists()) result.delete()
                }
                dao.setPageCount(id, pageCount)
                startIndex(id)
                message = "تم تنزيل «${item.title}» وإضافته إلى مكتبتك. ستبدأ فهرسته للبحث دون إنترنت."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                temp?.delete()
                catalogError = "فشل تنزيل الكتاب: ${e.message ?: "خطأ غير معروف"}"
            } finally {
                downloadingId = null
                downloadProgress = 0
            }
        }
    }

    // ---------- البحث ----------
    fun search() {
        searchJob?.cancel()
        val terms = Arabic.terms(query)
        if (terms.isEmpty() || terms.sumOf { it.length } < 2) {
            results = emptyList()
            searchedOnce = false
            searching = false
            return
        }
        val m = mode
        val scope = scopeBookId
        val limit = resultLimit
        val radius = when (snippetLen) {
            0 -> 50
            2 -> 150
            else -> 80
        }
        searchJob = viewModelScope.launch {
            searching = true
            val out = withContext(Dispatchers.Default) { runSearch(terms, m, scope, limit, radius) }
            lastTerms = out.second
            hlTerms = out.second
            results = out.first
            searchedOnce = true
            searching = false
        }
    }

    private suspend fun runSearch(
        terms: List<String>,
        m: SearchMode,
        scope: Long,
        limit: Int,
        radius: Int
    ): Pair<List<Hit>, List<String>> {
        val match = Arabic.ftsQuery(terms, m)
        val hl = if (m == SearchMode.PHRASE) listOf(terms.joinToString(" ")) else terms
        val sql = StringBuilder(
            "SELECT p.bookId AS bookId, p.pageNo AS pageNo, p.text AS text, b.title AS title " +
                "FROM pages_fts JOIN pages p ON p.id = pages_fts.rowid " +
                "JOIN books b ON b.id = p.bookId WHERE pages_fts MATCH ?"
        )
        val args = ArrayList<Any>()
        args.add(match)
        if (scope != 0L) {
            sql.append(" AND p.bookId = ?")
            args.add(scope)
        }
        sql.append(" ORDER BY b.title, p.pageNo LIMIT ").append(limit)
        val rows = try {
            dao.search(SimpleSQLiteQuery(sql.toString(), args.toTypedArray()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val hits = rows.map {
            val (snip, ranges) = Arabic.snippet(it.text, hl, radius)
            Hit(it.bookId, it.title, it.pageNo, snip, ranges)
        }
        return hits to hl
    }

    /** أرقام الصفحات المطابقة داخل كتاب واحد (للبحث من القارئ). */
    suspend fun searchInBook(bookId: Long, q: String): Pair<List<Int>, List<String>> {
        val terms = Arabic.terms(q)
        if (terms.isEmpty()) return Pair(emptyList(), emptyList())
        val m = if (terms.size >= 6) SearchMode.PHRASE else SearchMode.ALL
        val hl = if (m == SearchMode.PHRASE) listOf(terms.joinToString(" ")) else terms
        val sql = "SELECT p.bookId AS bookId, p.pageNo AS pageNo, '' AS text, '' AS title " +
            "FROM pages_fts JOIN pages p ON p.id = pages_fts.rowid " +
            "WHERE pages_fts MATCH ? AND p.bookId = ? ORDER BY p.pageNo LIMIT 3000"
        val rows = try {
            withContext(Dispatchers.Default) {
                dao.search(SimpleSQLiteQuery(sql, arrayOf<Any>(Arabic.ftsQuery(terms, m), bookId)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return Pair(rows.map { it.pageNo }, hl)
    }

    fun openHit(h: Hit) {
        addHistory(query)
        reader = ReaderTarget(h.bookId, h.pageNo, lastTerms)
    }

    suspend fun pageText(bookId: Long, pageNo: Int): String =
        dao.pageText(bookId, pageNo) ?: ""
}
