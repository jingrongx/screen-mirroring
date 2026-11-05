package io.github.jingrongx.screenmirror

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MIRROR, getString(R.string.mirror_notification_channel), NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_UPDATE, getString(R.string.update_notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    companion object {
        const val CHANNEL_MIRROR = "mirror"
        const val CHANNEL_UPDATE = "update"
    }
}
