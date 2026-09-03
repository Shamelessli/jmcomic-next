package com.par9uet.jm.store

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.worker.DownloadComicWorker
import com.par9uet.jm.cache.AdoptionPlan
import com.par9uet.jm.cache.CacheAdopter
import com.par9uet.jm.cache.CacheRootIndex
import com.par9uet.jm.cache.cachePathExists
import com.par9uet.jm.cache.deleteCachePath
import com.par9uet.jm.cache.deleteComicRoot
import com.par9uet.jm.cache.findExistingComicChapterDownloadPath
import com.par9uet.jm.cache.getComicChapterDownloadPath
import com.par9uet.jm.cache.getComicDownloadRootPath
import com.par9uet.jm.cache.listComicImagePaths
import com.par9uet.jm.cache.CacheIntegrityResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val DOWNLOAD_RETRY_BACKOFF_SECONDS = 30L

class DownloadManager(
    private val context: Context,
    private val downloadComicDao: DownloadComicDao,
    private val scope: CoroutineScope,
    private val toastManager: ToastManager,
    private val gson: Gson,
) {
    fun downloadComic(comic: Comic) {
        scope.launch(Dispatchers.IO) {
            if (downloadComicDao.getExistingIds(listOf(comic.id)).isNotEmpty()) {
                toastManager.showAsync("该漫画已在缓存列表中")
                return@launch
            }
            val bookName = comic.name
            val groupId = comic.id
            val expectedIds = comic.comicChapterList.map { it.id }.ifEmpty { listOf(comic.id) }.toSet()
            val plan = adoptExisting(bookName, groupId, expectedIds)
            if (plan != null) {
                applyAdoption(comic, comic.comicChapterList.map { it.id }.ifEmpty { listOf(comic.id) }, plan)
            } else {
                insertComicTask(comic)
                enqueueDownload(comic.id)
                toastManager.showAsync("创建缓存任务成功")
            }
        }
    }

    fun downloadComics(comics: List<Comic>) {
        if (comics.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val existingIds = downloadComicDao.getExistingIds(comics.map { it.id }).toSet()
            val newComics = comics.filterNot { it.id in existingIds }
            if (newComics.isEmpty()) {
                toastManager.showAsync("所选漫画已在缓存列表中")
                return@launch
            }

            newComics.forEach { insertComicTask(it) }
            enqueueDownloads(newComics.map { it.id })

            val skippedCount = comics.size - newComics.size
            toastManager.showAsync(
                if (skippedCount > 0) {
                    "已创建 ${newComics.size} 个缓存任务，跳过 $skippedCount 个已存在漫画"
                } else {
                    "已创建 ${newComics.size} 个缓存任务"
                }
            )
        }
    }

    fun downloadChapters(parentComic: Comic, chapters: List<ComicChapter>) {
        if (chapters.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val existingIds = downloadComicDao.getExistingIds(chapters.map { it.id }).toSet()
            val newChapters = chapters.filterNot { it.id in existingIds }
            if (newChapters.isEmpty()) {
                toastManager.showAsync("所选章节已在缓存列表中")
                return@launch
            }

            val bookName = parentComic.name
            val groupId = parentComic.id
            val expectedIds = newChapters.map { it.id }.toSet()
            val plan = adoptExisting(bookName, groupId, expectedIds)
            if (plan != null) {
                applyAdoption(parentComic, newChapters.map { it.id }, plan)
            } else {
                insertChapters(parentComic, newChapters)
                enqueueDownloads(newChapters.map { it.id })
            }

            val skippedCount = chapters.size - newChapters.size
            toastManager.showAsync(
                if (skippedCount > 0) {
                    "已创建 ${newChapters.size} 个缓存任务，跳过 $skippedCount 个已存在章节"
                } else {
                    "已创建 ${newChapters.size} 个缓存任务"
                }
            )
        }
    }

    private suspend fun insertComicTask(comic: Comic) {
        downloadComicDao.insert(
            DownloadComic(
                id = comic.id,
                name = comic.name,
                authorList = comic.authorList,
                tagList = comic.tagList,
                coverPath = "",
                zipPath = "",
                progress = 0f,
                status = "pending",
                createTime = System.currentTimeMillis(),
                groupId = comic.id,
                groupName = comic.name
            )
        )
    }

    private suspend fun insertChapters(parentComic: Comic, chapters: List<ComicChapter>) {
        val now = System.currentTimeMillis()
        chapters.forEachIndexed { index, chapter ->
            downloadComicDao.insert(
                DownloadComic(
                    id = chapter.id,
                    name = "${parentComic.name} ${chapter.name}".trim(),
                    authorList = parentComic.authorList,
                    tagList = parentComic.tagList,
                    coverPath = "",
                    zipPath = "",
                    progress = 0f,
                    status = "pending",
                    createTime = now + index,
                    groupId = parentComic.id,
                    groupName = parentComic.name,
                    chapterName = chapter.name,
                )
            )
        }
    }

    /** 下载前检测磁盘已有缓存（含 漫画/漫画1/漫画2 重复目录群）；无则返回 null 走全新下载。 */
    private fun adoptExisting(bookName: String, groupId: Int, expectedChapterIds: Set<Int>): AdoptionPlan? =
        runCatching { CacheAdopter(context, gson).adopt(bookName, groupId, expectedChapterIds) }
            .getOrNull()

    /**
     * 应用接管计划：已完整章节直接登记为已缓存（不重复下载）；缺页章节只补缺失页；
     * 清单未覆盖的章节走全新下载。根目录已在 [CacheAdopter.adopt] 内登记，章节路径随之收敛。
     */
    private suspend fun applyAdoption(comic: Comic, chapterIds: List<Int>, plan: AdoptionPlan) {
        val now = System.currentTimeMillis()
        val ownerChapterId = chapterIds.minByOrNull { it } ?: chapterIds.first()
        val chapterNameById = comic.comicChapterList.associate { it.id to it.name }

        val rows = chapterIds.mapIndexed { index, chapterId ->
            val chapterName = chapterNameById[chapterId].orEmpty()
            DownloadComic(
                id = chapterId,
                name = if (chapterName.isBlank()) comic.name else "${comic.name} $chapterName".trim(),
                authorList = comic.authorList,
                tagList = comic.tagList,
                coverPath = "",
                zipPath = "",
                progress = 0f,
                status = "pending",
                createTime = now + index,
                groupId = comic.id,
                groupName = comic.name,
                chapterName = chapterName,
            )
        }

        val repairIds = mutableListOf<Int>()
        val freshIds = mutableListOf<Int>()
        for (row in rows) {
            when {
                row.id in plan.completeChapterIds -> {
                    // 确认章节目录真实存在（兼容旧命名）；找不到则降级为全新下载，避免把空目录标记为已缓存
                    val verifiedDir = runCatching {
                        findExistingComicChapterDownloadPath(context, row)
                    }.getOrNull()
                    if (verifiedDir != null) {
                        downloadComicDao.insert(
                            row.copy(
                                status = "complete",
                                progress = 1f,
                                zipPath = verifiedDir,
                                coverPath = if (row.id == ownerChapterId) plan.coverPath else "",
                            )
                        )
                    } else {
                        downloadComicDao.insert(row)
                        freshIds += row.id
                    }
                }
                row.id in plan.repairChapters -> {
                    downloadComicDao.insert(row)
                    repairIds += row.id
                }
                else -> {
                    downloadComicDao.insert(row)
                    freshIds += row.id
                }
            }
        }

        val needCover = plan.coverPath.isBlank()
        val repairRequests = plan.repairChapters.map { (chapterId, missingPages) ->
            DownloadRepairRequest(
                comicId = chapterId,
                downloadCover = needCover && chapterId == ownerChapterId && freshIds.isEmpty(),
                downloadPages = true,
                writeConfig = true,
                missingPageIndices = missingPages.map { it - 1 }.filter { it >= 0 }.toIntArray(),
            )
        }
        if (freshIds.isNotEmpty()) {
            // fresh 章节全新下载时一并补下封面（downloadCover=true），repair 章节不再重复下封面
            enqueueDownloads(freshIds, downloadCover = needCover)
            if (repairIds.isNotEmpty()) enqueueRepairRequests(repairRequests)
        } else {
            if (repairIds.isNotEmpty()) {
                enqueueRepairRequests(repairRequests)
            } else if (needCover) {
                // 全部完整但缺封面：仅由 owner 章节补下封面
                enqueueRepairRequests(listOf(DownloadRepairRequest(ownerChapterId, true, false, true)))
            }
        }

        val summary = buildList {
            if (plan.completeChapterIds.isNotEmpty()) add("${plan.completeChapterIds.size} 章已缓存")
            if (repairIds.isNotEmpty()) add("${repairIds.size} 章补页")
            if (freshIds.isNotEmpty()) add("${freshIds.size} 章重新下载")
        }
        toastManager.showAsync("检测到已有缓存：${summary.joinToString("，")}")
    }

    private fun enqueueDownload(comicId: Int) {
        enqueueDownloads(listOf(comicId))
    }

    private fun enqueueDownloads(
        comicIds: List<Int>,
        downloadCover: Boolean = true,
        downloadPages: Boolean = true,
        writeConfig: Boolean = true,
    ) {
        if (comicIds.isEmpty()) return
        val distinctComicIds = comicIds.distinct()
        val batchId = if (distinctComicIds.size > 1) UUID.randomUUID().toString() else ""
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workManager = WorkManager.getInstance(context)
        distinctComicIds.forEach { comicId ->
            val downloadRequest = OneTimeWorkRequestBuilder<DownloadComicWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        "comicId" to comicId,
                        "batchId" to batchId,
                        "batchTotal" to distinctComicIds.size
                        ,"downloadCover" to downloadCover
                        ,"downloadPages" to downloadPages
                        ,"writeConfig" to writeConfig
                    )
                )
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    DOWNLOAD_RETRY_BACKOFF_SECONDS,
                    TimeUnit.SECONDS
                )
                .build()
            workManager.enqueue(downloadRequest)
        }
    }

    fun retryDownload(comicId: Int) {
        scope.launch(Dispatchers.IO) {
            val task = downloadComicDao.getById(comicId) ?: return@launch
            downloadComicDao.updateProgress(
                com.par9uet.jm.database.model.UpdateComicProgress(comicId, 0f)
            )
            downloadComicDao.updateStatus(
                com.par9uet.jm.database.model.UpdateComicStatus(comicId, "pending")
            )
            enqueueDownload(comicId)
            toastManager.showAsync("已重新加入下载队列")
        }
    }

    /**
     * 恢复已暂停的下载任务：更新状态为 pending 并重新入队 WorkManager。
     * 与 retryDownload 不同，不会重置已下载进度，而是从断点继续。
     */
    fun resumeDownloads(comicIds: List<Int>) {
        if (comicIds.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val validIds = comicIds.filter { id ->
                val task = downloadComicDao.getById(id)
                task != null && task.status != "complete"
            }.distinct()
            if (validIds.isEmpty()) {
                toastManager.showAsync("没有可恢复的下载任务")
                return@launch
            }
            downloadComicDao.updateStatusByIds(validIds, "pending")
            enqueueDownloads(validIds)
            toastManager.showAsync("已恢复 ${validIds.size} 个下载任务")
        }
    }

    fun retryGroup(groupId: Int) {
        scope.launch(Dispatchers.IO) {
            val chapters = downloadComicDao.getByGroupId(groupId)
            val errorIds = chapters.filter { it.status == "error" }.map { it.id }
            if (errorIds.isEmpty()) return@launch
            downloadComicDao.updateStatusByIds(errorIds, "pending")
            errorIds.forEach { id ->
                downloadComicDao.updateProgress(
                    com.par9uet.jm.database.model.UpdateComicProgress(id, 0f)
                )
            }
            enqueueDownloads(errorIds)
            toastManager.showAsync("已重新加入 ${errorIds.size} 个下载任务")
        }
    }

    suspend fun deleteCachedItems(ids: Collection<Int>): Int {
        val selectedIds = ids.distinct()
        if (selectedIds.isEmpty()) return 0
        val selected = mutableListOf<DownloadComic>()
        for (id in selectedIds) {
            downloadComicDao.getById(id)?.let(selected::add)
        }
        var deletedCount = 0
        selected.groupBy { it.groupId.takeIf { id -> id != 0 } ?: it.id }.forEach { (groupId, groupItems) ->
            val allGroupItems = downloadComicDao.getByGroupId(groupId)
            val deletingWholeGroup = allGroupItems.all { item -> item.id in selectedIds }
            val groupCoverPaths = groupItems.map { it.coverPath }.filter(String::isNotBlank).distinct()
            groupItems.forEach { item ->
                val zipDeleted = item.zipPath.isBlank() || !cachePathExists(context, item.zipPath) || deleteCachePath(context, item.zipPath)
                if (zipDeleted) {
                    downloadComicDao.delete(item)
                    deletedCount++
                }
            }
            if (deletingWholeGroup) {
                groupCoverPaths.forEach { path ->
                    if (cachePathExists(context, path)) deleteCachePath(context, path)
                }
                // 整组删除后清理漫画根目录与其中的 config.json，避免残留半空目录
                // 成为下一次下载时 SAF 重名改名（漫画/漫画1/漫画2）的碰撞源
                val rootProbe = groupItems.first()
                runCatching { deleteComicRoot(context, getComicDownloadRootPath(context, rootProbe)) }
            }
        }
        return deletedCount
    }

    fun redownloadMissing(groupId: Int) {
        scope.launch(Dispatchers.IO) {
            val items = downloadComicDao.getByGroupId(groupId)
            val missing = items.filter { item ->
                item.status == "complete" && (
                    item.zipPath.isBlank() ||
                        !cachePathExists(context, item.zipPath) ||
                        runCatching { listComicImagePaths(context, item.zipPath).isEmpty() }.getOrDefault(true)
                    )
            }
            if (missing.isEmpty()) {
                toastManager.showAsync("本地缓存文件完整")
                return@launch
            }
            missing.forEach { item ->
                if (item.zipPath.isNotBlank() && cachePathExists(context, item.zipPath)) deleteCachePath(context, item.zipPath)
                downloadComicDao.updateStatus(com.par9uet.jm.database.model.UpdateComicStatus(item.id, "pending"))
                downloadComicDao.updateProgress(com.par9uet.jm.database.model.UpdateComicProgress(item.id, 0f))
            }
            enqueueDownloads(missing.map { it.id })
            toastManager.showAsync("已重新下载 ${missing.size} 个缺失章节")
        }
    }

    fun repairCachedItems(result: CacheIntegrityResult) {
        val taskIds = (result.brokenChapterIds + result.chapterIds).distinct()
        if (taskIds.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val tasks = mutableListOf<DownloadComic>()
            for (id in taskIds) {
                downloadComicDao.getById(id)?.let(tasks::add)
            }
            if (tasks.isEmpty()) return@launch
            val needsPages = tasks.associate { it.id to (it.id in result.brokenChapterIds) }
            val owner = tasks.minByOrNull { it.createTime }?.id
            // A missing cover/JSON belongs to the comic, while broken pages belong
            // to individual chapters. Do not enqueue every chapter for metadata-only
            // repairs; that used to create needless worker runs and could look like a
            // full comic re-download.
            val repairIds = buildList {
                if (owner != null && (result.missingCover || result.missingConfig)) add(owner)
                addAll(result.brokenChapterIds)
            }.distinct()
            if (repairIds.isEmpty()) return@launch
            repairIds.forEach { id ->
                val item = tasks.firstOrNull { it.id == id } ?: return@forEach
                downloadComicDao.updateStatus(com.par9uet.jm.database.model.UpdateComicStatus(item.id, "pending"))
                // A repair must start from a visible, indeterminate-looking
                // zero state. Keeping 100% from the previous complete row
                // makes a missing-page repair appear frozen at 0 KB/100%.
                if (id in result.brokenChapterIds) {
                    downloadComicDao.updateProgress(
                        com.par9uet.jm.database.model.UpdateComicProgress(item.id, 0f)
                    )
                }
            }
            val requests = repairIds.distinct().map { id ->
                val isOwner = id == owner
                DownloadRepairRequest(
                    comicId = id,
                    downloadCover = result.missingCover && isOwner,
                    downloadPages = needsPages[id] == true,
                    writeConfig = result.missingConfig && isOwner,
                    missingPageIndices = result.missingPagesByChapter[id]
                        .orEmpty()
                        .map { it - 1 }
                        .filter { it >= 0 }
                        .toIntArray(),
                )
            }
            enqueueRepairRequests(requests)
            val parts = buildList {
                if (result.missingCover) add("封面")
                if (result.missingConfig) add("JSON")
                if (result.brokenChapterIds.isNotEmpty()) add("缺失页")
            }
            toastManager.showAsync("仅补齐：${parts.joinToString("、")}")
        }
    }

    @Deprecated("Use repairCachedItems(CacheIntegrityResult) to avoid redownloading complete comics")
    fun repairCachedItems(ids: Collection<Int>) =
        repairCachedItems(CacheIntegrityResult(brokenChapterIds = ids.toSet()))

    private data class DownloadRepairRequest(
        val comicId: Int,
        val downloadCover: Boolean,
        val downloadPages: Boolean,
        val writeConfig: Boolean,
        val missingPageIndices: IntArray = intArrayOf(),
    )

    /** 面向缓存扫描/接管的公开补页任务：只补缺失页，不整章重下。 */
    data class ChapterRepairTask(
        val chapterId: Int,
        val downloadCover: Boolean,
        val downloadPages: Boolean = true,
        val writeConfig: Boolean = true,
        val missingPageIndices: List<Int> = emptyList(),
    )

    fun enqueueChapterRepairs(tasks: List<ChapterRepairTask>) {
        if (tasks.isEmpty()) return
        enqueueRepairRequests(tasks.map {
            DownloadRepairRequest(
                comicId = it.chapterId,
                downloadCover = it.downloadCover,
                downloadPages = it.downloadPages,
                writeConfig = it.writeConfig,
                missingPageIndices = it.missingPageIndices.toIntArray(),
            )
        })
    }

    private fun enqueueRepairRequests(requests: List<DownloadRepairRequest>) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workManager = WorkManager.getInstance(context)
        requests.forEach { request ->
            val work = OneTimeWorkRequestBuilder<DownloadComicWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        "comicId" to request.comicId,
                        "batchId" to "",
                        "batchTotal" to requests.size,
                        "downloadCover" to request.downloadCover,
                        "downloadPages" to request.downloadPages,
                        "writeConfig" to request.writeConfig,
                        "missingPageIndices" to request.missingPageIndices,
                    )
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, DOWNLOAD_RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            workManager.enqueue(work)
        }
    }

    fun redownloadGroup(groupId: Int) {
        scope.launch(Dispatchers.IO) {
            val items = downloadComicDao.getByGroupId(groupId)
            if (items.isEmpty()) return@launch
            items.forEach { item ->
                if (item.zipPath.isNotBlank() && cachePathExists(context, item.zipPath)) deleteCachePath(context, item.zipPath)
                if (item.coverPath.isNotBlank() && cachePathExists(context, item.coverPath)) deleteCachePath(context, item.coverPath)
                downloadComicDao.updateStatus(
                    com.par9uet.jm.database.model.UpdateComicStatus(item.id, "pending")
                )
                downloadComicDao.updateProgress(
                    com.par9uet.jm.database.model.UpdateComicProgress(item.id, 0f)
                )
            }
            enqueueDownloads(items.map { it.id })
            toastManager.showAsync("已重新下载 ${items.size} 个任务")
        }
    }
}
