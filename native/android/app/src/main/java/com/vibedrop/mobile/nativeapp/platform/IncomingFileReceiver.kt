package com.vibedrop.mobile.nativeapp.platform

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class IncomingFileResult(
    val transferId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val savedPath: String,
    val saveTarget: String,
    val isArchive: Boolean,
    val historySessionId: String?,
    val historyItemIndex: Int,
    val historyItemCount: Int
)

class IncomingFileReceiver(
    private val context: Context
) {
    private val transfers = LinkedHashMap<String, IncomingTransfer>()

    fun begin(payload: JSONObject) {
        val transferId = payload.optString("transfer_id").ifBlank {
            throw IllegalArgumentException("缺少传输标识")
        }
        val fileName = sanitizeFileName(payload.optString("file_name").ifBlank { "file.bin" })
        val mimeType = payload.optString("mime_type").ifBlank { "application/octet-stream" }
        val sizeBytes = payload.optLong("size_bytes", 0L)
        val isArchive = payload.optBoolean("is_archive", false)
        val partFile = File(incomingDir(), "${sanitizeTransferId(transferId)}.part")
        partFile.parentFile?.mkdirs()
        partFile.writeBytes(ByteArray(0))
        transfers[transferId] = IncomingTransfer(
            transferId = transferId,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            partFile = partFile,
            isArchive = isArchive,
            historySessionId = payload.optString("history_session_id").takeIf { it.isNotBlank() },
            historyItemIndex = payload.optInt("history_item_index", 0).coerceAtLeast(0),
            historyItemCount = payload.optInt("history_item_count", 1).coerceAtLeast(1)
        )
    }

    fun append(payload: JSONObject) {
        val transferId = payload.optString("transfer_id").ifBlank {
            throw IllegalArgumentException("缺少传输标识")
        }
        val transfer = transfers[transferId] ?: throw IllegalStateException("接收状态不存在")
        val chunk = Base64.decode(payload.optString("chunk_base64"), Base64.DEFAULT)
        FileOutputStream(transfer.partFile, true).use { output ->
            output.write(chunk)
        }
        transfer.receivedBytes += chunk.size
    }

    fun complete(payload: JSONObject): IncomingFileResult {
        val transferId = payload.optString("transfer_id").ifBlank {
            throw IllegalArgumentException("缺少传输标识")
        }
        val transfer = transfers.remove(transferId) ?: throw IllegalStateException("接收状态不存在")
        val actualSize = transfer.partFile.length()
        if (transfer.sizeBytes > 0L && actualSize != transfer.sizeBytes) {
            transfer.partFile.delete()
            throw IllegalStateException("文件大小校验失败：$actualSize/${transfer.sizeBytes}")
        }
        val saveTarget = resolveSaveTarget(transfer.mimeType)
        val savedPath = savePartFile(transfer, saveTarget)
        transfer.partFile.delete()
        return IncomingFileResult(
            transferId = transfer.transferId,
            fileName = transfer.fileName,
            mimeType = transfer.mimeType,
            sizeBytes = actualSize,
            savedPath = savedPath,
            saveTarget = saveTarget,
            isArchive = transfer.isArchive,
            historySessionId = transfer.historySessionId,
            historyItemIndex = transfer.historyItemIndex,
            historyItemCount = transfer.historyItemCount
        )
    }

    fun cancel(transferId: String?) {
        if (transferId.isNullOrBlank()) return
        transfers.remove(transferId)?.partFile?.delete()
    }

    private fun savePartFile(
        transfer: IncomingTransfer,
        saveTarget: String
    ): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(transfer, saveTarget)
        } else {
            saveViaPublicDirectory(transfer, saveTarget)
        }
    }

    private fun saveViaMediaStore(
        transfer: IncomingTransfer,
        saveTarget: String
    ): String {
        val resolver = context.contentResolver
        val collection = when (saveTarget) {
            SAVE_TARGET_GALLERY_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            SAVE_TARGET_GALLERY_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val relativePath = when (saveTarget) {
            SAVE_TARGET_GALLERY_IMAGE -> "${Environment.DIRECTORY_PICTURES}/VibeDrop"
            SAVE_TARGET_GALLERY_VIDEO -> "${Environment.DIRECTORY_MOVIES}/VibeDrop"
            else -> "${Environment.DIRECTORY_DOWNLOADS}/VibeDrop"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, transfer.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, transfer.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.SIZE, transfer.partFile.length())
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("无法创建系统媒体记录")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                transfer.partFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法打开系统保存流")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri.toString()
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun saveViaPublicDirectory(
        transfer: IncomingTransfer,
        saveTarget: String
    ): String {
        val baseDir = when (saveTarget) {
            SAVE_TARGET_GALLERY_IMAGE -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            SAVE_TARGET_GALLERY_VIDEO -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }
        val dir = File(baseDir, "VibeDrop")
        dir.mkdirs()
        val destination = uniqueFile(dir, transfer.fileName)
        transfer.partFile.copyTo(destination, overwrite = false)
        return destination.absolutePath
    }

    private fun incomingDir(): File {
        return File(context.cacheDir, "incoming-transfer")
    }

    private fun resolveSaveTarget(mimeType: String): String = when {
        mimeType.startsWith("image/") -> SAVE_TARGET_GALLERY_IMAGE
        mimeType.startsWith("video/") -> SAVE_TARGET_GALLERY_VIDEO
        else -> SAVE_TARGET_DOWNLOAD
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val dotIndex = fileName.lastIndexOf('.')
        val base = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val ext = if (dotIndex > 0) fileName.substring(dotIndex) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base-$index$ext")
            index += 1
        }
        return candidate
    }

    private fun sanitizeTransferId(value: String): String {
        return value.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
        }.joinToString("").ifBlank { "transfer" }
    }

    private fun sanitizeFileName(value: String): String {
        return value.substringAfterLast('/').substringAfterLast('\\')
            .map { ch -> if (ch == ':' || ch.isISOControl()) '_' else ch }
            .joinToString("")
            .ifBlank { "file.bin" }
    }

    private data class IncomingTransfer(
        val transferId: String,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val partFile: File,
        val isArchive: Boolean,
        val historySessionId: String?,
        val historyItemIndex: Int,
        val historyItemCount: Int,
        var receivedBytes: Long = 0L
    )

    companion object {
        const val SAVE_TARGET_DOWNLOAD = "download"
        const val SAVE_TARGET_GALLERY_IMAGE = "gallery-image"
        const val SAVE_TARGET_GALLERY_VIDEO = "gallery-video"
    }
}
