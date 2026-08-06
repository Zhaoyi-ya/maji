package com.zhaoyi.maji.island

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

/**
 * 手动 Binder 接口（代替 AIDL），MiclawCredentialService 的 Stub。
 */
abstract class MiclawStub : Binder() {

    abstract fun getSessionJson(forceRefresh: Boolean): String
    abstract fun destroy()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            TRANSACTION_getSessionJson -> {
                data.enforceInterface(DESCRIPTOR)
                val forceRefresh = data.readInt() != 0
                val result = getSessionJson(forceRefresh)
                reply?.writeNoException()
                reply?.writeString(result)
                true
            }
            TRANSACTION_destroy -> {
                data.enforceInterface(DESCRIPTOR)
                destroy()
                reply?.writeNoException()
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    companion object {
        private const val DESCRIPTOR = "com.zhaoyi.maji.island.IMiclawCredentialService"
        private const val TRANSACTION_getSessionJson = 1
        private const val TRANSACTION_destroy = 2

        fun asInterface(binder: IBinder?): IMiclawProxy? = binder?.let {
            val iin = it.queryLocalInterface(DESCRIPTOR)
            if (iin is IMiclawProxy) iin else IMiclawProxy(it)
        }
    }
}

class IMiclawProxy(private val remote: IBinder) : Binder() {
    fun getSessionJson(forceRefresh: Boolean): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(if (forceRefresh) 1 else 0)
            remote.transact(1, data, reply, 0)
            reply.readException()
            reply.readString() ?: ""
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    fun destroy() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            remote.transact(2, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    companion object {
        private const val DESCRIPTOR = "com.zhaoyi.maji.island.IMiclawCredentialService"
    }
}
