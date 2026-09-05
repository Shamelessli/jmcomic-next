package com.par9uet.jm.cache

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.par9uet.jm.utils.logError
import com.par9uet.jm.utils.tryCreateDir
import java.io.File

private const val DOWNLOAD_TREE_PREFERENCES = "download_storage"
private const val DOWNLOAD_TREE_URI_KEY = "tree_uri"
private const val CACHE_MIGRATION_RUNNING_KEY = "migration_running"

/** 缓存目录授权失效时的统一恢复指引文案。 */
const val CACHE_ACCESS_DENIED_MESSAGE =
    "缓存目录访问授权已失效（系统可能回收了目录权限），请在 设置 → 阅读与缓存 → 缓存路径 中重新选择一次该文件夹。本地文件未丢失。"

fun getCommonCacheDir(context: Context) = tryCreateDir(File(context.cacheDir, "common"))
fun getCommonPicDecodeCacheDir(context: Context, comicId: Int) = tryCreateDir(File(context.cacheDir, "pic_decode/$comicId"))
fun getDownloadDir(context: Context) = tryCreateDir(File(context.cacheDir, "download"))

/** 本进程已尝试过重申持久化授权的树 URI，避免每次读取都重复 take。 */
private val persistedTreeUris = HashSet<String>()

fun getDownloadTreeUri(context: Context): Uri? =
    context.getSharedPreferences(DOWNLOAD_TREE_PREFERENCES, Context.MODE_PRIVATE)
        .getString(DOWNLOAD_TREE_URI_KEY, null)
        ?.takeIf { it.isNotBlank() }
        ?.let { uriString ->
            val uri = Uri.parse(uriString)
            // 每个进程重申一次持久化授权：授权仍在时是无害刷新（防止达到系统
            // 持久化授权上限后被 LRU 驱逐）；已被系统回收时 take 会失败，
            // 后续访问由 isSafPathAccessible 探测并给出恢复指引。
            synchronized(persistedTreeUris) {
                if (persistedTreeUris.add(uriString)) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }.onFailure {
                        logError("CacheConfig", "SAF 目录持久化授权不可用：${it.message}")
                    }
                }
            }
            uri
        }

fun setDownloadTreeUri(context: Context, uri: String) {
    context.getSharedPreferences(DOWNLOAD_TREE_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString(DOWNLOAD_TREE_URI_KEY, uri)
        .apply()
    // 任何写入树 URI 的路径都顺手重申持久化授权，保证"换了目录也能在重启后访问"
    if (uri.isNotBlank()) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }
}

/**
 * SAF 路径当前是否可访问。仅在抛 SecurityException（目录授权已被系统回收）时
 * 返回 false；目录不存在等其它情况返回 true，交由上层按真实缺失处理，
 * 避免把"无权访问"误报成"内容损坏/未找到"。
 */
fun isSafPathAccessible(context: Context, path: String): Boolean {
    if (!isDocumentCachePath(path)) return true
    return try {
        val uri = Uri.parse(path)
        val documentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri),
            )
        }.getOrNull() ?: uri
        context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null,
        )?.close()
        true
    } catch (e: SecurityException) {
        false
    } catch (e: Exception) {
        // 非授权类异常（如提供方瞬时异常）不误判为授权失效
        true
    }
}

fun isCacheMigrationRunning(context: Context): Boolean =
    context.getSharedPreferences(DOWNLOAD_TREE_PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(CACHE_MIGRATION_RUNNING_KEY, false)

fun setCacheMigrationRunning(context: Context, running: Boolean) {
    context.getSharedPreferences(DOWNLOAD_TREE_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(CACHE_MIGRATION_RUNNING_KEY, running)
        .apply()
}
