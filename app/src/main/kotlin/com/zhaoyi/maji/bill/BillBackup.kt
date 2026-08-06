package com.zhaoyi.maji.bill

import com.zhaoyi.maji.data.Transaction
import com.zhaoyi.maji.data.TransactionType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 账单备份：通用 CSV 生成 + 密码加密私有格式 `.zyk`（AES-256-GCM）。
 *
 * 不引入任何第三方依赖——加密用 Android 内置 `javax.crypto`。
 * 不再使用 zip 包裹：`.zyk` 是纯二进制加密容器，没有任何标准压缩包壳子，
 * 任意解压工具都打不开，只有本 APP 用正确密码才能解出 CSV。
 *
 * 文件结构（全部为二进制）：
 *   [MAGIC 4B "ZYK1"][version 1B][saltLen 1B][salt][ivLen 1B][iv][ciphertext + GCM tag]
 */
object BillBackup {

    private const val MAGIC = "ZYK1"
    private const val VERSION: Byte = 1
    private const val PBKDF2_ITER = 65536
    private const val KEY_BITS = 256
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val SALT_LEN = 16

    private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * 生成与 [BillImporters.parseGenericRows] 完全对齐的 CSV：
     * 表头 `类型,金额,分类,日期,备注`，类型用「收入/支出」，日期 `yyyy-MM-dd HH:mm:ss`。
     * 这样导出的文件可直接用「导入账单 → 通用CSV」导回。
     */
    fun buildCsv(transactions: List<Transaction>): String {
        val sb = StringBuilder()
        sb.append("类型,金额,分类,日期,备注\n")
        for (t in transactions) {
            val type = if (t.type == TransactionType.INCOME) "收入" else "支出"
            val amount = "%.2f".format(t.amount)
            val category = t.category.replace(",", "，").replace("\n", " ")
            val date = DATE_FMT.format(t.date)
            val note = (t.note ?: "").replace(",", "，").replace("\n", " ")
            sb.append("$type,$amount,$category,$date,$note\n")
        }
        return sb.toString()
    }

    /** 加密全部账单为 `.zyk` 二进制字节数组；密码错误只能在解密侧发现（GCM 校验失败）。 */
    fun encryptZyk(transactions: List<Transaction>, password: String): ByteArray {
        val plain = buildCsv(transactions).toByteArray(StandardCharsets.UTF_8)
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(GCM_IV_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain)

        val out = ByteArrayOutputStream()
        out.write(MAGIC.toByteArray(StandardCharsets.US_ASCII))
        out.write(VERSION.toInt())
        out.write(SALT_LEN)
        out.write(salt)
        out.write(GCM_IV_LEN)
        out.write(iv)
        out.write(ct)
        return out.toByteArray()
    }

    /**
     * 解密 `.zyk` 字节数组，返回 CSV 文本。
     * 密码错误 / 文件损坏 / 不是本格式会抛异常（GCM 校验失败或 magic 不匹配），调用方据此提示用户。
     */
    fun decryptZyk(bytes: ByteArray, password: String): String {
        val `in` = ByteArrayInputStream(bytes)
        val magic = ByteArray(MAGIC.length).also { if (`in`.read(it) != MAGIC.length) throw IllegalStateException("文件损坏") }
        if (!magic.contentEquals(MAGIC.toByteArray(StandardCharsets.US_ASCII))) {
            throw IllegalStateException("不是码记加密备份(.zyk)")
        }
        val version = `in`.read()
        if (version != VERSION.toInt()) throw IllegalStateException("不支持的备份版本")
        val saltLen = `in`.read()
        val salt = ByteArray(saltLen).also { if (`in`.read(it) != saltLen) throw IllegalStateException("文件损坏") }
        val ivLen = `in`.read()
        val iv = ByteArray(ivLen).also { if (`in`.read(it) != ivLen) throw IllegalStateException("文件损坏") }
        val ct = `in`.readBytes()

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plain = cipher.doFinal(ct)
        return String(plain, StandardCharsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITER, KEY_BITS)
        val secret = factory.generateSecret(spec)
        return SecretKeySpec(secret.encoded, "AES")
    }
}
