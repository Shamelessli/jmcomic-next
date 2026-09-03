package com.par9uet.jm.cache

import android.content.Context

/**
 * 漫画根目录索引：groupId → 真实根目录路径（本地绝对路径或 SAF 文档 URI）。
 *
 * 背景：SAF 提供方在 createDocument 重名时会把新目录自动改名（漫画 → 漫画1）。
 * 目录名由漫画名推导、且不在 Room 持久化，导致反复"缓存→取消→缓存"时同一本书的
 * 封面/章节/config 散落到多个兄弟根目录。这里把"实际落定的根目录"按漫画组登记下来，
 * [getComicDownloadRootPath] 优先命中登记值，使接管/补页后的所有定位都收敛到同一个根，
 * 从源头止住重复目录的产生。
 */
object CacheRootIndex {
    private const val PREFS_NAME = "download_root_index"
    private const val KEY_PREFIX = "root_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun groupIdFor(comicId: Int, groupId: Int): Int = groupId.takeIf { it != 0 } ?: comicId

    fun get(context: Context, groupId: Int): String? =
        prefs(context).getString(KEY_PREFIX + groupId, null)?.takeIf { it.isNotBlank() }

    fun put(context: Context, groupId: Int, rootPath: String) {
        if (rootPath.isBlank()) return
        prefs(context).edit().putString(KEY_PREFIX + groupId, rootPath).apply()
    }

    fun remove(context: Context, groupId: Int) {
        prefs(context).edit().remove(KEY_PREFIX + groupId).apply()
    }

    /** 缓存迁移切换存储目录后，旧根目录路径全部失效，清空索引让后续在新目录下重建。 */
    fun clearAll(context: Context) {
        val keys = prefs(context).all.keys.filter { it.startsWith(KEY_PREFIX) }
        if (keys.isEmpty()) return
        prefs(context).edit().apply {
            keys.forEach { remove(it) }
            apply()
        }
    }
}
