package com.nexo.nexorith.lab

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.nexo.nexorith.lab.build.BuildService

class NexorithApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            BuildService.CHANNEL_ID,
            "Build",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
