package com.par9uet.jm.repository

import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.retrofit.model.CollectComicResponse
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.ComicListResponse
import com.par9uet.jm.retrofit.model.ComicPicListResponse
import com.par9uet.jm.retrofit.model.CommentComicResponse
import com.par9uet.jm.retrofit.model.CommentListResponse
import com.par9uet.jm.retrofit.model.HomeSwiperComicListItemResponse
import com.par9uet.jm.retrofit.model.LikeComicResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.WeekRecommendComicResponse
import com.par9uet.jm.retrofit.model.WeekResponse

/** 多源取图的结果分类：用于区分"该重试的瞬时失败"与"资源确实不存在"。 */
sealed class ImageFetchResult {
    class Success(val bytes: ByteArray) : ImageFetchResult()

    /** 所有来源都明确返回 404/410：页面资源不存在，重试无意义。 */
    object NotFound : ImageFetchResult()

    /** 瞬时失败（网络抖动、非 404 的 HTTP 错误、超时等），可换源/重试。 */
    class Failed(val reason: String? = null) : ImageFetchResult()
}

interface ComicRepository {
    suspend fun getComicDetail(id: Int): NetWorkResult<ComicDetailResponse>
    suspend fun likeComic(id: Int): NetWorkResult<LikeComicResponse>
    suspend fun collectComic(id: Int): NetWorkResult<CollectComicResponse>
    suspend fun unCollectComic(id: Int): NetWorkResult<CollectComicResponse>
    suspend fun getHomeSwiperComicList(): NetWorkResult<List<HomeSwiperComicListItemResponse>>
    suspend fun getComicPicList(id: Int, shunt: String): NetWorkResult<ComicPicListResponse>
    suspend fun downloadImageBytes(comicId: Int, imageIndex: Int): ByteArray?

    /**
     * 依次尝试多个图片 URL（内嵌 API 列表、网络列表、封面域名规则等），
     * 返回第一个成功下载的图片字节。用于图片源临时不可用时自动换源重试。
     * 所有来源都返回 404/410 时给出 [ImageFetchResult.NotFound]，调用方可快速失败。
     */
    suspend fun fetchImageBytesForSources(comicId: Int, imageIndex: Int, sources: List<String>): ImageFetchResult
    suspend fun getComicList(
        page: Int,
        order: ComicSearchOrderFilter,
        searchContent: String,
    ): NetWorkResult<ComicListResponse>

    suspend fun getWeekData(): NetWorkResult<WeekResponse>
    suspend fun getWeekRecommendComicList(
        page: Int,
        categoryId: String,
        typeId: String,
    ): NetWorkResult<WeekRecommendComicResponse>

    suspend fun getCommentList(
        page: Int,
        comicId: Int,
    ): NetWorkResult<CommentListResponse>

    suspend fun comment(
        content: String,
        comicId: Int,
        commentId: Int?
    ): NetWorkResult<CommentComicResponse>

    suspend fun likeComment(commentId: Int): NetWorkResult<CommentComicResponse>

    suspend fun createFavoriteFolder(name: String): NetWorkResult<Unit>
    suspend fun deleteFavoriteFolder(folderId: String): NetWorkResult<Unit>
    suspend fun renameFavoriteFolder(folderId: String, newName: String): NetWorkResult<Unit>
    suspend fun moveComicToFolder(comicId: Int, folderId: String): NetWorkResult<Unit>

    /**
     * 通过 JMComic 内置 API 按标签名搜索，返回该标签下的漫画 ID 集合。
     * 用于标签排除：获取所有排除标签下的漫画 ID 并集，从搜索结果中过滤掉。
     *
     * @param tagName 标签名（如 "催眠"）
     * @param maxPages 最多扫描的页数（每页约 20 条），默认 5 页
     * @return 该标签下的漫画 ID 集合；标签不存在或网络错误时返回空集合
     */
    suspend fun getComicIdsByTag(tagName: String, maxPages: Int = 5): Set<Int>
}