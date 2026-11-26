package com.soda.adhelper.model

import java.util.UUID

enum class StepAction(val label: String) {
    CLICK_KEYWORD("识别关键词并点击"),
    OPTIONAL_CLICK("可选点击（出现才点，否则跳过）"),
    WAIT_REWARD_AND_CLOSE("等待广告播完并关闭"),
    WAIT_SECONDS("固定等待(秒)")
}

data class WorkflowStep(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val action: StepAction,
    val keywords: List<String> = emptyList(),
    val secondaryKeywords: List<String> = emptyList(),
    val timeoutSeconds: Int = 30
)

data class AutomationConfig(
    val targetPackageName: String = DEFAULT_TARGET_PACKAGE,
    val targetAppName: String = DEFAULT_TARGET_APP_NAME,
    val maxAdCount: Int = 10,
    val maxRunMinutes: Int = 60,
    val adTimeoutSeconds: Int = 90,
    val adCheckIntervalSeconds: Int = 3,
    val ocrStartAfterSeconds: Int = 8,
    val muteDuringRun: Boolean = true,
    val pollIntervalMs: Long = 1200L,
    val postClickDelayMs: Long = 1600L,
    val entryKeywords: List<String> = DEFAULT_ENTRY_KEYWORDS,
    val rewardClaimedKeywords: List<String> = DEFAULT_REWARD_KEYWORDS,
    val closeKeywords: List<String> = DEFAULT_CLOSE_KEYWORDS,
    val dailyLimitKeywords: List<String> = DEFAULT_LIMIT_KEYWORDS,
    val captchaKeywords: List<String> = DEFAULT_CAPTCHA_KEYWORDS,
    val workflowSteps: List<WorkflowStep> = defaultWorkflowSteps()
) {
    companion object {
        const val DEFAULT_TARGET_PACKAGE = "com.luna.music"
        const val DEFAULT_TARGET_APP_NAME = "汽水音乐"

        val DEFAULT_ENTRY_KEYWORDS = listOf(
            "领取奖励",
            "再看一个",
            "继续看广告",
            "看广告",
            "看视频领",
            "立即领取",
            "去领取",
            "领时长",
            "免费畅听",
            "免费听歌"
        )

        val DEFAULT_REWARD_KEYWORDS = listOf(
            "已获得奖励",
            "奖励已领取",
            "领取成功",
            "已领取",
            "获得奖励",
            "已发放",
            "成功领取"
        )

        val DEFAULT_CLOSE_KEYWORDS = listOf(
            "跳过广告",
            "关闭广告",
            "跳过",
            "关闭",
            "×",
            "✕",
            "✖"
        )

        val DEFAULT_LIMIT_KEYWORDS = listOf(
            "今日已领完",
            "已达上限",
            "明日再来",
            "暂无广告",
            "今日次数已用完",
            "活动已结束"
        )

        val DEFAULT_CAPTCHA_KEYWORDS = listOf(
            "安全验证",
            "滑块",
            "拖动滑块",
            "拼图验证",
            "人机验证",
            "图形验证码",
            "请完成验证",
            "点击验证"
        )

        fun defaultWorkflowSteps(
            entryKw: List<String> = DEFAULT_ENTRY_KEYWORDS,
            rewardKw: List<String> = DEFAULT_REWARD_KEYWORDS,
            closeKw: List<String> = DEFAULT_CLOSE_KEYWORDS,
            adTimeoutSec: Int = 90
        ): List<WorkflowStep> = listOf(
            WorkflowStep(
                id = "step_entry",
                name = "查找并点击广告入口",
                action = StepAction.CLICK_KEYWORD,
                keywords = entryKw,
                timeoutSeconds = 20
            ),
            WorkflowStep(
                id = "step_watch_and_close",
                name = "等待广告播完并关闭",
                action = StepAction.WAIT_REWARD_AND_CLOSE,
                keywords = rewardKw,
                secondaryKeywords = closeKw,
                timeoutSeconds = adTimeoutSec
            )
        )

        fun parseKeywords(raw: String): List<String> {
            return raw
                .split(',', '，', '\n', ';', '；')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }

        fun formatKeywords(list: List<String>): String = list.joinToString("，")
    }
}
