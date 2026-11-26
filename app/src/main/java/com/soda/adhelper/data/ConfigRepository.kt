package com.soda.adhelper.data

import android.content.Context
import com.soda.adhelper.model.AutomationConfig

class ConfigRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AutomationConfig {
        val default = AutomationConfig()
        return AutomationConfig(
            targetPackageName = prefs.getString(KEY_TARGET_PKG, default.targetPackageName)
                ?.ifBlank { default.targetPackageName } ?: default.targetPackageName,
            maxAdCount = prefs.getInt(KEY_MAX_AD_COUNT, default.maxAdCount).coerceAtLeast(0),
            maxRunMinutes = prefs.getInt(KEY_MAX_RUN_MINUTES, default.maxRunMinutes).coerceAtLeast(0),
            adTimeoutSeconds = prefs.getInt(KEY_AD_TIMEOUT_SEC, default.adTimeoutSeconds).coerceIn(20, 300),
            muteDuringRun = prefs.getBoolean(KEY_MUTE_DURING_RUN, default.muteDuringRun),
            pollIntervalMs = prefs.getLong(KEY_POLL_INTERVAL_MS, default.pollIntervalMs).coerceIn(500L, 5000L),
            postClickDelayMs = prefs.getLong(KEY_POST_CLICK_DELAY_MS, default.postClickDelayMs).coerceIn(500L, 5000L),
            entryKeywords = loadKeywords(KEY_ENTRY_KW, default.entryKeywords),
            rewardClaimedKeywords = loadKeywords(KEY_REWARD_KW, default.rewardClaimedKeywords),
            closeKeywords = loadKeywords(KEY_CLOSE_KW, default.closeKeywords),
            dailyLimitKeywords = loadKeywords(KEY_LIMIT_KW, default.dailyLimitKeywords),
            captchaKeywords = loadKeywords(KEY_CAPTCHA_KW, default.captchaKeywords)
        )
    }

    fun save(config: AutomationConfig) {
        prefs.edit()
            .putString(KEY_TARGET_PKG, config.targetPackageName.trim())
            .putInt(KEY_MAX_AD_COUNT, config.maxAdCount.coerceAtLeast(0))
            .putInt(KEY_MAX_RUN_MINUTES, config.maxRunMinutes.coerceAtLeast(0))
            .putInt(KEY_AD_TIMEOUT_SEC, config.adTimeoutSeconds.coerceIn(20, 300))
            .putBoolean(KEY_MUTE_DURING_RUN, config.muteDuringRun)
            .putLong(KEY_POLL_INTERVAL_MS, config.pollIntervalMs.coerceIn(500L, 5000L))
            .putLong(KEY_POST_CLICK_DELAY_MS, config.postClickDelayMs.coerceIn(500L, 5000L))
            .putString(KEY_ENTRY_KW, AutomationConfig.formatKeywords(config.entryKeywords))
            .putString(KEY_REWARD_KW, AutomationConfig.formatKeywords(config.rewardClaimedKeywords))
            .putString(KEY_CLOSE_KW, AutomationConfig.formatKeywords(config.closeKeywords))
            .putString(KEY_LIMIT_KW, AutomationConfig.formatKeywords(config.dailyLimitKeywords))
            .putString(KEY_CAPTCHA_KW, AutomationConfig.formatKeywords(config.captchaKeywords))
            .apply()
    }

    fun reset(): AutomationConfig {
        prefs.edit().clear().apply()
        return AutomationConfig()
    }

    private fun loadKeywords(key: String, fallback: List<String>): List<String> {
        val raw = prefs.getString(key, null) ?: return fallback
        val parsed = AutomationConfig.parseKeywords(raw)
        if (parsed.isEmpty()) return fallback
        if (key == KEY_ENTRY_KW && "领取奖励" !in parsed) {
            return listOf("领取奖励") + parsed
        }
        return parsed
    }

    companion object {
        private const val PREFS_NAME = "soda_ad_helper_prefs"
        private const val KEY_TARGET_PKG = "target_pkg"
        private const val KEY_MAX_AD_COUNT = "max_ad_count"
        private const val KEY_MAX_RUN_MINUTES = "max_run_minutes"
        private const val KEY_AD_TIMEOUT_SEC = "ad_timeout_sec"
        private const val KEY_MUTE_DURING_RUN = "mute_during_run"
        private const val KEY_POLL_INTERVAL_MS = "poll_interval_ms"
        private const val KEY_POST_CLICK_DELAY_MS = "post_click_delay_ms"
        private const val KEY_ENTRY_KW = "entry_kw"
        private const val KEY_REWARD_KW = "reward_kw"
        private const val KEY_CLOSE_KW = "close_kw"
        private const val KEY_LIMIT_KW = "limit_kw"
        private const val KEY_CAPTCHA_KW = "captcha_kw"
    }
}
