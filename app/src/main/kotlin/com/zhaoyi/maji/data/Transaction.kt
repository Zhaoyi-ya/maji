package com.zhaoyi.maji.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class TransactionType { INCOME, EXPENSE }

/**
 * 一条记账记录。金额始终以正数存储，正负号由 [type] 决定。
 */
@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val amount: Double,
    val type: TransactionType,
    val category: String,
    val note: String? = null,
    val imagePath: String? = null,
    val date: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)
