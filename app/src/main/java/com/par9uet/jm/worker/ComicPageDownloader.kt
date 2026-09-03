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

/**
 * 单个漫画页面的下载器。
 *
 * 与原实现（每页只请求一次，失败即中断整章）不同，这里对
 * "Coil 取图 → 解码去扰 → 校验落盘"整体做多源、多次重试：
 * - 每页最多尝试 [maxAttempts] 次，每次失败间隔递增延迟；
 * - 网络取图失败时自动经 [ComicPicImageState] 内置的 imageFetcher 走仓库级
 *   多源回退（内嵌 API 原始 URL + 换域名候选 URL），而不是直接放弃；
 * - 写盘前校验，重试前清理失败留下的半成品/损坏文件。
 */
class ComicPageDownloader(
    private val appContext: Context,
) {
    /**
     * 下载并校验单页，成功落盘后返回目标文件路径。
     * [imageState] 应已配置好 imageFetcher 做多源回退取字节。
     */
    suspend fun downloadPage(
        pageIndex: Int,
        imageState: ComicPicImageState,
        filePath: String,
        maxAttempts: Int = 3,
        retryDelayMillis: Long = 1200L,
    ): String {
        var lastReason: String? = null
        repeat(maxAttempts) { attempt ->
            if (attempt > 0) {
                deleteInvalidCacheFile(filePath)
                delay(retryDelayMillis * (attempt + 1))
            }
            imageState.decode(appContext)
            when (val result = imageState.imageResultState) {
                is ImageResultState.Success -> {
                    try {
                        val bitmap = result.decodeImageBitmap.asAndroidBitmap()
                        withContext(Dispatchers.IO) {
                            writeValidatedWebp(filePath, bitmap)
                        }
                        return filePath
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
        }
        throw IllegalStateException("第 ${pageIndex + 1} 页下载失败：$lastReason")
    }

    private fun writeValidatedWebp(filePath: String, bitmap: android.graphics.Bitmap) {
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
        private const val MIN_VALID_FILE_SIZE = 512L

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
