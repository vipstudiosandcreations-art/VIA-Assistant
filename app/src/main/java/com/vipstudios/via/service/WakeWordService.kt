package com.vipstudios.via.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.vipstudios.via.MainActivity

/** Plug-in point for the real "Hey VIA" detector (planned for a later stage). */
interface WakeWordEngine {
    fun start(onWake: () -> Unit)
    fun stop()
}

/**
 * Foreground service shell for background listening.
 *
 * STAGE 1 STATUS: this is only the Android plumbing (foreground service, microphone type,
 * persistent notification with a Stop button). It does NOT listen to the microphone and does
 * NOT detect "Hey VIA" yet. A wake-word engine must be added behind [WakeWordEngine].
 */
class WakeWordService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "VIA background listening", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("VIA is in the background")
            .setContentText("Wake-word engine not installed yet. Not listening.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stop).build())
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30)
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF_ID, n)
            running = true
        } catch (e: Exception) {
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.vipstudios.via.STOP_WAKE"
        private const val CHANNEL = "via_wake"
        private const val NOTIF_ID = 42
        @Volatile var running = false
    }
}
