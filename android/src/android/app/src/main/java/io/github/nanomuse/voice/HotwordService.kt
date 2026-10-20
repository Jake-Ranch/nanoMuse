package io.github.nanomuse.voice

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.ui.chat.voice.VoiceModePrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * nanoMuse: an always-on wake-word listener.
 *
 * Route B of the hotword plan: reuse the app's own Silero VAD plus the
 * configured cloud ASR. The VAD opens the mic, waits for speech, closes a
 * segment after ~1.3 s of trailing silence, the provider (SiliconFlow
 * SenseVoice) transcribes it, and the text is checked against [WAKE_WORDS].
 * A match stops the listener, opens the chat's voice mode (the same path as
 * the "语音对话" entry), and the listener re-arms once the conversation
 * winds down.
 *
 * Zero tokens while idle: the VAD is local; a cloud transcription only runs
 * when speech was actually heard.
 */
class HotwordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        // Always call startForeground at least once, with the microphone type
        // on Q+, so a startForegroundService never trips the 5-second rule.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, build())
            }
        } catch (e: Exception) {
            lastStartError = e.message ?: e.javaClass.simpleName
            isRunning = false
            runCatching { startForeground(NOTIFICATION_ID, build()) }
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        SpeechRecognitionManager.stopRecording()
        super.onDestroy()
    }

    private fun startListening() {
        scope.launch { loop() }
    }

    private suspend fun loop() {
        // nanoMuse: lazy-start the built-in offline ASR.
        io.github.nanomuse.voice.LocalAsr.ensureInit(this)
        while (true) {
            updateNotification()
            // Pause while the person is using the app, so the listener never
            // competes with the voice panel for the microphone.
            if (isAppForeground()) {
                delay(5_000)
                continue
            }
            if (VoiceModePrefs.isVoiceActive) {
                delay(5_000)
                continue
            }
            // nanoMuse: wait for the offline model; never fall back to the
            // discontinued system recognizer.
            if (!io.github.nanomuse.voice.LocalAsr.isReady) {
                delay(3_000)
                continue
            }
            if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) {
                delay(3_000)
                continue
            }
            val text = listenOnce()
            when {
                text == null -> delay(30_000) // engine error or no ASR: back off
                WAKE_WORDS.any { text.contains(it) } -> {
                    SpeechRecognitionManager.stopRecording()
                    openVoiceChat()
                    delay(90_000) // let the conversation happen, then re-arm
                }
                else -> delay(300)
            }
        }
    }

    /** One VAD capture + transcription; null on error or timeout. */
    private suspend fun listenOnce(): String? {
        val result = CompletableDeferred<String?>()
        SpeechRecognitionManager.startRecording(
            onPartialOrFinal = { text, isFinal ->
                if (isFinal && text.isNotBlank() && !result.isCompleted) {
                    result.complete(text)
                }
            },
            onError = { _, _ ->
                if (!result.isCompleted) result.complete(null)
            },
        )
        return withTimeoutOrNull(60_000) { result.await() }
    }

    private fun openVoiceChat() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("minis://action/voice_chat")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        runCatching { startActivity(intent) }
    }

    private fun isAppForeground(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.runningAppProcesses?.any {
            it.processName == packageName &&
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } == true
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, build()) }
    }

    private fun build(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "唤醒词监听", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, HotwordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stateText = when (io.github.nanomuse.voice.LocalAsr.state) {
            "ready" -> "说「小助手」开始对话"
            "failed" -> "本地识别加载失败：" + (io.github.nanomuse.voice.LocalAsr.lastError ?: "未知原因")
            else -> "离线识别模型加载中，请稍候"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nanomuse)
            .setContentTitle("nanoMuse 正在听唤醒词")
            .setContentText(stateText + "，点停止结束")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "停止监听", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "nanomuse_hotword"
        private const val NOTIFICATION_ID = 9109
        private const val ACTION_STOP = "io.github.nanomuse.voice.HOTWORD_STOP"

        /** Live service state, shown by the diagnostic panel. */
        @Volatile
        var isRunning: Boolean = false

        /** Why the last start attempt failed, if it did. */
        @Volatile
        var lastStartError: String? = null

        /** Spoken phrases that wake the listener. Order is irrelevant. */
        val WAKE_WORDS = listOf("小助手", "小缪斯", "缪斯")

        fun start(context: Context) {
            val intent = Intent(context, HotwordService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Background start restrictions: start again from the foreground.
                lastStartError = e.message ?: e.javaClass.simpleName
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HotwordService::class.java))
        }
    }
}
