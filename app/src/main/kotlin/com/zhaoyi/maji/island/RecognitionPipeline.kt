package com.zhaoyi.maji.island

import android.content.Context
import android.util.Base64
import com.zhaoyi.maji.data.AppDatabase
import com.zhaoyi.maji.data.CodeKind
import com.zhaoyi.maji.data.PickupCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 在线大模型识别客户端。调用 OpenAI 兼容 API（含 MiMo、智谱等）。
 */
object RecognitionPipeline {

    private const val SYSTEM_PROMPT = "你是订单与账单信息提取引擎。你必须只输出 JSON，不能输出 Markdown。"

    suspend fun recognize(context: Context, imageBytes: ByteArray): RecognizedData =
        withContext(Dispatchers.IO) {
            // 优先 MiMo session
            val session = MiclawSessionStore.load(context)
            if (session != null && session.isUsable) {
                AppLog.i("识别", "使用 MiMo session，token=${session.serviceToken.take(8)}…")
                val client = MiclawRecognitionClient(context)
                val response = client.recognize(imageBytes)
                return@withContext parseOrders(response)
            }

            AppLog.i("识别", "回退标准 API")
            // 回退到标准 API
            val prefs = context.getSharedPreferences("recognition", Context.MODE_PRIVATE)
            val apiUrl = prefs.getString("api_url", "https://api.xiaomimimo.com/v1/chat/completions")!!
            val apiKey = prefs.getString("api_key", "")!!
            val model = prefs.getString("model", "mimo-v2.5")!!
            require(apiKey.isNotBlank()) { "未配置 API Key，也未登录 MiMo" }

            val encoded = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val payload = JSONObject().apply {
                put("model", model)
                put("stream", false)
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    put(JSONObject().put("role", "user").put("content", org.json.JSONArray().apply {
                        put(JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$encoded")))
                        put(JSONObject().put("type", "text").put("text", loadPrompt(context)))
                    }))
                })
                put("max_tokens", 4096)
                put("temperature", 0)
                put("response_format", JSONObject().put("type", "json_object"))
            }

            val resp = post(apiUrl, apiKey, payload)
            parseOrders(resp)
        }

    /**
     * 纯文本识别（短信取件码/取餐码等）：与 [recognize] 共用同一套后端选择逻辑——
     * 有可用 MiMo session 时走 MiMo 文本接口，否则回退标准 OpenAI 兼容 API；
     * 结果复用 [parseOrders] 解析成 [RecognizedData]，与截图识别同一解析链路。
     *
     * 关键点：直接复用截图识别的提示词（[loadPrompt]，已适配取餐码/取件码），
     * 把"图片"换成"短信正文"交给同一套模型——大模型按同一 JSON 格式返回，
     * 因此不需要任何独立的短信提示词，也不存在第二条解析分支。
     */
    suspend fun recognizeText(context: Context, text: String): RecognizedData =
        withContext(Dispatchers.IO) {
            val session = MiclawSessionStore.load(context)
            if (session != null && session.isUsable) {
                AppLog.i("识别", "短信: 使用 MiMo session")
                val client = MiclawRecognitionClient(context)
                val response = client.recognizeText(text)
                return@withContext parseOrders(response)
            }

            AppLog.i("识别", "短信: 回退标准 API")
            val prefs = context.getSharedPreferences("recognition", Context.MODE_PRIVATE)
            val apiUrl = prefs.getString("api_url", "https://api.xiaomimimo.com/v1/chat/completions")!!
            val apiKey = prefs.getString("api_key", "")!!
            val model = prefs.getString("model", "mimo-v2.5")!!
            require(apiKey.isNotBlank()) { "未配置 API Key，也未登录 MiMo" }

            // 与截图识别同构：system 用简短角色提示，user 先放截图提示词、再放短信正文。
            val prompt = loadPrompt(context)
            val payload = JSONObject().apply {
                put("model", model)
                put("stream", false)
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    put(JSONObject().put("role", "user").put("content", org.json.JSONArray().apply {
                        put(JSONObject().put("type", "text").put("text", prompt))
                        put(JSONObject().put("type", "text").put("text", text))
                    }))
                })
                put("max_tokens", 4096)
                put("temperature", 0)
                put("response_format", JSONObject().put("type", "json_object"))
            }

            val resp = post(apiUrl, apiKey, payload)
            parseOrders(resp)
        }

    private suspend fun post(apiUrl: String, apiKey: String, payload: JSONObject): String {
        return try {
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("api-key", apiKey)
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}: ${body.take(500)}")
            body
        } catch (e: Exception) {
            if (e.message?.contains("abort") == true) {
                delay(1000)
                post(apiUrl, apiKey, payload)
            } else throw e
        }
    }

    private fun parseOrders(response: String): RecognizedData {
        val root = JSONObject(response)
        val content = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            ?.removeSurrounding("```json", "```")
            ?.trim()
            ?: response

        val json = runCatching { JSONObject(content) }.getOrElse { return RecognizedData() }

        // 归一化成订单列表：优先 orders 数组（多笔）；兼容旧的单条（无 orders 包裹）格式。
        val orders = mutableListOf<JSONObject>()
        val arr = json.optJSONArray("orders")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.length() > 0) orders.add(o)
            }
        } else if (json.has("type") || json.has("pickupCode") ||
            json.has("orderAmount") || json.has("brandName")
        ) {
            orders.add(json)
        }

        val pickupCodes = mutableListOf<PickupCode>()
        val transactions = mutableListOf<com.zhaoyi.maji.data.Transaction>()
        for (o in orders) runCatching { parseOneOrder(o, pickupCodes, transactions) }
            .onFailure { AppLog.e("识别", "单笔解析失败已跳过: ${it.message}") }

        return RecognizedData(pickupCodes, transactions)
    }

    /** 解析单条订单：可能是取餐/快递码（上岛）或一笔记账（入库），分别追加到对应列表。 */
    private fun parseOneOrder(
        o: JSONObject,
        pickupCodes: MutableList<PickupCode>,
        transactions: MutableList<com.zhaoyi.maji.data.Transaction>,
    ) {
        val rType = o.optString("type", "none")
        if (rType == "none") return

        val code = o.optString("pickupCode", "").trim().takeIf { it != "null" && it.isNotBlank() }
        val brand = o.optString("brandName", "").trim().takeIf { it != "null" } ?: ""
        val store = o.optString("storeName", "").trim().takeIf { it != "null" } ?: ""
        val amount = o.optString("orderAmount", "").trim().takeIf { it != "null" } ?: ""
        val item = o.optString("itemName", "").trim().takeIf { it != "null" } ?: ""
        val category = o.optString("category", "").trim().takeIf { it != "null" } ?: "其他"
        val dateStr = o.optString("date", "").trim().takeIf { it != "null" && it.isNotBlank() }
        val timeStr = o.optString("time", "").trim().takeIf { it != "null" && it.isNotBlank() }
        val happenedAt = parseDateTime(dateStr, timeStr) ?: System.currentTimeMillis()

        // 取餐/快递 → 上岛
        if (code != null && rType in setOf("meal", "express")) {
            val kind = when (rType) {
                "express" -> CodeKind.EXPRESS
                else -> CodeKind.MEAL
            }
            pickupCodes.add(PickupCode(
                code = code,
                kind = kind.name,
                merchant = brand.ifBlank { store },
                item = item,
                itemDetail = store,
                price = amount,
                onIsland = true,
                createdAt = happenedAt,
            ))
        }

        // 有金额 → 记账
        val pureAmount = amount.replace(Regex("[^0-9.]"), "")
        if (pureAmount.isNotBlank()) {
            val amt = pureAmount.toDoubleOrNull() ?: 0.0
            if (amt > 0) {
                val note = listOf(item, brand, store).filter { it.isNotBlank() }
                    .joinToString(" · ").take(200)
                transactions.add(com.zhaoyi.maji.data.Transaction(
                    amount = amt,
                    type = com.zhaoyi.maji.data.TransactionType.EXPENSE,
                    category = category,
                    note = note.ifBlank { null },
                    date = happenedAt,
                ))
            }
        }
    }

    /**
     * 把模型返回的 date/time 字符串解析成本地时区的时间戳。
     * 支持 "8月1日"/"8/1"/"2025-08-01" 等常见中文/数字格式，
     * 年份缺失时用当前年；若解析出的月日比今天还晚，则视为上一年。
     */
    private fun parseDateTime(dateStr: String?, timeStr: String?): Long? {
        if (dateStr.isNullOrBlank() && timeStr.isNullOrBlank()) return null

        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 解析日期
        when {
            dateStr.isNullOrBlank() -> {
                cal.timeInMillis = now.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
            }
            else -> {
                val d = dateStr.trim()
                val yearMonthDay = parseYearMonthDay(d, now)
                    ?: return null
                cal.set(Calendar.YEAR, yearMonthDay.first)
                cal.set(Calendar.MONTH, yearMonthDay.second - 1) // Calendar.MONTH 从 0 开始
                cal.set(Calendar.DAY_OF_MONTH, yearMonthDay.third)
            }
        }

        // 解析时间
        when {
            timeStr.isNullOrBlank() -> {
                cal.set(Calendar.HOUR_OF_DAY, now.get(Calendar.HOUR_OF_DAY))
                cal.set(Calendar.MINUTE, now.get(Calendar.MINUTE))
            }
            else -> {
                val t = parseHourMinute(timeStr.trim())
                if (t != null) {
                    cal.set(Calendar.HOUR_OF_DAY, t.first)
                    cal.set(Calendar.MINUTE, t.second)
                    cal.set(Calendar.SECOND, 0)
                } else {
                    cal.set(Calendar.HOUR_OF_DAY, now.get(Calendar.HOUR_OF_DAY))
                    cal.set(Calendar.MINUTE, now.get(Calendar.MINUTE))
                }
            }
        }

        return cal.timeInMillis
    }

    private fun parseYearMonthDay(d: String, now: Calendar): Triple<Int, Int, Int>? {
        val text = d.trim()
        // 相对日：今天 / 昨天 / 前天 / 大前天（大模型可能直接吐这些词）
        parseRelativeDay(text, now)?.let { return it }
        // 本周星期几：周一 … 周日（微信「微信支付」页本周记录只显示星期，不写具体日期）
        parseWeekdayThisWeek(text, now)?.let { return it }
        // 2025-08-01 / 2025/08/01 / 2025.08.01 / 2025年08月01日
        Regex("""(\d{2,4})[\-/年.](\d{1,2})[\-/月.](\d{1,2})[日]?""").find(d)?.let {
            val (y, m, day) = it.destructured
            return Triple(y.toYear(now), m.toInt(), day.toInt())
        }
        // 8月1日 / 8月1号
        Regex("""(\d{1,2})月(\d{1,2})[日号]?""").find(d)?.let {
            val (m, day) = it.destructured
            return Triple(inferYear(now, m.toInt(), day.toInt()), m.toInt(), day.toInt())
        }
        // 8/1 / 8-1 / 8.1
        Regex("""(\d{1,2})[\-/.](\d{1,2})""").find(d)?.let {
            val (m, day) = it.destructured
            return Triple(inferYear(now, m.toInt(), day.toInt()), m.toInt(), day.toInt())
        }
        return null
    }

    /** 今天 / 昨天 / 前天 / 大前天 → 相对今天的具体日期。 */
    private fun parseRelativeDay(d: String, now: Calendar): Triple<Int, Int, Int>? {
        val cal = Calendar.getInstance().apply { timeInMillis = now.timeInMillis }
        when {
            d.contains("大前天") -> cal.add(Calendar.DAY_OF_MONTH, -3)
            d.contains("前天") -> cal.add(Calendar.DAY_OF_MONTH, -2)
            d.contains("昨天") || d.contains("昨日") -> cal.add(Calendar.DAY_OF_MONTH, -1)
            d.contains("今天") || d.contains("今日") -> { /* 当天，无需偏移 */ }
            else -> return null
        }
        return Triple(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    /**
     * 本周星期几（周一..周日 / 星期一..星期日 / 星期天 / 周天）→ 本周对应的具体日期。
     * 以周一为一周起点（与微信一致）：把 now 回退到本周一，再加星期偏移。
     */
    private fun parseWeekdayThisWeek(d: String, now: Calendar): Triple<Int, Int, Int>? {
        val offset = when {
            d.contains("周一") || d.contains("星期一") -> 0
            d.contains("周二") || d.contains("星期二") -> 1
            d.contains("周三") || d.contains("星期三") -> 2
            d.contains("周四") || d.contains("星期四") -> 3
            d.contains("周五") || d.contains("星期五") -> 4
            d.contains("周六") || d.contains("星期六") -> 5
            d.contains("周日") || d.contains("星期日") || d.contains("星期天") || d.contains("周天") -> 6
            else -> return null
        }
        // Calendar.DAY_OF_WEEK: SUNDAY=1 … SATURDAY=7；距本周一的天数：周一=0 … 周日=6
        val dow = now.get(Calendar.DAY_OF_WEEK)
        val daysSinceMonday = (dow - Calendar.MONDAY + 7) % 7
        val monday = Calendar.getInstance().apply {
            timeInMillis = now.timeInMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -daysSinceMonday)
        }
        monday.add(Calendar.DAY_OF_MONTH, offset)
        return Triple(monday.get(Calendar.YEAR), monday.get(Calendar.MONTH) + 1, monday.get(Calendar.DAY_OF_MONTH))
    }

    /** 年份缺失时推断：若月日尚未到来，则视为上一年。 */
    private fun inferYear(now: Calendar, month: Int, day: Int): Int {
        val todayMonth = now.get(Calendar.MONTH) + 1
        val todayDay = now.get(Calendar.DAY_OF_MONTH)
        val year = now.get(Calendar.YEAR)
        return if (month * 100 + day > todayMonth * 100 + todayDay) year - 1 else year
    }

    private fun parseHourMinute(t: String): Pair<Int, Int>? {
        // 12:08 / 12:08:30 / 12时08分
        Regex("""(\d{1,2})[:点时](\d{1,2})(?::\d{1,2})?""").find(t)?.let {
            val (h, m) = it.destructured
            return Pair(h.toInt().coerceIn(0, 23), m.toInt().coerceIn(0, 59))
        }
        return null
    }

    private fun String.toYear(now: Calendar): Int {
        val y = this.toInt()
        return when {
            y >= 100 -> y
            y >= 50 -> 1900 + y
            else -> 2000 + y
        }
    }

    /**
     * 读取提示词：优先用用户在设置里自定义的（recognition 偏好 user_prompt）；
     * 用户未设置或清空保存后，回退到代码内置的 [DEFAULT_PROMPT]。
     */
    internal fun loadPrompt(context: Context): String {
        val prefs = context.getSharedPreferences("recognition", Context.MODE_PRIVATE)
        val user = prefs.getString("user_prompt", null)
        if (!user.isNullOrBlank()) return user
        return DEFAULT_PROMPT
    }

    /**
     * 内置默认提示词（代码常量，作为用户未自定义时的兜底）。
     * 重点约束了"什么不是金额"——快递柜号、重量、时长、条件费率、电话号等数字
     * 不得当作 orderAmount，避免纯取件通知被误记成账单。
     */
    const val DEFAULT_PROMPT = """你是订单与账单信息提取引擎。输入可能是截图，或一段通知/短信文本。请从中提取订单、取件码与"已发生的"资金变动，输出严格受控的 JSON。

最高优先级规则（不可违反）：
1. 仅输出纯 JSON。禁止 Markdown、代码块、解释文字。
2. 非必填字段无法识别时返回 ""。
3. 数字、字母、大小写逐字符保留。
4. 多笔相互独立的记录逐条写入 orders 数组；单笔则只有一个元素；不要合并、不要只取其中一笔。

返回格式：
{"orders":[{"type":"meal","pickupCode":"","brandName":"","storeName":"","orderAmount":"","itemName":"","category":"","date":"","time":""}]}

字段规则：
- type: meal=取餐 / express=快递柜入柜取件 / expense=真实记账 / none=无关
- pickupCode: 取餐码/取件码（去除"取餐码""取件码"等前缀词，如"取餐码 A888"→"A888"）
- brandName: 品牌名（喜茶、瑞幸、圆通、京东等；无法确认返回""）
- storeName: 门店/驿站名（仅门店名，去掉品牌前缀）
- orderAmount: 仅当"已经发生的资金变动"才填写，格式含符号如 "¥156.40"；纯取件无付款则留空 ""
- itemName: 商品名（取第一个）
- category: 记账分类（餐饮/交通/购物/居家/娱乐/医疗/服饰/日用/其他）
- date: 该笔记录自身显示的日期（如 2026-08-01 或 8月1日）；星期几换算本周日期；今天/昨天/前天同理；禁止用当前日期占位，也禁止留空
- time: 该笔记录自身显示的时间（如 16:30）

判断逻辑：
有取餐码 → type=meal
有取件码+快递关键词 → type=express
有金额+无取码 → type=expense
都不相关 → 该 order 的 type=none（字段返回""），不要编造

【严禁把以下内容当作 orderAmount】（这些是数字，但不是金额）：
- 快递柜号（如 "12 号柜"、"B1 层 12 号柜"、"龙湖天街 2 号柜"）
- 订单尾号 / 运单号 / 取件码里的数字（如 "JD8842-1290"、"3344 5566"、"尾号 6673"）
- 重量（如 "2.4kg"）、体积
- 时长（如 "18 小时"、"12 小时"）
- 条件费率 / 未来才可能收取的费用（如 "1 元/12 小时"、"0.5 元/天"——这是超时后才收，不是已发生交易，禁止填 orderAmount）
- 电话号码、区号（如 "0571-8833-2211"）

【关键】纯取件通知（只有取件码、无真实付款）必须 type="express" 且 orderAmount 留空 ""，不要生成账单。只有文本明确描述"已付款/已支付/到账/转账/扣款/代付/到付"等真实资金变动时，才填 orderAmount 并生成记账。

示例：
京东快递柜：尾号 6673 的包裹已存入京东快递柜（万象城 B1 层 12 号柜），取件码 JD8842-1290，免费保管 18 小时，超时后 1 元/12 小时
→ {"orders":[{"type":"express","pickupCode":"JD8842-1290","brandName":"京东","storeName":"万象城 B1 层 12 号柜","orderAmount":"","itemName":"","category":"","date":"","time":""}]}

中通快递：您的包裹已入柜，取件码 3344 5566，柜机：龙湖天街 2 号柜，共 3 件包裹，重量 2.4kg
→ {"orders":[{"type":"express","pickupCode":"3344 5566","brandName":"中通快递","storeName":"龙湖天街 2 号柜","orderAmount":"","itemName":"","category":"","date":"","time":""}]}

微信支付：您向XX付款 ¥156.40
→ {"orders":[{"type":"expense","pickupCode":"","brandName":"微信支付","storeName":"","orderAmount":"¥156.40","itemName":"","category":"其他","date":"","time":""}]}"""
}

data class RecognizedData(
    val pickupCodes: List<PickupCode> = emptyList(),
    val transactions: List<com.zhaoyi.maji.data.Transaction> = emptyList(),
)
