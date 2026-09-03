package com.par9uet.jm.store

import android.content.Context
import com.google.gson.Gson
import com.par9uet.jm.cache.CacheFolderScanner
import com.par9uet.jm.cache.CacheRootIndex
import com.par9uet.jm.cache.ensureNoMedia
import com.par9uet.jm.cache.findExistingComicChapterDownloadPath
import com.par9uet.jm.cache.listComicImagePaths
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.utils.logError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class CacheScanState {
    object Idle : CacheScanState()
    data class Scanning(val stage: String) : CacheScanState()
    data class Done(val report: CacheScanReport) : CacheScanState()
    data class Failed(val reason: String) : CacheScanState()
}

data class CacheScanReport(
    val importedComics: Int = 0,
    val importedChapters: Int = 0,
    val repairChapters: Int = 0,
    val skippedExistingChapters: Int = 0,
    val skippedMissingChapters: Int = 0,
    val deletedDuplicateDirs: Int = 0,
    val deletedEmptyDirs: Int = 0,
    val removedDeadRecords: Int = 0,
    val unrecognizedDirs: List<String> = emptyList(),
    val keptDuplicateDirs: List<String> = emptyList(),
)

/**
 * 扫描缓存文件夹：把磁盘上符合 config.json 格式的目录导入缓存列表，
 * 同时做完整性清理——删除重复/残留目录、移除指向已消失文件的失效记录，
 * 缺页章节自动加入补页队列（只补缺失页，不整章重下）。
 */
class CacheScanManager(
    private val context: Context,
    private val downloadComicDao: DownloadComicDao,
    private val gson: Gson,
    private val downloadManager: DownloadManager,
    private val scope: CoroutineScope,
) {
    private val _scanState = MutableStateFlow<CacheScanState>(CacheScanState.Idle)
    val scanState = _scanState.asStateFlow()

    @Volatile
    private var running = false

    fun startScan() {
        if (running) return
        running = true
        scope.launch(Dispatchers.IO) {
            try {
                runScan()
            } catch (t: Throwable) {
                logError("CacheScanManager", "扫描缓存文件夹失败：${t.message}")
                _scanState.value = CacheScanState.Failed(t.message ?: "未知错误")
            } finally {
                running = false
            }
        }
    }

    private suspend fun runScan() {
        _scanState.value = CacheScanState.Scanning("正在扫描缓存文件夹")
        val outcome = CacheFolderScanner(context, gson).scan { stage ->
            _scanState.value = CacheScanState.Scanning(stage)
        }

        // 完整性清理（Room 侧）：complete 状态但磁盘上已无任何图片、也找不到
        // 旧命名目录的记录属于失效数据，移除后用户可按需重新缓存
        _scanState.value = CacheScanState.Scanning("清理失效缓存记录")
        var removedDeadRecords = 0
        val deadRecords = downloadComicDao.getAll().filter { record ->
            record.status == "complete" && record.zipPath.isNotBlank() &&
                runCatching { listComicImagePaths(context, record.zipPath).isNotEmpty() }.getOrDefault(true)
                    .not() &&
                runCatching { findExistingComicChapterDownloadPath(context, record) }.getOrNull() == null
        }
        if (deadRecords.isNotEmpty()) {
            downloadComicDao.deleteByIds(deadRecords.map { it.id })
            removedDeadRecords = deadRecords.size
            // 整组记录都已失效时，连同根目录索引一起移除
            deadRecords.map { it.groupId.takeIf { g -> g != 0 } ?: it.id }.distinct()
                .forEach { groupId ->
                    if (downloadComicDao.getByGroupId(groupId).isEmpty()) {
                        CacheRootIndex.remove(context, groupId)
                    }
                }
        }

        // 导入：已完整章节直接登记为已缓存；缺页章节登记并加入补页队列
        _scanState.value = CacheScanState.Scanning("导入缓存记录")
        val now = System.currentTimeMillis()
        var importedComics = 0
        var importedChapters = 0
        var repairChapters = 0
        var skippedExisting = 0
        var skippedMissing = 0
        val repairTasks = mutableListOf<DownloadManager.ChapterRepairTask>()

        for (comic in outcome.comics) {
            CacheRootIndex.put(context, comic.groupId, comic.rootPath)
            ensureNoMedia(context, comic.rootPath)
            val existingIds = downloadComicDao
                .getExistingIds(comic.chapters.map { it.id })
                .toSet()
            val importable = comic.chapters.filterNot { it.id in existingIds }
            skippedExisting += comic.chapters.size - importable.size
            val chaptersToImport = importable.filter { it.hasImages }
            skippedMissing += importable.size - chaptersToImport.size
            if (chaptersToImport.isEmpty()) continue

            // 封面归属：优先挂到第一个完整章节，缺封面时由该章节的补页任务带回
            val ownerId = chaptersToImport.firstOrNull { it.isComplete }?.id
                ?: chaptersToImport.first().id
            var index = 0
            var importedAny = false
            for (chapter in chaptersToImport) {
                val completeFraction = if (chapter.isComplete) 1f else {
                    ((chapter.imageCount - chapter.missingPages.size).toFloat()
                        / chapter.imageCount.coerceAtLeast(1)).coerceIn(0f, 1f)
                }
                downloadComicDao.insert(
                    DownloadComic(
                        id = chapter.id,
                        name = if (comic.chapters.size > 1) {
                            "${comic.title} ${chapter.name}".trim()
                        } else comic.title,
                        authorList = comic.authors,
                        tagList = comic.tags,
                        coverPath = if (chapter.id == ownerId) comic.coverPath else "",
                        zipPath = chapter.path,
                        progress = completeFraction,
                        status = if (chapter.isComplete) "complete" else "pending",
                        createTime = now + index,
                        groupId = comic.groupId,
                        groupName = comic.title,
                        chapterName = chapter.name,
                    )
                )
                index++
                importedAny = true
                if (chapter.isComplete) {
                    importedChapters++
                } else {
                    repairChapters++
                    repairTasks += DownloadManager.ChapterRepairTask(
                        chapterId = chapter.id,
                        downloadCover = false,
                        missingPageIndices = chapter.missingPages.map { it - 1 }.filter { it >= 0 },
                    )
                }
            }
            if (importedAny) importedComics++
            if (comic.coverPath.isBlank()) {
                // 目录缺封面：合并到 owner 章节的补页任务（或单独补封面）
                val ownerTask = repairTasks.firstOrNull { it.chapterId == ownerId }
                if (ownerTask != null) {
                    repairTasks[repairTasks.indexOf(ownerTask)] = ownerTask.copy(downloadCover = true)
                } else {
                    repairTasks += DownloadManager.ChapterRepairTask(
                        chapterId = ownerId,
                        downloadCover = true,
                        downloadPages = false,
                    )
                }
            }
        }

        if (repairTasks.isNotEmpty()) {
            _scanState.value = CacheScanState.Scanning("正在加入补页队列（${repairTasks.size} 章）")
            downloadManager.enqueueChapterRepairs(repairTasks)
        }

        _scanState.value = CacheScanState.Done(
            CacheScanReport(
                importedComics = importedComics,
                importedChapters = importedChapters,
                repairChapters = repairChapters,
                skippedExistingChapters = skippedExisting,
                skippedMissingChapters = skippedMissing,
                deletedDuplicateDirs = outcome.deletedDuplicateDirs,
                deletedEmptyDirs = outcome.deletedEmptyDirs,
                removedDeadRecords = removedDeadRecords,
                unrecognizedDirs = outcome.unrecognizedDirs,
                keptDuplicateDirs = outcome.keptDuplicateDirs,
            )
        )
    }
}
