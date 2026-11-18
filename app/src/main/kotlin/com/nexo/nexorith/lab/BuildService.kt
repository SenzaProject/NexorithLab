package com.nexo.nexorith.lab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service: menjaga proses tetap hidup saat build berjalan, menampilkan progres
 * di notifikasi (dengan tombol Batalkan), dan memberi tahu hasil akhir.
 */
class BuildService : Service(), BuildObserver {

    companion object {
        private const val CHANNEL = "build"
        private const val ONGOING_ID = 42
        private const val RESULT_ID = 43
        const val ACTION_CANCEL = "com.nexo.nexorith.lab.CANCEL_BUILD"

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, BuildService::class.java))
            } catch (e: Exception) {
                // gagal memulai service (mis. aplikasi di latar belakang): build tetap jalan tanpa notifikasi
            }
        }
    }

    private lateinit var nm: NotificationManager
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Build APK", NotificationManager.IMPORTANCE_LOW))
        BuildManager.observers.add(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            BuildManager.cancel()
            return START_NOT_STICKY
        }
        val n = ongoing("Menyiapkan…", 0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ONGOING_ID, n)
        }
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NexorithLab:build").apply { acquire(30 * 60 * 1000L) }
        }
        if (!BuildManager.building) finishService()
        return START_NOT_STICKY
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun ongoing(text: String, step: Int): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, BuildService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_build)
            .setContentTitle("Membangun ${BuildManager.project ?: "proyek"}")
            .setContentText(text)
            .setProgress(LabBuilder.TOTAL_STEPS, step, step == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Batalkan", cancel).build())
            .build()
    }

    private fun finishService() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onLog(text: String) {}

    override fun onStep(n: Int, total: Int, text: String) {
        nm.notify(ONGOING_ID, ongoing("Langkah $n dari $total: $text", n))
    }

    override fun onFinish(success: Boolean) {
        val title = if (success) "Build berhasil" else "Build gagal"
        val text = if (success) "APK siap dipasang. Ketuk untuk membuka." else (BuildManager.lastError?.message?.lineSequence()?.firstOrNull() ?: "Ketuk untuk melihat detail.")
        nm.notify(
            RESULT_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_build)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
        )
        finishService()
    }

    override fun onDestroy() {
        BuildManager.observers.remove(this)
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
