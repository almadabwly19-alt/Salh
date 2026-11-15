package com.bahith.pdf

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

const val ST_QUEUED = 0
const val ST_INDEXING = 1
const val ST_DONE = 2
const val ST_ERROR = 3
const val ST_REFINING = 4

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val pageCount: Int = 0,
    val indexedPages: Int = 0,
    val status: Int = ST_QUEUED,
    val addedAt: Long = System.currentTimeMillis(),
    val author: String = "",
    val favorite: Boolean = false,
    val lastPage: Int = 1
)

@Entity(tableName = "pages", indices = [Index("bookId")])
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val pageNo: Int,
    val text: String,
    val norm: String
)

// فهرس البحث السريع (FTS4) مرتبط بجدول الصفحات ويتحدّث تلقائياً
@Fts4(contentEntity = PageEntity::class)
@Entity(tableName = "pages_fts")
data class PageFts(val norm: String)

data class IdText(val id: Long, val text: String)

data class SearchRow(
    val bookId: Long,
    val pageNo: Int,
    val text: String,
    val title: String
)

@Dao
abstract class BookDao {
    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    abstract fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    abstract suspend fun getBook(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE status = 0 OR status = 1 OR status = 4")
    abstract suspend fun unfinished(): List<BookEntity>

    @Insert
    abstract suspend fun insertBook(b: BookEntity): Long

    @Insert
    abstract suspend fun insertPages(p: List<PageEntity>)

    @Query("UPDATE books SET title = :title, author = :author WHERE id = :id")
    abstract suspend fun updateInfo(id: Long, title: String, author: String)

    @Query("UPDATE books SET favorite = :fav WHERE id = :id")
    abstract suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE books SET lastPage = :page WHERE id = :id")
    abstract suspend fun setLastPage(id: Long, page: Int)

    @Query("SELECT * FROM books")
    abstract suspend fun allBooks(): List<BookEntity>

    @Query("DELETE FROM pages")
    abstract suspend fun deleteAllPages()

    @Query("DELETE FROM books")
    abstract suspend fun deleteAllBooks()

    @Query("UPDATE books SET pageCount = :count WHERE id = :id")
    abstract suspend fun setPageCount(id: Long, count: Int)

    @Query("UPDATE books SET status = :status WHERE id = :id")
    abstract suspend fun setStatus(id: Long, status: Int)

    @Query("UPDATE books SET indexedPages = :n, status = :status WHERE id = :id")
    abstract suspend fun setProgress(id: Long, n: Int, status: Int)

    @Query("DELETE FROM pages WHERE bookId = :id")
    abstract suspend fun deletePages(id: Long)

    @Query("DELETE FROM books WHERE id = :id")
    abstract suspend fun deleteBook(id: Long)

    @Query("SELECT text FROM pages WHERE bookId = :bookId AND pageNo = :pageNo LIMIT 1")
    abstract suspend fun pageText(bookId: Long, pageNo: Int): String?

    @Query("UPDATE pages SET text = :text, norm = :norm WHERE bookId = :bookId AND pageNo = :pageNo")
    abstract suspend fun updatePage(bookId: Long, pageNo: Int, text: String, norm: String)

    @Query("UPDATE pages SET norm = :norm WHERE bookId = :bookId AND pageNo = :pageNo")
    abstract suspend fun setNormForPage(bookId: Long, pageNo: Int, norm: String)

    @Query("SELECT id, text FROM pages WHERE id > :after ORDER BY id LIMIT :n")
    abstract suspend fun textBatch(after: Long, n: Int): List<IdText>

    @Query("UPDATE pages SET norm = :norm WHERE id = :id")
    abstract suspend fun setNorm(id: Long, norm: String)

    @Transaction
    open suspend fun updateMany(items: List<PageEntity>) {
        for (e in items) updatePage(e.bookId, e.pageNo, e.text, e.norm)
    }

    @Transaction
    open suspend fun renormBatch(items: List<IdText>) {
        for (e in items) setNorm(e.id, Arabic.normalize(e.text))
    }

    @Transaction
    open suspend fun deleteBookFully(id: Long) {
        deletePages(id)
        deleteBook(id)
    }

    @RawQuery
    abstract suspend fun search(query: SupportSQLiteQuery): List<SearchRow>
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN author TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE books ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE books ADD COLUMN lastPage INTEGER NOT NULL DEFAULT 1")
    }
}

@Database(
    entities = [BookEntity::class, PageEntity::class, PageFts::class],
    version = 2,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): BookDao

    companion object {
        @Volatile
        private var inst: AppDb? = null

        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "bahith.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { inst = it }
        }
    }
}
