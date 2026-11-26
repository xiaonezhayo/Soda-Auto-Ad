package com.soda.adhelper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.soda.adhelper.core.AudioMuteController
import com.soda.adhelper.core.AutomationRuntime
import com.soda.adhelper.core.NodeInspector
import com.soda.adhelper.core.NotificationHelper
import com.soda.adhelper.core.OcrRecognizer
import com.soda.adhelper.core.OcrTextHit
import com.soda.adhelper.core.UiNodeSnapshot
import com.soda.adhelper.model.AdPhase
import com.soda.adhelper.model.AutomationConfig
import com.soda.adhelper.model.LogLevel
import com.soda.adhelper.model.RunStatus
import com.soda.adhelper.model.StepAction
import com.soda.adhelper.model.WorkflowStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.random.Random

class SodaAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var audioMuteController: AudioMuteController
    private lateinit var ocrRecognizer: OcrRecognizer

    private var automationJob: Job? = null
    @Volatile
    private var lastObservedPackage: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        AutomationRuntime.init(this)
        NotificationHelper.ensureChannels(this)
        audioMuteController = AudioMuteController(this)
        ocrRecognizer = OcrRecognizer()
        AutomationRuntime.setServiceConnected(true)

        observeRunStatus()
        observeNodeDumpRequests()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()?.trim().orEmpty()
        if (pkg.isNotEmpty() && pkg != SYSTEM_UI_PACKAGE && pkg != packageName) {
            lastObservedPackage = pkg
            AutomationRuntime.updateCurrentPackage(pkg)
        }
    }

    override fun onInterrupt() {
        AutomationRuntime.log(LogLevel.WARN, "无障碍服务被系统中断")
    }

    override fun onDestroy() {
        automationJob?.cancel()
        if (::audioMuteController.isInitialized) {
            audioMuteController.restoreIfMuted()
        }
        if (::ocrRecognizer.isInitialized) {
            ocrRecognizer.close()
        }
        AutomationRuntime.setServiceConnected(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeRunStatus() {
        serviceScope.launch {
            AutomationRuntime.snapshot.collectLatest { snap ->
                when (snap.status) {
                    RunStatus.RUNNING -> {
                        if (automationJob?.isActive != true) {
                            startAutomationLoop()
                        }
                    }

                    RunStatus.PAUSED,
                    RunStatus.COMPLETED,
                    RunStatus.IDLE,
                    RunStatus.ERROR -> {
                        stopAutomationLoop()
                    }
                }
            }
        }
    }

    private fun observeNodeDumpRequests() {
        serviceScope.launch {
            var lastHandled = AutomationRuntime.nodeDumpRequestCounter.value
            AutomationRuntime.nodeDumpRequestCounter.collect { counter ->
                if (counter != lastHandled) {
                    lastHandled = counter
                    val root = rootInActiveWindow
                    val pkg = root?.packageName?.toString().orEmpty().ifBlank { lastObservedPackage }
                    val nodes = NodeInspector.collectNodes(root)
                    val dump = NodeInspector.formatNodeDump(pkg, nodes)
                    AutomationRuntime.updateNodeDump(dump)
                    AutomationRuntime.log(LogLevel.INFO, "已抓取当前页面节点快照（共 ${nodes.size} 个节点）")
                }
            }
        }
    }

    private fun startAutomationLoop() {
        val config = AutomationRuntime.config.value
        audioMuteController.muteIfNeeded(config.muteDuringRun)

        automationJob = serviceScope.launch {
            try {
                runWorkflowEngine()
            } catch (t: Throwable) {
                if (isActive) {
                    pauseWithAlert("自动化循环发生异常：${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun stopAutomationLoop() {
        automationJob?.cancel()
        automationJob = null
        if (::audioMuteController.isInitialized) {
            audioMuteController.restoreIfMuted()
        }
    }

    private suspend fun runWorkflowEngine() {
        // Phase 0: Ensure target app is in foreground
        if (!waitForTargetApp()) return

        while (isStillRunning()) {
            val config = AutomationRuntime.config.value
            if (checkLimitsReached(config)) return

            val steps = config.workflowSteps.ifEmpty { AutomationConfig.defaultWorkflowSteps() }
            for ((index, step) in steps.withIndex()) {
                if (!isStillRunning()) return
                val stepLabel = "步骤 ${index + 1}/${steps.size}：${step.name}"
                AutomationRuntime.log(LogLevel.INFO, "开始执行 $stepLabel")

                val ok = when (step.action) {
                    StepAction.CLICK_KEYWORD -> executeClickKeywordStep(step, stepLabel, optional = false)
                    StepAction.OPTIONAL_CLICK -> executeClickKeywordStep(step, stepLabel, optional = true)
                    StepAction.WAIT_REWARD_AND_CLOSE -> executeWaitRewardAndCloseStep(step, stepLabel)
                    StepAction.WAIT_SECONDS -> executeWaitSecondsStep(step, stepLabel)
                }
                if (!ok) return
            }

            val newCount = AutomationRuntime.snapshot.value.completedAds + 1
            AutomationRuntime.incrementCompletedAds("已完成第 $newCount 轮流程，准备下一轮…")
            AutomationRuntime.log(LogLevel.INFO, "完整流程执行完毕，累计完成 $newCount 次")
        }
    }

    private suspend fun waitForTargetApp(): Boolean {
        val config = AutomationRuntime.config.value
        val targetPkg = config.targetPackageName
        val appName = config.targetAppName.ifBlank { targetPkg }
        var launchAttemptedAt = 0L

        while (isStillRunning()) {
            val activePkg = rootInActiveWindow?.packageName?.toString()?.trim().orEmpty()
            if (activePkg == targetPkg) {
                AutomationRuntime.log(LogLevel.INFO, "已进入目标应用：$appName")
                delay(700L)
                return true
            }

            val now = System.currentTimeMillis()
            if (launchAttemptedAt == 0L) {
                launchAttemptedAt = now
                if (!launchTargetApp(targetPkg)) {
                    pauseWithAlert("未找到目标应用 $appName（包名：$targetPkg），请在规则配置中重新选择应用")
                    return false
                }
                AutomationRuntime.updatePhase(AdPhase.WAITING_APP, "正在打开 $appName…")
            } else if (now - launchAttemptedAt > LAUNCH_GRACE_MS) {
                pauseWithAlert("等待进入 $appName 超时，请手动切回目标应用后点击继续")
                return false
            }
            delay(config.pollIntervalMs)
        }
        return false
    }

    private suspend fun executeClickKeywordStep(
        step: WorkflowStep,
        stepLabel: String,
        optional: Boolean
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        var lastOcrAt = 0L
        val timeoutMs = (step.timeoutSeconds.coerceAtLeast(1)) * 1000L

        AutomationRuntime.updatePhase(
            AdPhase.FINDING_ENTRY,
            if (optional) "$stepLabel（可选检测中…）" else "$stepLabel（等待识别…）"
        )

        while (isStillRunning()) {
            val config = AutomationRuntime.config.value
            if (checkLimitsReached(config)) return false

            val screen = inspectAndGuardScreen(config) ?: return false
            val keywords = step.keywords.ifEmpty { config.entryKeywords }
            val now = System.currentTimeMillis()

            val node = NodeInspector.findFirstMatch(screen.nodes, keywords)
            if (node != null) {
                AutomationRuntime.log(LogLevel.INFO, "[$stepLabel] 控件树命中「${node.label}」，执行点击")
                performTap(node.bounds)
                delay(config.postClickDelayMs)
                return true
            }

            val checkIntervalMs = (config.adCheckIntervalSeconds * 1000L).coerceAtLeast(1000L)
            val ocrDelayMs = if (optional) 800L else ENTRY_OCR_START_MS
            if ((now - startedAt) >= ocrDelayMs && (now - lastOcrAt) >= checkIntervalMs) {
                lastOcrAt = now
                val ocrHits = runOcr(topFraction = 1.0f)
                if (ocrHits.isNotEmpty()) {
                    if (!guardOcrHits(ocrHits, config)) return false
                    val ocrMatch = findOcrMatch(ocrHits, keywords)
                    if (ocrMatch != null) {
                        AutomationRuntime.log(LogLevel.INFO, "[$stepLabel] OCR 命中「${ocrMatch.text}」，执行点击")
                        performTap(ocrMatch.bounds)
                        delay(config.postClickDelayMs)
                        return true
                    }
                }
            }

            if (now - startedAt >= timeoutMs) {
                if (optional) {
                    AutomationRuntime.log(LogLevel.INFO, "[$stepLabel] 未出现目标关键词，跳过继续下一步")
                    return true
                }
                AutomationRuntime.updateNodeDump(NodeInspector.formatNodeDump(screen.packageName, screen.nodes))
                pauseWithAlert("$stepLabel 超时（${step.timeoutSeconds} 秒未找到关键词），保守模式已暂停")
                return false
            }

            delay(config.pollIntervalMs.coerceAtMost(1000L))
        }
        return false
    }

    private suspend fun executeWaitRewardAndCloseStep(step: WorkflowStep, stepLabel: String): Boolean {
        val startedAt = System.currentTimeMillis()
        var rewardDetected = false
        var rewardDetectedAt = 0L
        var lastOcrAt = 0L
        val timeoutSec = step.timeoutSeconds.coerceAtLeast(15)

        AutomationRuntime.updatePhase(AdPhase.WATCHING_AD, "$stepLabel（等待广告播完…）")

        while (isStillRunning()) {
            val config = AutomationRuntime.config.value
            if (checkLimitsReached(config)) return false

            val screen = inspectAndGuardScreen(config) ?: return false
            val now = System.currentTimeMillis()
            val elapsedSec = (now - startedAt) / 1000L
            val rewardKeywords = step.keywords.ifEmpty { config.rewardClaimedKeywords }
            val closeKeywords = step.secondaryKeywords.ifEmpty { config.closeKeywords }
            val checkIntervalMs = (config.adCheckIntervalSeconds * 1000L).coerceAtLeast(1000L)

            if (elapsedSec >= timeoutSec) {
                AutomationRuntime.updateNodeDump(NodeInspector.formatNodeDump(screen.packageName, screen.nodes))
                pauseWithAlert("$stepLabel 超过 $timeoutSec 秒仍未完成，保守模式已暂停")
                return false
            }

            val hasCountdownNode = NodeInspector.findFirstMatch(screen.nodes, COUNTDOWN_KEYWORDS) != null

            // If accidental early-exit dialog appeared before reward was earned, tap "继续观看"
            if (!rewardDetected) {
                val continueWatchNode = NodeInspector.findFirstMatch(screen.nodes, CONTINUE_WATCH_KEYWORDS)
                if (continueWatchNode != null) {
                    AutomationRuntime.log(LogLevel.INFO, "检测到提前退出弹窗，点击「${continueWatchNode.label}」继续等待播完")
                    performTap(continueWatchNode.bounds)
                    delay(config.postClickDelayMs)
                    continue
                }
            }

            // 1. Check reward via Accessibility tree (only when countdown is gone)
            if (!rewardDetected && !hasCountdownNode) {
                val rewardNode = NodeInspector.findFirstMatch(screen.nodes, rewardKeywords)
                if (rewardNode != null) {
                    rewardDetected = true
                    rewardDetectedAt = now
                    AutomationRuntime.log(LogLevel.INFO, "[$stepLabel] 控件树检测到奖励提示：${rewardNode.label}")
                    AutomationRuntime.updatePhase(AdPhase.WATCHING_AD, "已获得奖励，准备关闭广告…")
                }
            }

            // 2. If reward is confirmed and countdown is gone, click close via Accessibility tree
            val closeNode = NodeInspector.findFirstMatch(screen.nodes, closeKeywords)
            if (closeNode != null && rewardDetected && !hasCountdownNode) {
                AutomationRuntime.log(LogLevel.INFO, "[$stepLabel] 控件树找到关闭按钮：${closeNode.label}，执行点击")
                performTap(closeNode.bounds)
                return verifyAdClosed(rewardKeywords, closeKeywords)
            }

            // 3. Periodic OCR check on top band using user-configured interval & start delay
            if (elapsedSec >= config.ocrStartAfterSeconds && (now - lastOcrAt) >= checkIntervalMs) {
                lastOcrAt = now
                val ocrHits = runOcr(topFraction = 0.28f)
                if (ocrHits.isNotEmpty()) {
                    if (!guardOcrHits(ocrHits, config)) return false
                    val ocrHasCountdown = findOcrMatch(ocrHits, COUNTDOWN_KEYWORDS) != null

                    if (!rewardDetected && !ocrHasCountdown) {
                        val ocrReward = findOcrMatch(ocrHits, rewardKeywords)
                        if (ocrReward != null) {
                            rewardDetected = true
                            rewardDetectedAt = now
                            AutomationRuntime.log(LogLevel.INFO, "[$stepLabel] OCR 检测到奖励提示：${ocrReward.text}")
                            AutomationRuntime.updatePhase(AdPhase.WATCHING_AD, "OCR 确认已获得奖励，寻找关闭按钮…")
                        }
                    }

                    val ocrClose = findOcrMatch(ocrHits, closeKeywords)
                    if (ocrClose != null && rewardDetected && !ocrHasCountdown) {
                        AutomationRuntime.log(LogLevel.INFO, "[$stepLabel] OCR 找到关闭按钮：${ocrClose.text}，执行点击")
                        performTap(ocrClose.bounds)
                        return verifyAdClosed(rewardKeywords, closeKeywords)
                    }
                }
            }

            // 4. Fallback to top-corner close icon node after reward confirmed
            if (rewardDetected && !hasCountdownNode && (now - rewardDetectedAt) >= 2000L) {
                val metrics = resources.displayMetrics
                val iconClose = NodeInspector.findTopCornerCloseCandidate(
                    nodes = screen.nodes,
                    screenWidth = metrics.widthPixels,
                    screenHeight = metrics.heightPixels
                )
                if (iconClose != null) {
                    AutomationRuntime.log(LogLevel.INFO, "[$stepLabel] 奖励已发放，点击顶部角落关闭图标（${iconClose.className}）")
                    performTap(iconClose.bounds)
                    return verifyAdClosed(rewardKeywords, closeKeywords)
                }
            }

            AutomationRuntime.updatePhase(
                AdPhase.WATCHING_AD,
                if (rewardDetected) "奖励已到位，寻找关闭按钮（${elapsedSec}s）"
                else "等待广告播完（已播放 ${elapsedSec}s，每 ${config.adCheckIntervalSeconds}s 检查）"
            )
            delay(checkIntervalMs.coerceAtMost(config.pollIntervalMs.coerceAtLeast(1000L)))
        }
        return false
    }

    private suspend fun verifyAdClosed(
        rewardKeywords: List<String>,
        closeKeywords: List<String>
    ): Boolean {
        val config = AutomationRuntime.config.value
        AutomationRuntime.updatePhase(AdPhase.CLOSING_AD, "正在确认广告已关闭…")
        delay(config.postClickDelayMs)

        var attempts = 1
        while (isStillRunning()) {
            val screen = inspectAndGuardScreen(config) ?: return false

            val nextStepEntryKeywords = config.workflowSteps
                .firstOrNull { it.action == StepAction.CLICK_KEYWORD }
                ?.keywords
                ?.ifEmpty { config.entryKeywords }
                ?: config.entryKeywords

            val entryAgain = NodeInspector.findFirstMatch(screen.nodes, nextStepEntryKeywords)
            if (entryAgain != null) {
                AutomationRuntime.log(LogLevel.INFO, "检测到下一轮入口「${entryAgain.label}」，本条广告已结束")
                return true
            }

            val stillHasClose = NodeInspector.findFirstMatch(screen.nodes, closeKeywords)
            val stillHasReward = NodeInspector.findFirstMatch(screen.nodes, rewardKeywords)
            val confirmExitNode = NodeInspector.findFirstMatch(screen.nodes, SECONDARY_EXIT_KEYWORDS)

            when {
                confirmExitNode != null -> {
                    AutomationRuntime.log(LogLevel.INFO, "奖励已到位，点击退出确认「${confirmExitNode.label}」")
                    performTap(confirmExitNode.bounds)
                    attempts++
                    delay(config.postClickDelayMs)
                }

                stillHasClose != null && attempts < MAX_CLOSE_ATTEMPTS -> {
                    AutomationRuntime.log(LogLevel.INFO, "再次尝试点击关闭按钮：${stillHasClose.label}（第 ${attempts + 1} 次）")
                    performTap(stillHasClose.bounds)
                    attempts++
                    delay(config.postClickDelayMs)
                }

                stillHasClose == null && stillHasReward == null -> {
                    AutomationRuntime.log(LogLevel.INFO, "广告页已关闭")
                    return true
                }

                attempts >= MAX_CLOSE_ATTEMPTS -> {
                    AutomationRuntime.updateNodeDump(NodeInspector.formatNodeDump(screen.packageName, screen.nodes))
                    pauseWithAlert("连续 $MAX_CLOSE_ATTEMPTS 次尝试关闭广告均未退出，保守模式已暂停")
                    return false
                }

                else -> {
                    attempts++
                    delay(config.pollIntervalMs)
                }
            }
        }
        return false
    }

    private suspend fun executeWaitSecondsStep(step: WorkflowStep, stepLabel: String): Boolean {
        val totalSeconds = step.timeoutSeconds.coerceAtLeast(1)
        for (elapsed in 0 until totalSeconds) {
            if (!isStillRunning()) return false
            val config = AutomationRuntime.config.value
            if (checkLimitsReached(config)) return false
            if (inspectAndGuardScreen(config) == null) return false

            AutomationRuntime.updatePhase(
                AdPhase.WATCHING_AD,
                "$stepLabel（剩余 ${totalSeconds - elapsed} 秒）"
            )
            delay(1000L)
        }
        return true
    }

    private data class ScreenState(
        val packageName: String,
        val nodes: List<UiNodeSnapshot>
    )

    private fun inspectAndGuardScreen(config: AutomationConfig): ScreenState? {
        val root = rootInActiveWindow
        val activePkg = root?.packageName?.toString()?.trim().orEmpty()
        val targetPkg = config.targetPackageName
        val appName = config.targetAppName.ifBlank { targetPkg }

        if (activePkg.isNotEmpty() && activePkg != targetPkg && activePkg != SYSTEM_UI_PACKAGE && activePkg != packageName) {
            pauseWithAlert("检测到已跳出 $appName（当前应用：$activePkg），保守模式已自动暂停")
            return null
        }

        val nodes = NodeInspector.collectNodes(root)

        val captchaNode = NodeInspector.findFirstMatch(nodes, config.captchaKeywords)
        if (captchaNode != null) {
            pauseWithAlert("检测到人机验证（${captchaNode.label}），请手动完成后点击继续")
            return null
        }

        val limitNode = NodeInspector.findFirstMatch(nodes, config.dailyLimitKeywords)
        if (limitNode != null) {
            val done = AutomationRuntime.snapshot.value.completedAds
            completeWithAlert("检测到上限提示（${limitNode.label}），本次共完成 $done 次")
            return null
        }

        return ScreenState(packageName = activePkg, nodes = nodes)
    }

    private fun guardOcrHits(hits: List<OcrTextHit>, config: AutomationConfig): Boolean {
        val ocrCaptcha = findOcrMatch(hits, config.captchaKeywords)
        if (ocrCaptcha != null) {
            pauseWithAlert("OCR 检测到人机验证（${ocrCaptcha.text}），请手动完成后点击继续")
            return false
        }
        val ocrLimit = findOcrMatch(hits, config.dailyLimitKeywords)
        if (ocrLimit != null) {
            val done = AutomationRuntime.snapshot.value.completedAds
            completeWithAlert("OCR 检测到上限提示（${ocrLimit.text}），本次共完成 $done 次")
            return false
        }
        return true
    }

    private fun checkLimitsReached(config: AutomationConfig): Boolean {
        val snap = AutomationRuntime.snapshot.value
        val now = System.currentTimeMillis()
        if (config.maxAdCount > 0 && snap.completedAds >= config.maxAdCount) {
            completeWithAlert("已达到设定目标次数（共完成 ${snap.completedAds} 次）")
            return true
        }
        if (config.maxRunMinutes > 0 && snap.startedAtMs > 0L) {
            val elapsedMinutes = (now - snap.startedAtMs) / 60_000L
            if (elapsedMinutes >= config.maxRunMinutes) {
                completeWithAlert("已达到最大运行时长（${config.maxRunMinutes} 分钟，共完成 ${snap.completedAds} 次）")
                return true
            }
        }
        return false
    }

    private suspend fun isStillRunning(): Boolean {
        return coroutineContext.isActive && AutomationRuntime.snapshot.value.status == RunStatus.RUNNING
    }

    private suspend fun runOcr(topFraction: Float = 0.28f): List<OcrTextHit> {
        val bitmap = takeScreenshotBitmap() ?: return emptyList()
        return try {
            withContext(Dispatchers.Default) {
                ocrRecognizer.recognize(bitmap, topFraction = topFraction)
            }
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private suspend fun takeScreenshotBitmap(): Bitmap? = suspendCancellableCoroutine { continuation ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    var softwareBitmap: Bitmap? = null
                    val hardwareBuffer = screenshot.hardwareBuffer
                    try {
                        val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                        if (hwBitmap != null) {
                            softwareBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap.recycle()
                        }
                    } catch (t: Throwable) {
                        AutomationRuntime.log(LogLevel.WARN, "截屏转换失败：${t.message}")
                    } finally {
                        hardwareBuffer.close()
                    }
                    if (continuation.isActive) {
                        continuation.resume(softwareBitmap)
                    } else {
                        softwareBitmap?.recycle()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    AutomationRuntime.log(LogLevel.WARN, "系统截屏失败（错误码：$errorCode）")
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        )
    }

    private fun findOcrMatch(hits: List<OcrTextHit>, keywords: List<String>): OcrTextHit? {
        for (kw in keywords) {
            if (kw.isBlank()) continue
            val matched = hits.firstOrNull { it.text.contains(kw, ignoreCase = true) }
            if (matched != null) return matched
        }
        return null
    }

    private suspend fun performTap(bounds: Rect): Boolean = suspendCancellableCoroutine { continuation ->
        val jitterX = if (bounds.width() > 12) Random.nextInt(-3, 4) else 0
        val jitterY = if (bounds.height() > 12) Random.nextInt(-3, 4) else 0
        val x = (bounds.centerX() + jitterX).coerceIn(1, resources.displayMetrics.widthPixels - 1).toFloat()
        val y = (bounds.centerY() + jitterY).coerceIn(1, resources.displayMetrics.heightPixels - 1).toFloat()

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 65L))
            .build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            },
            null
        )

        if (!dispatched && continuation.isActive) {
            continuation.resume(false)
        }
    }

    private fun launchTargetApp(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        return true
    }

    private fun pauseWithAlert(reason: String) {
        AutomationRuntime.pause(reason)
        NotificationHelper.notifyAlert(this, "广告助手已暂停", reason)
    }

    private fun completeWithAlert(reason: String) {
        AutomationRuntime.complete(reason)
        NotificationHelper.notifyAlert(this, "广告助手已完成", reason)
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val LAUNCH_GRACE_MS = 9_000L
        private const val ENTRY_OCR_START_MS = 2_500L
        private const val MAX_CLOSE_ATTEMPTS = 3

        private val COUNTDOWN_KEYWORDS = listOf(
            "秒后可领",
            "秒后获得",
            "秒后领",
            "秒后解锁",
            "s后可领",
            "s后获得"
        )

        private val CONTINUE_WATCH_KEYWORDS = listOf(
            "继续观看",
            "继续看广告"
        )

        private val SECONDARY_EXIT_KEYWORDS = listOf(
            "坚持退出",
            "残忍离开",
            "确认关闭",
            "直接关闭",
            "离开"
        )
    }
}
