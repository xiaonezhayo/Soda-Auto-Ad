package com.soda.adhelper.data

import android.content.Context
import com.soda.adhelper.model.AutomationConfig
import com.soda.adhelper.model.StepAction
import com.soda.adhelper.model.WorkflowStep
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ConfigRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AutomationConfig {
        val default = AutomationConfig()
        val entryKw = loadKeywords(KEY_ENTRY_KW, default.entryKeywords)
        val rewardKw = loadKeywords(KEY_REWARD_KW, default.rewardClaimedKeywords)
        val closeKw = loadKeywords(KEY_CLOSE_KW, default.closeKeywords)
        val adTimeout = prefs.getInt(KEY_AD_TIMEOUT_SEC, default.adTimeoutSeconds).coerceIn(20, 300)

        val steps = loadWorkflowSteps(
            fallback = AutomationConfig.defaultWorkflowSteps(
                entryKw = entryKw,
                rewardKw = rewardKw,
                closeKw = closeKw,
                adTimeoutSec = adTimeout
            )
        )

        return AutomationConfig(
            targetPackageName = prefs.getString(KEY_TARGET_PKG, default.targetPackageName)
                ?.ifBlank { default.targetPackageName } ?: default.targetPackageName,
            targetAppName = prefs.getString(KEY_TARGET_APP_NAME, default.targetAppName)
                ?.ifBlank { default.targetAppName } ?: default.targetAppName,
            maxAdCount = prefs.getInt(KEY_MAX_AD_COUNT, default.maxAdCount).coerceAtLeast(0),
            maxRunMinutes = prefs.getInt(KEY_MAX_RUN_MINUTES, default.maxRunMinutes).coerceAtLeast(0),
            adTimeoutSeconds = adTimeout,
            adCheckIntervalSeconds = prefs.getInt(KEY_AD_CHECK_INTERVAL_SEC, default.adCheckIntervalSeconds).coerceIn(1, 30),
            ocrStartAfterSeconds = prefs.getInt(KEY_OCR_START_AFTER_SEC, default.ocrStartAfterSeconds).coerceIn(0, 60),
            muteDuringRun = prefs.getBoolean(KEY_MUTE_DURING_RUN, default.muteDuringRun),
            pollIntervalMs = prefs.getLong(KEY_POLL_INTERVAL_MS, default.pollIntervalMs).coerceIn(500L, 5000L),
            postClickDelayMs = prefs.getLong(KEY_POST_CLICK_DELAY_MS, default.postClickDelayMs).coerceIn(500L, 5000L),
            entryKeywords = entryKw,
            rewardClaimedKeywords = rewardKw,
            closeKeywords = closeKw,
            dailyLimitKeywords = loadKeywords(KEY_LIMIT_KW, default.dailyLimitKeywords),
            captchaKeywords = loadKeywords(KEY_CAPTCHA_KW, default.captchaKeywords),
            workflowSteps = steps
        )
    }

    fun save(config: AutomationConfig) {
        prefs.edit()
            .putString(KEY_TARGET_PKG, config.targetPackageName.trim())
            .putString(KEY_TARGET_APP_NAME, config.targetAppName.trim())
            .putInt(KEY_MAX_AD_COUNT, config.maxAdCount.coerceAtLeast(0))
            .putInt(KEY_MAX_RUN_MINUTES, config.maxRunMinutes.coerceAtLeast(0))
            .putInt(KEY_AD_TIMEOUT_SEC, config.adTimeoutSeconds.coerceIn(20, 300))
            .putInt(KEY_AD_CHECK_INTERVAL_SEC, config.adCheckIntervalSeconds.coerceIn(1, 30))
            .putInt(KEY_OCR_START_AFTER_SEC, config.ocrStartAfterSeconds.coerceIn(0, 60))
            .putBoolean(KEY_MUTE_DURING_RUN, config.muteDuringRun)
            .putLong(KEY_POLL_INTERVAL_MS, config.pollIntervalMs.coerceIn(500L, 5000L))
            .putLong(KEY_POST_CLICK_DELAY_MS, config.postClickDelayMs.coerceIn(500L, 5000L))
            .putString(KEY_ENTRY_KW, AutomationConfig.formatKeywords(config.entryKeywords))
            .putString(KEY_REWARD_KW, AutomationConfig.formatKeywords(config.rewardClaimedKeywords))
            .putString(KEY_CLOSE_KW, AutomationConfig.formatKeywords(config.closeKeywords))
            .putString(KEY_LIMIT_KW, AutomationConfig.formatKeywords(config.dailyLimitKeywords))
            .putString(KEY_CAPTCHA_KW, AutomationConfig.formatKeywords(config.captchaKeywords))
            .putString(KEY_WORKFLOW_STEPS, encodeWorkflowSteps(config.workflowSteps))
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
        if (key == KEY_ENTRY_KW) {
            val merged = parsed.toMutableList()
            if ("领取奖励" !in merged) merged.add(0, "领取奖励")
            if ("免费听" !in merged) merged.add("免费听")
            return merged
        }
        return parsed
    }

    private fun encodeWorkflowSteps(steps: List<WorkflowStep>): String {
        val arr = JSONArray()
        for (step in steps) {
            val obj = JSONObject()
            obj.put("id", step.id)
            obj.put("name", step.name)
            obj.put("action", step.action.name)
            obj.put("keywords", AutomationConfig.formatKeywords(step.keywords))
            obj.put("secondaryKeywords", AutomationConfig.formatKeywords(step.secondaryKeywords))
            obj.put("timeoutSeconds", step.timeoutSeconds)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun loadWorkflowSteps(fallback: List<WorkflowStep>): List<WorkflowStep> {
        val raw = prefs.getString(KEY_WORKFLOW_STEPS, null) ?: return fallback
        return runCatching {
            val arr = JSONArray(raw)
            val list = ArrayList<WorkflowStep>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val actionName = obj.optString("action", StepAction.CLICK_KEYWORD.name)
                val action = runCatching { StepAction.valueOf(actionName) }.getOrDefault(StepAction.CLICK_KEYWORD)
                val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                var keywords = AutomationConfig.parseKeywords(obj.optString("keywords"))
                if (id == "step_entry" && "免费听歌" in keywords && "免费听" !in keywords) {
                    keywords = keywords + "免费听"
                }
                list.add(
                    WorkflowStep(
                        id = id,
                        name = obj.optString("name").ifBlank { action.label },
                        action = action,
                        keywords = keywords,
                        secondaryKeywords = AutomationConfig.parseKeywords(obj.optString("secondaryKeywords")),
                        timeoutSeconds = obj.optInt("timeoutSeconds", 30).coerceIn(1, 300)
                    )
                )
            }
            list.ifEmpty { fallback }
        }.getOrDefault(fallback)
    }

    companion object {
        private const val PREFS_NAME = "soda_ad_helper_prefs"
        private const val KEY_TARGET_PKG = "target_pkg"
        private const val KEY_TARGET_APP_NAME = "target_app_name"
        private const val KEY_MAX_AD_COUNT = "max_ad_count"
        private const val KEY_MAX_RUN_MINUTES = "max_run_minutes"
        private const val KEY_AD_TIMEOUT_SEC = "ad_timeout_sec"
        private const val KEY_AD_CHECK_INTERVAL_SEC = "ad_check_interval_sec"
        private const val KEY_OCR_START_AFTER_SEC = "ocr_start_after_sec"
        private const val KEY_MUTE_DURING_RUN = "mute_during_run"
        private const val KEY_POLL_INTERVAL_MS = "poll_interval_ms"
        private const val KEY_POST_CLICK_DELAY_MS = "post_click_delay_ms"
        private const val KEY_ENTRY_KW = "entry_kw"
        private const val KEY_REWARD_KW = "reward_kw"
        private const val KEY_CLOSE_KW = "close_kw"
        private const val KEY_LIMIT_KW = "limit_kw"
        private const val KEY_CAPTCHA_KW = "captcha_kw"
        private const val KEY_WORKFLOW_STEPS = "workflow_steps_json"
    }
}
