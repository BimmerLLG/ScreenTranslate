package com.screentranslate.collector

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.screentranslate.logger.L
import java.util.LinkedList
import java.util.Queue

/**
 * AccessibilityCollector - 无障碍文本采集器
 * 通过 Android 无障碍服务遍历屏幕上的所有节点，提取可见文本
 * 使用 BFS（广度优先搜索）算法遍历节点树
 */
class AccessibilityCollector {

    /**
     * 收集屏幕上所有可见的文本节点
     * @param root 根节点（当前活动窗口的根节点）
     * @return 包含文本内容和位置信息的 TextNode 列表
     */
    fun collectVisibleText(root: AccessibilityNodeInfo): List<TextNode> {
        L.d("Collector", "开始 BFS 扫描窗口内容")
        val result = mutableListOf<TextNode>()          // 存储采集结果
        val visited = mutableSetOf<Int>()                // 已访问节点集合，防止重复遍历
        val queue: Queue<AccessibilityNodeInfo> = LinkedList()  // BFS 队列
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val nodeId = System.identityHashCode(node)  // 使用节点的内存地址作为唯一标识

            if (!visited.add(nodeId)) continue           // 跳过已访问的节点
            if (!isNodeRelevant(node)) continue          // 跳过不相关的节点

            val bounds = Rect()
            node.getBoundsInScreen(bounds)               // 获取节点在屏幕上的位置

            val text = extractText(node)
            // 只采集有文本且尺寸大于0的节点
            if (text.isNotBlank() && bounds.width() > 0 && bounds.height() > 0) {
                result.add(
                    TextNode(
                        text = text.trim(),              // 去除首尾空格
                        bounds = Rect(bounds),           // 复制矩形区域
                        packageName = node.packageName?.toString() ?: "",
                        isEditable = node.isEditable     // 标记是否为可编辑输入框
                    )
                )
            }

            // 将子节点加入队列继续遍历
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }

        L.d("Collector", "扫描完成: 原始节点 ${result.size} 个")
        return result
    }

    /**
     * 判断节点是否需要采集
     * 排除：不可见的节点、密码输入框（保护用户隐私）
     */
    private fun isNodeRelevant(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) {
            L.d("Collector", "跳过: 节点不可见")
            return false
        }
        if (node.isPassword) {
            L.d("Collector", "跳过: 密码输入框")
            return false
        }
        return true
    }

    /**
     * 从节点中提取文本内容
     * 优先使用 text 属性，如果没有则使用 contentDescription 属性
     */
    private fun extractText(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        if (text.isNotBlank()) return text
        L.d("Collector", "text为空, 使用contentDescription")
        return contentDesc
    }
}
