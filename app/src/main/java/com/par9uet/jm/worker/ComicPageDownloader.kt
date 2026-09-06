package com.par9uet.jm.worker

import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
import com.par9uet.jm.cache.cachePathLength
import com.par9uet.jm.cache.isDocumentCachePath
import com.par9uet.jm.cache.openCacheOutputStream
import com.par9uet.jm.data.models.ComicPicImageState
import com.par9uet.jm.data.models.ImageResultState
import com.par9uet.jm.utils.compressWebpCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** 页面资源在所有来源上均不存在（HTTP 404/410）：重试和换源都无意义，应立即失败。 */
class PermanentDownloadException(message: String) : Exception(message)

data class PageDownloadResult(
    val filePath: String,
    val sizeBytes: Long,
)

/**
 * 单个漫画页面的下载器。
 *
 * 对"Coil 取图 → 解码去扰 → 校验落盘"整体做多源、多次重试：
 * - 每页最多尝试 [maxAttempts] 次，每次失败间隔递增延迟；
 * - [imageState] 应配置 imageFetcher 做多源回退取字节（见 DownloadComicWorker）；
 * - 写盘前校验，重试前清理失败留下的半成品/损坏文件；
 * - [permanentFailureProbe] 非空时表示资源在所有来源上都不存在，立即抛出
 *   [PermanentDownloadException] 快速失败，不再消耗剩余尝试次数。
 */
class ComicPageDownloader(
    private val appContext: Context,
) {
    /**
     * 下载并校验单页，成功落盘后返回 [PageDownloadResult]。
     * 下载场景跳过阅读解码缓存双写（saveToDecodeCache = false）。
     */
    suspend fun downloadPage(
        pageIndex: Int,
        imageState: ComicPicImageState,
        filePath: String,
        maxAttempts: Int = 3,
        retryDelayMillis: Long = 1200L,
        permanentFailureProbe: () -> String? = { null },
    ): PageDownloadResult {
        var lastReason: String? = null
        repeat(maxAttempts) { attempt ->
            if (attempt > 0) {
                deleteInvalidCacheFile(filePath)
                delay(retryDelayMillis * (attempt + 1))
            }
            imageState.decode(appContext, saveToDecodeCache = false)
            when (val result = imageState.imageResultState) {
                is ImageResultState.Success -> {
                    try {
                        val bitmap = result.decodeImageBitmap.asAndroidBitmap()
                        val sizeBytes = withContext(Dispatchers.IO) {
                            writeValidatedWebp(filePath, bitmap)
                        }
                        return PageDownloadResult(filePath, sizeBytes)
                    } catch (e: Exception) {
                        lastReason = "第 ${pageIndex + 1} 页写盘校验失败：${e.message}"
                    }
                }

                is ImageResultState.Failure -> {
                    lastReason = result.reason
                }

                ImageResultState.Loading -> {
                    lastReason = "第 ${pageIndex + 1} 页仍在加载中"
                }
            }
            permanentFailureProbe()?.let { throw PermanentDownloadException(it) }
        }
        throw IllegalStateException("第 ${pageIndex + 1} 页下载失败：$lastReason")
    }

    /** 校验性写入，返回落盘字节数；内容不完整时抛异常并保留现场供删除重试。 */
    private fun writeValidatedWebp(filePath: String, bitmap: android.graphics.Bitmap): Long {
        if (isDocumentCachePath(filePath)) {
            // SAF 目标：先写临时文件并校验，成功后再拷贝到目标文档，避免残留半成品
            val tmp = File.createTempFile("page_", ".webp")
            try {
                tmp.outputStream().use { out ->
                    bitmap.compressWebpCompat(50, out)
                }
                check(tmp.exists() && tmp.length() >= MIN_VALID_FILE_SIZE) { "写入内容不完整" }
                openCacheOutputStream(appContext, filePath).use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                }
                return tmp.length()
            } finally {
                tmp.delete()
            }
        } else {
            val target = File(filePath)
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                bitmap.compressWebpCompat(50, out)
            }
            if (!target.isFile || target.length() < MIN_VALID_FILE_SIZE) {
                throw IllegalStateException("写入内容不完整")
            }
            return target.length()
        }
    }

    private fun deleteInvalidCacheFile(filePath: String) {
        runCatching {
            if (isDocumentCachePath(filePath)) {
                com.par9uet.jm.cache.deleteCachePath(appContext, filePath)
            } else {
                File(filePath).delete()
            }
        }
    }

    companion object {
        /** 图片文件的最小可信字节数，低于该值视为损坏。 */
        const val MIN_VALID_FILE_SIZE = 512L

        /** 判断本地缓存文件是否为完整可用的图片文件。 */
        fun isCompleteCacheFile(context: Context, filePath: String): Boolean {
            if (filePath.isBlank()) return false
            if (isDocumentCachePath(filePath)) {
                return cachePathLength(context, filePath) >= MIN_VALID_FILE_SIZE
            }
            val file = File(filePath)
            return file.isFile && file.length() >= MIN_VALID_FILE_SIZE
        }
    }
}
