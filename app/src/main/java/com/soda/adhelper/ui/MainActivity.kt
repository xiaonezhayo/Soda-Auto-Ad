package com.soda.adhelper.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soda.adhelper.core.AutomationRuntime
import com.soda.adhelper.core.NotificationHelper
import com.soda.adhelper.model.AutomationConfig
import com.soda.adhelper.model.AutomationSnapshot
import com.soda.adhelper.model.LogEntry
import com.soda.adhelper.model.LogLevel
import com.soda.adhelper.model.RunStatus
import com.soda.adhelper.service.FloatingOverlayService
import com.soda.adhelper.ui.theme.SodaAdHelperTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutomationRuntime.init(this)
        NotificationHelper.ensureChannels(this)

        setContent {
            SodaAdHelperTheme {
                SodaAdHelperApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SodaAdHelperApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val snapshot by AutomationRuntime.snapshot.collectAsStateWithLifecycle()
    val serviceConnected by AutomationRuntime.serviceConnected.collectAsStateWithLifecycle()
    val config by AutomationRuntime.config.collectAsStateWithLifecycle()
    val logs by AutomationRuntime.logs.collectAsStateWithLifecycle()
    val nodeDump by AutomationRuntime.lastNodeDump.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasNotificationPermission by remember { mutableStateOf(checkNotificationPermission(context)) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                hasNotificationPermission = checkNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "汽水广告助手",
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = "控制") },
                    label = { Text("控制") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "规则配置") },
                    label = { Text("规则配置") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.BugReport, contentDescription = "调试日志") },
                    label = { Text("调试日志") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> DashboardTab(
                    snapshot = snapshot,
                    serviceConnected = serviceConnected,
                    hasOverlayPermission = hasOverlayPermission,
                    hasNotificationPermission = hasNotificationPermission,
                    onOpenAccessibilitySettings = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onOpenOverlaySettings = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onStart = {
                        if (!serviceConnected) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("请先开启“汽水广告助手无障碍服务”")
                            }
                            return@DashboardTab
                        }
                        if (hasOverlayPermission) {
                            FloatingOverlayService.show(context)
                        }
                        AutomationRuntime.startNewSession()
                    },
                    onResume = {
                        if (hasOverlayPermission) {
                            FloatingOverlayService.show(context)
                        }
                        AutomationRuntime.resumeSession()
                    },
                    onPause = {
                        AutomationRuntime.pause("用户手动暂停任务")
                    },
                    onStop = {
                        AutomationRuntime.stop("用户已停止任务")
                        FloatingOverlayService.hide(context)
                    }
                )

                1 -> SettingsTab(
                    config = config,
                    onSave = { updated ->
                        AutomationRuntime.updateConfig(updated)
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("配置已保存并立即生效")
                        }
                    },
                    onReset = {
                        AutomationRuntime.resetConfig()
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("已恢复默认规则")
                        }
                    }
                )

                2 -> DebugLogsTab(
                    logs = logs,
                    nodeDump = nodeDump,
                    serviceConnected = serviceConnected,
                    onRequestNodeDump = {
                        if (!serviceConnected) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("请先开启无障碍服务")
                            }
                        } else {
                            AutomationRuntime.requestNodeDump()
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("已请求抓取当前节点快照")
                            }
                        }
                    },
                    onCopyText = { label, text ->
                        copyToClipboard(context, label, text)
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("已复制$label")
                        }
                    },
                    onClearLogs = {
                        AutomationRuntime.clearLogs()
                    }
                )
            }
        }
    }
}

@Composable
private fun DashboardTab(
    snapshot: AutomationSnapshot,
    serviceConnected: Boolean,
    hasOverlayPermission: Boolean,
    hasNotificationPermission: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStart: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Status overview card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "当前状态：${snapshot.status.label}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "已领 ${snapshot.completedAds} 次",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (snapshot.status == RunStatus.RUNNING) {
                    Text(
                        text = "执行阶段：${snapshot.phase.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Text(
                    text = snapshot.lastMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF4B5563)
                )

                snapshot.pauseReason?.let { reason ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFEF3C7), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "暂停原因：$reason",
                            color = Color(0xFF92400E),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // Action buttons
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (snapshot.status) {
                    RunStatus.RUNNING -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(
                                onClick = onPause,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("暂停")
                            }
                            Button(
                                onClick = onStop,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("停止任务")
                            }
                        }
                    }

                    RunStatus.PAUSED -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onResume,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("继续任务")
                            }
                            OutlinedButton(
                                onClick = onStop,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("停止")
                            }
                        }
                    }

                    else -> {
                        Button(
                            onClick = onStart,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text("一键启动并打开汽水音乐", fontSize = 16.sp)
                        }
                    }
                }
            }
        }

        // Permission checklist card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "权限检查",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                PermissionRow(
                    title = "无障碍服务（必需）",
                    granted = serviceConnected,
                    actionLabel = if (serviceConnected) "已开启" else "去开启",
                    onClick = onOpenAccessibilitySettings
                )
                PermissionRow(
                    title = "悬浮窗控制条（推荐）",
                    granted = hasOverlayPermission,
                    actionLabel = if (hasOverlayPermission) "已授权" else "去授权",
                    onClick = onOpenOverlaySettings
                )
                PermissionRow(
                    title = "状态与暂停通知（推荐）",
                    granted = hasNotificationPermission,
                    actionLabel = if (hasNotificationPermission) "已授权" else "去授权",
                    onClick = onRequestNotificationPermission
                )
            }
        }

        // Usage tips
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "保守模式说明",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF065F46)
                )
                Text(
                    text = "• 启动前建议先在汽水音乐内进入“免费听歌时长”页面，或停留在带“看广告/领时长”入口的界面。\n" +
                        "• 运行期间若跳出汽水音乐、广告超时或出现人机验证，会立即暂停并发送通知提醒你处理。\n" +
                        "• 若新版汽水音乐文案有变化，可在「调试日志」抓取节点快照后到「规则配置」微调关键词。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF065F46)
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (granted) Color(0xFF0E9F6E) else Color(0xFFD97706)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onClick) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun SettingsTab(
    config: AutomationConfig,
    onSave: (AutomationConfig) -> Unit,
    onReset: () -> Unit
) {
    var targetPkg by remember(config) { mutableStateOf(config.targetPackageName) }
    var maxAdCount by remember(config) { mutableStateOf(config.maxAdCount.toString()) }
    var maxRunMinutes by remember(config) { mutableStateOf(config.maxRunMinutes.toString()) }
    var adTimeoutSeconds by remember(config) { mutableStateOf(config.adTimeoutSeconds.toString()) }
    var muteDuringRun by remember(config) { mutableStateOf(config.muteDuringRun) }

    var entryKw by remember(config) { mutableStateOf(AutomationConfig.formatKeywords(config.entryKeywords)) }
    var rewardKw by remember(config) { mutableStateOf(AutomationConfig.formatKeywords(config.rewardClaimedKeywords)) }
    var closeKw by remember(config) { mutableStateOf(AutomationConfig.formatKeywords(config.closeKeywords)) }
    var limitKw by remember(config) { mutableStateOf(AutomationConfig.formatKeywords(config.dailyLimitKeywords)) }
    var captchaKw by remember(config) { mutableStateOf(AutomationConfig.formatKeywords(config.captchaKeywords)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("基础限制与体验", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = maxAdCount,
                onValueChange = { maxAdCount = it.filter(Char::isDigit) },
                label = { Text("目标次数 (0不限)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = maxRunMinutes,
                onValueChange = { maxRunMinutes = it.filter(Char::isDigit) },
                label = { Text("最长分钟 (0不限)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = adTimeoutSeconds,
                onValueChange = { adTimeoutSeconds = it.filter(Char::isDigit) },
                label = { Text("单条广告超时(秒)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = targetPkg,
                onValueChange = { targetPkg = it.trim() },
                label = { Text("目标应用包名") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("运行期间自动静音", fontWeight = FontWeight.Medium)
                Text(
                    "暂停、完成或停止时自动恢复原来的媒体音量",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B7280)
                )
            }
            Switch(checked = muteDuringRun, onCheckedChange = { muteDuringRun = it })
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("识别关键词（用逗号分隔）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = entryKw,
            onValueChange = { entryKw = it },
            label = { Text("广告入口关键词") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        OutlinedTextField(
            value = rewardKw,
            onValueChange = { rewardKw = it },
            label = { Text("奖励已发放关键词") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        OutlinedTextField(
            value = closeKw,
            onValueChange = { closeKw = it },
            label = { Text("关闭/跳过按钮关键词") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        OutlinedTextField(
            value = limitKw,
            onValueChange = { limitKw = it },
            label = { Text("每日上限提示关键词") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        OutlinedTextField(
            value = captchaKw,
            onValueChange = { captchaKw = it },
            label = { Text("人机验证暂停关键词") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val updated = config.copy(
                        targetPackageName = targetPkg.ifBlank { AutomationConfig.DEFAULT_TARGET_PACKAGE },
                        maxAdCount = maxAdCount.toIntOrNull() ?: config.maxAdCount,
                        maxRunMinutes = maxRunMinutes.toIntOrNull() ?: config.maxRunMinutes,
                        adTimeoutSeconds = (adTimeoutSeconds.toIntOrNull() ?: config.adTimeoutSeconds).coerceIn(20, 300),
                        muteDuringRun = muteDuringRun,
                        entryKeywords = AutomationConfig.parseKeywords(entryKw).ifEmpty { AutomationConfig.DEFAULT_ENTRY_KEYWORDS },
                        rewardClaimedKeywords = AutomationConfig.parseKeywords(rewardKw).ifEmpty { AutomationConfig.DEFAULT_REWARD_KEYWORDS },
                        closeKeywords = AutomationConfig.parseKeywords(closeKw).ifEmpty { AutomationConfig.DEFAULT_CLOSE_KEYWORDS },
                        dailyLimitKeywords = AutomationConfig.parseKeywords(limitKw).ifEmpty { AutomationConfig.DEFAULT_LIMIT_KEYWORDS },
                        captchaKeywords = AutomationConfig.parseKeywords(captchaKw).ifEmpty { AutomationConfig.DEFAULT_CAPTCHA_KEYWORDS }
                    )
                    onSave(updated)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("保存配置")
            }
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f)
            ) {
                Text("恢复默认")
            }
        }
    }
}

@Composable
private fun DebugLogsTab(
    logs: List<LogEntry>,
    nodeDump: String,
    serviceConnected: Boolean,
    onRequestNodeDump: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onClearLogs: () -> Unit
) {
    var showNodeDump by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    onRequestNodeDump()
                    showNodeDump = true
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (serviceConnected) "抓取节点快照" else "服务未开启")
            }
            OutlinedButton(
                onClick = {
                    val text = logs.joinToString("\n") { "[${it.timestamp}][${it.level}] ${it.message}" }
                    onCopyText("运行日志", text)
                }
            ) {
                Text("复制日志")
            }
            TextButton(onClick = onClearLogs) {
                Text("清空")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("最近页面节点快照", fontWeight = FontWeight.Bold)
                    Row {
                        TextButton(onClick = { onCopyText("节点快照", nodeDump) }) {
                            Text("复制")
                        }
                        TextButton(onClick = { showNodeDump = !showNodeDump }) {
                            Text(if (showNodeDump) "收起" else "展开")
                        }
                    }
                }
                if (showNodeDump) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(Color(0xFF111827), RoundedCornerShape(8.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        Text(
                            text = nodeDump,
                            color = Color(0xFFE5E7EB),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        Text(
            text = "实时运行日志（共 ${logs.size} 条）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF111827), RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(logs.asReversed()) { entry ->
                val color = when (entry.level) {
                    LogLevel.INFO -> Color(0xFFD1FAE5)
                    LogLevel.WARN -> Color(0xFFFDE68A)
                    LogLevel.ERROR -> Color(0xFFFCA5A5)
                }
                Text(
                    text = "[${entry.timestamp}] ${entry.message}",
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

private fun checkNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
