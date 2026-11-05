package io.github.jingrongx.screenmirror.host

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.jingrongx.screenmirror.App
import io.github.jingrongx.screenmirror.R

/** 受控端前台服务：持有投屏授权、保持采集与信令连接 */
class MirrorHostService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val audio = intent?.getBooleanExtra(EXTRA_AUDIO, false) ?: false
        val data: Intent? = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (resultCode == Int.MIN_VALUE || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        HostSession.start(this, resultCode, data, audio)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        HostSession.stop()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, App.CHANNEL_MIRROR)
            .setSmallIcon(R.drawable.ic_stat_mirror)
            .setContentTitle(getString(R.string.mirror_notification_title))
            .setContentText(getString(R.string.mirror_notification_text))
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_AUDIO = "audio"

        fun start(context: Context, resultCode: Int, data: Intent, audio: Boolean) {
            val intent = Intent(context, MirrorHostService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_AUDIO, audio)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MirrorHostService::class.java))
        }
    }
}
