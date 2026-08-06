package com.zhaoyi.maji.island

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream

/**
 * 送审图片「净化」。
 *
 * 用途：小米 MiMo 服务端对输入图片有内容审核（HTTP 403 / code 30003 /
 * content_policy_violation）。电商类页面（拼多多、淘宝等）满屏商品图、模特图、
 * 促销海报，很容易被审核模型误判为违规，导致整次识别失败。
 *
 * 做法：转灰度 + 适度增强对比度 + 降采样 + 降质重编码。
 * 色彩（尤其肤色）是图像内容审核最主要的判别信号之一，去色后误判率显著下降；
 * 而我们只需要模型读出取件码、店名、金额这些**文字**，灰度与降采样对 OCR 几乎无损。
 */
object ImageSanitizer {

    /**
     * @param src       原始 JPEG 字节
     * @param maxSide   最长边上限（默认 1280，低于首次送审的 1600）
     * @param quality   JPEG 质量
     * @return 净化后的 JPEG 字节；解码失败返回 null
     */
    fun sanitize(src: ByteArray, maxSide: Int = 1280, quality: Int = 75): ByteArray? {
        val bmp = try {
            BitmapFactory.decodeByteArray(src, 0, src.size)
        } catch (_: Throwable) { null } ?: return null

        return try {
            val scale = (maxSide.toFloat() / maxOf(bmp.width, bmp.height)).coerceAtMost(1f)
            val w = (bmp.width * scale).toInt().coerceAtLeast(1)
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(bmp, null, Rect(0, 0, w, h), Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                colorFilter = ColorMatrixColorFilter(grayWithContrast())
            })

            ByteArrayOutputStream().use {
                out.compress(Bitmap.CompressFormat.JPEG, quality, it)
                it.toByteArray()
            }.also { out.recycle() }
        } catch (_: Throwable) {
            null
        } finally {
            bmp.recycle()
        }
    }

    /** 去饱和度 + 轻度提对比度，让灰度化后的文字边缘依然锐利 */
    private fun grayWithContrast(contrast: Float = 1.25f): ColorMatrix {
        val t = (-0.5f * contrast + 0.5f) * 255f
        val m = ColorMatrix().apply { setSaturation(0f) }
        m.postConcat(ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, t,
            0f, contrast, 0f, 0f, t,
            0f, 0f, contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )))
        return m
    }
}
