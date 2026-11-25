package com.soda.adhelper.core

import android.content.Context
import com.soda.adhelper.data.ConfigRepository
import com.soda.adhelper.model.AdPhase
import com.soda.adhelper.model.AutomationConfig
import com.soda.adhelper.model.AutomationSnapshot
import com.soda.adhelper.model.LogEntry
import com.soda.adhelper.model.LogLevel
import com.soda.adhelper.model.RunStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AutomationRuntime {
    private const val MAX_LOGS = 300
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    private var repository: ConfigRepository? = null

    private val _config = MutableStateFlow(AutomationConfig())
    val config: StateFlow<AutomationConfig> = _config.asStateFlow()

    private val _snapshot = MutableStateFlow(AutomationSnapshot())
    val snapshot: StateFlow<AutomationSnapshot> = _snapshot.asStateFlow()

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _lastNodeDump = MutableStateFlow("尚未抓取节点快照。开启无障碍服务后，点击下方按钮可查看当前页面控件信息。")
    val lastNodeDump: StateFlow<String> = _lastNodeDump.asStateFlow()

    private val _nodeDumpRequestCounter = MutableStateFlow(0)
    val nodeDumpRequestCounter: StateFlow<Int> = _nodeDumpRequestCounter.asStateFlow()

    fun init(context: Context) {
        if (repository == null) {
            repository = ConfigRepository(context.applicationContext)
            _config.value = repository!!.load()
        }
    }

    fun updateConfig(newConfig: AutomationConfig) {
        _config.value = newConfig
        repository?.save(newConfig)
        log(LogLevel.INFO, "已保存配置：目标 ${newConfig.maxAdCount} 次，最长 ${newConfig.maxRunMinutes} 分钟，超时 ${newConfig.adTimeoutSeconds} 秒")
    }

    fun resetConfig() {
        val default = repository?.reset() ?: AutomationConfig()
        _config.value = default
        log(LogLevel.INFO, "已恢复默认规则配置")
    }

    fun setServiceConnected(connected: Boolean) {
        _serviceConnected.value = connected
        log(
            if (connected) LogLevel.INFO else LogLevel.WARN,
            if (connected) "无障碍服务已连接" else "无障碍服务已断开"
        )
        if (!connected && _snapshot.value.status == RunStatus.RUNNING) {
            pause("无障碍服务意外断开")
        }
    }

    fun startNewSession() {
        val now = System.currentTimeMillis()
        _snapshot.value = AutomationSnapshot(
            status = RunStatus.RUNNING,
            phase = AdPhase.WAITING_APP,
            completedAds = 0,
            startedAtMs = now,
            lastMessage = "正在启动汽水音乐…",
            pauseReason = null,
            currentPackageName = _snapshot.value.currentPackageName
        )
        log(LogLevel.INFO, "开始新一轮自动领时长任务")
    }

    fun resumeSession() {
        val current = _snapshot.value
        val startedAt = if (current.startedAtMs > 0L) current.startedAtMs else System.currentTimeMillis()
        _snapshot.value = current.copy(
            status = RunStatus.RUNNING,
            phase = AdPhase.WAITING_APP,
            startedAtMs = startedAt,
            lastMessage = "任务已恢复，正在检测汽水音乐界面…",
            pauseReason = null
        )
        log(LogLevel.INFO, "已恢复自动任务（当前已完成 ${current.completedAds} 次）")
    }

    fun pause(reason: String) {
        _snapshot.update {
            it.copy(
                status = RunStatus.PAUSED,
                lastMessage = reason,
                pauseReason = reason
            )
        }
        log(LogLevel.WARN, "任务暂停：$reason")
    }

    fun complete(reason: String) {
        _snapshot.update {
            it.copy(
                status = RunStatus.COMPLETED,
                lastMessage = reason,
                pauseReason = null
            )
        }
        log(LogLevel.INFO, "任务完成：$reason")
    }

    fun stop(reason: String = "用户已停止任务") {
        _snapshot.update {
            it.copy(
                status = RunStatus.IDLE,
                phase = AdPhase.WAITING_APP,
                lastMessage = reason,
                pauseReason = null
            )
        }
        log(LogLevel.INFO, reason)
    }

    fun updatePhase(phase: AdPhase, message: String) {
        _snapshot.update {
            it.copy(
                phase = phase,
                lastMessage = message
            )
        }
    }

    fun incrementCompletedAds(message: String) {
        _snapshot.update {
            it.copy(
                completedAds = it.completedAds + 1,
                lastMessage = message
            )
        }
    }

    fun updateCurrentPackage(pkg: String) {
        if (pkg.isNotBlank() && pkg != _snapshot.value.currentPackageName) {
            _snapshot.update { it.copy(currentPackageName = pkg) }
        }
    }

    fun requestNodeDump() {
        _nodeDumpRequestCounter.update { it + 1 }
    }

    fun updateNodeDump(dump: String) {
        _lastNodeDump.value = dump
    }

    fun log(level: LogLevel, message: String) {
        val ts = synchronized(timeFormat) { timeFormat.format(Date()) }
        val entry = LogEntry(timestamp = ts, level = level, message = message)
        _logs.update { current ->
            val updated = current + entry
            if (updated.size > MAX_LOGS) updated.takeLast(MAX_LOGS) else updated
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
