package com.screentranslate.collector

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.LinkedList
import java.util.Queue

class AccessibilityCollector {

    fun collectVisibleText(root: AccessibilityNodeInfo): List<TextNode> {
        val result = mutableListOf<TextNode>()
        val visited = mutableSetOf<Int>()
        val queue: Queue<AccessibilityNodeInfo> = LinkedList()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val nodeId = System.identityHashCode(node)

            if (!visited.add(nodeId)) continue
            if (!isNodeRelevant(node)) continue

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val text = extractText(node)
            if (text.isNotBlank() && bounds.width() > 0 && bounds.height() > 0) {
                result.add(
                    TextNode(
                        text = text.trim(),
                        bounds = Rect(bounds),
                        packageName = node.packageName?.toString() ?: "",
                        isEditable = node.isEditable
                    )
                )
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }

        return result
    }

    private fun isNodeRelevant(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        if (node.isPassword) return false
        return true
    }

    private fun extractText(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        return if (text.isNotBlank()) text else contentDesc
    }
}
