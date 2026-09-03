package com.par9uet.jm.cache

import android.content.Context
import android.provider.DocumentsContract
import com.google.gson.Gson
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError
import java.io.File

/**
 * 缓存接管：在下载前检测磁盘上是否已存在该书的历史缓存（含被系统改名的 漫画/漫画1/漫画2
 * 这类同名目录群），读取其 config.json 并与"要缓存的章节 id"比对，校验资源完整性后：
 * - 已完整的章节直接进缓存列表（不重复下载）；
 * - 缺页的章节只补缺失页（repair）；
 * - 清单未覆盖的章节走全新下载；
 * - 把规范根目录登记进 [CacheRootIndex]，并清理被完全覆盖的冗余重复目录。
 *
 * 返回 null 表示没有可接管的历史缓存，调用方应走原有全新下载流程。
 */
class CacheAdopter(
    private val context: Context,
    private val gson: Gson,
) {
    /**
     * 扫描并接管 [groupId] 漫画组、[bookName] 名下 [expectedChapterIds] 章节的已有缓存。
     *
     * @return 接管计划；无可接管缓存时为 null。
     */
    fun adopt(bookName: String, groupId: Int, expectedChapterIds: Set<Int>): AdoptionPlan? {
        if (expectedChapterIds.isEmpty()) return null
        val dirName = safeCacheFileName(bookName)

        val canonical = discoverCanonicalRoot(dirName, groupId, expectedChapterIds) ?: return null

        // 登记规范根目录：此后封面/章节/config 的定位都收敛到同一根，止住重复目录。
        CacheRootIndex.put(context, groupId, canonical.path)
        // 接管的常是共享存储上的历史目录：补写 .nomedia 并尽力触发重扫，
        // 把 .nomedia 出现前已入库的缓存图片从相册/图片选择器中隐藏
        ensureNoMedia(context, canonical.path)

        val configChapters = canonical.config?.chapters.orEmpty().associateBy { it.id }
        val complete = LinkedHashMap<Int, Int>()
        val repair = LinkedHashMap<Int, List<Int>>()
        val fresh = ArrayList<Int>()
        for (chapterId in expectedChapterIds) {
            val manifest = configChapters[chapterId] ?: continue
            val (ok, missing) = assessChapterPages(context, manifest)
            when {
                ok -> complete[chapterId] = manifest.imageCount
                missing.isNotEmpty() -> repair[chapterId] = missing
                else -> fresh.add(chapterId)
            }
        }

        val coverPath = findCacheChildPath(context, canonical.path, COVER_FILE_NAME)
            ?.takeIf { cachePathHasContent(context, it) }
            .orEmpty()

        cleanupRedundantSiblings(dirName, canonical)

        log(
            "CacheAdopter",
            "接管 $bookName：规范根=${canonical.displayName} 完整=${complete.size} " +
                "补页=${repair.size} 全新=${fresh.size} 封面=${coverPath.isNotBlank()}"
        )
        return AdoptionPlan(
            rootPath = canonical.path,
            groupId = groupId,
            completeChapterIds = complete.keys.toSet(),
            completePageCounts = complete,
            repairChapters = repair,
            freshChapterIds = fresh.toSet(),
            coverPath = coverPath,
        )
    }

    private data class CandidateRoot(
        val path: String,
        val displayName: String,
        val config: DownloadComicCacheConfig?,
    )

    /** 在所有同名候选目录中选出 config 合法、覆盖期望章节最多、且名字最规范的一个。 */
    private fun discoverCanonicalRoot(
        dirName: String,
        groupId: Int,
        expectedChapterIds: Set<Int>,
    ): CandidateRoot? {
        val siblings = scanSiblingRoots(dirName)
        if (siblings.isEmpty()) return null
        return siblings
            .map { (path, displayName) -> CandidateRoot(path, displayName, readConfig(path)) }
            .filter { it.isManifestValid(groupId, expectedChapterIds) }
            .maxWithOrNull(compareBy<CandidateRoot> { countComplete(it, expectedChapterIds) }
                .thenByDescending { it.displayName == dirName })
    }

    private fun CandidateRoot.isManifestValid(groupId: Int, expectedChapterIds: Set<Int>): Boolean =
        runCatching {
            val config = config ?: return false
            config.id == groupId &&
                config.formatVersion >= 1 &&
                config.chapters.isNotEmpty() &&
                config.chapters.any { it.id in expectedChapterIds }
        }.getOrDefault(false)

    /** 该候选根下期望章节中"已完整"的数量，用于挑选覆盖最多的根。 */
    private fun countComplete(candidate: CandidateRoot, expectedChapterIds: Set<Int>): Int {
        val byId = candidate.config?.chapters.orEmpty().associateBy { it.id }
        return expectedChapterIds.count { id ->
            byId[id]?.let { assessChapterPages(context, it).first } == true
        }
    }

    /** 列出父目录下所有 漫画/漫画1/漫画2 这类兄弟根目录，返回 (path, displayName)。 */
    private fun scanSiblingRoots(dirName: String): List<Pair<String, String>> {
        val treeUri = getDownloadTreeUri(context)
        val result = mutableListOf<Pair<String, String>>()
        if (treeUri == null) {
            val parent = File(getDownloadDir(context), dirName).parentFile ?: return emptyList()
            parent.listFiles()
                ?.filter { it.isDirectory && isRenamedVariant(dirName, it.name) }
                ?.forEach { result += it.absolutePath to it.name }
        } else {
            val root = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri),
            )
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getDocumentId(root))
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR && isRenamedVariant(dirName, name)) {
                        result += DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(0)).toString() to name
                    }
                }
            }
        }
        return result
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

    /**
     * 清理冗余重复目录：仅当规范根对某兄弟目录的每章完整页数都 ≥ 该兄弟时（即规范根完全
     * 覆盖它、删除不丢数据）才删除。无法验证的兄弟一律保留，避免误删用户数据。
     */
    private fun cleanupRedundantSiblings(dirName: String, canonical: CandidateRoot) {
        scanSiblingRoots(dirName).forEach { (path, displayName) ->
            if (path == canonical.path) return@forEach
            if (isFullyCoveredBy(canonical, path)) {
                runCatching { deleteComicRoot(context, path) }
                    .onSuccess { log("CacheAdopter", "清理冗余重复目录 $displayName → $path") }
                    .onFailure { logError("CacheAdopter", "清理冗余目录 $displayName 失败: ${it.message}") }
            }
        }
    }

    private fun isFullyCoveredBy(canonical: CandidateRoot, siblingPath: String): Boolean {
        val siblingConfig = readConfig(siblingPath) ?: return false
        val siblingChapters = siblingConfig.chapters
        if (siblingChapters.isEmpty()) return canonical.config != null
        val canonicalByChapter = canonical.config?.chapters.orEmpty().associateBy { it.id }
        return siblingChapters.all { chapter ->
            val canonicalChapter = canonicalByChapter[chapter.id] ?: return false
            completeCount(canonicalChapter) >= completeCount(chapter)
        }
    }

    private fun completeCount(chapter: DownloadComicCacheChapter): Int {
        val total = chapter.imageCount.takeIf { it > 0 } ?: chapter.imageFiles.size
        val (_, missing) = assessChapterPages(context, chapter)
        return (total - missing.size).coerceAtLeast(0)
    }

    private fun isRenamedVariant(base: String, displayName: String): Boolean {
        if (displayName == base) return true
        if (!displayName.startsWith(base)) return false
        val suffix = displayName.substring(base.length)
        return suffix.isNotEmpty() && suffix.all(Char::isDigit)
    }
}

/**
 * 接管计划：[completeChapterIds] 直接登记为已缓存（[completePageCounts] 为各章页数）；
 * [repairChapters] 只补缺失页（1-based 页号）；[freshChapterIds] 走全新下载。
 * [coverPath] 为空表示封面也需补下。
 */
data class AdoptionPlan(
    val rootPath: String,
    val groupId: Int,
    val completeChapterIds: Set<Int>,
    val completePageCounts: Map<Int, Int>,
    val repairChapters: Map<Int, List<Int>>,
    val freshChapterIds: Set<Int>,
    val coverPath: String,
)

private const val CONFIG_FILE_NAME = "config.json"
private const val COVER_FILE_NAME = "cover.webp"
