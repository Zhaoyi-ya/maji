package com.zhaoyi.maji.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 取件码 / 取餐码的类型。
 *
 * [label] 是显示在岛上的 2-4 字类型标签，[action] 是岛上按钮的文案，
 * [emoji] 用于列表和岛图标的占位符号。
 */
enum class CodeKind(
    val label: String,
    val action: String,
    val emoji: String,
) {
    MEAL("取餐码", "已取餐", "🍔"),
    MILK_TEA("奶茶", "已取餐", "🧋"),
    COFFEE("咖啡", "已取餐", "☕"),
    EXPRESS("取件码", "已取件", "📦"),
    LOCKER("快递柜", "已取件", "🗄️"),
    TICKET("票号", "已使用", "🎫"),
    QUEUE("排号", "已就餐", "🔔"),
    OTHER("号码", "已完成", "🔖"),
    ;

    companion object {
        fun fromName(name: String): CodeKind = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

@Entity(tableName = "pickup_codes")
data class PickupCode(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    /** 号码本体，例如 "K555"、"8-3-2024" */
    val code: String,
    /** 类型，存枚举名 */
    val kind: String = CodeKind.MEAL.name,
    /** 商家，例如 "肯德基(北大北门店)" */
    val merchant: String = "",
    /** 商品名，例如 "香辣鸡腿堡套餐" */
    val item: String = "",
    /** 商品副标题，例如 "中杯 · 少冰" */
    val itemDetail: String = "",
    /** 价格文本，例如 "¥29.90"，空表示不显示 */
    val price: String = "",
    /** 备注 */
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** 完成时间，非空表示已取 */
    val doneAt: Long? = null,
    /** 当前是否挂在岛上 */
    val onIsland: Boolean = false,
) {
    val codeKind: CodeKind get() = CodeKind.fromName(kind)
    val isDone: Boolean get() = doneAt != null
}
