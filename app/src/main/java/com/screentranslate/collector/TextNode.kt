package com.screentranslate.collector

import android.graphics.Rect

data class TextNode(
    val text: String,
    val bounds: Rect,
    val packageName: String,
    val isEditable: Boolean = false,
    val nodeId: Int = hashCode()
)
