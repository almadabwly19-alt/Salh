package com.bahith.pdf

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap

/** مكان واحد لأعمال الفهرسة، يبقى حياً حتى لو أُغلقت الشاشة. */
object Work {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val lock = Mutex()
    val jobs = ConcurrentHashMap<Long, Job>()

    /** عدد الصفحات المتبقية لتحسين نصها بالـ OCR، لكل كتاب. */
    val refineLeft = mutableStateMapOf<Long, Int>()
    val refineStartLeft = mutableStateMapOf<Long, Int>()
    val refineStartMs = mutableStateMapOf<Long, Long>()

    /** رسالة آخر خطأ في فهرسة كل كتاب. */
    val errors = mutableStateMapOf<Long, String>()

    /** إيقاف مؤقت لكل أعمال الفهرسة. */
    var paused by mutableStateOf(false)

    @Volatile
    private var idx: Indexer? = null

    fun indexer(ctx: Context): Indexer = idx ?: synchronized(this) {
        idx ?: Indexer(ctx.applicationContext, AppDb.get(ctx).dao()).also { idx = it }
    }
}

/** خدمة أمامية تُبقي التطبيق يعمل أثناء الفهرسة، وتتوقف وحدها عند الانتهاء. */
class IndexService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel("index", "الفهرسة", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "index")
            .setContentTitle("باحث PDF")
            .setContentText("جارٍ فهرسة الكتب…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher == null) {
            val dao = AppDb.get(this).dao()
            watcher = Work.scope.launch {
                dao.observeBooks().collect { list ->
                    val busy = list.any {
                        it.status == ST_QUEUED || it.status == ST_INDEXING || it.status == ST_REFINING
                    }
                    if (!busy) stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, IndexService::class.java))
            } catch (e: Exception) {
                // إن منع النظام الخدمة نكمل الفهرسة داخل التطبيق فقط
            }
        }
    }
}
