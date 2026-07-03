package com.screentranslate.collector

import android.graphics.Rect

object TextSorter {

    fun sortByPosition(nodes: List<TextNode>): List<TextNode> {
        return nodes.sortedWith(compareBy<TextNode> { it.bounds.top / 20 }
            .thenBy { it.bounds.left })
    }

    fun mergeAdjacentText(nodes: List<TextNode>): List<TextNode> {
        if (nodes.isEmpty()) return nodes

        val sorted = sortByPosition(nodes)
        val result = mutableListOf<TextNode>()
        var current = sorted[0]

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            val verticalGap = next.bounds.top - current.bounds.bottom
            val horizontalOverlap = current.bounds.right >= next.bounds.left - 10

            if (verticalGap in 0..10 && horizontalOverlap) {
                current = TextNode(
                    text = "${current.text} ${next.text}",
                    bounds = Rect(
                        current.bounds.left,
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
        return result
    }
}
