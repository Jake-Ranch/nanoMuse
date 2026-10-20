package io.github.nanomuse.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * nanoMuse: a diagnostic + control panel for the hotword listener.
 *
 * Shows the three facts that matter (mic permission, service state, offline
 * model state with its failure reason) and offers start/stop buttons. The
 * status refreshes every second, so the model's 加载中 → 就绪/失败 transition
 * is visible on screen.
 */
class HotwordEntryActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.WHITE)
        }
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            setLineSpacing(6f, 1f)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "开启唤醒监听"
            setOnClickListener { ensureMicThenStart() }
        })
        root.addView(Button(this).apply {
            text = "停止监听"
            setOnClickListener {
                HotwordService.stop(this@HotwordEntryActivity)
                refresh()
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        refreshLoop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun ensureMicThenStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        HotwordService.start(this)
        refresh()
    }

    private fun refreshLoop() {
        handler.postDelayed({ if (!isFinishing) { refresh(); refreshLoop() } }, 1_000)
    }

    private fun refresh() {
        val mic = if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            "已授予"
        } else {
            "未授予"
        }
        val svc = if (HotwordService.isRunning) "运行中" else "未运行"
        val asr = LocalAsr.state + (LocalAsr.lastError?.let { "（$it）" } ?: "")
        val startErr = HotwordService.lastStartError?.let { "\n启动错误：$it" } ?: ""
        status.text =
            "麦克风权限：$mic\n" +
                "唤醒服务：$svc\n" +
                "离线模型：$asr\n" +
                "唤醒词：小助手 / 小缪斯 / 缪斯$startErr\n\n" +
                "说明：模型首次加载约需 10-30 秒，状态会自动刷新。"
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val REQ_MIC = 41
    }
}
