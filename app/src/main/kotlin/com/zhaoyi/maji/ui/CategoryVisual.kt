package com.zhaoyi.maji.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class CategoryVisual(val icon: ImageVector, val color: Color)

fun categoryVisual(category: String): CategoryVisual = when (category) {
    "餐饮" -> CategoryVisual(Icons.Filled.Restaurant, Color(0xFFF57C00))
    "交通" -> CategoryVisual(Icons.Filled.DirectionsCar, Color(0xFF1E88E5))
    "购物" -> CategoryVisual(Icons.Filled.ShoppingCart, Color(0xFFD81B60))
    "居家" -> CategoryVisual(Icons.Filled.Home, Color(0xFF43A047))
    "娱乐" -> CategoryVisual(Icons.Filled.SportsEsports, Color(0xFF8E24AA))
    "医疗" -> CategoryVisual(Icons.Filled.LocalHospital, Color(0xFF00ACC1))
    "通讯" -> CategoryVisual(Icons.Filled.Phone, Color(0xFF607D8B))
    "转账" -> CategoryVisual(Icons.Filled.SwapHoriz, Color(0xFF795548))
    "其他" -> CategoryVisual(Icons.Filled.MoreHoriz, Color(0xFF757575))
    "转账收款" -> CategoryVisual(Icons.Filled.ArrowDownward, Color(0xFF26A69A))
    "工资" -> CategoryVisual(Icons.Filled.AccountBalanceWallet, Color(0xFFFFA000))
    "红包" -> CategoryVisual(Icons.Filled.CardGiftcard, Color(0xFFE53935))
    "理财收益" -> CategoryVisual(Icons.Filled.Savings, Color(0xFF3949AB))
    else -> CategoryVisual(Icons.Filled.MoreHoriz, Color(0xFF757575))
}
