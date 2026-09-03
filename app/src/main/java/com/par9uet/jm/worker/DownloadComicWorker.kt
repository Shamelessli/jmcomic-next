package com.par9uet.jm.worker

import android.content.Context
import androidx.core.graphics.drawable.toBitmap
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import coil.ImageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.par9uet.jm.cache.cachePathLength
import com.par9uet.jm.cache.isCacheMigrationRunning
import com.par9uet.jm.cache.getComicChapterDownloadPath
import com.par9uet.jm.cache.getComicCoverDownloadPath
import com.par9uet.jm.cache.getOrCreateCacheFile
import com.par9uet.jm.cache.openCacheOutputStream
import com.par9uet.jm.cache.writeDocumentComicCacheConfig
import com.par9uet.jm.data.models.ComicPicImageState
import com.par9uet.jm.network.ComicCoverUrlResolver
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.UpdateComicCover
import com.par9uet.jm.database.model.UpdateComicProgress
import com.par9uet.jm.database.model.UpdateComicStatus
import com.par9uet.jm.database.model.UpdateComicZipPath
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.retrofit.model.ComicPicListResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.DownloadToastAggregator
import com.par9uet.jm.store.DownloadProgressMessageStore
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.RemoteSettingManager
import com.par9uet.jm.utils.COMIC_CACHE_NOTIFICATION_ID_BASE
import com.par9uet.jm.utils.DownloadSpeedTracker
import com.par9uet.jm.utils.cancelProgressNotification
import com.par9uet.jm.utils.compressWebpCompat
import com.par9uet.jm.utils.logError
import com.par9uet.jm.utils.showProgressNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val DOWNLOAD_PAGE_TIMEOUT_MS = 180_000L
private const val DOWNLOAD_MAX_ATTEMPTS = 6

/** 每页解码落盘的尝试次数；超过后由 WorkManager 整章退避重试兜底。 */
private const val PAGE_ATTEMPTS = 3

class DownloadComicWorker(
    private val appContext: Context,
    params: WorkerParameters,
    private val downloadComicDao: DownloadComicDao,
    private val remoteSettingManager: RemoteSettingManager,
    private val localSettingManager: LocalSettingManager,
    private val comicRepository: ComicRepository,
    private val downloadToastAggregator: DownloadToastAggregator,
    private val imageLoader: ImageLoader,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (isCacheMigrationRunning(appContext)) return Result.retry()
        val comicId = inputData.getInt("comicId", -1)
        val batchId = inputData.getString("batchId").orEmpty()
        val batchTotal = inputData.getInt("batchTotal", 1)
        val downloadCover = inputData.getBoolean("downloadCover", true)
        val downloadPages = inputData.getBoolean("downloadPages", true)
        val writeConfig = inputData.getBoolean("writeConfig", true)
        val missingPageIndices = inputData.getIntArray("missingPageIndices")
            ?.toSet()
            .orEmpty()
        if (comicId == -1) {
            return Result.failure()
        }

        val coverOwnerId = downloadComicDao.getById(comicId)?.let {
            it.groupId.takeIf { g -> g != 0 } ?: comicId
        } ?: comicId

        return try {
            val downloadTask = downloadComicDao.getById(comicId) ?: return Result.failure()
            downloadComicDao.updateStatus(UpdateComicStatus(comicId, "downloading"))
            updateProgressMessage(downloadTask, "准备缓存")
            DownloadSpeedTracker.startTracking(coverOwnerId)
            showComicCacheNotification(
                downloadTask,
                resolveGroupProgress(downloadTask, downloadTask.progress)
            )

            if (downloadCover) {
                updateProgressMessage(downloadTask, "下载封面")
                val coverPath = downloadCover(downloadTask, coverOwnerId)
                if (coverPath.isNotBlank()) {
                    downloadComicDao.updateCover(UpdateComicCover(comicId, coverPath))
                }
            }

            if (downloadPages) {
                downloadPicList(
                    downloadTask,
                    localSettingManager.localSettingState.value.shunt,
                    missingPageIndices,
                )
            }
            updateProgressMessage(downloadTask, "整理缓存文件")
            showComicCacheNotification(downloadTask, updateChapterProgress(downloadTask, 1f))

            val chapterDirPath = getComicChapterDownloadPath(appContext, downloadTask)
            downloadComicDao.updateZipPath(UpdateComicZipPath(comicId, chapterDirPath))
            downloadComicDao.updateStatus(UpdateComicStatus(comicId, "complete"))
            if (writeConfig) {
                updateProgressMessage(downloadTask, "生成 JSON")
                writeCacheConfig(comicId)
            }
            DownloadSpeedTracker.stopTracking(coverOwnerId)
            DownloadProgressMessageStore.clear(coverOwnerId)
            cancelComicCacheNotificationIfIdle(downloadTask)
            downloadToastAggregator.report(batchId, batchTotal, comicId, success = true)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // worker 被系统取消时应立即终止，不能按失败进入退避重试
            throw e
        } catch (e: Exception) {
            updateProgressMessage(
                downloadTask = downloadComicDao.getById(comicId),
                message = if (runAttemptCount < DOWNLOAD_MAX_ATTEMPTS - 1) {
                    "下载失败，准备重试"
                } else {
                    "下载失败，可点击重试"
                },
            )
            logError(
                "DownloadComicWorker",
                "章节 $comicId 下载失败（第 ${runAttemptCount + 1} 次）：${e.message ?: e::class.java.simpleName}"
            )
            if (runAttemptCount < DOWNLOAD_MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                downloadComicDao.updateStatus(UpdateComicStatus(comicId, "error"))
                DownloadSpeedTracker.stopTracking(coverOwnerId)
                downloadComicDao.getById(comicId)?.let {
                    cancelComicCacheNotificationIfIdle(it)
                }
                downloadToastAggregator.report(batchId, batchTotal, comicId, success = false)
                Result.failure()
            }
        }
    }

    private suspend fun downloadCover(downloadTask: DownloadComic, coverOwnerId: Int): String {
        return withContext(Dispatchers.IO) {
            val apiImage = runCatching {
                    when (val result = comicRepository.getComicDetail(coverOwnerId)) {
                        is NetWorkResult.Success -> result.data.image
                        else -> null
                    }
                }.getOrNull().orEmpty()
            val configuredImageHost = remoteSettingManager.remoteSettingState.value.imgHost
            val coverUrls = ComicCoverUrlResolver.resolve(
                comicId = coverOwnerId,
                apiImage = apiImage,
                configuredImageHost = configuredImageHost,
            )
            val file = getComicCoverDownloadPath(appContext, downloadTask)
            var lastError: String? = null
            // 封面较小且多为单次尝试：多 URL 候选失败后再整组重试一次
            repeat(2) { attempt ->
                for (coverUrl in coverUrls) {
                    try {
                        val request = ImageRequest.Builder(appContext)
                            .data(coverUrl)
                            .allowHardware(false)
                            .build()
                        when (val result = imageLoader.execute(request)) {
                            is ErrorResult -> {
                                lastError = result.throwable.message
                            }

                            is SuccessResult -> {
                                val bitmap = result.drawable.toBitmap()
                                withContext(Dispatchers.IO) {
                                    openCacheOutputStream(appContext, file).use { out ->
                                        bitmap.compressWebpCompat(50, out)
                                    }
                                }
                                if (ComicPageDownloader.isCompleteCacheFile(appContext, file)) {
                                    return@withContext file
                                }
                                lastError = "封面写入内容不完整"
                            }
                        }
                    } catch (e: Exception) {
                        lastError = e.message
                    }
                }
                if (attempt == 0) kotlinx.coroutines.delay(1000L)
            }
            logError("DownloadComicWorker", "章节 $coverOwnerId 封面下载失败：$lastError")
            ""
        }
    }

    private suspend fun downloadPicList(
        downloadTask: DownloadComic,
        shunt: String,
        missingPageIndices: Set<Int> = emptySet(),
    ): List<String> {
        return withContext(Dispatchers.IO) {
            val comicId = downloadTask.id
            updateProgressMessage(downloadTask, "获取图片列表")
            when (val data = comicRepository.getComicPicList(comicId, shunt)) {
                is NetWorkResult.Error -> throw IllegalStateException(data.message)
                is NetWorkResult.Success<ComicPicListResponse> -> {
                    if (data.data.list.isEmpty()) {
                        throw IllegalStateException("图片列表为空")
                    }

                    val dir = getComicChapterDownloadPath(appContext, downloadTask)
                    val imageHost = remoteSettingManager.remoteSettingState.value.imgHost
                    var maxProgress = downloadComicDao.getById(comicId)?.progress ?: 0f
                    val pageDownloader = ComicPageDownloader(appContext)

                    data.data.list.mapIndexed { index, url ->
                        // Repair jobs carry the exact missing page indexes.
                        // Existing pages and pages not requested by the repair
                        // are never decoded or overwritten.
                        if (missingPageIndices.isNotEmpty() && index !in missingPageIndices) {
                            return@mapIndexed ""
                        }
                        val file = getOrCreateCacheFile(appContext, dir, "$index.webp", "image/webp")
                        val nextProgress = (index + 1).toFloat() / data.data.list.size
                        val progressMessage = {
                            updateProgressMessage(
                                downloadTask,
                                if (ComicPageDownloader.isCompleteCacheFile(appContext, file)) {
                                    "检查第 ${index + 1}/${data.data.list.size} 张图片"
                                } else {
                                    "下载第 ${index + 1}/${data.data.list.size} 张图片"
                                }
                            )
                        }
                        // 已有完整图片直接跳过，不重复下载/解码
                        if (ComicPageDownloader.isCompleteCacheFile(appContext, file)) {
                            progressMessage()
                            val progress = updateChapterProgressIfAdvanced(
                                downloadTask = downloadTask,
                                currentMaxProgress = maxProgress,
                                nextProgress = nextProgress
                            )
                            maxProgress = progress.chapterProgress
                            showComicCacheNotification(downloadTask, progress.groupProgress)
                            return@mapIndexed file
                        }

                        progressMessage()
                        // 取图源：网络列表 URL + 换域名候选；内置 API 源由仓库 imageFetcher 兜底
                        val fallbackSources = ComicCoverUrlResolver.imageHostCandidates(url, imageHost)
                        val imageState = ComicPicImageState(
                            index = index,
                            comicId = comicId,
                            originSrc = url,
                            __scrambleId = data.data.__scrambleId,
                            __speed = data.data.__speed,
                            picImageLoader = imageLoader,
                            imageFetcher = {
                                comicRepository.fetchImageBytesForSources(
                                    comicId = comicId,
                                    imageIndex = index,
                                    sources = fallbackSources,
                                )
                            }
                        )
                        val resultFile = try {
                            withTimeout(DOWNLOAD_PAGE_TIMEOUT_MS) {
                                pageDownloader.downloadPage(
                                    pageIndex = index,
                                    imageState = imageState,
                                    filePath = file,
                                    maxAttempts = PAGE_ATTEMPTS,
                                )
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            throw IllegalStateException("第 ${index + 1} 页下载或解码超时", e)
                        }
                        DownloadSpeedTracker.addBytes(
                            downloadTask.groupId.takeIf { it != 0 } ?: downloadTask.id,
                            cachePathLength(appContext, resultFile)
                        )
                        val progress = updateChapterProgressIfAdvanced(
                            downloadTask = downloadTask,
                            currentMaxProgress = maxProgress,
                            nextProgress = nextProgress
                        )
                        maxProgress = progress.chapterProgress
                        showComicCacheNotification(downloadTask, progress.groupProgress)
                        resultFile
                    }
                }
            }
        }
    }

    private suspend fun writeCacheConfig(comicId: Int) {
        val current = downloadComicDao.getById(comicId) ?: return
        val groupId = current.groupId.takeIf { it != 0 } ?: current.id
        val chapters = downloadComicDao.getByGroupId(groupId)
        withContext(Dispatchers.IO) {
            writeDocumentComicCacheConfig(appContext, current, chapters)
        }
    }

    private fun updateProgressMessage(downloadTask: DownloadComic?, message: String) {
        val groupId = downloadTask?.groupId?.takeIf { it != 0 } ?: downloadTask?.id ?: return
        DownloadProgressMessageStore.update(groupId, message)
    }

    private suspend fun updateChapterProgress(downloadTask: DownloadComic, progress: Float): Float {
        val chapterProgress = progress.coerceIn(0f, 1f)
        downloadComicDao.updateProgress(UpdateComicProgress(downloadTask.id, chapterProgress))
        return resolveGroupProgress(downloadTask, chapterProgress)
    }

    private suspend fun updateChapterProgressIfAdvanced(
        downloadTask: DownloadComic,
        currentMaxProgress: Float,
        nextProgress: Float
    ): DownloadProgress {
        val chapterProgress = maxOf(currentMaxProgress, nextProgress.coerceIn(0f, 1f))
        if (chapterProgress > currentMaxProgress) {
            downloadComicDao.updateProgress(UpdateComicProgress(downloadTask.id, chapterProgress))
        }
        return DownloadProgress(
            chapterProgress = chapterProgress,
            groupProgress = resolveGroupProgress(downloadTask, chapterProgress)
        )
    }

    private suspend fun resolveGroupProgress(downloadTask: DownloadComic, currentProgress: Float): Float {
        val groupId = downloadTask.groupId.takeIf { it != 0 } ?: downloadTask.id
        val chapters = downloadComicDao.getByGroupId(groupId)
        if (chapters.isEmpty()) return currentProgress
        return chapters.map { chapter ->
            when {
                chapter.id == downloadTask.id -> currentProgress
                chapter.status == "complete" -> 1f
                else -> chapter.progress.coerceIn(0f, 1f)
            }
        }.average().toFloat().coerceIn(0f, 1f)
    }

    private suspend fun cancelComicCacheNotificationIfIdle(downloadTask: DownloadComic) {
        val groupId = downloadTask.groupId.takeIf { it != 0 } ?: downloadTask.id
        val chapters = downloadComicDao.getByGroupId(groupId)
        val hasActiveTask = chapters.any { it.status == "pending" || it.status == "downloading" }
        if (!hasActiveTask) {
            cancelProgressNotification(appContext, COMIC_CACHE_NOTIFICATION_ID_BASE + groupId)
        }
    }

    private fun showComicCacheNotification(downloadTask: DownloadComic, progress: Float) {
        val groupId = downloadTask.groupId.takeIf { it != 0 } ?: downloadTask.id
        val setting = localSettingManager.localSettingState.value
        if (!setting.showComicCacheNotification) {
            cancelProgressNotification(appContext, COMIC_CACHE_NOTIFICATION_ID_BASE + groupId)
            return
        }
        val comicName = downloadTask.groupName.ifBlank { downloadTask.name }
        val title = if (setting.showComicCacheNotificationName && comicName.isNotBlank()) {
            "正在缓存$comicName"
        } else {
            "正在缓存漫画"
        }
        val progressPercent = (progress.coerceIn(0f, 1f) * 100).toInt()
        showProgressNotification(
            context = appContext,
            notificationId = COMIC_CACHE_NOTIFICATION_ID_BASE + groupId,
            title = title,
            text = "$progressPercent%",
            progressPercent = progressPercent
        )
    }

    private data class DownloadProgress(
        val chapterProgress: Float,
        val groupProgress: Float
    )
}
