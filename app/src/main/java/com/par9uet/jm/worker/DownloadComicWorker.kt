package com.par9uet.jm.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import coil.ImageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.par9uet.jm.MainActivity
import com.par9uet.jm.R
import com.par9uet.jm.cache.getComicChapterDownloadPath
import com.par9uet.jm.cache.getComicCoverDownloadPath
import com.par9uet.jm.cache.getOrCreateCacheFile
import com.par9uet.jm.cache.isCacheMigrationRunning
import com.par9uet.jm.cache.isDocumentCachePath
import com.par9uet.jm.cache.openCacheOutputStream
import com.par9uet.jm.cache.writeDocumentComicCacheConfig
import com.par9uet.jm.data.models.ComicPicImageState
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.UpdateComicCover
import com.par9uet.jm.database.model.UpdateComicProgress
import com.par9uet.jm.database.model.UpdateComicStatus
import com.par9uet.jm.database.model.UpdateComicZipPath
import com.par9uet.jm.network.ComicCoverUrlResolver
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.repository.ImageFetchResult
import com.par9uet.jm.retrofit.model.ComicPicListResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.DownloadConcurrencyGate
import com.par9uet.jm.store.DownloadPageGate
import com.par9uet.jm.store.DownloadProgressMessageStore
import com.par9uet.jm.store.DownloadToastAggregator
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.RemoteSettingManager
import com.par9uet.jm.utils.COMIC_CACHE_NOTIFICATION_ID_BASE
import com.par9uet.jm.utils.DOWNLOAD_NOTIFICATION_CHANNEL_ID
import com.par9uet.jm.utils.DownloadSpeedTracker
import com.par9uet.jm.utils.cancelProgressNotification
import com.par9uet.jm.utils.compressWebpCompat
import com.par9uet.jm.utils.logError
import com.par9uet.jm.utils.showProgressNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 单页超时收紧到 90s：配合页内 3 次尝试与整章退避重试，挂起连接不再长时间占住章节。 */
private const val DOWNLOAD_PAGE_TIMEOUT_MS = 90_000L
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
    private val downloadConcurrencyGate: DownloadConcurrencyGate,
    private val downloadPageGate: DownloadPageGate,
) : CoroutineWorker(appContext, params) {

    private val notificationMutex = Mutex()

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

        // 通过进程级门控把"真正并行下载的章节数"限制到用户设置值；
        // 获取/释放对正常、失败、取消三种路径都成立。
        return downloadConcurrencyGate.withPermit {
            try {
                val downloadTask = downloadComicDao.getById(comicId) ?: return@withPermit Result.failure()
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
            } catch (e: PermanentDownloadException) {
                // 页面在所有来源上都不存在：重试无意义，直接置错误状态，
                // 避免消耗 6 次整章退避去撞同一堵墙
                updateProgressMessage(
                    downloadTask = downloadComicDao.getById(comicId),
                    message = e.message ?: "部分页面资源不存在",
                )
                logError("DownloadComicWorker", "章节 $comicId 快速失败：${e.message}")
                downloadComicDao.updateStatus(UpdateComicStatus(comicId, "error"))
                DownloadSpeedTracker.stopTracking(coverOwnerId)
                downloadComicDao.getById(comicId)?.let { cancelComicCacheNotificationIfIdle(it) }
                downloadToastAggregator.report(batchId, batchTotal, comicId, success = false)
                Result.failure()
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

    /**
     * 下载章节内所有页面。页与页之间按 [downloadPageGate] 的全局许可并行执行
     * （网络等待互相重叠），已完整落盘的页面按每章一次的磁盘列表直接跳过。
     */
    private suspend fun downloadPicList(
        downloadTask: DownloadComic,
        shunt: String,
        missingPageIndices: Set<Int> = emptySet(),
    ) {
        withContext(Dispatchers.IO) {
            val comicId = downloadTask.id
            updateProgressMessage(downloadTask, "获取图片列表")
            when (val data = comicRepository.getComicPicList(comicId, shunt)) {
                is NetWorkResult.Error -> throw IllegalStateException(data.message)
                is NetWorkResult.Success<ComicPicListResponse> -> {
                    if (data.data.list.isEmpty()) {
                        throw IllegalStateException("图片列表为空")
                    }

                    val urls = data.data.list
                    val dir = getComicChapterDownloadPath(appContext, downloadTask)
                    val imageHost = remoteSettingManager.remoteSettingState.value.imgHost
                    val pageDownloader = ComicPageDownloader(appContext)
                    val total = urls.size
                    val groupChapters = downloadComicDao.getByGroupId(
                        downloadTask.groupId.takeIf { it != 0 } ?: downloadTask.id
                    )
                    // 每章一次列出磁盘上已有的完整页面，替代旧的逐页存在性查询
                    // （自定义 SAF 路径下每次查询都是一次跨进程 IPC）
                    val existingPages = listExistingPageIndexes(dir)
                    // Repair jobs carry the exact missing page indexes. Existing
                    // pages and pages not requested by the repair are never
                    // decoded or overwritten.
                    val requestedPages = if (missingPageIndices.isEmpty()) {
                        (0 until total).toList()
                    } else {
                        (0 until total).filter { it in missingPageIndices }
                    }
                    val done = AtomicInteger(existingPages.size.coerceIn(0, total))
                    val permanentFailure = AtomicReference<String?>(null)
                    val firstFailure = AtomicReference<Throwable?>(null)

                    if (existingPages.containsAll(requestedPages)) {
                        // 断点续跑：所需页面均已完整落盘，无需网络请求
                        reportPageProgress(downloadTask, done.get(), total, groupChapters)
                    } else {
                        coroutineScope {
                            for (index in requestedPages) {
                                if (permanentFailure.get() != null) break
                                if (index in existingPages) continue
                                launch {
                                    try {
                                        val written = downloadPageGate.withPermit {
                                            // 轮到自己执行时若已知资源不存在，直接放弃，
                                            // 不再为排队中的页面发起无意义请求
                                            permanentFailure.get()?.let {
                                                throw PermanentDownloadException(it)
                                            }
                                            val file = getOrCreateCacheFile(
                                                appContext, dir, "$index.webp", "image/webp",
                                            )
                                            // 取图源：网络列表 URL + 换域名候选；内置 API 源由仓库 imageFetcher 兜底
                                            val fallbackSources = ComicCoverUrlResolver.imageHostCandidates(
                                                urls[index], imageHost,
                                            )
                                            val imageState = ComicPicImageState(
                                                index = index,
                                                comicId = comicId,
                                                originSrc = urls[index],
                                                __scrambleId = data.data.__scrambleId,
                                                __speed = data.data.__speed,
                                                picImageLoader = imageLoader,
                                                imageFetcher = {
                                                    when (val result = comicRepository.fetchImageBytesForSources(
                                                        comicId, index, fallbackSources,
                                                    )) {
                                                        is ImageFetchResult.Success -> result.bytes
                                                        is ImageFetchResult.NotFound -> {
                                                            permanentFailure.compareAndSet(
                                                                null,
                                                                "第 ${index + 1} 页在所有图片来源上均不存在（HTTP 404）",
                                                            )
                                                            null
                                                        }

                                                        is ImageFetchResult.Failed -> null
                                                    }
                                                }
                                            )
                                            withTimeout(DOWNLOAD_PAGE_TIMEOUT_MS) {
                                                pageDownloader.downloadPage(
                                                    pageIndex = index,
                                                    imageState = imageState,
                                                    filePath = file,
                                                    maxAttempts = PAGE_ATTEMPTS,
                                                    permanentFailureProbe = { permanentFailure.get() },
                                                )
                                            }
                                        }
                                        DownloadSpeedTracker.addBytes(
                                            downloadTask.groupId.takeIf { it != 0 } ?: downloadTask.id,
                                            written.sizeBytes,
                                        )
                                        reportPageProgress(
                                            downloadTask, done.incrementAndGet(), total, groupChapters,
                                        )
                                    } catch (e: PermanentDownloadException) {
                                        permanentFailure.compareAndSet(null, e.message ?: "页面资源不存在")
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        firstFailure.compareAndSet(null, e)
                                    }
                                }
                            }
                        }
                        permanentFailure.get()?.let { throw PermanentDownloadException(it) }
                        firstFailure.get()?.let { throw it }
                    }
                }
            }
        }
    }

    /** 每章一次列出磁盘上已有的完整页面索引（带大小校验）。 */
    private fun listExistingPageIndexes(dirPath: String): Set<Int> {
        return if (isDocumentCachePath(dirPath)) {
            runCatching {
                val uri = Uri.parse(dirPath)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    uri, DocumentsContract.getDocumentId(uri),
                )
                val indexes = mutableSetOf<Int>()
                appContext.contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    null, null, null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0) ?: continue
                        val size = if (cursor.isNull(1)) 0L else cursor.getLong(1)
                        name.substringBeforeLast('.').toIntOrNull()
                            ?.takeIf { size >= ComicPageDownloader.MIN_VALID_FILE_SIZE }
                            ?.let(indexes::add)
                    }
                }
                indexes
            }.getOrDefault(emptySet())
        } else {
            File(dirPath).listFiles()
                ?.mapNotNull { file ->
                    file.name.substringBeforeLast('.').toIntOrNull()
                        ?.takeIf { file.length() >= ComicPageDownloader.MIN_VALID_FILE_SIZE }
                }
                ?.toSet()
                .orEmpty()
        }
    }

    private suspend fun reportPageProgress(
        downloadTask: DownloadComic,
        doneCount: Int,
        total: Int,
        groupChapters: List<DownloadComic>,
    ) {
        if (total <= 0) return
        val chapterProgress = (doneCount.toFloat() / total).coerceIn(0f, 1f)
        downloadComicDao.updateProgress(UpdateComicProgress(downloadTask.id, chapterProgress))
        updateProgressMessage(downloadTask, "已缓存 $doneCount/$total 页")
        showComicCacheNotification(
            downloadTask,
            resolveGroupProgress(downloadTask, chapterProgress, groupChapters)
        )
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

    private suspend fun resolveGroupProgress(
        downloadTask: DownloadComic,
        currentProgress: Float,
        cachedChapters: List<DownloadComic>? = null,
    ): Float {
        val groupId = downloadTask.groupId.takeIf { it != 0 } ?: downloadTask.id
        val chapters = cachedChapters ?: downloadComicDao.getByGroupId(groupId)
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

    /**
     * 更新下载进度通知，并尽力把 worker 提升为前台服务：长时间批量下载在后台
     * 极易被系统冻结/杀进程（还连累 SAF 授权），前台服务可以显著降低这种概率。
     * 通知开关关闭时保持原行为（不显示通知也不进前台）；Android 12+ 在后台
     * 禁止启动前台服务时静默降级为普通后台执行。
     */
    private suspend fun showComicCacheNotification(downloadTask: DownloadComic, progress: Float) {
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
        // 串行化通知/前台更新：并行页会并发触发本方法
        notificationMutex.withLock {
            showProgressNotification(
                context = appContext,
                notificationId = COMIC_CACHE_NOTIFICATION_ID_BASE + groupId,
                title = title,
                text = "$progressPercent%",
                progressPercent = progressPercent
            )
            runCatching {
                setForeground(createDownloadForeground(groupId, title, progressPercent))
            }.onFailure {
                logError("DownloadComicWorker", "前台服务启动失败（后台限制）：${it.message}")
            }
        }
    }

    private fun createDownloadForeground(groupId: Int, title: String, progressPercent: Int): ForegroundInfo {
        val openApp = PendingIntent.getActivity(
            appContext,
            COMIC_CACHE_NOTIFICATION_ID_BASE + groupId,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(title)
            .setContentText("$progressPercent%")
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progressPercent.coerceIn(0, 100), false)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                COMIC_CACHE_NOTIFICATION_ID_BASE + groupId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(COMIC_CACHE_NOTIFICATION_ID_BASE + groupId, notification)
        }
    }
}
