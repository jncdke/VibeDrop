package com.vibedrop.mobile.nativeapp.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.vibedrop.mobile.nativeapp.network.DesktopConnectionController
import java.io.ByteArrayOutputStream
import java.util.UUID

private const val FILE_CHUNK_BYTES = 192 * 1024
private const val MAX_IMAGE_CLIPBOARD_BYTES = 10 * 1024 * 1024

data class ContentTransferResult(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sourceUri: String?,
    val transferId: String?,
    val savedPath: String? = null,
    val status: String = "success",
    val error: String? = null
)

enum class ContentTransferStage {
    Sending,
    Saving,
    Finished
}

data class ContentTransferProgress(
    val stage: ContentTransferStage,
    val sentBytes: Long,
    val totalBytes: Long,
    val progressPercent: Int
)

suspend fun sendImageUriToMacClipboard(
    context: Context,
    uri: Uri,
    controller: DesktopConnectionController
): ContentTransferResult {
    val meta = context.queryContentMeta(uri, fallbackName = "image")
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_IMAGE_CLIPBOARD_BYTES) {
                throw IllegalStateException("图片超过 10MB，暂不适合写入 Mac 剪贴板")
            }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: throw IllegalStateException("无法读取图片")

    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    controller.sendImageClipboard(
        fileName = meta.fileName,
        mimeType = meta.mimeType.ifBlank { "image/*" },
        imageBase64 = base64
    )
    return meta.copy(
        sizeBytes = bytes.size.toLong(),
        sourceUri = uri.toString()
    )
}

suspend fun sendUriToDesktopInbox(
    context: Context,
    uri: Uri,
    controller: DesktopConnectionController,
    onProgress: (ContentTransferProgress) -> Unit = {}
): ContentTransferResult {
    val meta = queryContentTransferMeta(context, uri)
    if (meta.sizeBytes <= 0L) {
        throw IllegalStateException("无法识别文件大小")
    }

    val transferId = "native-${UUID.randomUUID()}"
    controller.trackIncomingFileAck(transferId)
    if (!controller.sendIncomingFileStart(transferId, meta.fileName, meta.mimeType, meta.sizeBytes)) {
        controller.cancelIncomingFileAck(transferId)
        throw IllegalStateException("连接不可用")
    }

    try {
        onProgress(
            ContentTransferProgress(
                stage = ContentTransferStage.Sending,
                sentBytes = 0,
                totalBytes = meta.sizeBytes,
                progressPercent = 0
            )
        )
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(FILE_CHUNK_BYTES)
            var sentBytes = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                val base64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                if (!controller.sendIncomingFileChunk(transferId, base64)) {
                    throw IllegalStateException("发送分片失败")
                }
                sentBytes += read.toLong()
                val progress = ((sentBytes.toDouble() / meta.sizeBytes.toDouble()) * 100.0)
                    .toInt()
                    .coerceIn(1, 99)
                onProgress(
                    ContentTransferProgress(
                        stage = ContentTransferStage.Sending,
                        sentBytes = sentBytes.coerceAtMost(meta.sizeBytes),
                        totalBytes = meta.sizeBytes,
                        progressPercent = progress
                    )
                )
                controller.waitForOutboundQueueBelow()
            }
        } ?: throw IllegalStateException("无法读取文件")

        if (!controller.sendIncomingFileComplete(transferId)) {
            throw IllegalStateException("发送完成消息失败")
        }
        onProgress(
            ContentTransferProgress(
                stage = ContentTransferStage.Saving,
                sentBytes = meta.sizeBytes,
                totalBytes = meta.sizeBytes,
                progressPercent = 100
            )
        )
        val ack = controller.awaitIncomingFileAck(transferId)
        onProgress(
            ContentTransferProgress(
                stage = ContentTransferStage.Finished,
                sentBytes = meta.sizeBytes,
                totalBytes = meta.sizeBytes,
                progressPercent = 100
            )
        )
        return meta.copy(
            sourceUri = uri.toString(),
            transferId = transferId,
            savedPath = ack.savedPath
        )
    } catch (error: Exception) {
        controller.cancelIncomingFileAck(transferId)
        throw error
    }
}

fun queryContentTransferMeta(
    context: Context,
    uri: Uri,
    fallbackName: String = "file.bin"
): ContentTransferResult = context.queryContentMeta(uri, fallbackName)

fun failedContentTransferResult(
    context: Context,
    uri: Uri,
    error: Throwable,
    fallbackName: String = "file.bin"
): ContentTransferResult {
    return runCatching { queryContentTransferMeta(context, uri, fallbackName) }
        .getOrElse {
            ContentTransferResult(
                fileName = fallbackName,
                mimeType = "application/octet-stream",
                sizeBytes = -1L,
                sourceUri = uri.toString(),
                transferId = null
            )
        }
        .copy(
            status = "failed",
            error = error.message ?: error.javaClass.simpleName
        )
}

private fun Context.queryContentMeta(
    uri: Uri,
    fallbackName: String
): ContentTransferResult {
    var fileName = fallbackName
    var sizeBytes = -1L
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            if (nameIndex >= 0) {
                fileName = cursor.getString(nameIndex) ?: fallbackName
            }
            if (sizeIndex >= 0) {
                sizeBytes = cursor.getLong(sizeIndex)
            }
        }
    }
    val mimeType = contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
    return ContentTransferResult(
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        sourceUri = uri.toString(),
        transferId = null
    )
}
