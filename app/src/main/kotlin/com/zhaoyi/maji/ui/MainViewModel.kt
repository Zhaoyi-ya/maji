package com.zhaoyi.maji.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhaoyi.maji.data.AppDatabase
import com.zhaoyi.maji.data.CodeKind
import com.zhaoyi.maji.data.PickupCode
import com.zhaoyi.maji.data.Transaction
import com.zhaoyi.maji.data.TransactionType
import com.zhaoyi.maji.island.IslandController
import com.zhaoyi.maji.widget.MiniWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.nio.charset.StandardCharsets

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val tDao = AppDatabase.getInstance(application).transactionDao()
    private val pDao = AppDatabase.getInstance(application).pickupCodeDao()

    // ---- 记账 ----
    val transactions: StateFlow<List<Transaction>> = tDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addTransaction(t: Transaction) {
        viewModelScope.launch(Dispatchers.IO) { tDao.insert(t); refreshWidget() }
    }

    fun deleteTransaction(t: Transaction) {
        viewModelScope.launch(Dispatchers.IO) { tDao.delete(t); refreshWidget() }
    }

    fun updateTransaction(t: Transaction) {
        viewModelScope.launch(Dispatchers.IO) { tDao.update(t); refreshWidget() }
    }

    /** 记账变更后通知桌面小组件刷新（跨进程显式广播落到 :widgetProvider 进程） */
    private fun refreshWidget() {
        val app = getApplication<Application>()
        app.sendBroadcast(
            Intent(app, MiniWidgetProvider::class.java).setAction(MiniWidgetProvider.ACTION_REFRESH)
        )
    }

    // ---- 取件码 ----
    val pickupCodes: StateFlow<List<PickupCode>> = pDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 新建一条码，默认立即上岛 */
    fun addPickupCode(code: PickupCode, pushToIsland: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val pushed = pushToIsland && IslandController.show(getApplication(), code)
            pDao.insert(code.copy(onIsland = pushed))
        }
    }

    /**
     * 编辑已有取件码并保存：保存后若未标记已取件，立即重新尝试上岛（通知被手动划掉后
     * 也能一键恢复钉住）。已取件的跳过。
     */
    fun updatePickupCode(code: PickupCode) {
        viewModelScope.launch(Dispatchers.IO) {
            pDao.update(code)
            if (!code.isDone) {
                val ok = IslandController.show(getApplication(), code)
                pDao.setOnIsland(code.id, ok)
            }
        }
    }

    /**
     * 上岛 / 下岛切换（按通知"是否真的还在岛上"判定，而非仅看 DB 标记）：
     *  - 确实在岛上 → 撤掉通知并把 DB 标记清零（下岛）。
     *  - 不在岛上（含"DB 记已上岛但被手动划掉"）→ 先 dismiss 清掉可能残留的旧通知，
     *    再 show 重建；系统对"被划掉的同 id 通知"会短期屏蔽重发，先清后发才能恢复。
     * 已取件的跳过。
     */
    fun toggleIsland(code: PickupCode) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            if (code.isDone) return@launch
            if (IslandController.isOnIslandActive(app, code)) {
                IslandController.dismiss(app, code)
                pDao.setOnIsland(code.id, false)
            } else {
                IslandController.dismiss(app, code)
                val ok = IslandController.show(app, code)
                pDao.setOnIsland(code.id, ok)
            }
        }
    }

    /** 标记已取 / 撤销已取（只改状态字段，保留全部内容） */
    fun toggleDone(code: PickupCode) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            if (code.isDone) {
                pDao.setDoneState(code.id, null, code.onIsland)
            } else {
                if (code.onIsland) IslandController.dismiss(app, code)
                pDao.setDoneState(code.id, System.currentTimeMillis(), false)
            }
        }
    }

    fun deletePickupCode(code: PickupCode) {
        viewModelScope.launch(Dispatchers.IO) {
            if (code.onIsland) IslandController.dismiss(getApplication(), code)
            pDao.delete(code)
        }
    }

    /** 清理已完成超过 7 天的记录 */
    fun purgeOldDoneCodes() {
        viewModelScope.launch(Dispatchers.IO) {
            pDao.purgeDoneBefore(System.currentTimeMillis() - 7L * 24 * 3600 * 1000)
        }
    }

    // ---- 多选删除 ----
    //
    // 注意：这里绝不能构造「只填了 id 的空壳对象」再交给 Room 的 @Update/@Delete。
    // @Update 是按主键整行覆盖，空壳会把 code / merchant / item / price / createdAt
    // 全部清成空值，直接毁掉这条记录，并让超级岛拿不到内容。
    // 一律先按 id 查出真实行，再走定向 UPDATE 或按 id 删除。

    fun deleteTransactions(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { tDao.deleteByIds(ids) }
    }

    fun deletePickupCodes(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            // 删除前先把还挂在岛上的通知撤掉，否则岛会残留且再也点不动
            pDao.getByIds(ids).forEach { code ->
                if (code.onIsland) IslandController.dismiss(app, code)
            }
            pDao.deleteByIds(ids)
        }
    }

    // ---- 备份 / 恢复 ----
    fun exportBackup(context: Context) {
        val src = context.getDatabasePath("maji.db")
        val dst = File(context.getExternalFilesDir(null), "maji_backup.db")
        if (src.exists()) src.copyTo(dst, overwrite = true)
    }

    fun importBackup(context: Context, uri: Uri) {
        val input = context.contentResolver.openInputStream(uri) ?: return
        val dst = context.getDatabasePath("maji.db")
        AppDatabase.getInstance(context).close()
        val out = dst.outputStream()
        input.copyTo(out)
        input.close()
        out.close()
    }

    // ---- 账单导入（支付宝 / 微信） ----

    /** 导入结果（插入数 + 跳过数），供 UI 弹 Toast 后清空 */
    private val _importResult = MutableStateFlow<BillImportResult?>(null)
    val importResult: StateFlow<BillImportResult?> = _importResult

    fun importBill(context: Context, uri: Uri, platform: com.zhaoyi.maji.bill.BillPlatform) {
        viewModelScope.launch(Dispatchers.IO) {
            val bill = com.zhaoyi.maji.bill.BillImporters.parse(context, uri, platform)
            val existing = tDao.getAllList()
            val toInsert = com.zhaoyi.maji.bill.BillImporters.toTransactions(bill, existing)
            toInsert.forEach { tDao.insert(it) }
            val duplicates = bill.transactions.size - toInsert.size
            _importResult.value = BillImportResult(toInsert.size, bill.skipped + duplicates)
        }
    }

    // ---- 导出 / 加密备份 ----

    /** 导出全部账单为通用 CSV 到用户选择的 URI（SAF）。 */
    fun exportCsv(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = tDao.getAllList()
            val csv = com.zhaoyi.maji.bill.BillBackup.buildCsv(all)
            context.contentResolver.openOutputStream(uri)?.use { os ->
                // UTF-8 BOM：让中文 Windows 版 Excel 自动以 UTF-8 打开，避免 GBK 乱码。
                os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                os.write(csv.toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    /** 导出全部账单为密码加密 .zyk 到用户选择的 URI（SAF）。 */
    fun exportEncryptedZip(context: Context, uri: Uri, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = tDao.getAllList()
            val data = com.zhaoyi.maji.bill.BillBackup.encryptZyk(all, password)
            context.contentResolver.openOutputStream(uri)?.use { os -> os.write(data) }
        }
    }

    /** 从加密 .zyk 解密并导入账单（复用通用CSV解析 + 去重）。密码错误会反馈 error。 */
    fun importEncryptedZip(context: Context, uri: Uri, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
            val csv = try {
                com.zhaoyi.maji.bill.BillBackup.decryptZyk(bytes, password)
            } catch (e: Exception) {
                _importResult.value = BillImportResult(0, 0, "密码错误或文件损坏，无法解密")
                return@launch
            }
            val bill = com.zhaoyi.maji.bill.BillImporters.parseCsvText(csv)
            val existing = tDao.getAllList()
            val toInsert = com.zhaoyi.maji.bill.BillImporters.toTransactions(bill, existing)
            toInsert.forEach { tDao.insert(it) }
            val duplicates = bill.transactions.size - toInsert.size
            _importResult.value = BillImportResult(toInsert.size, bill.skipped + duplicates)
        }
    }

    fun clearImportResult() { _importResult.value = null }

    data class BillImportResult(val inserted: Int, val skipped: Int, val error: String? = null)
}
