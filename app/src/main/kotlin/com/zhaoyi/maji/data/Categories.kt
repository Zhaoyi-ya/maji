package com.zhaoyi.maji.data

/**
 * 记账分类的单一数据源：自动识别（识别提示词）与手动记账（对话框选择）共用同一份词表，
 * 从根上避免"提示词分类"与"UI 分类"漂移导致识别结果落不到对应 chip。
 *
 * 收入与支出是两套完全独立的分类，互不包含。
 */
object Categories {
    /** 支出分类。顺序即对话框/提示词里的展示顺序。 */
    val EXPENSE = listOf("餐饮", "交通", "购物", "居家", "娱乐", "医疗", "通讯", "转账", "其他")

    /** 收入分类。与支出分离，互不包含。 */
    val INCOME = listOf("转账收款", "工资", "红包", "理财收益", "其他")

    /** 按收支类型取对应的分类列表。 */
    fun forType(type: TransactionType): List<String> =
        if (type == TransactionType.INCOME) INCOME else EXPENSE
}
