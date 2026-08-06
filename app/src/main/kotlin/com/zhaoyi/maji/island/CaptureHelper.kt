package com.zhaoyi.maji.island

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream

/**
 * 截图工具：Shizuku 和 Root 两种方式。
 */
object CaptureHelper {

    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    fun requestShizuku(requestCode: Int = 7001): Boolean = try {
        if (!Shizuku.pingBinder()) false
        else { Shizuku.requestPermission(requestCode); true }
    } catch (_: Exception) { false }

    fun hasRoot(): Boolean = try {
        ProcessBuilder("su", "-c", "id").start().waitFor() == 0
    } catch (_: Exception) { false }

    /** Shizuku 反射调用 screencap -p */
    fun captureShizuku(): Bitmap? {
        if (!shizukuReady()) return null
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("screencap", "-p"), null, null)
                as rikka.shizuku.ShizukuRemoteProcess
            val bitmap = BitmapFactory.decodeStream(process.inputStream)
            process.waitFor()
            bitmap
        } catch (_: Exception) { null }
    }

    /** Root 调用 screencap -p */
    fun captureRoot(): Bitmap? {
        if (!hasRoot()) return null
        return try {
            val process = ProcessBuilder("su", "-c", "screencap -p").start()
            BitmapFactory.decodeStream(process.inputStream).also {
                process.waitFor()
            }
        } catch (_: Exception) { null }
    }

    /** 可用截图方式：null=需 MediaProjection */
    fun preferredMode(): String? = when {
        shizukuReady() -> CAPTURE_SHIZUKU
        hasRoot() -> CAPTURE_ROOT
        else -> null
    }

    fun Bitmap.toUploadJpeg(maxSide: Int = 1600, quality: Int = 82): ByteArray {
        val scale = (maxSide.toFloat() / maxOf(width, height)).coerceAtMost(1f)
        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
        } else this
        return try {
            ByteArrayOutputStream().use {
                resized.compress(Bitmap.CompressFormat.JPEG, quality, it)
                it.toByteArray()
            }
        } finally {
            // 只回收自己创建的缩放副本；绝不回收调用方传进来的原图
            // （旧实现会把 this 一并 recycle，调用方之后再用就是野对象）
            if (resized !== this) resized.recycle()
        }
    }

    const val CAPTURE_SHIZUKU = "shizuku"
    const val CAPTURE_ROOT = "root"
}
