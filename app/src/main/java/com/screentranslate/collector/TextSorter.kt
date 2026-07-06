package com.screentranslate.collector

import android.graphics.Rect
import com.screentranslate.logger.L

/**
 * TextSorter - 文本排序与合并工具
 * 负责将采集到的文本节点按屏幕位置排序，并合并相邻的文本片段
 */
object TextSorter {

    /**
     * 按屏幕位置排序文本节点
     * 排序规则：先按垂直位置（从上到下），再按水平位置（从左到右）
     * 除以20是为了将相近位置的文本归为同一行
     */
    fun sortByPosition(nodes: List<TextNode>): List<TextNode> {
        return nodes.sortedWith(compareBy<TextNode> { it.bounds.top / 20 }
            .thenBy { it.bounds.left })
    }

    /**
     * 合并相邻的文本节点
     * 将垂直距离小于50像素且在同一列区域的文本合并成段落
     * 这样可以减少翻译请求次数，提高效率，并保持上下文连贯
     */
    fun mergeAdjacentText(nodes: List<TextNode>): List<TextNode> {
        L.d("TextSorter", "合并前: ${nodes.size} 个文本块")
        if (nodes.isEmpty()) return nodes

        val sorted = sortByPosition(nodes)
        val result = mutableListOf<TextNode>()
        var current = sorted[0]

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            val verticalGap = next.bounds.top - current.bounds.bottom      // 垂直间距
            val lineSpacing = current.bounds.height() / 3                  // 估算行间距（约1/3行高）
            val maxGap = lineSpacing.coerceAtLeast(30)                     // 最大允许间距，至少30px
            
            // 检查是否在同一列区域（水平位置相近）
            val horizontalDistance = kotlin.math.abs(next.bounds.left - current.bounds.left)
            val sameColumn = horizontalDistance < 100                      // 水平距离小于100px视为同列

            // 如果垂直间距在合理范围内且在同一列，则合并
            if (verticalGap in 0..maxGap && sameColumn) {
                L.d("TextSorter", "合并段落: \"${current.text.take(20)}\" + \"${next.text.take(20)}\" (间距=${verticalGap}px, 阈值=${maxGap}px)")
                current = TextNode(
                    text = "${current.text}\n${next.text}",                // 用换行连接，保持段落结构
                    bounds = Rect(
                        current.bounds.left.coerceAtMost(next.bounds.left),
                        current.bounds.top,
                        next.bounds.right.coerceAtLeast(current.bounds.right),
                        next.bounds.bottom.coerceAtLeast(current.bounds.bottom)
                    ),
                    packageName = current.packageName
                )
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        L.d("TextSorter", "合并后: ${result.size} 个文本块")
        return result
    }
}
