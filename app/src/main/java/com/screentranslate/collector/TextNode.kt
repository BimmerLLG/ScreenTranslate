package com.screentranslate.collector

import android.graphics.Rect

/**
 * TextNode - 文本节点数据类
 * 用于存储从屏幕上采集到的单个文本块信息
 *
 * @param text 文本内容
 * @param bounds 文本在屏幕上的位置（矩形区域）
 * @param packageName 所属应用包名
 * @param isEditable 是否为可编辑文本（如输入框）
 * @param nodeId 节点唯一标识（默认使用 hashCode）
 */
data class TextNode(
    val text: String,
    val bounds: Rect,
    val packageName: String,
    val isEditable: Boolean = false,
    val nodeId: Int = text.hashCode() xor bounds.hashCode()
)
