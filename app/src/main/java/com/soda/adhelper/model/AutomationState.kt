package com.soda.adhelper.model

enum class RunStatus(val label: String) {
    IDLE("未运行"),
    RUNNING("运行中"),
    PAUSED("已暂停"),
    COMPLETED("已完成"),
    ERROR("异常停止")
}

enum class AdPhase(val label: String) {
    WAITING_APP("等待进入汽水音乐"),
    FINDING_ENTRY("寻找广告入口"),
    WATCHING_AD("等待广告播放完成"),
    CLOSING_AD("关闭广告并返回")
}

data class AutomationSnapshot(
    val status: RunStatus = RunStatus.IDLE,
    val phase: AdPhase = AdPhase.WAITING_APP,
    val completedAds: Int = 0,
    val startedAtMs: Long = 0L,
    val lastMessage: String = "准备就绪，点击开始后将自动打开汽水音乐",
    val pauseReason: String? = null,
    val currentPackageName: String = ""
)

enum class LogLevel {
    INFO,
    WARN,
    ERROR
}

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val message: String
)
