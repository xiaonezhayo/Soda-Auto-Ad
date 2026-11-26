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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.soda.adhelper.model.StepAction
import com.soda.adhelper.model.WorkflowStep
import com.soda.adhelper.service.FloatingOverlayService
import com.soda.adhelper.ui.theme.SodaAdHelperTheme
import kotlinx.coroutines.launch
import java.util.UUID

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

private data class InstalledAppInfo(
    val appName: String,
    val packageName: String
)

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
                    icon = { Icon(Icons.Default.Settings, contentDescription = "流程与配置") },
                    label = { Text("流程与配置") }
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
                    config = config,
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
                            snackbarHostState.showSnackbar("流程与配置已保存并生效")
                        }
                    },
                    onReset = {
                        AutomationRuntime.resetConfig()
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("已恢复汽水音乐默认流程与规则")
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
    config: AutomationConfig,
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
    val targetDisplay = config.targetAppName.ifBlank { config.targetPackageName }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
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

                Text(
                    text = "目标应用：$targetDisplay（${config.workflowSteps.size} 个流程步骤 · 每 ${config.adCheckIntervalSeconds}s 检查）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

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
                            Text("一键启动并打开 $targetDisplay", fontSize = 16.sp)
                        }
                    }
                }
            }
        }

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

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "保守模式与自定义流程说明",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF065F46)
                )
                Text(
                    text = "• 可在「流程与配置」中自选任意目标应用，并自由增删、插入或排序执行步骤。\n" +
                        "• 遇到直播间倒计时弹窗等偶发场景，可添加「可选点击」步骤：识别到关键词才点，未出现自动跳过。\n" +
                        "• 运行期间若跳出目标应用、步骤超时或出现人机验证，会立即暂停并发送通知提醒。",
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
    val context = LocalContext.current

    var targetPkg by remember(config) { mutableStateOf(config.targetPackageName) }
    var targetAppName by remember(config) { mutableStateOf(config.targetAppName) }
    var maxAdCount by remember(config) { mutableStateOf(config.maxAdCount.toString()) }
    var maxRunMinutes by remember(config) { mutableStateOf(config.maxRunMinutes.toString()) }
    var checkIntervalSec by remember(config) { mutableStateOf(config.adCheckIntervalSeconds.toString()) }
    var ocrStartSec by remember(config) { mutableStateOf(config.ocrStartAfterSeconds.toString()) }
    var muteDuringRun by remember(config) { mutableStateOf(config.muteDuringRun) }
    var limitKw by remember(config) { mutableStateOf(AutomationConfig.formatKeywords(config.dailyLimitKeywords)) }
    var captchaKw by remember(config) { mutableStateOf(AutomationConfig.formatKeywords(config.captchaKeywords)) }
    var steps by remember(config) { mutableStateOf(config.workflowSteps) }

    var showAppPicker by remember { mutableStateOf(false) }
    var editingStep by remember { mutableStateOf<WorkflowStep?>(null) }
    var insertAtIndex by remember { mutableStateOf<Int?>(null) }

    if (showAppPicker) {
        AppPickerDialog(
            context = context,
            currentPackage = targetPkg,
            onDismiss = { showAppPicker = false },
            onSelect = { app ->
                targetAppName = app.appName
                targetPkg = app.packageName
                showAppPicker = false
            }
        )
    }

    if (editingStep != null || insertAtIndex != null) {
        StepEditDialog(
            initial = editingStep,
            onDismiss = {
                editingStep = null
                insertAtIndex = null
            },
            onConfirm = { savedStep ->
                steps = if (editingStep != null) {
                    steps.map { if (it.id == savedStep.id) savedStep else it }
                } else {
                    val idx = (insertAtIndex ?: steps.size).coerceIn(0, steps.size)
                    steps.toMutableList().apply { add(idx, savedStep) }
                }
                editingStep = null
                insertAtIndex = null
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("目标应用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAppPicker = true },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = targetAppName.ifBlank { "点击选择目标应用" },
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = targetPkg,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6B7280)
                        )
                    }
                }
                TextButton(onClick = { showAppPicker = true }) {
                    Text("更换应用")
                }
            }
        }

        Text("运行限制与广告检查节奏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = maxAdCount,
                onValueChange = { maxAdCount = it.filter(Char::isDigit) },
                label = { Text("目标轮数 (0不限)") },
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
                value = checkIntervalSec,
                onValueChange = { checkIntervalSec = it.filter(Char::isDigit) },
                label = { Text("每几秒检查一次") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = ocrStartSec,
                onValueChange = { ocrStartSec = it.filter(Char::isDigit) },
                label = { Text("第几秒启用OCR") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("自定义执行流程（按顺序循环）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = { insertAtIndex = steps.size }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("添加步骤")
            }
        }

        steps.forEachIndexed { index, step ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${index + 1}. ${step.name}",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${step.action.label} · ${step.timeoutSeconds}秒",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row {
                            IconButton(
                                onClick = {
                                    if (index > 0) {
                                        val mutable = steps.toMutableList()
                                        val item = mutable.removeAt(index)
                                        mutable.add(index - 1, item)
                                        steps = mutable
                                    }
                                },
                                enabled = index > 0
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "上移")
                            }
                            IconButton(
                                onClick = {
                                    if (index < steps.lastIndex) {
                                        val mutable = steps.toMutableList()
                                        val item = mutable.removeAt(index)
                                        mutable.add(index + 1, item)
                                        steps = mutable
                                    }
                                },
                                enabled = index < steps.lastIndex
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "下移")
                            }
                            IconButton(onClick = { editingStep = step }) {
                                Icon(Icons.Default.Edit, contentDescription = "编辑")
                            }
                            IconButton(
                                onClick = {
                                    if (steps.size > 1) {
                                        steps = steps.filterNot { it.id == step.id }
                                    }
                                },
                                enabled = steps.size > 1
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "删除")
                            }
                        }
                    }

                    if (step.keywords.isNotEmpty()) {
                        Text(
                            text = "关键词：${AutomationConfig.formatKeywords(step.keywords)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4B5563)
                        )
                    }
                    if (step.action == StepAction.WAIT_REWARD_AND_CLOSE && step.secondaryKeywords.isNotEmpty()) {
                        Text(
                            text = "关闭词：${AutomationConfig.formatKeywords(step.secondaryKeywords)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4B5563)
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { insertAtIndex = index + 1 }) {
                            Text("+ 在此步后插入")
                        }
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text("全局保护关键词（用逗号分隔）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = limitKw,
            onValueChange = { limitKw = it },
            label = { Text("每日上限提示关键词（命中后自动完成停止）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        OutlinedTextField(
            value = captchaKw,
            onValueChange = { captchaKw = it },
            label = { Text("人机验证暂停关键词（命中后立即暂停提醒）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val updated = config.copy(
                        targetPackageName = targetPkg.ifBlank { AutomationConfig.DEFAULT_TARGET_PACKAGE },
                        targetAppName = targetAppName.ifBlank { targetPkg },
                        maxAdCount = maxAdCount.toIntOrNull() ?: config.maxAdCount,
                        maxRunMinutes = maxRunMinutes.toIntOrNull() ?: config.maxRunMinutes,
                        adCheckIntervalSeconds = (checkIntervalSec.toIntOrNull() ?: config.adCheckIntervalSeconds).coerceIn(1, 30),
                        ocrStartAfterSeconds = (ocrStartSec.toIntOrNull() ?: config.ocrStartAfterSeconds).coerceIn(0, 60),
                        muteDuringRun = muteDuringRun,
                        dailyLimitKeywords = AutomationConfig.parseKeywords(limitKw).ifEmpty { AutomationConfig.DEFAULT_LIMIT_KEYWORDS },
                        captchaKeywords = AutomationConfig.parseKeywords(captchaKw).ifEmpty { AutomationConfig.DEFAULT_CAPTCHA_KEYWORDS },
                        workflowSteps = steps.ifEmpty { AutomationConfig.defaultWorkflowSteps() }
                    )
                    onSave(updated)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("保存流程与配置")
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
private fun AppPickerDialog(
    context: Context,
    currentPackage: String,
    onDismiss: () -> Unit,
    onSelect: (InstalledAppInfo) -> Unit
) {
    val allApps = remember(context) { loadLauncherApps(context, currentPackage) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(allApps, query) {
        if (query.isBlank()) allApps
        else allApps.filter {
            it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择目标应用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索应用名称或包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        val selected = app.packageName == currentPackage
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) Color(0xFFD1FAE5) else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onSelect(app) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = if (selected) "${app.appName}（当前已选）" else app.appName,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF6B7280)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun StepEditDialog(
    initial: WorkflowStep?,
    onDismiss: () -> Unit,
    onConfirm: (WorkflowStep) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var action by remember(initial) { mutableStateOf(initial?.action ?: StepAction.OPTIONAL_CLICK) }
    var keywordsText by remember(initial) {
        mutableStateOf(AutomationConfig.formatKeywords(initial?.keywords.orEmpty()))
    }
    var secondaryKeywordsText by remember(initial) {
        mutableStateOf(
            AutomationConfig.formatKeywords(
                initial?.secondaryKeywords?.ifEmpty { AutomationConfig.DEFAULT_CLOSE_KEYWORDS }
                    ?: AutomationConfig.DEFAULT_CLOSE_KEYWORDS
            )
        )
    }
    var timeoutText by remember(initial) {
        mutableStateOf(
            (initial?.timeoutSeconds ?: when (action) {
                StepAction.OPTIONAL_CLICK -> 4
                StepAction.WAIT_REWARD_AND_CLOSE -> 90
                StepAction.WAIT_SECONDS -> 5
                StepAction.CLICK_KEYWORD -> 20
            }).toString()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加 / 插入流程步骤" else "编辑流程步骤") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("步骤名称（如：关闭直播间提示）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("动作类型", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StepAction.entries.forEach { candidate ->
                        val selected = action == candidate
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) Color(0xFFD1FAE5) else Color(0xFFF3F4F6),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    action = candidate
                                    if (initial == null) {
                                        timeoutText = when (candidate) {
                                            StepAction.OPTIONAL_CLICK -> "4"
                                            StepAction.WAIT_REWARD_AND_CLOSE -> "90"
                                            StepAction.WAIT_SECONDS -> "5"
                                            StepAction.CLICK_KEYWORD -> "20"
                                        }
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = candidate.label,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color(0xFF065F46) else Color(0xFF1F2937),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (action != StepAction.WAIT_SECONDS) {
                    OutlinedTextField(
                        value = keywordsText,
                        onValueChange = { keywordsText = it },
                        label = {
                            Text(
                                when (action) {
                                    StepAction.WAIT_REWARD_AND_CLOSE -> "奖励已发放关键词（逗号分隔）"
                                    StepAction.OPTIONAL_CLICK -> "出现才点的关键词（如：取消，暂不进入，跳过）"
                                    else -> "识别点击关键词（逗号分隔）"
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }

                if (action == StepAction.WAIT_REWARD_AND_CLOSE) {
                    OutlinedTextField(
                        value = secondaryKeywordsText,
                        onValueChange = { secondaryKeywordsText = it },
                        label = { Text("广告关闭按钮关键词（逗号分隔）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }

                OutlinedTextField(
                    value = timeoutText,
                    onValueChange = { timeoutText = it.filter(Char::isDigit) },
                    label = {
                        Text(
                            when (action) {
                                StepAction.OPTIONAL_CLICK -> "检测时长（秒，超时未出现则自动跳过）"
                                StepAction.WAIT_SECONDS -> "固定等待时长（秒）"
                                else -> "超时时间（秒，超时触发保守暂停）"
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val step = WorkflowStep(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        name = name.ifBlank { action.label },
                        action = action,
                        keywords = AutomationConfig.parseKeywords(keywordsText),
                        secondaryKeywords = if (action == StepAction.WAIT_REWARD_AND_CLOSE) {
                            AutomationConfig.parseKeywords(secondaryKeywordsText)
                        } else {
                            emptyList()
                        },
                        timeoutSeconds = (timeoutText.toIntOrNull() ?: 15).coerceIn(1, 300)
                    )
                    onConfirm(step)
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
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

private fun loadLauncherApps(context: Context, currentPackage: String): List<InstalledAppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }

    return resolved
        .mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
            InstalledAppInfo(appName = label, packageName = pkg)
        }
        .distinctBy { it.packageName }
        .sortedWith(
            compareByDescending<InstalledAppInfo> { it.packageName == currentPackage }
                .thenBy { it.appName.lowercase() }
        )
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
