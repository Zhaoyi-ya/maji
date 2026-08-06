package com.zhaoyi.maji.util

import androidx.annotation.DrawableRes
import com.zhaoyi.maji.R
import com.zhaoyi.maji.data.CodeKind

/**
 * 品牌图标映射的单一事实来源。
 * 通知(IslandNotifier)与列表页(PickupCodePage)都必须走这里，
 * 禁止各自再写一份 when 映射 —— 之前列表页是过时副本(只有 4 个品牌)，
 * 导致大部分品牌在列表里显示成「未知图标」，而通知里却正常。
 */
@DrawableRes
fun brandIconRes(merchant: String, kind: CodeKind): Int {
    val m = merchant.trim().lowercase()
    return when {
        m.contains("星巴克") || m.contains("starbucks") -> R.drawable.brand_starbucks
        m.contains("瑞幸") || m.contains("luckin") -> R.drawable.brand_luckin
        m.contains("库迪") || m.contains("cotti") -> R.drawable.brand_cotti
        m.contains("蜜雪") || m.contains("mixue") -> R.drawable.brand_mixue
        m.contains("古茗") -> R.drawable.brand_guming
        m.contains("茶百道") -> R.drawable.brand_chabaidao
        m.contains("喜茶") -> R.drawable.brand_xicha
        m.contains("霸王") || m.contains("bawang") -> R.drawable.brand_bawangchaji
        m.contains("coco") || m.contains("都可") -> R.drawable.brand_coco
        m.contains("manner") -> R.drawable.brand_manner
        m.contains("肯德基") || m.contains("kfc") -> R.drawable.brand_kfc
        m.contains("麦当劳") || m.contains("mcdonald") || m.contains("金拱门") -> R.drawable.brand_mcdonalds
        // 以下为用户后续补充的品牌图标
        m.contains("沪上") -> R.drawable.brand_hushangayi
        m.contains("书亦") || m.contains("烧仙草") -> R.drawable.brand_shuyishaoxiancao
        m.contains("幸运咖") -> R.drawable.brand_xinyunka
        m.contains("奈雪") -> R.drawable.brand_naixuedecha
        m.contains("益禾堂") -> R.drawable.brand_yihetang
        m.contains("塔斯汀") -> R.drawable.brand_tasiting
        m.contains("华莱士") -> R.drawable.brand_hualaishi
        m.contains("必胜客") || m.contains("pizzahut") -> R.drawable.brand_pizzahut
        m.contains("汉堡王") || m.contains("burgerking") || m.contains("burger king") -> R.drawable.brand_burgerking
        // 以下为快递品牌（用户补充）。注意极兔(jdl)必须放在京东(jd)之前，否则"JDL"会被jd误命中
        m.contains("极兔") || m.contains("j&t") || m.contains("jdl") -> R.drawable.express_jitu
        m.contains("京东") || m.contains("jd") -> R.drawable.express_jd
        m.contains("顺丰") || m.contains("sf") -> R.drawable.express_shunfeng
        m.contains("中通") || m.contains("zto") -> R.drawable.express_zhongtong
        m.contains("圆通") || m.contains("yto") -> R.drawable.express_yuantong
        m.contains("申通") || m.contains("sto") -> R.drawable.express_shentong
        m.contains("韵达") || m.contains("yunda") -> R.drawable.express_yunda
        m.contains("德邦") -> R.drawable.express_debang
        m.contains("菜鸟") || m.contains("丹鸟") || m.contains("cainiao") -> R.drawable.express_cainiao
        m.contains("ems") -> R.drawable.express_ems
        m.contains("中国邮政") -> R.drawable.express_china_post
        m.contains("丰巢") -> R.drawable.express_fengcao
        else -> when (kind) {
            CodeKind.MEAL -> R.drawable.brand_generic_food
            CodeKind.MILK_TEA, CodeKind.COFFEE -> R.drawable.brand_generic_drink
            else -> R.drawable.brand_express
        }
    }
}
