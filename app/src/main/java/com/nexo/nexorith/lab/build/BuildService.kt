package com.nexo.nexorith.lab.build

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nexo.nexorith.lab.R
import java.io.File

/** Menjalankan build sebagai foreground service dengan notifikasi progres dan tombol Batal. */
class BuildService : Service() {

    companion object {
        const val ACTION_START = "com.nexo.nexorith.lab.action.BUILD_START"
        const val ACTION_CANCEL = "com.nexo.nexorith.lab.action.BUILD_CANCEL"
        const val EXTRA_PROJECT = "project"
        const val CHANNEL_ID = "build"
        private const val NOTIF_ONGOING = 1001
        private const val NOTIF_RESULT = 1002
    }

    @Volatile
    private var cancelled = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CANCEL) {
            if (worker?.isAlive == true) {
                cancelled = true
                worker?.interrupt()
            } else {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        val path = intent?.getStringExtra(EXTRA_PROJECT)
        if (action != ACTION_START || path == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val dir = File(path)
        ServiceCompat.startForeground(
            this,
            NOTIF_ONGOING,
            buildNotification(dir.name, "Memulai...", 0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        if (worker?.isAlive != true) {
            cancelled = false
            worker = Thread({ runBuild(dir) }, "nexorith-build").also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        worker?.interrupt()
        super.onDestroy()
    }

    private fun runBuild(dir: File) {
        val stages = BuildPipeline.default()
        BuildState.begin(dir.name, stages.size)
        BuildState.log("== Build ${dir.name} ==")
        val ctx = BuildContext(dir, { BuildState.log(it) }, { cancelled })

        val result = try {
            for ((i, stage) in stages.withIndex()) {
                ctx.checkCancelled()
                if (!stage.needed(ctx)) {
                    BuildState.log("[lewati] ${stage.name}")
                    continue
                }
                BuildState.stage(i + 1, stage.name)
                BuildState.log("[${i + 1}/${stages.size}] ${stage.name}")
                notifyOngoing(dir.name, stage.name, i + 1, stages.size)
                stage.run(ctx)
            }
            "Build selesai tanpa error"
        } catch (e: BuildCancelled) {
            "Build dibatalkan"
        } catch (e: InterruptedException) {
            "Build dibatalkan"
        } catch (e: StageFailure) {
            "Build gagal: ${e.message}"
        } catch (e: Throwable) {
            "Build error: ${e.javaClass.simpleName}: ${e.message}"
        }

        BuildState.log(result)
        BuildState.finish(result)

        val done = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_build)
            .setContentTitle(dir.name)
            .setContentText(result)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_RESULT, done)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notifyOngoing(project: String, stage: String, index: Int, total: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ONGOING, buildNotification(project, "Tahap $index/$total: $stage", index, total))
    }

    private fun buildNotification(project: String, text: String, index: Int, total: Int): Notification {
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, BuildService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_build)
            .setContentTitle("Membangun $project")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, index, total == 0)
            .addAction(0, "Batal", cancel)
            .build()
    }
}
