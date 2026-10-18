package io.github.nanomuse.voice

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.openminis.app.MainActivity

/**
 * 一个没有界面的入口，只做一件事：把"语音对话"深链交给 MainActivity。
 *
 * 为什么需要它：小爱同学的"训练 → 打开应用"只能启动带 LAUNCHER 的组件，
 * 不能发 Intent、也点不了 App 里的按钮。把它做成一个独立图标（label 叫
 * "语音对话"），小爱就能"打开"它，于是等于按了一下语音键。
 *
 * 链路与桌面长按图标的"语音对话"快捷方式完全一致：
 *   minis://action/voice_chat
 *     → DeepLinkHandler → DeepLinkAction.NewVoiceChat
 *     → DeepLinkCoordinator.setPendingChatAction(START_VOICE)
 *     → ChatScreen 触发 triggerVoiceInput()
 *
 * 放置位置：
 *   android/src/android/app/src/main/java/io/github/nanomuse/voice/VoiceEntryActivity.kt
 */
class VoiceEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val target = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("minis://action/voice_chat")
            // MainActivity 是 singleTask：已经开着时走 onNewIntent，
            // 于是"再说一次口令"等于"再按一次语音键"，对话不丢。
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(target)
        finish()
    }
}
