package io.github.nanomuse.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * nanoMuse: a launcher entry that turns the hotword listener on.
 *
 * Shows as its own icon in the app drawer (label 唤醒监听). Tapping it once
 * asks for the microphone permission if needed, starts [HotwordService], and
 * closes. The listener then runs continuously (this phone stays plugged in);
 * say 小助手 to start a hands-free conversation.
 */
class HotwordEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            Toast.makeText(this, "需要麦克风权限才能开启唤醒监听", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun startListening() {
        HotwordService.start(this)
        Toast.makeText(this, "唤醒监听已开启：说「小助手」开始对话", Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private const val REQ_MIC = 41
    }
}
