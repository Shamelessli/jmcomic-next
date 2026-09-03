package com.par9uet.jm.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Normalizes the API's `image` field into loadable cover URLs.
 *
 * JMComic's API commonly returns a relative path such as
 * `/media/albums/<id>_3x4.jpg`; the path is not itself a URL. The official
 * client keeps image domains separately in its constants, so every caller must
 * resolve both pieces before requesting the image. These values mirror the
 * upstream list but are local to avoid initializing the Java 9 collection APIs
 * used by the upstream class on Android 6.
 */
object ComicCoverUrlResolver {
    private val upstreamImageDomains = listOf(
        "cdn-msp.jmapiproxy1.cc",
        "cdn-msp.jmapiproxy2.cc",
        "cdn-msp2.jmapiproxy2.cc",
        "cdn-msp3.jmapiproxy2.cc",
        "cdn-msp.jmapinodeudzn.net",
        "cdn-msp3.jmapinodeudzn.net",
    )

    fun resolve(
        comicId: Int,
        apiImage: String,
        configuredImageHost: String,
    ): List<String> {
        val source = apiImage.trim()
        val imageHosts = buildList {
            normalizeHost(configuredImageHost)?.let(::add)
            upstreamImageDomains
                .mapNotNull(::normalizeHost)
                .forEach(::add)
        }.distinct()
        val relativeSource = source.takeIf { it.isNotBlank() && !isAbsoluteUrl(it) }
            ?.let(::normalizeRelativePath)

        return buildList {
            when {
                isAbsoluteUrl(source) -> add(source)
                source.startsWith("//") -> add("https:$source")
            }
            if (relativeSource != null) {
                imageHosts.forEach { host -> add(host + relativeSource) }
            }
            // The album endpoint does not always include `image`; retain the
            // canonical cover convention as a final fallback for that case.
            if (comicId > 0) {
                imageHosts.forEach { host -> add("$host/media/albums/${comicId}_3x4.jpg") }
            }
        }.distinct()
    }

    /**
     * 将 [source] 当作"path 完整、仅域名可变"的图片 URL，在配置图床 +
     * 上游图床域名上生成候选地址。用于页面图片在单个域名抽风时自动换源。
     * 仅当 source 是绝对 URL（或 // 开头的协议相对地址）时才有意义。
     */
    fun imageHostCandidates(
        source: String,
        configuredImageHost: String,
    ): List<String> {
        val trimmed = source.trim()
        if (trimmed.isBlank()) return emptyList()
        val hosts = buildList {
            normalizeHost(configuredImageHost)?.let(::add)
            upstreamImageDomains.mapNotNull(::normalizeHost).forEach(::add)
        }.distinct()
        if (hosts.isEmpty()) return listOf(trimmed)

        val absolute = when {
            isAbsoluteUrl(trimmed) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> return emptyList()
        }
        val parsed = absolute.toHttpUrlOrNull() ?: return listOf(trimmed)
        val host = parsed.host
        val pathAndQuery = absolute.substringAfter("//").substringAfter('/')

        return buildList {
            add(absolute)
            hosts.filterNot { normalizeHost(it)?.contains(host) == true }
                .forEach { candidateHost ->
                    add("${candidateHost.trimEnd('/')}/$pathAndQuery")
                }
        }.distinct()
    }

    private fun normalizeHost(value: String): String? {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        return if (isAbsoluteUrl(trimmed)) trimmed else "https://$trimmed"
    }

    private fun normalizeRelativePath(value: String): String =
        if (value.startsWith('/')) value else "/$value"

    private fun isAbsoluteUrl(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true)
}
