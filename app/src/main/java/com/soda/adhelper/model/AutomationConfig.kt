package com.soda.adhelper.model

data class AutomationConfig(
    val targetPackageName: String = DEFAULT_TARGET_PACKAGE,
    val maxAdCount: Int = 10,
    val maxRunMinutes: Int = 60,
    val adTimeoutSeconds: Int = 75,
    val muteDuringRun: Boolean = true,
    val pollIntervalMs: Long = 1200L,
    val postClickDelayMs: Long = 1600L,
    val entryKeywords: List<String> = DEFAULT_ENTRY_KEYWORDS,
    val rewardClaimedKeywords: List<String> = DEFAULT_REWARD_KEYWORDS,
    val closeKeywords: List<String> = DEFAULT_CLOSE_KEYWORDS,
    val dailyLimitKeywords: List<String> = DEFAULT_LIMIT_KEYWORDS,
    val captchaKeywords: List<String> = DEFAULT_CAPTCHA_KEYWORDS
) {
    companion object {
        const val DEFAULT_TARGET_PACKAGE = "com.luna.music"

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
