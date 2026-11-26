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
import com.soda.adhelper.model.AdPhase
import com.soda.adhelper.model.LogLevel
import com.soda.adhelper.model.RunStatus
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
                runStateMachine()
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

    private suspend fun runStateMachine() {
        var launchAttemptedAt = 0L
        var findingEntrySince = 0L
        var adStartedAt = 0L
        var rewardDetected = false
        var rewardDetectedAt = 0L
        var lastOcrAt = 0L
        var closeAttempts = 0

        while (coroutineContext.isActive && AutomationRuntime.snapshot.value.status == RunStatus.RUNNING) {
            val config = AutomationRuntime.config.value
            val snap = AutomationRuntime.snapshot.value
            val now = System.currentTimeMillis()

            // 1. Check stop conditions (count & total duration)
            if (config.maxAdCount > 0 && snap.completedAds >= config.maxAdCount) {
                completeWithAlert("已达到设定目标次数（共完成 ${snap.completedAds} 次）")
                return
            }
            if (config.maxRunMinutes > 0 && snap.startedAtMs > 0L) {
                val elapsedMinutes = (now - snap.startedAtMs) / 60_000L
                if (elapsedMinutes >= config.maxRunMinutes) {
                    completeWithAlert("已达到最大运行时长（${config.maxRunMinutes} 分钟，共完成 ${snap.completedAds} 次）")
                    return
                }
            }

            val root = rootInActiveWindow
            val activePkg = root?.packageName?.toString()?.trim().orEmpty()
            val targetPkg = config.targetPackageName

            // 2. Waiting for Soda Music to come to foreground
            if (snap.phase == AdPhase.WAITING_APP) {
                if (activePkg == targetPkg) {
                    AutomationRuntime.log(LogLevel.INFO, "已进入汽水音乐，开始寻找广告入口")
                    findingEntrySince = now
                    AutomationRuntime.updatePhase(AdPhase.FINDING_ENTRY, "正在寻找领时长广告入口…")
                    delay(800L)
                    continue
                }

                if (launchAttemptedAt == 0L) {
                    launchAttemptedAt = now
                    val launched = launchTargetApp(targetPkg)
                    if (!launched) {
                        pauseWithAlert("未找到汽水音乐（包名：$targetPkg），请确认已安装或检查包名配置")
                        return
                    }
                    AutomationRuntime.updatePhase(AdPhase.WAITING_APP, "正在打开汽水音乐…")
                } else if (now - launchAttemptedAt > LAUNCH_GRACE_MS) {
                    pauseWithAlert("等待进入汽水音乐超时，请手动切换到汽水音乐后点击继续")
                    return
                }
                delay(config.pollIntervalMs)
                continue
            }

            // 3. Conservative check: pause immediately if foreground left Soda Music
            if (activePkg.isNotEmpty() && activePkg != targetPkg && activePkg != SYSTEM_UI_PACKAGE && activePkg != packageName) {
                pauseWithAlert("检测到已跳出汽水音乐（当前应用：$activePkg），保守模式已自动暂停")
                return
            }

            val nodes = NodeInspector.collectNodes(root)

            // 4. Conservative check: CAPTCHA / human verification in UI tree
            val captchaNode = NodeInspector.findFirstMatch(nodes, config.captchaKeywords)
            if (captchaNode != null) {
                pauseWithAlert("检测到人机验证（${captchaNode.label}），请手动完成后点击继续")
                return
            }

            // 5. Daily cap check in UI tree
            val limitNode = NodeInspector.findFirstMatch(nodes, config.dailyLimitKeywords)
            if (limitNode != null) {
                completeWithAlert("检测到上限提示（${limitNode.label}），本次共完成 ${snap.completedAds} 次")
                return
            }

            when (snap.phase) {
                AdPhase.FINDING_ENTRY -> {
                    val entryNode = NodeInspector.findFirstMatch(nodes, config.entryKeywords)
                    if (entryNode != null) {
                        AutomationRuntime.log(LogLevel.INFO, "控件树找到广告入口：${entryNode.label}，准备点击")
                        performTap(entryNode.bounds)
                        adStartedAt = System.currentTimeMillis()
                        rewardDetected = false
                        rewardDetectedAt = 0L
                        lastOcrAt = 0L
                        closeAttempts = 0
                        AutomationRuntime.updatePhase(AdPhase.WATCHING_AD, "已进入广告，等待奖励发放…")
                        delay(config.postClickDelayMs)
                    } else {
                        if (findingEntrySince == 0L) findingEntrySince = now
                        // Fallback to full-screen OCR after 2.5s (handles WebView/image popups like "领取奖励")
                        if ((now - findingEntrySince) >= ENTRY_OCR_START_MS && (now - lastOcrAt) >= OCR_INTERVAL_MS) {
                            lastOcrAt = now
                            val ocrHits = runOcr(topFraction = 1.0f)
                            if (ocrHits.isNotEmpty()) {
                                val ocrCaptcha = findOcrMatch(ocrHits, config.captchaKeywords)
                                if (ocrCaptcha != null) {
                                    pauseWithAlert("OCR 检测到人机验证（${ocrCaptcha.text}），请手动完成后点击继续")
                                    return
                                }
                                val ocrLimit = findOcrMatch(ocrHits, config.dailyLimitKeywords)
                                if (ocrLimit != null) {
                                    completeWithAlert("OCR 检测到上限提示（${ocrLimit.text}），本次共完成 ${snap.completedAds} 次")
                                    return
                                }
                                val ocrEntry = findOcrMatch(ocrHits, config.entryKeywords)
                                if (ocrEntry != null) {
                                    AutomationRuntime.log(LogLevel.INFO, "OCR 找到广告入口：${ocrEntry.text}，准备点击")
                                    performTap(ocrEntry.bounds)
                                    adStartedAt = System.currentTimeMillis()
                                    rewardDetected = false
                                    rewardDetectedAt = 0L
                                    lastOcrAt = 0L
                                    closeAttempts = 0
                                    AutomationRuntime.updatePhase(AdPhase.WATCHING_AD, "已进入广告，等待奖励发放…")
                                    delay(config.postClickDelayMs)
                                    continue
                                }
                            }
                        }

                        if (now - findingEntrySince > FIND_ENTRY_TIMEOUT_MS) {
                            AutomationRuntime.updateNodeDump(NodeInspector.formatNodeDump(activePkg, nodes))
                            pauseWithAlert("连续 20 秒未找到广告入口按钮，请手动切到“领免费时长”页面或检查关键词设置")
                            return
                        }
                        delay(config.pollIntervalMs)
                    }
                }

                AdPhase.WATCHING_AD -> {
                    val adElapsedSec = (now - adStartedAt) / 1000L
                    if (adElapsedSec >= config.adTimeoutSeconds) {
                        AutomationRuntime.updateNodeDump(NodeInspector.formatNodeDump(activePkg, nodes))
                        pauseWithAlert("单条广告超过 ${config.adTimeoutSeconds} 秒未完成，保守模式已暂停")
                        return
                    }

                    val hasCountdownNode = NodeInspector.findFirstMatch(nodes, COUNTDOWN_KEYWORDS) != null

                    // If an accidental early-exit dialog appeared before reward was earned, tap "继续观看"
                    if (!rewardDetected) {
                        val continueWatchNode = NodeInspector.findFirstMatch(nodes, CONTINUE_WATCH_KEYWORDS)
                        if (continueWatchNode != null) {
                            AutomationRuntime.log(LogLevel.INFO, "检测到提前退出确认弹窗，点击“${continueWatchNode.label}”继续等待播完")
                            performTap(continueWatchNode.bounds)
                            delay(config.postClickDelayMs)
                            continue
                        }
                    }

                    // Check reward text via Accessibility tree first (only when countdown is gone)
                    if (!rewardDetected && !hasCountdownNode) {
                        val rewardNode = NodeInspector.findFirstMatch(nodes, config.rewardClaimedKeywords)
                        if (rewardNode != null) {
                            rewardDetected = true
                            rewardDetectedAt = now
                            AutomationRuntime.log(LogLevel.INFO, "控件树检测到奖励提示：${rewardNode.label}")
                            AutomationRuntime.updatePhase(AdPhase.WATCHING_AD, "已获得奖励，准备关闭广告…")
                        }
                    }

                    // Only click close after reward is confirmed AND countdown is no longer shown
                    val closeNode = NodeInspector.findFirstMatch(nodes, config.closeKeywords)
                    if (closeNode != null && rewardDetected && !hasCountdownNode) {
                        AutomationRuntime.log(LogLevel.INFO, "控件树找到关闭按钮：${closeNode.label}，执行点击")
                        performTap(closeNode.bounds)
                        closeAttempts = 1
                        AutomationRuntime.updatePhase(AdPhase.CLOSING_AD, "正在关闭广告并返回…")
                        delay(config.postClickDelayMs)
                        continue
                    }

                    // Fallback to on-device ML Kit OCR on top screen band when needed
                    if (adElapsedSec >= OCR_START_AFTER_SECONDS && (now - lastOcrAt) >= OCR_INTERVAL_MS) {
                        lastOcrAt = now
                        val ocrHits = runOcr(topFraction = 0.28f)
                        if (ocrHits.isNotEmpty()) {
                            val ocrCaptcha = findOcrMatch(ocrHits, config.captchaKeywords)
                            if (ocrCaptcha != null) {
                                pauseWithAlert("OCR 检测到人机验证（${ocrCaptcha.text}），请手动完成后点击继续")
                                return
                            }

                            val ocrLimit = findOcrMatch(ocrHits, config.dailyLimitKeywords)
                            if (ocrLimit != null) {
                                completeWithAlert("OCR 检测到上限提示（${ocrLimit.text}），本次共完成 ${snap.completedAds} 次")
                                return
                            }

                            val ocrHasCountdown = findOcrMatch(ocrHits, COUNTDOWN_KEYWORDS) != null

                            if (!rewardDetected && !ocrHasCountdown) {
                                val ocrReward = findOcrMatch(ocrHits, config.rewardClaimedKeywords)
                                if (ocrReward != null) {
                                    rewardDetected = true
                                    rewardDetectedAt = now
                                    AutomationRuntime.log(LogLevel.INFO, "OCR 检测到奖励提示：${ocrReward.text}")
                                    AutomationRuntime.updatePhase(AdPhase.WATCHING_AD, "OCR 确认已获得奖励，寻找关闭按钮…")
                                }
                            }

                            val ocrClose = findOcrMatch(ocrHits, config.closeKeywords)
                            if (ocrClose != null && rewardDetected && !ocrHasCountdown) {
                                AutomationRuntime.log(LogLevel.INFO, "OCR 找到关闭按钮：${ocrClose.text}，执行点击")
                                performTap(ocrClose.bounds)
                                closeAttempts = 1
                                AutomationRuntime.updatePhase(AdPhase.CLOSING_AD, "正在关闭广告并返回…")
                                delay(config.postClickDelayMs)
                                continue
                            }
                        }
                    }

                    // If reward is already claimed (and no countdown) but close button has no text, try top-corner icon node
                    if (rewardDetected && !hasCountdownNode && (now - rewardDetectedAt) >= 2000L) {
                        val metrics = resources.displayMetrics
                        val iconClose = NodeInspector.findTopCornerCloseCandidate(
                            nodes = nodes,
                            screenWidth = metrics.widthPixels,
                            screenHeight = metrics.heightPixels
                        )
                        if (iconClose != null) {
                            AutomationRuntime.log(LogLevel.INFO, "奖励已发放，点击顶部角落关闭图标（${iconClose.className}）")
                            performTap(iconClose.bounds)
                            closeAttempts = 1
                            AutomationRuntime.updatePhase(AdPhase.CLOSING_AD, "正在关闭广告并返回…")
                            delay(config.postClickDelayMs)
                            continue
                        }
                    }

                    AutomationRuntime.updatePhase(
                        AdPhase.WATCHING_AD,
                        if (rewardDetected) "奖励已到位，等待关闭按钮（${adElapsedSec}s）"
                        else "正在观看广告（已播放 ${adElapsedSec}s）"
                    )
                    delay(config.pollIntervalMs)
                }

                AdPhase.CLOSING_AD -> {
                    val entryAgain = NodeInspector.findFirstMatch(nodes, config.entryKeywords)
                    val stillHasClose = NodeInspector.findFirstMatch(nodes, config.closeKeywords)
                    val stillHasReward = NodeInspector.findFirstMatch(nodes, config.rewardClaimedKeywords)
                    val confirmExitNode = NodeInspector.findFirstMatch(nodes, SECONDARY_EXIT_KEYWORDS)

                    when {
                        entryAgain != null -> {
                            val newCount = snap.completedAds + 1
                            AutomationRuntime.incrementCompletedAds("已完成第 $newCount 条广告，准备下一条…")
                            AutomationRuntime.log(LogLevel.INFO, "成功返回入口页，累计完成 $newCount 条广告")
                            findingEntrySince = now
                            lastOcrAt = 0L
                            AutomationRuntime.updatePhase(AdPhase.FINDING_ENTRY, "已完成 $newCount 次，寻找下一条广告…")
                            delay(config.pollIntervalMs)
                        }

                        confirmExitNode != null -> {
                            AutomationRuntime.log(LogLevel.INFO, "检测到二次确认弹窗：${confirmExitNode.label}，执行点击")
                            performTap(confirmExitNode.bounds)
                            closeAttempts++
                            delay(config.postClickDelayMs)
                        }

                        stillHasClose != null && closeAttempts < MAX_CLOSE_ATTEMPTS -> {
                            AutomationRuntime.log(LogLevel.INFO, "再次尝试点击关闭按钮：${stillHasClose.label}（第 ${closeAttempts + 1} 次）")
                            performTap(stillHasClose.bounds)
                            closeAttempts++
                            delay(config.postClickDelayMs)
                        }

                        stillHasClose == null && stillHasReward == null -> {
                            val newCount = snap.completedAds + 1
                            AutomationRuntime.incrementCompletedAds("广告已关闭（累计 $newCount 次），正在检查入口…")
                            AutomationRuntime.log(LogLevel.INFO, "广告页已退出，累计完成 $newCount 条广告")
                            findingEntrySince = now
                            lastOcrAt = 0L
                            AutomationRuntime.updatePhase(AdPhase.FINDING_ENTRY, "寻找下一条广告入口…")
                            delay(config.pollIntervalMs)
                        }

                        closeAttempts >= MAX_CLOSE_ATTEMPTS -> {
                            AutomationRuntime.updateNodeDump(NodeInspector.formatNodeDump(activePkg, nodes))
                            pauseWithAlert("连续 $MAX_CLOSE_ATTEMPTS 次尝试关闭广告均未退出，保守模式已暂停")
                            return
                        }

                        else -> {
                            closeAttempts++
                            delay(config.pollIntervalMs)
                        }
                    }
                }

                AdPhase.WAITING_APP -> Unit
            }
        }
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
        NotificationHelper.notifyAlert(this, "汽水广告助手已暂停", reason)
    }

    private fun completeWithAlert(reason: String) {
        AutomationRuntime.complete(reason)
        NotificationHelper.notifyAlert(this, "汽水广告助手已完成", reason)
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val LAUNCH_GRACE_MS = 9_000L
        private const val FIND_ENTRY_TIMEOUT_MS = 20_000L
        private const val ENTRY_OCR_START_MS = 2_500L
        private const val OCR_START_AFTER_SECONDS = 12L
        private const val OCR_INTERVAL_MS = 3_000L
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
