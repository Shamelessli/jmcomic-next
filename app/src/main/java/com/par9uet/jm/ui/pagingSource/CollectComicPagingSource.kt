package com.par9uet.jm.ui.pagingSource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.par9uet.jm.data.models.CollectComicOrderFilter
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.repository.UserRepository
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.UserCollectComicListResponse
import com.par9uet.jm.utils.filterBlockedTags

class CollectComicPagingSource(
    private val userRepository: UserRepository,
    private val order: CollectComicOrderFilter,
    private val blockedTagList: List<String> = listOf(),
    private val searchText: String = "",
    private val selectedTags: Set<String> = emptySet(),
    private val selectedAuthors: Set<String> = emptySet(),
    private val folderId: Int = 0,
    private val tagLogic: TagFilterLogic = TagFilterLogic.AND,
) : PagingSource<Int, Comic>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Comic> {
        val currentPage = params.key ?: 1
        return when (val data =
            userRepository.getCollectComicList(currentPage, order, folderId)) {
            is NetWorkResult.Error -> {
                LoadResult.Error(Exception(data.message))
            }

            is NetWorkResult.Success<UserCollectComicListResponse> -> {
                val query = searchText.trim()
                val lowerSelectedTags = selectedTags.map { it.lowercase().trim() }.filter { it.isNotBlank() }.toSet()
                val lowerSelectedAuthors = selectedAuthors.map { it.lowercase().trim() }.filter { it.isNotBlank() }.toSet()
                val list = data.data.toComicList()
                    .filterBlockedTags(blockedTagList)
                    .filter { comic ->
                        // 顶部搜索支持按漫画名、作者或标签匹配
                        query.isBlank() ||
                            comic.name.contains(query, ignoreCase = true) ||
                            comic.authorList.any { it.contains(query, ignoreCase = true) } ||
                            comic.tagList.any { it.contains(query, ignoreCase = true) }
                    }
                    .filter { comic ->
                        if (lowerSelectedTags.isEmpty()) return@filter true
                        val comicAllTags = (comic.tagList + comic.roleList + comic.workList)
                            .map { it.lowercase().trim() }
                            .filter { it.isNotBlank() }
                            .toSet()
                        when (tagLogic) {
                            TagFilterLogic.AND -> lowerSelectedTags.all { it in comicAllTags }
                            TagFilterLogic.OR -> lowerSelectedTags.any { it in comicAllTags }
                            TagFilterLogic.NOT -> lowerSelectedTags.none { it in comicAllTags }
                        }
                    }
                    .filter { comic ->
                        if (lowerSelectedAuthors.isEmpty()) return@filter true
                        comic.authorList.any { author ->
                            val lowerAuthor = author.lowercase().trim()
                            lowerAuthor in lowerSelectedAuthors
                        }
                    }
                val total = data.data.total
                val isLastPage = currentPage >= (total + params.loadSize - 1) / params.loadSize
                LoadResult.Page(
                    data = list,
                    prevKey = if (currentPage == 1) null else currentPage - 1,
                    nextKey = if (isLastPage) null else currentPage + 1
                )
            }
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Comic>): Int? = null
}
