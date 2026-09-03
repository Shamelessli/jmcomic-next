package com.par9uet.jm.cache

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.google.gson.Gson
import java.io.File

/**
 * 缓存文件夹扫描器：遍历当前缓存根目录（SAF 树根或默认下载目录）下的所有漫画目录，
 * 解析各自的 config.json 并按格式校验：
 * - 格式合法的目录（formatVersion/id/章节清单齐全）产出 [ScannedComic] 供上层导入缓存列表；
 * - 同名重复目录群（漫画/漫画1/漫画2）按漫画组 id 细分后选出最完整的规范根，
 *   完全被规范根覆盖或空的重复目录直接清理；
 * - 无清单且无任何图片的残留目录清理；有图片但无法识别的目录保留并上报，不做任何冒险删除。
 *
 * 扫描器只做磁盘侧的发现与清理决策，不触碰 Room，导入由 CacheScanManager 完成。
 */
class CacheFolderScanner(
    private val context: Context,
    private val gson: Gson,
) {
    /** config.json 清单里的单个章节，附带上磁盘评估后的完整性。 */
    data class ScannedChapter(
        val id: Int,
        val name: String,
        val path: String,
        val imageCount: Int,
        /** 1-based 缺失页号；目录整个缺失时无意义（由 hasImages 区分） */
        val missingPages: List<Int>,
        val hasImages: Boolean,
    ) {
        val isComplete: Boolean get() = hasImages && missingPages.isEmpty()
        val needsRepair: Boolean get() = hasImages && missingPages.isNotEmpty()
    }

    /** 一部可导入的漫画：规范根目录 + 清单元数据 + 逐章评估结果。 */
    data class ScannedComic(
        val rootPath: String,
        val displayName: String,
        val groupId: Int,
        val title: String,
        val authors: List<String>,
        val tags: List<String>,
        val chapters: List<ScannedChapter>,
        val coverPath: String,
    )

    data class ScanOutcome(
        val comics: List<ScannedComic>,
        /** 有图片但没有合法 config.json、无法识别导入的目录名 */
        val unrecognizedDirs: List<String>,
        /** 含数据但未被规范根完全覆盖、不能安全删除的重复目录名 */
        val keptDuplicateDirs: List<String>,
        val deletedEmptyDirs: Int,
        val deletedDuplicateDirs: Int,
    )

    fun scan(onProgress: (String) -> Unit = {}): ScanOutcome {
        val entries = listRootDirectories()
        onProgress("发现 ${entries.size} 个目录")
        val comics = mutableListOf<ScannedComic>()
        val unrecognized = mutableListOf<String>()
        val keptDuplicates = mutableListOf<String>()
        var deletedEmpty = 0
        var deletedDuplicate = 0

        // 按去数字后缀的基础名分组（漫画/漫画1/漫画2 → 同组），组内再按漫画组 id 细分
        val groups = entries.groupBy { baseNameOf(it.second) }
        for ((base, members) in groups) {
            onProgress("正在扫描 $base")
            val parsed = members.map { it to readConfig(it.first) }
            // mapNotNull 让 config 收窄为非空，后续分组/比较/构造都不必再判空
            val valid = parsed.mapNotNull { (entry, config) ->
                if (config != null && isValidConfig(config)) entry to config else null
            }
            val invalid = parsed.filterNot { (_, config) -> config != null && isValidConfig(config) }

            // 同名但 config.id 不同是不同的漫画，各自独立选出规范根并导入
            for (sameComic in valid.groupBy { it.second.id }.values) {
                val canonical = sameComic.maxWithOrNull(
                    compareBy<Pair<Pair<String, String>, DownloadComicCacheConfig>> { countCompleteChapters(it.second) }
                        .thenByDescending { it.first.second == base }
                ) ?: continue
                comics += buildComic(canonical.first, canonical.second)
                for (other in sameComic) {
                    if (other === canonical) continue
                    val path = other.first.first
                    when {
                        isDirEmpty(path) -> if (deleteComicRoot(context, path)) deletedEmpty++
                        isFullyCovered(other.second, canonical.second) -> if (deleteComicRoot(context, path)) deletedDuplicate++
                        else -> keptDuplicates += other.first.second
                    }
                }
            }

            for ((entry, _) in invalid) {
                val (path, displayName) = entry
                when {
                    isDirEmpty(path) -> if (deleteComicRoot(context, path)) deletedEmpty++
                    !hasAnyImages(path) -> if (deleteComicRoot(context, path)) deletedEmpty++
                    else -> unrecognized += displayName
                }
            }
        }
        return ScanOutcome(
            comics = comics,
            unrecognizedDirs = unrecognized,
            keptDuplicateDirs = keptDuplicates,
            deletedEmptyDirs = deletedEmpty,
            deletedDuplicateDirs = deletedDuplicate,
        )
    }

    private fun buildComic(
        entry: Pair<String, String>,
        config: DownloadComicCacheConfig,
    ): ScannedComic {
        val chapters = runCatching { config.chapters }.getOrNull().orEmpty().map { chapter ->
            val images = listComicImageEntries(context, chapter.path)
            val (_, missing) = assessChapterPages(context, chapter)
            ScannedChapter(
                id = chapter.id,
                name = chapter.name,
                path = chapter.path,
                imageCount = chapter.imageCount.takeIf { it > 0 } ?: chapter.imageFiles.size,
                missingPages = missing,
                hasImages = images.isNotEmpty(),
            )
        }
        val coverPath = findCacheChildPath(context, entry.first, COVER_FILE_NAME)
            ?.takeIf { cachePathHasContent(context, it) }
            .orEmpty()
        return ScannedComic(
            rootPath = entry.first,
            displayName = entry.second,
            groupId = config.id,
            title = config.title,
            authors = runCatching { config.authors }.getOrDefault(emptyList()),
            tags = runCatching { config.tags }.getOrDefault(emptyList()),
            chapters = chapters,
            coverPath = coverPath,
        )
    }

    private fun isValidConfig(config: DownloadComicCacheConfig): Boolean = runCatching {
        config.id > 0 && config.formatVersion >= 1 && !config.chapters.isNullOrEmpty()
    }.getOrDefault(false)

    private fun countCompleteChapters(config: DownloadComicCacheConfig): Int =
        runCatching { config.chapters }.getOrNull().orEmpty()
            .count { assessChapterPages(context, it).first }

    /** 规范根是否完全覆盖重复目录（每章完整页数都不少于它）；空清单目录视为纯冗余。 */
    private fun isFullyCovered(
        duplicate: DownloadComicCacheConfig,
        canonical: DownloadComicCacheConfig,
    ): Boolean {
        val duplicateChapters = runCatching { duplicate.chapters }.getOrNull().orEmpty()
        if (duplicateChapters.isEmpty()) return true
        val canonicalById = runCatching { canonical.chapters }.getOrNull().orEmpty()
            .associateBy { it.id }
        return duplicateChapters.all { chapter ->
            val canonicalChapter = canonicalById[chapter.id] ?: return false
            completeCount(canonicalChapter) >= completeCount(chapter)
        }
    }

    private fun completeCount(chapter: DownloadComicCacheChapter): Int {
        val total = chapter.imageCount.takeIf { it > 0 } ?: chapter.imageFiles.size
        val (_, missing) = assessChapterPages(context, chapter)
        return (total - missing.size).coerceAtLeast(0)
    }

    /** 列出缓存根目录下的全部子目录，返回 (path, displayName)。 */
    private fun listRootDirectories(): List<Pair<String, String>> {
        val treeUri = getDownloadTreeUri(context)
        if (treeUri == null) {
            return getDownloadDir(context).listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.absolutePath to it.name }
                .orEmpty()
        }
        val root = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            root, DocumentsContract.getDocumentId(root),
        )
        return runCatching {
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            add(
                                DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(0)).toString()
                                    to cursor.getString(1)
                            )
                        }
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun readConfig(rootPath: String): DownloadComicCacheConfig? {
        val configPath = findCacheChildPath(context, rootPath, CONFIG_FILE_NAME) ?: return null
        if (!cachePathHasContent(context, configPath)) return null
        return runCatching {
            openCacheInputStream(context, configPath)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                gson.fromJson(reader, DownloadComicCacheConfig::class.java)
            }
        }.getOrNull()
    }

    /** 目录是否完全没有子项；查询失败时按"非空"处理，绝不因失败误删。 */
    private fun isDirEmpty(path: String): Boolean {
        return if (isDocumentCachePath(path)) {
            runCatching {
                val uri = Uri.parse(path)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    uri, DocumentsContract.getDocumentId(uri),
                )
                context.contentResolver.query(
                    children,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null, null, null,
                )?.use { !it.moveToFirst() } ?: false
            }.getOrDefault(false)
        } else {
            val dir = File(path)
            dir.isDirectory && dir.listFiles().isNullOrEmpty()
        }
    }

    /** 目录内（含一层章节子目录）是否存在任何图片文件。 */
    private fun hasAnyImages(path: String): Boolean {
        if (listComicImageEntries(context, path).isNotEmpty()) return true
        return childDirectories(path).any { listComicImageEntries(context, it).isNotEmpty() }
    }

    private fun childDirectories(path: String): List<String> {
        if (!isDocumentCachePath(path)) {
            return File(path).listFiles()
                ?.filter(File::isDirectory)
                ?.map(File::getAbsolutePath)
                .orEmpty()
        }
        return runCatching {
            val uri = Uri.parse(path)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                uri, DocumentsContract.getDocumentId(uri),
            )
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            add(DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0)).toString())
                        }
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** 去掉末尾数字后缀（漫画1 → 漫画），用于把系统改名产生的重复目录归为一组。 */
    private fun baseNameOf(displayName: String): String {
        var name = displayName
        while (name.isNotEmpty() && name.last().isDigit()) name = name.dropLast(1)
        return name
    }
}

private const val CONFIG_FILE_NAME = "config.json"
private const val COVER_FILE_NAME = "cover.webp"
