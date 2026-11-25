package com.soda.adhelper.core

import android.content.Context
import android.media.AudioManager
import com.soda.adhelper.model.LogLevel

class AudioMuteController(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedVolume: Int? = null

    fun muteIfNeeded(enabled: Boolean) {
        if (!enabled) return
        if (savedVolume != null) return
        runCatching {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            savedVolume = current
            if (current > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                AutomationRuntime.log(LogLevel.INFO, "已自动静音媒体音量（原音量：$current）")
            }
        }.onFailure {
            AutomationRuntime.log(LogLevel.WARN, "自动静音失败：${it.message}")
        }
    }

    fun restoreIfMuted() {
        val original = savedVolume ?: return
        savedVolume = null
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
            AutomationRuntime.log(LogLevel.INFO, "已恢复原媒体音量：$original")
        }.onFailure {
            AutomationRuntime.log(LogLevel.WARN, "恢复媒体音量失败：${it.message}")
        }
    }
}
