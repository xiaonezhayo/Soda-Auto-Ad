package com.soda.adhelper.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class UiNodeSnapshot(
    val text: String,
    val contentDesc: String,
    val viewId: String,
    val className: String,
    val clickable: Boolean,
    val visibleToUser: Boolean,
    val bounds: Rect
) {
    val label: String
        get() = when {
            text.isNotBlank() && contentDesc.isNotBlank() && text != contentDesc -> "$text ($contentDesc)"
            text.isNotBlank() -> text
            contentDesc.isNotBlank() -> contentDesc
            else -> ""
        }

    fun matchesAny(keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        return keywords.any { kw ->
            kw.isNotBlank() && (
                text.contains(kw, ignoreCase = true) ||
                    contentDesc.contains(kw, ignoreCase = true)
                )
        }
    }
}

object NodeInspector {
    private const val MAX_DEPTH = 28
    private const val MAX_NODES = 600

    fun collectNodes(root: AccessibilityNodeInfo?): List<UiNodeSnapshot> {
        if (root == null) return emptyList()
        val result = ArrayList<UiNodeSnapshot>(128)
        walk(root, 0, result)
        return result
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<UiNodeSnapshot>
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_NODES) return

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            out.add(
                UiNodeSnapshot(
                    text = node.text?.toString()?.trim().orEmpty(),
                    contentDesc = node.contentDescription?.toString()?.trim().orEmpty(),
                    viewId = node.viewIdResourceName?.substringAfterLast('/')?.trim().orEmpty(),
                    className = node.className?.toString()?.substringAfterLast('.')?.trim().orEmpty(),
                    clickable = node.isClickable,
                    visibleToUser = node.isVisibleToUser,
                    bounds = rect
                )
            )
        }

        val count = node.childCount
        for (i in 0 until count) {
            if (out.size >= MAX_NODES) break
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, out)
        }
    }

    fun findFirstMatch(
        nodes: List<UiNodeSnapshot>,
        keywords: List<String>,
        maxBottomY: Int? = null,
        exclude: ((UiNodeSnapshot) -> Boolean)? = null
    ): UiNodeSnapshot? {
        val visible = nodes.filter { node ->
            node.visibleToUser &&
                (maxBottomY == null || node.bounds.centerY() <= maxBottomY) &&
                (exclude == null || !exclude(node))
        }
        for (kw in keywords) {
            if (kw.isBlank()) continue
            val matched = visible.filter {
                it.text.contains(kw, ignoreCase = true) ||
                    it.contentDesc.contains(kw, ignoreCase = true)
            }
            if (matched.isNotEmpty()) {
                return matched.firstOrNull { it.clickable } ?: matched.first()
            }
        }
        return null
    }

    fun findTopCornerCloseCandidate(
        nodes: List<UiNodeSnapshot>,
        screenWidth: Int,
        screenHeight: Int
    ): UiNodeSnapshot? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val maxTopY = (screenHeight * 0.18f).toInt()
        val maxButtonWidth = (screenWidth * 0.42f).toInt()
        val maxButtonHeight = (screenHeight * 0.10f).toInt()
        val minButtonSize = (screenWidth * 0.03f).toInt()

        return nodes.firstOrNull { node ->
            val w = node.bounds.width()
            val h = node.bounds.height()
            val inTopBand = node.bounds.top >= 0 && node.bounds.bottom <= maxTopY
            val nearEdge = node.bounds.right >= (screenWidth * 0.75f) || node.bounds.left <= (screenWidth * 0.22f)
            val reasonableSize = w in minButtonSize..maxButtonWidth && h in minButtonSize..maxButtonHeight
            val looksLikeCloseId = node.viewId.contains("close", ignoreCase = true) ||
                node.viewId.contains("skip", ignoreCase = true) ||
                node.viewId.contains("dismiss", ignoreCase = true)
            val isFeedbackOrAdTag = node.label.contains("反馈") || node.label == "广告"
            node.visibleToUser && !isFeedbackOrAdTag && inTopBand && nearEdge && reasonableSize && (node.clickable || looksLikeCloseId)
        }
    }

    fun formatNodeDump(packageName: String, nodes: List<UiNodeSnapshot>): String {
        val sb = StringBuilder()
        sb.appendLine("前台包名: ${packageName.ifBlank { "(未知)" }}")
        sb.appendLine("可见节点数: ${nodes.count { it.visibleToUser }} / 总计: ${nodes.size}")
        sb.appendLine("----------------------------------------")
        nodes
            .filter { it.visibleToUser && (it.label.isNotBlank() || it.viewId.isNotBlank() || it.clickable) }
            .take(80)
            .forEachIndexed { idx, n ->
                sb.append("${idx + 1}. [${n.className}]")
                if (n.label.isNotBlank()) sb.append(" 文本=\"${n.label}\"")
                if (n.viewId.isNotBlank()) sb.append(" id=${n.viewId}")
                if (n.clickable) sb.append(" (可点击)")
                sb.appendLine(" 坐标=(${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom})")
            }
        return sb.toString().trim()
    }
}
