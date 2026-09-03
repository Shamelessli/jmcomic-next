package com.par9uet.jm.cache

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.google.gson.Gson
import com.par9uet.jm.database.model.DownloadComic
import java.io.File
import java.io.OutputStream

fun isDocumentCachePath(path: String): Boolean = path.startsWith("content://")

fun getTreeUriForCachePath(path: String): Uri? = runCatching {
    if (!isDocumentCachePath(path)) return@runCatching null
    val uri = Uri.parse(path)
    DocumentsContract.buildTreeDocumentUri(
        uri.authority ?: return@runCatching null,
        DocumentsContract.getTreeDocumentId(uri),
    )
}.getOrNull()

fun getComicDownloadRootPath(context: Context, comic: DownloadComic): String {
    val treeUri = getDownloadTreeUri(context)
    val groupId = CacheRootIndex.groupIdFor(comic.id, comic.groupId)
    // 优先使用已登记的根目录：SAF 在 createDocument 重名时会改名（漫画 → 漫画1），
    // 目录名又不入库，导致同一本书散落到多个兄弟根。命中登记值后封面/章节/config
    // 都收敛到同一个根，从源头止住重复目录。
    CacheRootIndex.get(context, groupId)?.let { registered ->
        if (isRootStillValid(context, registered, treeUri)) return registered
    }
    if (treeUri == null) {
        val localRoot = getComicDownloadRootDir(context, comic).absolutePath
        CacheRootIndex.put(context, groupId, localRoot)
        return localRoot
    }
    val root = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    val resolved = requireNotNull(findOrCreateCacheDocument(
        context,
        root,
        safeCacheFileName(comic.groupName.ifBlank { comic.name }),
        DocumentsContract.Document.MIME_TYPE_DIR,
    )).toString()
    CacheRootIndex.put(context, groupId, resolved)
    return resolved
}

/**
 * 登记的根目录是否仍可用：必须真实存在，且与当前活动存储树一致
 * （本地模式要求登记的是本地路径，SAF 模式要求同树）。否则视为失效并重新解析。
 */
private fun isRootStillValid(context: Context, rootPath: String, activeTree: Uri?): Boolean {
    if (!cachePathExists(context, rootPath)) return false
    if (activeTree == null) return !isDocumentCachePath(rootPath)
    val pathTree = getTreeUriForCachePath(rootPath) ?: return false
    return pathTree.authority == activeTree.authority &&
        DocumentsContract.getTreeDocumentId(pathTree) == DocumentsContract.getTreeDocumentId(activeTree)
}

fun getComicChapterDownloadPath(context: Context, comic: DownloadComic): String {
    val root = getComicDownloadRootPath(context, comic)
    if (!isDocumentCachePath(root)) return File(root, getChapterCacheName(comic)).also { it.mkdirs() }.absolutePath
    return requireNotNull(findOrCreateCacheDocument(
        context,
        Uri.parse(root),
        getChapterCacheName(comic),
        DocumentsContract.Document.MIME_TYPE_DIR,
    )).toString()
}

/**
 * Find an already migrated chapter directory without falling back to the
 * default cache. This also understands the directory names used before the
 * chapter-id suffix was introduced.
 */
fun findExistingComicChapterDownloadPath(context: Context, comic: DownloadComic): String? {
    val root = getComicDownloadRootPath(context, comic)
    val names = buildList {
        add(getChapterCacheName(comic))
        if (comic.chapterName.isNotBlank()) add(safeCacheFileName(comic.chapterName))
        // The old single-chapter layout used the comic name or "单篇". Do not
        // use these shared names for multi-chapter rows, otherwise every row
        // could resolve to the same legacy directory.
        if (comic.groupId == 0 || comic.groupId == comic.id) {
            add(safeCacheFileName(comic.name))
            add("单篇")
        }
    }.filter { it.isNotBlank() }.distinct()
    if (!isDocumentCachePath(root)) {
        return names.asSequence()
            .map { File(root, it) }
            .firstOrNull { it.isDirectory && listComicImageFiles(it).isNotEmpty() }
            ?.absolutePath
    }
    return runCatching {
        val parent = Uri.parse(root)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR && name in names) {
                    val path = DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0)).toString()
                    if (listComicImageEntries(context, path).isNotEmpty()) return@use path
                }
            }
            null
        }
    }.getOrNull()
}

fun getOrCreateCacheFile(
    context: Context,
    directoryPath: String,
    name: String,
    mimeType: String,
): String {
    if (!isDocumentCachePath(directoryPath)) return File(directoryPath, name).absolutePath
    return requireNotNull(findOrCreateCacheDocument(context, Uri.parse(directoryPath), name, mimeType)).toString()
}

fun getComicCoverDownloadPath(context: Context, comic: DownloadComic): String =
    getOrCreateCacheFile(context, getComicDownloadRootPath(context, comic), "cover.webp", "image/webp")

fun openCacheOutputStream(context: Context, path: String): OutputStream =
    if (isDocumentCachePath(path)) {
        requireNotNull(context.contentResolver.openOutputStream(Uri.parse(path), "wt"))
    } else {
        File(path).also { it.parentFile?.mkdirs() }.outputStream()
    }

fun cachePathExists(context: Context, path: String): Boolean = if (isDocumentCachePath(path)) {
    runCatching {
        context.contentResolver.query(
            Uri.parse(path),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null,
        )?.use { it.moveToFirst() } == true
    }.getOrDefault(false)
} else File(path).exists()

fun cachePathLength(context: Context, path: String): Long = if (isDocumentCachePath(path)) {
    runCatching {
        context.contentResolver.query(
            Uri.parse(path),
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null, null, null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)
} else File(path).length()

/** Some SAF providers do not expose COLUMN_SIZE even for non-empty files. */
fun cachePathHasContent(context: Context, path: String): Boolean {
    if (path.isBlank()) return false
    if (!isDocumentCachePath(path)) return File(path).isFile && File(path).length() > 0L
    return runCatching {
        context.contentResolver.openInputStream(Uri.parse(path))?.use { it.read() >= 0 } == true
    }.getOrDefault(false)
}

fun cachePathSize(context: Context, path: String): Long {
    if (!isDocumentCachePath(path)) {
        val file = File(path)
        return if (file.isDirectory) {
            file.walkTopDown().filter(File::isFile).sumOf(File::length)
        } else {
            file.length()
        }
    }
    return runCatching { documentPathSize(context, Uri.parse(path)) }.getOrDefault(0L)
}

data class CacheImageEntry(
    val name: String,
    val path: String,
)

fun listComicImagePaths(context: Context, directoryPath: String): List<String> =
    listComicImageEntries(context, directoryPath).map(CacheImageEntry::path)

fun listComicImageEntries(context: Context, directoryPath: String): List<CacheImageEntry> {
    if (!isDocumentCachePath(directoryPath)) {
        return runCatching {
            listComicImageFiles(File(directoryPath)).map { CacheImageEntry(it.name, it.absolutePath) }
        }.getOrDefault(emptyList())
    }
    return runCatching {
        val parent = Uri.parse(directoryPath)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val result = mutableListOf<CacheImageEntry>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (name.substringAfterLast('.', "").lowercase() in setOf("webp", "jpg", "jpeg", "png")) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0)).toString()
                    result += CacheImageEntry(name, uri)
                }
            }
        }
        result.sortedWith(
            compareBy<CacheImageEntry> { it.name.substringBeforeLast('.').toIntOrNull() ?: Int.MAX_VALUE }
                .thenBy { it.name }
        )
    }.getOrDefault(emptyList())
}

fun getCacheParentPath(path: String): String? = runCatching {
    if (!isDocumentCachePath(path)) {
        File(path).parentFile?.absolutePath
    } else {
        val uri = Uri.parse(path)
        val documentId = DocumentsContract.getDocumentId(uri)
        documentId.substringBeforeLast('/', "").takeIf(String::isNotBlank)
            ?.let { DocumentsContract.buildDocumentUriUsingTree(uri, it).toString() }
    }
}.getOrNull()

fun findCacheChildPath(context: Context, parentPath: String, name: String): String? {
    if (!isDocumentCachePath(parentPath)) {
        return File(parentPath, name).takeIf(File::exists)?.absolutePath
    }
    return runCatching {
        val parent = Uri.parse(parentPath)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0)).toString()
                }
            }
            null
        }
    }.getOrNull()
}

fun openCacheInputStream(context: Context, path: String) =
    if (isDocumentCachePath(path)) context.contentResolver.openInputStream(Uri.parse(path)) else File(path).inputStream()

fun writeDocumentComicCacheConfig(
    context: Context,
    comic: DownloadComic,
    chapters: List<DownloadComic>,
    gson: Gson = Gson(),
) {
    val rootPath = getComicDownloadRootPath(context, comic)
    val coverPath = getComicCoverDownloadPath(context, comic)
    val config = buildComicCacheConfig(comic, chapters, rootPath, coverPath) { path ->
        listComicImageEntries(context, path).map(CacheImageEntry::name)
    }
    val configPath = getOrCreateCacheFile(context, rootPath, "config.json", "application/json")
    openCacheOutputStream(context, configPath).bufferedWriter(Charsets.UTF_8).use {
        it.write(gson.toJson(config))
    }
}

fun deleteCachePath(context: Context, path: String): Boolean = if (isDocumentCachePath(path)) {
    runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(path)) }.getOrDefault(false)
} else {
    File(path).let { if (it.isDirectory) it.deleteRecursively() else it.delete() }
}

/**
 * 删除漫画根目录（含 config.json、cover 与任何残留章节目录）。
 * 取消缓存后若不清理根目录，它会成为下一次下载时 SAF 重名改名的碰撞源
 * （产生 漫画/漫画1/漫画2 这类重复目录），因此整组删除时应一并移除。
 */
fun deleteComicRoot(context: Context, rootPath: String): Boolean {
    if (rootPath.isBlank()) return false
    if (!isDocumentCachePath(rootPath)) {
        val dir = File(rootPath)
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }
    return runCatching { deleteDocumentTree(context, Uri.parse(rootPath)) }.getOrDefault(false)
}

private fun deleteDocumentTree(context: Context, uri: Uri): Boolean {
    val resolver = context.contentResolver
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
    // 先收集再删除，避免游标在删除过程中失效
    val childUris = mutableListOf<Uri>()
    resolver.query(
        children,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        ),
        null, null, null,
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            val mime = cursor.getString(1)
            childUris += DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0)).also {
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    deleteDocumentTree(context, it)
                }
            }
        }
    }
    childUris.forEach { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
    return DocumentsContract.deleteDocument(resolver, uri)
}

fun findOrCreateCacheDocument(context: Context, parent: Uri, name: String, mimeType: String): Uri? {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
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
            if (cursor.getString(1) == name) {
                return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
            }
        }
    }
    val created = DocumentsContract.createDocument(context.contentResolver, parent, mimeType, name) ?: return null
    // SAF 提供方在重名时会把新目录改名为 "name1"。若创建结果的名字与期望不符，说明同名
    // 目录已残留（通常是取消缓存后留下的半空目录），此时接管那个持有数据的兄弟目录，
    // 而不是静默接受改名、让后续封面/章节/config 散落到新目录里。
    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR && displayNameOf(context, created) != name) {
        takeOverRenamedSibling(context, parent, name)?.let { return it }
    }
    return created
}

private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null, null, null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()

/**
 * 在 [parent] 下查找被系统改名成 "name+数字" 的目录变体，接管其中数据最完整的一个。
 * 数字后缀越小代表越早创建；多个都完整时接管最早的那个。
 */
private fun takeOverRenamedSibling(context: Context, parent: Uri, name: String): Uri? {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
    val candidates = mutableListOf<Uri>()
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
            val displayName = cursor.getString(1)
            if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR && isRenamedVariant(name, displayName)) {
                candidates += DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
            }
        }
    }
    if (candidates.isEmpty()) return null
    return candidates
        .sortedBy { displayNameOf(context, it).orEmpty().removePrefix(name).toIntOrNull() ?: Int.MAX_VALUE }
        .firstOrNull { listComicImageEntries(context, it.toString()).isNotEmpty() }
        ?: candidates.firstOrNull()
}

private fun isRenamedVariant(base: String, displayName: String): Boolean {
    if (displayName == base) return true
    if (!displayName.startsWith(base)) return false
    val suffix = displayName.substring(base.length)
    return suffix.isNotEmpty() && suffix.all(Char::isDigit)
}

private fun documentPathSize(context: Context, uri: Uri): Long {
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )
    val row = context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) null else Triple(
            cursor.getString(0),
            cursor.getString(1),
            if (cursor.isNull(2)) 0L else cursor.getLong(2),
        )
    } ?: return 0L
    if (row.second != DocumentsContract.Document.MIME_TYPE_DIR) return row.third

    val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, row.first)
    return context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
        var total = 0L
        while (cursor.moveToNext()) {
            val childId = cursor.getString(0)
            val mimeType = cursor.getString(1)
            total += if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                documentPathSize(
                    context,
                    DocumentsContract.buildDocumentUriUsingTree(uri, childId),
                )
            } else if (cursor.isNull(2)) {
                0L
            } else {
                cursor.getLong(2)
            }
        }
        total
    } ?: 0L
}
