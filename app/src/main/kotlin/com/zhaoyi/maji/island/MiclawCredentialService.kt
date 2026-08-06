package com.zhaoyi.maji.island

import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.util.Log

/**
 * Shizuku UserService：以 root 身份读取系统小米账号的 serviceToken。
 */
class MiclawCredentialService(private val context: android.content.Context) : MiclawStub() {

    override fun getSessionJson(forceRefresh: Boolean): String {
        check(Process.myUid() == 0) { "读取系统小米账号需要 root 模式的 Shizuku" }
        val account = readAccountMaterial() ?: error("系统小米账号中没有可用登录信息")
        return runCatching { MiclawPassportClient.refresh(account).toJson() }
            .onSuccess { Log.i("Miclaw", "Passport 刷新成功") }
            .getOrElse {
                if (forceRefresh || account.serviceToken.isBlank()) throw it
                else account.toJson()
            }
    }

    override fun destroy() = Process.killProcess(Process.myPid())

    private fun readAccountMaterial(): MiclawSession? {
        val dbFile = java.io.File("/data/system_ce/0/accounts_ce.db")
        if (!dbFile.isFile) return null
        return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT a.name, a.password, t.authtoken, COALESCE((SELECT value FROM extras WHERE accounts_id=a._id AND key='encrypted_user_id'), '') FROM accounts a JOIN authtokens t ON t.accounts_id=a._id WHERE a.type=? AND t.type=? LIMIT 1",
                arrayOf("com.xiaomi", "osbotapi"),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val pass = cursor.getString(1).orEmpty()
                val auth = cursor.getString(2).orEmpty()
                MiclawSession(
                    userId = cursor.getString(0).orEmpty(),
                    passToken = pass.substringBefore(','),
                    cUserId = cursor.getString(3).orEmpty(),
                    serviceToken = auth.substringBeforeLast(','),
                )
            }
        }
    }
}
