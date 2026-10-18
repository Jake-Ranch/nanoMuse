package io.github.nanomuse.voice

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
import androidx.core.app.NotificationCompat
import com.openminis.app.R

/**
 * nanoMuse: a microphone foreground service.
 *
 * Android 11+ cuts an app's AudioRecord the moment it loses focus unless a
 * foreground service of type `microphone` is running. This service exists so
 * the person can keep talking while nanoMuse is backgrounded (for example,
 * while Hands is operating another app).
 *
 * Stage 1: posts the "listening" notification and keeps the service alive with
 * the microphone type declared. The actual background capture loop (VAD +
 * recognition + handing the transcript to the chat) is a later stage; until it
 * lands, the service only reserves the microphone entitlement.
 */
class HandsFreeVoiceService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, build())
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun build(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "正在聆听", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, HandsFreeVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nanomuse)
            .setContentTitle("nanoMuse 正在聆听")
            .setContentText("麦克风保持开启，点「停止聆听」结束")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .apply { if (open != null) setContentIntent(open) }
            .addAction(0, "停止聆听", stop)
            .build()
    }

    companion object {
        private const val TAG = "HandsFreeVoiceService"
        private const val CHANNEL = "nanomuse_hands_free_voice"
        private const val NOTIFICATION_ID = 9108
        private const val ACTION_STOP = "io.github.nanomuse.voice.STOP"

        fun start(context: Context) {
            val intent = Intent(context, HandsFreeVoiceService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Background start restrictions: start again on the next foreground entry.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HandsFreeVoiceService::class.java))
        }
    }
}
