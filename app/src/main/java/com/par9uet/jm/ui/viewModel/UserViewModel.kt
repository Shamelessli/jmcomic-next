package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.par9uet.jm.data.models.CollectComicOrderFilter
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.SignInData
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.repository.UserRepository
import com.par9uet.jm.retrofit.model.LoginResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.SignInDataResponse
import com.par9uet.jm.retrofit.model.SignInResponse
import com.par9uet.jm.store.DownloadManager
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.ui.models.CommonUIState
import com.par9uet.jm.ui.pagingSource.CollectComicPagingSource
import com.par9uet.jm.ui.pagingSource.HistoryComicPagingSource
import com.par9uet.jm.ui.pagingSource.HistoryCommentPagingSource
import com.par9uet.jm.utils.filterBlockedTags
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectComicLocalFilter(
    val searchText: String = "",
    val selectedTags: Set<String> = emptySet(),
    val selectedAuthors: Set<String> = emptySet(),
    val tagLogic: TagFilterLogic = TagFilterLogic.AND
)

data class CollectEditState(
    val editing: Boolean = false,
    val selectedComicIds: Set<Int> = emptySet()
)

data class HistoryEditState(
    val editing: Boolean = false,
    val selectedComicIds: Set<Int> = emptySet()
)

private data class CollectPagerKey(
    val order: CollectComicOrderFilter,
    val blockedTagList: List<String>,
    val filter: CollectComicLocalFilter,
    val folderId: Int
)

class UserViewModel(
    private val userManager: UserManager,
    private val userRepository: UserRepository,
    private val toastManager: ToastManager,
    private val localSettingManager: LocalSettingManager,
    private val comicRepository: ComicRepository,
    private val downloadManager: DownloadManager,
) : ViewModel() {
    private val _loginState = MutableStateFlow(CommonUIState(data = null))
    val loginState = _loginState.asStateFlow()
    fun login(username: String, password: String) {
        viewModelScope.launch {
            _loginState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            when (val data = userRepository.login(username, password)) {
                is NetWorkResult.Error -> {
                    _loginState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success<LoginResponse> -> {
                    userManager.updateUser(
                        data.data.toUser(
                            password = password
                        )
                    )
                }
            }
            _loginState.update {
                it.copy(
                    isLoading = false
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            userManager.clearUser()
        }
    }

    private val _collectComicOrder = MutableStateFlow(CollectComicOrderFilter.COLLECT_TIME)
    val collectComicOrder = _collectComicOrder.asStateFlow()
    private val _collectComicFilter = MutableStateFlow(CollectComicLocalFilter())
    val collectComicFilter = _collectComicFilter.asStateFlow()
    private val _collectTagCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val collectTagCounts = _collectTagCounts.asStateFlow()
    private val _collectAuthorCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val collectAuthorCounts = _collectAuthorCounts.asStateFlow()
    private val _selectedFolderId = MutableStateFlow(0)
    val selectedFolderId = _selectedFolderId.asStateFlow()
    private val _folderList = MutableStateFlow<Map<String, String>>(emptyMap())
    val folderList = _folderList.asStateFlow()
    private val _collectEditState = MutableStateFlow(CollectEditState())
    val collectEditState = _collectEditState.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val collectComicPager = combine(
        _collectComicOrder,
        localSettingManager.localSettingState,
        _collectComicFilter,
        _selectedFolderId
    ) { order, localSetting, filter, folderId ->
        CollectPagerKey(order, localSetting.blockedTagList, filter, folderId)
    }.flatMapLatest { key ->
        Pager(
            config = PagingConfig(pageSize = 20, prefetchDistance = 6, initialLoadSize = 20),
            pagingSourceFactory = {
                CollectComicPagingSource(
                    userRepository,
                    key.order,
                    key.blockedTagList,
                    key.filter.searchText,
                    key.filter.selectedTags,
                    key.filter.selectedAuthors,
                    key.folderId,
                    key.filter.tagLogic
                )
            }
        ).flow
    }.cachedIn(viewModelScope)

    fun changeCollectComicOrder(order: CollectComicOrderFilter) {
        _collectComicOrder.update {
            order
        }
        refreshCollectTagCounts()
    }

    fun updateCollectSearchText(value: String) {
        _collectComicFilter.update { it.copy(searchText = value) }
    }

    fun updateCollectSelectedTags(tags: Set<String>) {
        _collectComicFilter.update { it.copy(selectedTags = tags) }
    }

    fun updateCollectTagLogic(logic: TagFilterLogic) {
        _collectComicFilter.update { it.copy(tagLogic = logic) }
    }

    fun updateCollectSelectedAuthors(authors: Set<String>) {
        _collectComicFilter.update { it.copy(selectedAuthors = authors) }
    }

    fun changeFolder(folderId: Int) {
        _selectedFolderId.update { folderId }
        refreshCollectTagCounts()
    }

    fun enterCollectEdit(comicId: Int) {
        _collectEditState.update {
            it.copy(editing = true, selectedComicIds = it.selectedComicIds + comicId)
        }
    }

    fun toggleCollectSelected(comicId: Int) {
        _collectEditState.update {
            val selected = if (comicId in it.selectedComicIds) {
                it.selectedComicIds - comicId
            } else {
                it.selectedComicIds + comicId
            }
            it.copy(editing = selected.isNotEmpty(), selectedComicIds = selected)
        }
    }

    fun clearCollectSelection() {
        _collectEditState.update { CollectEditState() }
    }

    fun deleteCollectedComics(comics: List<Comic>) {
        if (comics.isEmpty()) return
        viewModelScope.launch {
            var success = 0
            var fail = 0
            comics.forEach { comic ->
                when (comicRepository.unCollectComic(comic.id)) {
                    is NetWorkResult.Error -> fail++
                    is NetWorkResult.Success -> success++
                }
            }
            toastManager.showAsync(
                if (fail == 0) "已取消收藏 $success 部漫画"
                else "成功 $success 部，失败 $fail 部"
            )
            clearCollectSelection()
        }
    }

    fun cacheCollectedComics(comics: List<Comic>) {
        if (comics.isEmpty()) return
        downloadManager.downloadComics(comics)
        clearCollectSelection()
    }

    fun moveCollectedToFolder(comics: List<Comic>, folderId: String) {
        if (comics.isEmpty()) return
        viewModelScope.launch {
            var success = 0
            var fail = 0
            comics.forEach { comic ->
                when (comicRepository.moveComicToFolder(comic.id, folderId)) {
                    is NetWorkResult.Error -> fail++
                    is NetWorkResult.Success -> success++
                }
            }
            toastManager.showAsync(
                if (fail == 0) "已移动 $success 部漫画"
                else "成功 $success 部，失败 $fail 部"
            )
            clearCollectSelection()
        }
    }

    fun refreshFolderList() {
        viewModelScope.launch {
            val order = _collectComicOrder.value
            when (val data = userRepository.getCollectComicList(1, order, 0)) {
                is NetWorkResult.Error -> {
                    // 文件夹列表为可选功能，错误时忽略
                }

                is NetWorkResult.Success -> {
                    _folderList.value = data.data.folder_list ?: emptyMap()
                }
            }
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            when (val data = comicRepository.createFavoriteFolder(name)) {
                is NetWorkResult.Error -> toastManager.showAsync(data.message)
                is NetWorkResult.Success -> {
                    refreshFolderList()
                    toastManager.showAsync("创建成功")
                }
            }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            when (val data = comicRepository.deleteFavoriteFolder(folderId)) {
                is NetWorkResult.Error -> toastManager.showAsync(data.message)
                is NetWorkResult.Success -> {
                    _selectedFolderId.update { 0 }
                    refreshFolderList()
                    toastManager.showAsync("删除成功")
                }
            }
        }
    }

    fun renameFolder(folderId: String, newName: String) {
        viewModelScope.launch {
            when (val data = comicRepository.renameFavoriteFolder(folderId, newName)) {
                is NetWorkResult.Error -> toastManager.showAsync(data.message)
                is NetWorkResult.Success -> {
                    refreshFolderList()
                    toastManager.showAsync("重命名成功")
                }
            }
        }
    }

    fun refreshCollectTagCounts() {
        viewModelScope.launch {
            val blockedTagList = localSettingManager.localSettingState.value.blockedTagList
            val order = _collectComicOrder.value
            val folderId = _selectedFolderId.value
            val tagCounts = mutableMapOf<String, Int>()
            val authorCounts = mutableMapOf<String, Int>()
            var page = 1
            var loaded = 0
            var total = Int.MAX_VALUE
            while (loaded < total && page <= 100) {
                when (val data = userRepository.getCollectComicList(page, order, folderId)) {
                    is NetWorkResult.Error -> {
                        toastManager.showAsync(data.message)
                        return@launch
                    }

                    is NetWorkResult.Success -> {
                        val comics = data.data.toComicList().filterBlockedTags(blockedTagList)
                        comics.flatMap { it.tagList }.forEach { tag ->
                            tagCounts[tag] = (tagCounts[tag] ?: 0) + 1
                        }
                        comics.flatMap { it.authorList }.forEach { author ->
                            authorCounts[author] = (authorCounts[author] ?: 0) + 1
                        }
                        total = data.data.total
                        loaded += data.data.list.size
                        if (data.data.list.isEmpty()) break
                        page += 1
                    }
                }
            }
            _collectTagCounts.value = tagCounts.toSortedMap()
            _collectAuthorCounts.value = authorCounts.toSortedMap()
        }
    }

    private val _historyRefreshVersion = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val historyComicPager = combine(
        localSettingManager.localSettingState,
        _historyRefreshVersion
    ) { localSetting, _ -> localSetting }
        .flatMapLatest { localSetting ->
        Pager(
            config = PagingConfig(pageSize = 20, prefetchDistance = 6, initialLoadSize = 20),
            pagingSourceFactory = {
                HistoryComicPagingSource(
                    userRepository,
                    localSetting.blockedTagList
                )
            }
        ).flow
    }.cachedIn(viewModelScope)

    private val _historyEditState = MutableStateFlow(HistoryEditState())
    val historyEditState = _historyEditState.asStateFlow()

    fun enterHistoryEdit(comicId: Int) {
        _historyEditState.update {
            it.copy(editing = true, selectedComicIds = it.selectedComicIds + comicId)
        }
    }

    fun toggleHistorySelected(comicId: Int) {
        _historyEditState.update {
            val selected = if (comicId in it.selectedComicIds) {
                it.selectedComicIds - comicId
            } else {
                it.selectedComicIds + comicId
            }
            it.copy(editing = selected.isNotEmpty(), selectedComicIds = selected)
        }
    }

    fun clearHistorySelection() {
        _historyEditState.update { HistoryEditState() }
    }

    fun deleteHistoryComics(comics: List<Comic>) {
        if (comics.isEmpty()) return
        log("UserViewModel", "deleteHistoryComics: 开始删除 ${comics.size} 条历史记录, ids=${comics.map { it.id }}")
        viewModelScope.launch {
            var success = 0
            var fail = 0
            val errors = mutableListOf<String>()
            comics.forEach { comic ->
                log("UserViewModel", "deleteHistoryComics: 正在删除 comic.id=${comic.id}")
                when (val result = userRepository.deleteHistoryComic(comic.id)) {
                    is NetWorkResult.Error -> {
                        logError(
                            "UserViewModel",
                            "deleteHistoryComics: 删除 comic.id=${comic.id} 失败: ${result.message}"
                        )
                        errors += result.message
                        fail++
                    }
                    is NetWorkResult.Success -> success++
                }
            }
            log("UserViewModel", "deleteHistoryComics: 完成, 成功=$success, 失败=$fail")
            val message = when {
                fail == 0 -> "已删除 $success 条历史记录"
                success == 0 -> errors.firstOrNull() ?: "删除失败"
                else -> "成功 $success 条，失败 $fail 条：${errors.firstOrNull().orEmpty()}"
            }
            toastManager.showAsync(message)
            if (success > 0) {
                _historyRefreshVersion.update { it + 1 }
            }
            clearHistorySelection()
        }
    }

    fun cacheHistoryComics(comics: List<Comic>) {
        if (comics.isEmpty()) return
        downloadManager.downloadComics(comics)
        clearHistorySelection()
    }

    val historyCommentPager = Pager(
        config = PagingConfig(pageSize = 20, prefetchDistance = 6, initialLoadSize = 20),
        pagingSourceFactory = {
            HistoryCommentPagingSource(
                userRepository,
                userManager.userState.value.data?.id ?: 0
            )
        }
    ).flow.cachedIn(viewModelScope)

    private val _signInDataState = MutableStateFlow(
        CommonUIState<SignInData>(
            isLoading = true
        )
    )
    val signDataState = _signInDataState.asStateFlow()
    fun getSignInData() {
        viewModelScope.launch {
            _signInDataState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            when (val data = userRepository.getSignData(userManager.userState.value.data?.id ?: 0)) {
                is NetWorkResult.Error -> {
                    _signInDataState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success<SignInDataResponse> -> {
                    _signInDataState.update {
                        it.copy(
                            data = data.data.toSignData()
                        )
                    }
                }
            }
            _signInDataState.update {
                it.copy(
                    isLoading = false
                )
            }
        }
    }

    private val _signInState = MutableStateFlow(CommonUIState<String>())
    val signInState = _signInState.asStateFlow()
    fun signIn() {
        viewModelScope.launch {
            _signInState.update {
                it.copy(
                    isLoading = true,
                    isError = false,
                    errorMsg = ""
                )
            }
            when (val data = userRepository.signIn(
                userManager.userState.value.data?.id ?: 0,
                _signInDataState.value.data?.dailyId ?: 0
            )) {
                is NetWorkResult.Error -> {
                    _signInState.update {
                        it.copy(
                            isError = true,
                            errorMsg = data.message
                        )
                    }
                }

                is NetWorkResult.Success<SignInResponse> -> {
                    toastManager.showAsync(data.data.msg)
                    getSignInData()
                    _signInState.update {
                        it.copy(
                            data = data.data.msg
                        )
                    }
                }
            }
            _signInState.update {
                it.copy(
                    isLoading = false
                )
            }
        }
    }
}
