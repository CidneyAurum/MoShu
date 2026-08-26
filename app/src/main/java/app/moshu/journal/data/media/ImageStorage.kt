package app.moshu.journal.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import app.moshu.journal.ai.AiClient
import app.moshu.journal.data.db.AttachmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

object ImageStorage {
    const val MAX_ATTACHMENTS = 9
    private const val MAX_STORED_EDGE = 2560
    private const val MAX_AI_EDGE = 1600

    suspend fun importImage(context: Context, uri: Uri, entryId: Long, sortOrder: Int): AttachmentEntity =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片" }

            var sample = 1
            while (max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_STORED_EDGE * 2) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("无法解码这张图片")

            val orientation = resolver.openInputStream(uri)?.use {
                runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                    .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val oriented = applyOrientation(decoded, orientation)
            val resized = resize(oriented, MAX_STORED_EDGE)
            val rgb = Bitmap.createBitmap(resized.width, resized.height, Bitmap.Config.ARGB_8888).also { target ->
                Canvas(target).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(resized, 0f, 0f, null)
                }
            }

            val dir = File(context.filesDir, "attachments").apply { mkdirs() }
            val uid = UUID.randomUUID().toString()
            val file = File(dir, "$uid.jpg")
            val savedWidth = rgb.width
            val savedHeight = rgb.height
            try {
                file.outputStream().buffered().use { out ->
                    check(rgb.compress(Bitmap.CompressFormat.JPEG, 88, out)) { "图片保存失败" }
                }
            } catch (error: Throwable) {
                file.delete()
                throw error
            } finally {
                listOf(decoded, oriented, resized, rgb).distinct().forEach { if (!it.isRecycled) it.recycle() }
            }

            AttachmentEntity(
                uid = uid,
                entryId = entryId,
                localPath = file.absolutePath,
                mimeType = "image/jpeg",
                width = savedWidth,
                height = savedHeight,
                sortOrder = sortOrder,
            )
        }

    suspend fun toAiInput(attachment: AttachmentEntity): AiClient.ImageInput? = withContext(Dispatchers.IO) {
        runCatching {
            val source = BitmapFactory.decodeFile(attachment.localPath) ?: return@runCatching null
            val resized = resize(source, MAX_AI_EDGE)
            val bytes = ByteArrayOutputStream().use { out ->
                resized.compress(Bitmap.CompressFormat.JPEG, 82, out)
                out.toByteArray()
            }
            if (source !== resized) source.recycle()
            resized.recycle()
            AiClient.ImageInput("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }.getOrNull()
    }

    fun delete(attachment: AttachmentEntity) {
        runCatching { File(attachment.localPath).delete() }
    }

    private fun resize(source: Bitmap, maxEdge: Int): Bitmap {
        val edge = max(source.width, source.height)
        if (edge <= maxEdge) return source
        val ratio = maxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).roundToInt().coerceAtLeast(1),
            (source.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                else -> return source
            }
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
