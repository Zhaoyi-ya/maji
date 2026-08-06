package com.zhaoyi.maji.bill

import android.content.Context
import android.net.Uri
import com.zhaoyi.maji.data.Transaction
import com.zhaoyi.maji.data.TransactionType
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * 账单导入：适配支付宝（CSV）与微信（XLSX）两种账单。
 * 解析策略：按表头关键词定位列、按「收/支」判定收支、
 * 关键词映射成码记固定分类。不引入 POI 等重依赖——XLSX 用内置 zip+XML 手动解析。
 */

enum class BillPlatform { ALIPAY, WECHAT, GENERIC }

data class ParsedTransaction(
    val date: Long,
    val type: TransactionType,
    val amount: Double,
    val category: String,
    val note: String?,
)

data class ParsedBill(
    val transactions: List<ParsedTransaction>,
    val skipped: Int,
)

object BillImporters {

    fun parse(context: Context, uri: Uri, platform: BillPlatform): ParsedBill {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ParsedBill(emptyList(), 0)
        return when (platform) {
            BillPlatform.WECHAT -> parseRows(readXlsxToRows(bytes))
            BillPlatform.ALIPAY -> parseRows(readCsvToRows(bytes))
            BillPlatform.GENERIC -> parseGenericRows(readCsvToRows(bytes))
        }
    }

    /** 直接解析一段 CSV 文本（通用格式），供加密备份解密后的内容复用通用CSV解析逻辑。 */
    fun parseCsvText(text: String): ParsedBill {
        return parseGenericRows(readCsvToRows(text.toByteArray(Charsets.UTF_8)))
    }

    // ---------------------------------------------------------------- CSV

    private fun readCsvToRows(bytes: ByteArray): List<List<String>> {
        var text = String(bytes, Charsets.UTF_8)
        // 支付宝旧版可能是 GBK，若 UTF-8 解码出现替换符则回退 GBK
        if (text.contains('\uFFFD')) text = String(bytes, Charset.forName("GBK"))
        if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)
        val lines = text.replace("\r\n", "\n").replace("\r", "\n")
            .split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val delim = detectDelimiter(lines)
        val stripTab = delim != '\t'
        return lines.map { line ->
            val processed = if (stripTab) line.replace("\t", "") else line
            splitDelimitedLine(processed, delim)
        }
    }

    /** 在前 50 行（引号外）统计分隔符出现次数，取最多者；都没有则回退逗号 */
    private fun detectDelimiter(lines: List<String>): Char {
        val counts = mutableMapOf<Char, Int>()
        val candidates = charArrayOf(',', '\t', ';', '|')
        val maxLines = minOf(lines.size, 50)
        for (i in 0 until maxLines) {
            val line = lines[i]
            var inQuotes = false
            for (ch in line) {
                if (ch == '"') { inQuotes = !inQuotes; continue }
                if (!inQuotes && ch in candidates) {
                    counts[ch] = counts.getOrDefault(ch, 0) + 1
                }
            }
        }
        var best = ','
        var bestCount = 0
        for ((k, v) in counts) if (v > bestCount) { best = k; bestCount = v }
        return if (bestCount > 0) best else ','
    }

    /** 引号感知的单字符分隔符拆分（支持双引号内分隔符与 "" 转义） */
    private fun splitDelimitedLine(line: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    buf.append('"'); i += 2; continue
                }
                inQuotes = !inQuotes; i++; continue
            }
            if (!inQuotes && ch == delimiter) {
                out.add(cleanField(buf.toString())); buf.clear(); i++; continue
            }
            buf.append(ch); i++
        }
        out.add(cleanField(buf.toString()))
        return out
    }

    private fun cleanField(raw: String): String {
        var s = raw.trim()
        if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) {
            s = s.substring(1, s.length - 1).replace("\"\"", "\"")
        }
        return s.trim()
    }

    // ---------------------------------------------------------------- XLSX

    private fun readXlsxToRows(bytes: ByteArray): List<List<String>> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries[entry.name] = zis.readBytes()
                entry = zis.nextEntry
            }
        }
        val shared = parseSharedStrings(entries["xl/sharedStrings.xml"])
        val sheetName = entries.keys.firstOrNull { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) } ?: return emptyList()
        return parseSheet(entries[sheetName]!!, shared)
    }

    private fun parseSharedStrings(bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        val parser = newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val list = mutableListOf<String>()
        val buf = StringBuilder()
        var inT = false
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "si" -> buf.clear()
                        "t" -> { inT = true; buf.clear() }
                    }
                }
                XmlPullParser.TEXT -> if (inT) buf.append(parser.text)
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "t" -> inT = false
                        "si" -> { list.add(buf.toString()); buf.clear() }
                    }
                }
            }
            event = parser.next()
        }
        return list
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val parser = newPullParser()
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableMapOf<Int, String>()
        var maxCol = -1
        var inC = false
        var cellRef = ""
        var cellType = ""
        var cellVal = StringBuilder()
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> { currentRow = mutableMapOf(); maxCol = -1 }
                        "c" -> {
                            cellRef = parser.getAttributeValue(null, "r") ?: ""
                            cellType = parser.getAttributeValue(null, "t") ?: ""
                            cellVal = StringBuilder()
                            inC = true
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inC) cellVal.append(parser.text)
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "c" -> {
                            val col = colIndex(cellRef)
                            val raw = cellVal.toString().trim()
                            val value = if (cellType == "s") {
                                raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            } else raw
                            if (col >= 0) {
                                currentRow[col] = value
                                if (col > maxCol) maxCol = col
                            }
                            inC = false
                        }
                        "row" -> {
                            val list = MutableList(maxCol + 1) { "" }
                            currentRow.forEach { (k, v) -> list[k] = v }
                            rows.add(list)
                        }
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    private fun colIndex(ref: String): Int {
        val letters = ref.takeWhile { it.isLetter() }
        if (letters.isEmpty()) return -1
        var n = 0
        for (c in letters) n = n * 26 + (c.uppercaseChar() - 'A' + 1)
        return n - 1
    }

    private fun newPullParser(): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newPullParser()
    }

    // ---------------------------------------------------------------- 通用行解析

    private fun parseRows(rows: List<List<String>>): ParsedBill {
        var headerIdx = -1
        for (i in 0 until minOf(30, rows.size)) {
            val r = rows[i]
            if (r.any { it.contains("交易时间") } && r.any { it.contains("收/支") }) {
                headerIdx = i
                break
            }
        }
        if (headerIdx < 0) return ParsedBill(emptyList(), 0)

        val header = rows[headerIdx]
        val dateCol = header.indexOfFirst { normalizeKey(it) == "date" }.takeIf { it >= 0 } ?: return ParsedBill(emptyList(), 0)
        val typeCol = header.indexOfFirst { normalizeKey(it) == "type" }.takeIf { it >= 0 } ?: return ParsedBill(emptyList(), 0)
        val amountCol = header.indexOfFirst { normalizeKey(it) == "amount" }.takeIf { it >= 0 } ?: return ParsedBill(emptyList(), 0)
        val categoryCol = header.indexOfFirst { normalizeKey(it) == "category" }.takeIf { it >= 0 } ?: -1
        val noteCols = header.mapIndexedNotNull { i, h -> if (normalizeKey(h) == "note") i else null }

        var inserted = 0
        var skipped = 0
        val out = mutableListOf<ParsedTransaction>()
        for (i in headerIdx + 1 until rows.size) {
            val row = rows[i]
            if (row.all { it.isBlank() }) continue

            val typeRaw = row.getOrNull(typeCol)?.trim() ?: ""
            val type = when {
                typeRaw.contains("收入") -> TransactionType.INCOME
                typeRaw.contains("支出") -> TransactionType.EXPENSE
                else -> null
            }
            if (type == null) { skipped++; continue }

            val amountRaw = (row.getOrNull(amountCol) ?: "").replace(",", "").replace(" ", "").trim()
            val amount = amountRaw.toDoubleOrNull()
            if (amount == null || amount <= 0) { skipped++; continue }

            val dateRaw = (row.getOrNull(dateCol) ?: "").trim()
            val date = parseDate(dateRaw)
            if (date == null) { skipped++; continue }

            val rawCat = if (categoryCol >= 0) (row.getOrNull(categoryCol)?.trim() ?: "") else ""
            val noteText = noteCols.mapNotNull {
                row.getOrNull(it)?.trim()?.takeIf { s -> s.isNotBlank() && s != "/" }
            }.joinToString(" · ")
            val category = mapCategory(type, rawCat, noteText)
            val note = noteText.takeIf { it.isNotBlank() }

            out.add(ParsedTransaction(date, type, amount, category, note))
            inserted++
        }
        return ParsedBill(out, skipped)
    }

    /**
     * 通用 CSV 解析：固定列顺序 [类型, 金额, 分类, 日期, 备注]（兼容首行表头）。
     * 由「码记通用账单导入」脚本生成，分类已预先映射，直接采用字符串。
     */
    private fun parseGenericRows(rows: List<List<String>>): ParsedBill {
        if (rows.isEmpty()) return ParsedBill(emptyList(), 0)
        var data = rows
        val first = rows.first().map { it.trim() }
        val hasHeader = first.getOrNull(0)?.contains("类型") == true
            || first.getOrNull(1)?.contains("金额") == true
            || first.getOrNull(2)?.contains("分类") == true
        if (hasHeader) data = rows.drop(1)

        var inserted = 0
        var skipped = 0
        val out = mutableListOf<ParsedTransaction>()
        for (row in data) {
            if (row.size < 4) { skipped++; continue }
            val typeRaw = row[0].trim()
            val type = when {
                typeRaw.contains("收入") -> TransactionType.INCOME
                typeRaw.contains("支出") -> TransactionType.EXPENSE
                else -> { skipped++; continue }
            }
            val amount = row[1].replace(",", "").replace(" ", "").trim().toDoubleOrNull()
            if (amount == null || amount <= 0) { skipped++; continue }
            val category = row.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() } ?: "其他"
            val date = parseDate(row.getOrNull(3)?.trim() ?: "")
            if (date == null) { skipped++; continue }
            val note = row.getOrNull(4)?.trim()?.takeIf { it.isNotBlank() && it != "/" }
            out.add(ParsedTransaction(date, type, amount, category, note))
            inserted++
        }
        return ParsedBill(out, skipped)
    }

    /** 微信日期列为 Excel 序列号（数字），其余平台为字符串；两者都兼容 */
    private fun parseDate(raw: String): Long? {
        if (raw.isEmpty()) return null
        // 数字序列号：范围大致 20000~80000（对应 1954~2064 年）
        val serial = raw.toDoubleOrNull()
        if (serial != null && serial > 20000 && serial < 80000) {
            val days = serial.toLong()
            val frac = ((serial - days) * 86400000.0).toLong()
            return (days - 25569L) * 86400000L + frac
        }
        // 字符串日期：尝试若干常见格式
        for (fmt in DATE_FORMATS) {
            try {
                val d = fmt.parse(raw)
                if (d != null) return d.time
            } catch (_: Exception) { }
        }
        return null
    }

    private val DATE_FORMATS = listOf(
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US),
        SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US),
        SimpleDateFormat("yyyy-MM-dd", Locale.US),
        SimpleDateFormat("yyyy/MM/dd", Locale.US),
    )

    /**
     * 将表头文本规范化为字段 key，顺序敏感（先匹配更具体的「分类/类型」再匹配「类型/收支」）。
     * 返回 null 表示忽略该列。
     */
    private fun normalizeKey(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val noSpace = s.replace(Regex("\\s+"), "")

        if (containsAny(s, listOf("日期", "时间", "交易时间", "账单时间", "创建时间"))) return "date"
        if (containsAny(s, listOf("金额", "金额(元)", "交易金额", "变动金额", "收支金额"))) return "amount"
        if (containsAny(s, listOf("二级分类", "子分类", "次分类"))) return "sub_category"
        if (containsAny(s, listOf("分类", "类别", "账目名称", "科目", "交易分类", "交易类型"))) return "category"
        if (containsAny(s, listOf("收/支", "收支", "方向", "类型"))) return "type"
        if (containsAny(s, listOf("备注", "说明", "标题", "摘要", "附言", "商品名称", "商品说明", "交易对方", "商家", "商品"))) return "note"
        if (containsAny(s, listOf("账目编号", "编号", "单号", "流水号", "交易号", "相关图片", "图片", "交易单号", "订单号"))) return null
        return null
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean =
        keywords.any { text.contains(it) }

    // ---------------------------------------------------------------- 分类映射

    private val EXPENSE_KEYWORDS = listOf(
        Pair(listOf("餐饮", "美食", "食品", "饭", "吃", "蜜雪", "瑞幸", "麦当劳", "肯德基", "超市", "便利店", "糖水", "螺蛳粉", "面条", "包子"), "餐饮"),
        Pair(listOf("交通", "地铁", "公交", "滴滴", "单车", "打车", "铁路", "机票", "火车", "加油", "出行", "青桔", "岭南通", "高德", "加油卡"), "交通"),
        Pair(listOf("购物", "淘宝", "京东", "商城", "服饰", "日用", "京邦达", "快递", "拼多多", "唯品会"), "购物"),
        Pair(listOf("居家", "物业", "房租", "水电", "家居", "宿舍", "电费", "电表", "燃气", "物业费的"), "居家"),
        Pair(listOf("医疗", "医院", "药店", "卫生", "健康", "诊所", "卫生院", "药房"), "医疗"),
        Pair(listOf("通讯", "话费", "手机", "宽带", "网费", "移动", "电信", "联通", "流量"), "通讯"),
        Pair(listOf("娱乐", "游戏", "视频", "音乐", "会员", "电影", "steam", "腾讯", "网易", "deepseek", "云", "订阅", "qq音乐", "b站", "爱奇艺"), "娱乐"),
        Pair(listOf("转账", "还款", "借还", "aa", "随礼"), "转账"),
    )

    private fun mapCategory(type: TransactionType, rawCat: String, note: String): String {
        val text = "$rawCat $note".lowercase()
        return if (type == TransactionType.INCOME) {
            when {
                text.contains("红包") -> "红包"
                text.contains("工资") || text.contains("薪酬") || text.contains("薪金") || text.contains("薪资") || text.contains("津贴") -> "工资"
                text.contains("理财") || text.contains("收益") || text.contains("利息") || text.contains("基金") || text.contains("股息") -> "理财收益"
                text.contains("转账") || text.contains("收款") || text.contains("退款") -> "转账收款"
                else -> "其他"
            }
        } else {
            for ((kws, cat) in EXPENSE_KEYWORDS) {
                if (kws.any { text.contains(it) }) return cat
            }
            "其他"
        }
    }

    // ---------------------------------------------------------------- 落库辅助

    /** 将解析结果与已有记录去重后转换为 Transaction 列表（类型+金额+时间+备注相同视为重复） */
    fun toTransactions(bill: ParsedBill, existing: List<Transaction>): List<Transaction> {
        val seen = existing.map { key(it) }.toSet()
        val result = mutableListOf<Transaction>()
        for (p in bill.transactions) {
            val t = Transaction(
                amount = p.amount,
                type = p.type,
                category = p.category,
                note = p.note,
                date = p.date,
            )
            if (seen.contains(key(t))) continue
            result.add(t)
        }
        return result
    }

    private fun key(t: Transaction): String {
        val amt = "%.2f".format(t.amount)
        val note = (t.note ?: "").trim()
        return "${t.type}:$amt:${t.date}:$note"
    }
}
