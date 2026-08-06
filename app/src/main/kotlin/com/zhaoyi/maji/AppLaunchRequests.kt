package com.zhaoyi.maji

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 跨界面传递「通知点击要执行的动作」。
 *
 * 取件码 / 取餐码通知点击后，期望进入软件的取件码页面（tab）。
 * 用 extraBufferCapacity 兜住冷启动时「先发后订阅」的时序：MainActivity.onCreate
 * 在 setContent 之前就发送，缓冲区保留，等 MainScreen 的收集器挂上后立刻收到；
 * 已运行时 onNewIntent 发送则直接交给活跃收集器。
 */
object AppLaunchRequests {
    sealed interface Request
    data object OpenPickup : Request
    /** 点击「记一笔」通知后，打开 App 对应的编辑账单面板（id 为 Transaction.id） */
    data class OpenEditTxn(val txnId: String) : Request
    /** 桌面小组件「查看账单」→ 打开 App 并切到报表页（tab 1） */
    data object OpenReport : Request
    /** 桌面小组件「+」→ 打开 App 并直接弹出「记一笔」新建面板 */
    data object OpenNewTxn : Request

    // replay=1：每个新订阅者（如深链冷启动时 MainScreen 在 setContent 之后才挂上收集器）
    // 都能拿到最近一次事件，避免「先发后订阅」导致事件丢失、点了通知只开 App 不弹面板。
    private val _requests = MutableSharedFlow<Request>(replay = 1, extraBufferCapacity = 4)
    fun send(req: Request) { _requests.tryEmit(req) }
    val requests: Flow<Request> = _requests.asSharedFlow()
}
