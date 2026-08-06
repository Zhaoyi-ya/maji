package com.zhaoyi.maji.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import androidx.annotation.DrawableRes
import com.zhaoyi.maji.MainActivity
import com.zhaoyi.maji.R
import com.zhaoyi.maji.data.CodeKind
import com.zhaoyi.maji.util.brandIconRes
import com.zhaoyi.maji.data.PickupCode
import org.json.JSONObject

/**
 * 把一条取件码 / 取餐码挂到 HyperOS 超级岛上。
 *
 * 品牌图标按商家名自动匹配（内置常见快递/外卖/电商品牌映射）。
 */
object IslandNotifier {

    private const val CHANNEL_ID = "maji_island"
    private const val TAG = "IslandNotifier"
    private const val ID_BASE = 7100
    private const val DEFAULT_TIMEOUT_SEC = 7200
    private const val ACTION_KEY_DONE = "miui.focus.action_main"

    // ── 品牌图标资源 key（5-key 结构）──
    private const val PIC_MAIN = "miui.focus.pic_icon_main"
    private const val PIC_APP = "miui.focus.pic_app"
    private const val PIC_IM_BADGE = "miui.focus.pic_im_badge"
    private const val PIC_TICKER = "miui.focus.pic_ticker"
    private const val PIC_AOD = "miui.focus.pic_aod"

    // ── 对外 API ──

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "取件码提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "把取件码 / 取餐码挂到超级岛与通知栏"
                    setShowBadge(false)
                }
            )
        }
    }

    fun notificationIdFor(code: PickupCode): Int = ID_BASE + (code.id.hashCode() and 0x0FFF)

    fun show(context: Context, code: PickupCode, timeoutSec: Int = DEFAULT_TIMEOUT_SEC): Boolean {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val notification = build(context, code, timeoutSec)
        var ok = false
        // 若开启「断网绕过白名单」，则在断开小米推送服务联网的盲窗内发布通知，绕过超级岛白名单。
        WhitelistBypass.runIfEnabled(context, WhitelistBypass.isEnabled(context)) {
            ok = runCatching {
                nm.notify(notificationIdFor(code), notification)
                true
            }.onFailure {
                android.util.Log.e(TAG, "notify failed", it)
            }.getOrDefault(false)
        }
        return ok
    }

    fun dismiss(context: Context, code: PickupCode) {
        dismiss(context, notificationIdFor(code))
    }

    fun dismiss(context: Context, notificationId: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId)
    }

    /** 进度通知：截图中 / 识别中 */
    fun showProgress(context: Context, text: String) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val appIcon = Icon.createWithResource(context, R.mipmap.ic_launcher)
        val pics = android.os.Bundle().apply {
            putParcelable("miui.focus.pic_icon_main", appIcon)
            putParcelable("miui.focus.pic_app", appIcon)
        }
        val param = JSONObject().put("param_v2", JSONObject().apply {
            put("protocol", 3)
            put("ticker", text)
            put("tickerPic", "miui.focus.pic_app")
            put("param_island", JSONObject().apply {
                put("islandProperty", 1)
                put("bigIslandArea", JSONObject().apply {
                    put("imageTextInfoLeft", JSONObject().apply {
                        put("type", 1)
                        put("picInfo", JSONObject().apply {
                            put("type", 1); put("pic", "miui.focus.pic_icon_main")
                        })
                    })
                    put("textInfo", JSONObject().apply {
                        put("title", text); put("showHighlightColor", false)
                    })
                })
                put("smallIslandArea", JSONObject().apply {
                    put("imageTextInfoRight", JSONObject().apply {
                        put("type", 6)
                        put("textInfo", JSONObject().apply {
                            put("title", text); put("showHighlightColor", false)
                        })
                        put("picInfo", JSONObject().apply {
                            put("type", 4); put("pic", "miui.focus.pic_icon_main")
                        })
                    })
                })
            })
        })
        val n = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(text).setContentText("正在处理截图")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true).setShowWhen(false)
            .addExtras(android.os.Bundle().apply {
                putString("miui.focus.param", param.toString())
                putBundle("miui.focus.pics", pics)
            })
            .build()
        nm.notify(PROGRESS_ID, n)
        // 安排安全超时：避免识别卡死导致进度永久占岛
        progressCtx = context.applicationContext
        progressHandler.removeCallbacks(progressAutoDismiss)
        progressHandler.postDelayed(progressAutoDismiss, PROGRESS_TIMEOUT_MS)
    }

    fun dismissProgress(context: Context) {
        progressHandler.removeCallbacks(progressAutoDismiss)
        progressCtx = null
        context.getSystemService(NotificationManager::class.java).cancel(PROGRESS_ID)
    }

    /** 发送普通通知（记账结果）。点击后打开 App 并定位到对应账单的编辑面板。 */
    fun notifyLedger(context: Context, title: String, body: String, txnId: String? = null) {
        ensureChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                // singleTask + SINGLE_TOP：App 已在前台/后台时点通知，新意图必走 onNewIntent；
                // 冷启动则走 onCreate。避免标准 launchMode 下「复用实例但不回调」导致深链丢失。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (txnId != null) putExtra(MainActivity.EXTRA_EDIT_TXN_ID, txnId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title).setContentText(body)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(LEDGER_ID, n)
    }

    /** 识别到空内容时，发一条普通通知（不走超级岛浮窗） */
    fun notifyEmptyResult(context: Context) {
        ensureChannel(context)
        val n = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("未识别到有效内容")
            .setContentText("截图中未检测到账单或取件码")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(EMPTY_ID, n)
    }

    private const val PROGRESS_ID = ID_BASE + 9000
    private const val LEDGER_ID = ID_BASE + 9001
    private const val EMPTY_ID = ID_BASE + 9002

    // 进度通知安全超时：即使识别卡死也最多占用 120s，绝不永久占岛
    private const val PROGRESS_TIMEOUT_MS = 120_000L
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressCtx: Context? = null
    private val progressAutoDismiss = Runnable {
        progressCtx?.let { ctx ->
            runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(PROGRESS_ID) }
        }
    }

    // ── 通知构造（protocol=1，在原模板上只改图标和内容） ──

    private fun build(context: Context, code: PickupCode, timeoutSec: Int): Notification {
        val kind = code.codeKind
        val brandIconRes = brandIslandIcon(code)
        val brandIcon = Icon.createWithResource(context, brandIconRes)
        val appIcon = Icon.createWithResource(context, R.mipmap.ic_launcher)
        val transparentIcon = Icon.createWithResource(context, R.drawable.transparent_icon)

        val pics = android.os.Bundle().apply {
            putParcelable(PIC_MAIN, brandIcon)
            putParcelable(PIC_APP, appIcon)
            putParcelable(PIC_IM_BADGE, transparentIcon)
            putParcelable(PIC_TICKER, brandIcon)
            putParcelable(PIC_AOD, brandIcon)
        }

        val codeText = code.code.trim()
        val merchant = code.merchant.trim().ifEmpty { kind.label }
        val item = code.item.trim()
        val detail = code.itemDetail.trim()
        val price = code.price.trim().take(8)

        // iconTextInfo：新图文组件（模版16/17）——左侧品牌图 + 文本，无 APP 角标。
        // 此前误用 chatInfo（IM图文组件，自带 appiconPkg 角标）导致展开态带角标；
        // 又误用 baseInfo（文本组件2，无左图）导致无法显示品牌图。官方字段即 iconTextInfo。
        val iconTextInfo = JSONObject().apply {
            put("animIconInfo", JSONObject().apply {
                put("type", 0)            // 0: 静态图
                put("src", PIC_MAIN)      // 左侧品牌图（如圆通/菜鸟图标）
                put("srcDark", PIC_MAIN)
            })
            // 排布（对齐模板16/17 新图文组件）：取件码放 title（最大字 + 蓝色高亮，最突出），
            // 商家名放 content，商品名放 subContent（与地址互换位置；地址移到底部 primaryText）。
            put("title", codeText)                       // 主要文本：取件码（蓝字高亮）
            put("colorTitle", "#006EFF")
            put("colorTitleDark", "#4DA3FF")
            if (merchant.isNotBlank()) {
                put("content", merchant)                // 次要文本1：商家名（中字）
            }
            if (item.isNotBlank()) {
                put("subContent", item)                 // 次要文本2：商品名（最小字）
            }
        }

        // picInfo：品牌图标
        val picInfo = JSONObject().apply {
            put("type", 1)
            put("pic", PIC_MAIN)
            put("picDark", PIC_MAIN)
        }

        // hintInfo（按钮组件5，展开态下半部分；官方认可组件，highlightInfoV3 在本机不被识别故弃用）：
        //   title（大字）：地址/柜号（空则不输出该 key，避免渲染空块）
        //   content（文字标签）：金额/柜号（红色小字；快递场景 price 存的是柜号，用 formatAmountLabel 防无脑加 ¥）
        //   actionInfo：已取件按钮（纯文字，不含图标；沿用原始可工作结构）
        val hintInfo = JSONObject().apply {
            put("type", 1)
            if (detail.isNotBlank()) {
                put("title", detail)                        // 大字：地址/柜号
                put("colorTitle", "#000000")
                put("colorTitleDark", "#FFFFFF")
            }
            if (price.isNotBlank()) {
                put("content", formatAmountLabel(price))    // 文字标签：金额 / 柜号（红字）
                put("colorContent", "#FF3B30")
                put("colorContentDark", "#FF6B61")
            }
            put("actionInfo", JSONObject().apply {
                put("action", ACTION_KEY_DONE)
                put("actionTitle", kind.action)
                put("actionTitleColor", "#FFFFFF")
                put("actionTitleColorDark", "#000000")
                put("actionBgColor", "#1A1A1A")
                put("actionBgColorDark", "#E0E0E0")
                put("clickWithCollapse", false)
            })
        }

        // 胶囊态（大岛摘要态）：摄像头把它切成 A / B 两块
        //   A 区 imageTextInfoLeft：品牌图标（快递场景额外附上快递名 + 柜号）
        //   B 区 textInfo：取件码大字
        val leftLabel = capsuleLeftLabel(code)
        val bigIslandArea = JSONObject().apply {
            put("imageTextInfoLeft", JSONObject().apply {
                put("type", 1)
                put("picInfo", JSONObject().apply {
                    put("type", 1)
                    put("pic", PIC_MAIN)
                    put("loop", false)
                    put("autoplay", false)
                    put("number", 0)
                })
                // 协议 4.2：A 区 type=1 为「图标 + 可选文字」。仅快递/柜机场景在图标旁补文字，
                // 取餐场景保持纯图标（最开始版本）。
                if (leftLabel != null) {
                    put("textInfo", JSONObject().apply {
                        put("frontTitle", "")
                        put("title", leftLabel.first)
                        put("content", leftLabel.second)
                        put("showHighlightColor", false)
                        put("narrowFont", false)
                    })
                }
            })
            // B 区：正文大字=取件码（最开始版本，不附加后置小字）
            put("textInfo", JSONObject().apply {
                put("frontTitle", "")
                put("title", codeText)
                put("content", "")
                put("showHighlightColor", false)
                put("narrowFont", false)
            })
        }

        // 压缩态：品牌图标
        val smallIslandArea = JSONObject().apply {
            put("imageTextInfoRight", JSONObject().apply {
                put("type", 6)
                put("textInfo", JSONObject().apply {
                    put("title", codeText)
                    put("showHighlightColor", false)
                })
                put("picInfo", JSONObject().apply {
                    put("type", 4)
                    put("pic", PIC_MAIN)
                })
            })
        }

        val paramIsland = JSONObject().apply {
            put("islandProperty", 2)
            put("islandOrder", false)
            put("dismissIsland", false)
            put("needCloseAnimation", true)
            put("bigIslandArea", bigIslandArea)
            put("smallIslandArea", smallIslandArea)
        }

        val paramV2 = JSONObject().apply {
            put("protocol", 3)
            put("business", "maji_pickup_code")
            put("updatable", true)
            put("ticker", "${kind.label} $codeText")
            put("tickerPic", PIC_TICKER)
            put("enableFloat", true)
            put("isShowNotification", true)
            put("islandFirstFloat", true)
            put("aodTitle", "${kind.label}：$codeText")
            put("aodPic", PIC_AOD)
            put("picInfo", JSONObject().apply {
                put("type", 1)
                put("pic", PIC_APP)
                put("loop", false)
                put("autoplay", false)
                put("number", 0)
            })
            put("smallWindowInfo", JSONObject().apply {
                put("targetPage", "${context.packageName}.MainActivity")
            })
            // 展开态（点开大岛）两段结构：
            //   iconTextInfo：官方「新图文组件」（模版16/17），左侧品牌图 + 取餐码(蓝)/商品/商家，无 APP 角标。
            //   hintInfo：按钮组件5（展开态下半部分），地址/柜号(title大字) + 金额(红色 content 文字标签) + 已取件按钮。
            // 历史弃用：chatInfo（带 APP 角标）、baseInfo（当前系统不渲染会导致取件码消失）、
            //           highlightInfoV3（本机不被识别，会渲染异常空块）。
            put("iconTextInfo", iconTextInfo)
            put("hintInfo", hintInfo)
            put("param_island", paramIsland)
        }

        val param = JSONObject().apply { put("param_v2", paramV2) }

        val donePi = IslandActionReceiver.donePendingIntent(context, code.id)
        val completePendingIntent = PendingIntent.getBroadcast(
            context, code.id.hashCode() + 1,
            Intent(context, IslandActionReceiver::class.java).apply {
                action = "com.zhaoyi.maji.island.DONE"
                setPackage(context.packageName)
                putExtra("code_id", code.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val openApp = PendingIntent.getActivity(
            context,
            code.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_PICKUP, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val smallIconRes = when (code.codeKind) {
            CodeKind.MEAL -> android.R.drawable.ic_menu_compass
            CodeKind.EXPRESS, CodeKind.LOCKER -> android.R.drawable.ic_menu_upload
            else -> android.R.drawable.ic_menu_compass
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("${kind.label} $codeText")
            .setContentText(listOf(merchant, item).filter { it.isNotBlank() }.distinct().joinToString(" · "))
            .setSmallIcon(Icon.createWithResource(context, smallIconRes))
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, brandIconRes))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        // pics + actions 分开 addExtras，避免单个 bundle 超限
        builder.addExtras(buildPicsBundle(context, brandIconRes))
        builder.addExtras(android.os.Bundle().apply {
            putBundle("miui.focus.actions", android.os.Bundle().apply {
                putParcelable(ACTION_KEY_DONE, Notification.Action.Builder(null, kind.action, donePi).build())
            })
        })
        builder.addAction(Notification.Action.Builder(null, kind.action, completePendingIntent).build())

        val notification = builder.build()
        // param 在 build 之后设，避免被模板默认值覆盖
        notification.extras.putString("miui.focus.param", param.toString())
        return notification
    }

    // ── pics bundle（5-key 结构）──

    private fun buildPicsBundle(context: Context, @DrawableRes iconRes: Int): android.os.Bundle {
        val mainIcon = Icon.createWithResource(context, iconRes)
        val appIcon = Icon.createWithResource(context, R.mipmap.ic_launcher)
        val transparentIcon = Icon.createWithResource(context, R.drawable.transparent_icon)
        val pics = android.os.Bundle().apply {
            putParcelable(PIC_MAIN, mainIcon)
            putParcelable(PIC_APP, appIcon)
            putParcelable(PIC_IM_BADGE, transparentIcon)
            putParcelable(PIC_TICKER, mainIcon)
            putParcelable(PIC_AOD, mainIcon)
        }
        return android.os.Bundle().apply { putBundle("miui.focus.pics", pics) }
    }

    // ── 胶囊态 A 区文案（快递专用）──

    /** 常见快递 / 驿站品牌：关键词 → 岛上显示的规范短名（控制在 4 字内，胶囊很窄） */
    private val EXPRESS_BRANDS: List<Pair<List<String>, String>> = listOf(
        listOf("菜鸟") to "菜鸟驿站",
        listOf("妈妈驿站") to "妈妈驿站",
        listOf("兔喜") to "兔喜驿站",
        listOf("熊猫快收") to "熊猫快收",
        listOf("近邻宝") to "近邻宝",
        listOf("速递易") to "速递易",
        listOf("丰巢") to "丰巢柜",
        listOf("顺丰", "sf-express") to "顺丰速运",
        listOf("京东", "jd") to "京东快递",
        listOf("圆通", "yto") to "圆通快递",
        listOf("中通", "zto") to "中通快递",
        listOf("申通", "sto") to "申通快递",
        listOf("韵达", "yunda") to "韵达快递",
        listOf("极兔", "j&t", "jitu") to "极兔速递",
        listOf("邮政", "ems", "china post") to "邮政快递",
        listOf("德邦") to "德邦快递",
        listOf("宅急送") to "宅急送",
        listOf("跨越") to "跨越速运",
        listOf("百世") to "百世快递",
    )

    /** 柜号：「07号快递柜」「C柜」「3号架」→ 07号柜 / C柜 / 3号架 */
    private val SPOT_SLOT = Regex("([0-9A-Za-z]{1,3})\\s*号?[^0-9A-Za-z]{0,3}?(柜|格|架|排)")

    /** 楼层：「一层」「3楼」 */
    private val SPOT_FLOOR = Regex("([0-9一二三四五六七八九十]{1,3})\\s*(层|楼)")

    /**
     * 胶囊态左侧（A 区）在图标旁补的文字。仅快递/柜机场景返回非空（快递名 + 柜号），
     * 取餐场景返回 null → 纯图标（最开始版本）。
     *
     * @return first = 大字（快递/驿站名），second = 后置小字（柜号或楼层，可能为空）
     */
    private fun capsuleLeftLabel(code: PickupCode): Pair<String, String>? {
        return when (code.codeKind) {
            CodeKind.EXPRESS, CodeKind.LOCKER -> expressCapsuleLabel(code)
            else -> null
        }
    }

    private fun expressCapsuleLabel(code: PickupCode): Pair<String, String>? {
        val haystack = listOf(code.merchant, code.itemDetail, code.note, code.item)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (haystack.isBlank()) return null

        val lower = haystack.lowercase()
        val brand = EXPRESS_BRANDS
            .firstOrNull { (keys, _) -> keys.any { lower.contains(it) } }
            ?.second
            ?: fallbackBrandName(code)
            ?: return null

        return brand to pickupSpot(code)
    }

    /** 没命中品牌表时，从 merchant / itemDetail 里挑一个能用的短名 */
    private fun fallbackBrandName(code: PickupCode): String? {
        val candidates = listOf(code.merchant, code.itemDetail)
        for (raw in candidates) {
            val cleaned = raw.trim()
                .replace(Regex("[（(\\[【].*?[）)\\]】]"), "")   // 去掉括号内的门店后缀
                .trim()
            // "快递" 是模型识别不出品牌时的兜底值，等于没信息
            if (cleaned.isBlank() || cleaned == "快递" || cleaned == "取件码") continue
            return if (cleaned.length > 5) cleaned.take(5) else cleaned
        }
        return null
    }

    /** 从柜号 / 驿站描述里提取取件位置，取不到返回空串 */
    private fun pickupSpot(code: PickupCode): String {
        val source = listOf(code.price, code.itemDetail, code.note)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (source.isBlank()) return ""

        SPOT_SLOT.find(source)?.let { m ->
            val num = m.groupValues[1]
            val unit = m.groupValues[2]
            // 纯数字带「号」更自然：07号柜；字母则直接贴：C柜
            return if (num.all { it.isDigit() }) "${num}号${unit}" else "${num}${unit}"
        }
        SPOT_FLOOR.find(source)?.let { m ->
            return m.groupValues[1] + m.groupValues[2]
        }
        return ""
    }

    /** price 字段在快递场景存的是柜号而非金额，只有真金额才加 ¥ */
    private fun formatAmountLabel(raw: String): String {
        val p = raw.trim()
        if (p.isEmpty()) return ""
        if (p.startsWith("¥") || p.startsWith("￥") || p.startsWith("$")) return p
        if (Regex("^[0-9]+(\\.[0-9]{1,2})?元?$").matches(p)) return "¥" + p.removeSuffix("元")
        return p
    }

    // ── 品牌图标映射 ──

    @DrawableRes
    fun brandIslandIcon(code: PickupCode): Int = brandIconRes(code.merchant, code.codeKind)
}

/** 小米窄体字只支持数字和拉丁字符 */
internal fun supportsNarrowFont(text: String): Boolean {
    val compact = text.trim()
    return compact.isNotEmpty() && compact.all {
        it.isDigit() || it in 'A'..'Z' || it in 'a'..'z' || it in ".:-+/"
    }
}

internal fun capsuleText(text: String, narrowFont: Boolean): String =
    text.trimEnd() + if (narrowFont) "\u2009" else ""
