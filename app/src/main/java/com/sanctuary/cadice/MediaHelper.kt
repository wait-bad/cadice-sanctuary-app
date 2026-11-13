package com.sanctuary.cadice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class CompressedImageResult(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val fileName: String,
    val localCacheFile: File
)

/**
 * 🖼️ 端侧多媒体压缩与隐私脱敏流水线（借鉴 Element Android ImageCompressor + ExifTagRemover）
 * 1. inJustDecodeBounds 降采样防止 4000 万像素原图 OOM；
 * 2. 读取 ExifInterface 旋转角，用 Matrix 真正扶正竖拍照片；
 * 3. 重新压缩编码为纯净 JPEG，彻底剥离原图 GPS 经纬度、相机型号等敏感隐私标签。
 */
object MediaHelper {

    fun compressAndStripExif(
        context: Context,
        uri: Uri,
        maxDimension: Int = 1600,
        quality: Int = 82
    ): CompressedImageResult? {
        return try {
            val resolver = context.contentResolver

            // 1. 先读取 EXIF 方向角（必须在二次压缩前从原流获取）
            val orientation = resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL

            // 2. 读取原图宽高并计算降采样倍率
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var inSampleSize = 1
            while ((bounds.outWidth / inSampleSize) > maxDimension * 2 ||
                (bounds.outHeight / inSampleSize) > maxDimension * 2
            ) {
                inSampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                this.inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampled = resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOpts)
            } ?: return null

            // 3. 应用 EXIF 旋转与等比缩放
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.preScale(-1f, 1f)
                }
            }

            val upright = if (!matrix.isIdentity) {
                Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true).also {
                    if (it !== sampled) sampled.recycle()
                }
            } else {
                sampled
            }

            val maxSide = maxOf(upright.width, upright.height)
            val finalBmp = if (maxSide > maxDimension) {
                val scale = maxDimension.toFloat() / maxSide.toFloat()
                val targetW = (upright.width * scale).toInt().coerceAtLeast(1)
                val targetH = (upright.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(upright, targetW, targetH, true).also {
                    if (it !== upright) upright.recycle()
                }
            } else {
                upright
            }

            // 4. 输出为纯净 JPEG（不包含原图任何 GPS/Exif 隐私字段）
            val baos = ByteArrayOutputStream()
            finalBmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val jpegBytes = baos.toByteArray()
            val outW = finalBmp.width
            val outH = finalBmp.height
            finalBmp.recycle()

            val originalName = queryDisplayName(context, uri)
                .substringBeforeLast('.')
                .ifBlank { "photo_${System.currentTimeMillis()}" } + ".jpg"

            val cacheDir = File(context.cacheDir, "chat_images").apply { if (!exists()) mkdirs() }
            val cacheFile = File(cacheDir, "local_${System.currentTimeMillis()}.jpg")
            FileOutputStream(cacheFile).use { it.write(jpegBytes) }

            CompressedImageResult(
                bytes = jpegBytes,
                width = outW,
                height = outH,
                fileName = originalName,
                localCacheFile = cacheFile
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "" else ""
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
