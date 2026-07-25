package com.par9uet.jm.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.storage.ComicReadHistory
import com.par9uet.jm.store.DownloadManager
import com.par9uet.jm.store.ReadHistoryManager
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.ui.components.ChapterMultiSelectDialog
import com.par9uet.jm.ui.components.ComicContentTag
import com.par9uet.jm.ui.components.ComicCoverImage
import com.par9uet.jm.ui.components.ComicRoleTag
import com.par9uet.jm.ui.components.ComicWorkTag
import com.par9uet.jm.ui.viewModel.ComicDetailViewModel
import com.par9uet.jm.utils.shimmer
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
private fun ComicInfoListItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AssistChip(
            border = null,
            modifier = Modifier
                .width(50.dp)
                .height(50.dp),
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            onClick = {},
            label = {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ComicDetailSkeleton() {
    val scrollState = rememberScrollState()
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .shimmer()
            )
            Column(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(36.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .shimmer()
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(34.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .shimmer()
                )
            }
        }
    }
}

@Composable
private fun ComicDetailErrorPage(
    errorMessage: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = "加载失败",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = errorMessage ?: "加载失败，请稍后重试",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onRetry) {
                Text("重试")
            }
            TextButton(onClick = onBack) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun ComicMetadataContent(
    comic: Comic,
    onTagSearch: (String) -> Unit,
) {
    Text(
        modifier = Modifier.padding(top = 10.dp),
        text = comic.name,
        style = MaterialTheme.typography.titleLarge,
        lineHeight = 1.5.em,
        fontWeight = FontWeight.Bold,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        comic.authorList.forEach {
            key(it) {
                Text(
                    modifier = Modifier.clickable(onClick = { onTagSearch(it) }),
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ComicInfoListItem(
            modifier = Modifier.weight(.5f),
            icon = Icons.Default.Favorite,
            label = "\u559c\u6b22",
            value = comic.likeCount.toString()
        )
        ComicInfoListItem(
            modifier = Modifier.weight(.5f),
            icon = Icons.Default.RemoveRedEye,
            label = "\u6d4f\u89c8",
            value = comic.readCount.toString()
        )
    }
    if (comic.tagList.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            comic.tagList.forEach {
                key(it) {
                    ComicContentTag(it)
                }
            }
        }
    }
    if (comic.roleList.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            comic.roleList.forEach {
                key(it) {
                    ComicRoleTag(it)
                }
            }
        }
    }
    if (comic.workList.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            comic.workList.forEach {
                key(it) {
                    ComicWorkTag(it)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicDetailScreen(
    id: Int,
    comicDetailViewModel: ComicDetailViewModel = koinActivityViewModel(),
    readHistoryManager: ReadHistoryManager = getKoin().get(),
    downloadManager: DownloadManager = getKoin().get(),
    userManager: UserManager = getKoin().get()
) {
    val mainNavController = LocalMainNavController.current
    val scrollState = rememberScrollState()
    val comicDetailState by comicDetailViewModel.comicDetailState.collectAsState()
    val readHistory by readHistoryManager.readHistoryState.collectAsState()
    val isLogin by userManager.isLoginState.collectAsState(false)
    var showDownloadChapterDialog by remember { mutableStateOf(false) }
    var selectedChapterIds by remember { mutableStateOf<Set<Int>>(emptySet()) }

    fun requireLogin(action: () -> Unit) {
        if (isLogin) action() else mainNavController.navigate("login")
    }

    fun searchTag(tag: String) {
        mainNavController.navigate("comicSearchResult/${Uri.encode(tag)}")
    }

    LaunchedEffect(id) {
        if (comicDetailState.data?.id != id) {
            comicDetailViewModel.getComicDetail(id)
        }
    }

    // Error state: no data and error occurred
    if (comicDetailState.isError && comicDetailState.data == null) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    ),
                    navigationIcon = {
                        IconButton(onClick = { mainNavController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "\u8fd4\u56de"
                            )
                        }
                    },
                    title = {}
                )
            }
        ) { innerPadding ->
            ComicDetailErrorPage(
                errorMessage = comicDetailState.errorMsg,
                onRetry = { comicDetailViewModel.getComicDetail(id) },
                onBack = { mainNavController.popBackStack() },
                modifier = Modifier.padding(innerPadding)
            )
        }
        return
    }

    // Loading skeleton
    if (comicDetailState.isLoading && comicDetailState.data == null) {
        ComicDetailSkeleton()
        return
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            val comicTitle = comicDetailState.data?.name ?: ""
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = { mainNavController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "\u8fd4\u56de"
                        )
                    }
                },
                title = {
                    Text(
                        text = comicTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            val comic = comicDetailState.data ?: return@Scaffold
            ComicDetailBottomBar(
                comic = comic,
                readHistoryManager = readHistoryManager,
                readHistory = readHistory,
                onLike = {
                    requireLogin {
                        if (!comic.isLike) comicDetailViewModel.likeComic(comic.id)
                    }
                },
                onCollect = {
                    requireLogin {
                        if (comic.isCollect) {
                            comicDetailViewModel.unCollect(comic.id)
                        } else if (comicDetailViewModel.shouldShowFolderPicker()) {
                            // 内置 API 模式：弹出收藏夹选择
                            comicDetailViewModel.refreshFolderList()
                            comicDetailViewModel.showFolderPicker()
                        } else {
                            // 网络 API 模式：直接收藏到默认夹
                            comicDetailViewModel.collect(comic.id)
                        }
                    }
                },
                onRelated = { mainNavController.navigate("comicRelate/${comic.id}") },
                onDownload = {
                    if (comic.comicChapterList.isEmpty()) {
                        downloadManager.downloadComic(comic)
                    } else {
                        selectedChapterIds = comic.comicChapterList.map { it.id }.toSet()
                        showDownloadChapterDialog = true
                    }
                },
                onRead = { targetId -> mainNavController.navigate("comicRead/$targetId") },
                onChapters = {
                    val currentChapterId = readHistoryManager.lastReadChapterId(comic, readHistory) ?: -1
                    mainNavController.navigate("comicChapter/${comic.id}?currentChapterId=$currentChapterId")
                }
            )
        }
    ) { innerPadding ->
        val comic = comicDetailState.data ?: return@Scaffold

        if (showDownloadChapterDialog) {
            ChapterMultiSelectDialog(
                title = "\u9009\u62e9\u7f13\u5b58\u7ae0\u8282",
                chapters = comic.comicChapterList,
                selectedChapterIds = selectedChapterIds,
                onSelectedChange = { selectedChapterIds = it },
                onDismiss = { showDownloadChapterDialog = false },
                confirmText = "\u5f00\u59cb\u7f13\u5b58",
                onConfirm = {
                    val selectedChapters = comic.comicChapterList.filter { it.id in selectedChapterIds }
                    downloadManager.downloadChapters(comic, selectedChapters)
                    showDownloadChapterDialog = false
                }
            )
        }

        PullToRefreshBox(
            isRefreshing = comicDetailState.isLoading,
            state = rememberPullToRefreshState(),
            onRefresh = { comicDetailViewModel.getComicDetail(id) },
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
            ) {
                val isTabletLayout = maxWidth >= 700.dp
                val viewportHeight = maxHeight
                if (isTabletLayout) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        ComicCoverImage(
                            comic = comic,
                            modifier = Modifier
                                .widthIn(max = 320.dp)
                                .weight(0.42f),
                            showIdChip = true
                        )
                        Column(
                            modifier = Modifier
                                .weight(0.58f)
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            ComicMetadataContent(comic, ::searchTag)
                            ComicCommentArea(
                                comicId = comic.id,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(viewportHeight)
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ComicCoverImage(comic = comic, showIdChip = true)
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            ComicMetadataContent(comic, ::searchTag)
                        }
                        ComicCommentArea(
                            comicId = comic.id,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(viewportHeight)
                        )
                    }
                }
            }
        }

        // 收藏夹选择弹窗（仅内置 API 模式）
        val showFolderPicker by comicDetailViewModel.showFolderPicker.collectAsState()
        val folderList by comicDetailViewModel.folderList.collectAsState()
        if (showFolderPicker) {
            FolderPickerSheet(
                comicId = comic.id,
                folderList = folderList,
                onSelect = { folderId -> comicDetailViewModel.collectWithFolder(comic.id, folderId) },
                onDismiss = { comicDetailViewModel.hideFolderPicker() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPickerSheet(
    comicId: Int,
    folderList: Map<String, String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = "\u9009\u62e9\u6536\u85cf\u5939",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f)
        ) {
            // "0"（全部）排第一，其余按原序
            val sortedFolders = linkedMapOf<String, String>().apply {
                folderList["0"]?.let { put("0", it) }
                folderList.filterKeys { it != "0" }.forEach { (id, name) -> put(id, name) }
                if (containsKey("0").not() && folderList.isNotEmpty()) {
                    put("0", "\u5168\u90e8")
                }
            }
            items(sortedFolders.size) { index ->
                val entry = sortedFolders.entries.elementAt(index)
                ListItem(
                    headlineContent = { Text(entry.value) },
                    modifier = Modifier.clickable { onSelect(entry.key) }
                )
                if (index < sortedFolders.size - 1) {
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ComicDetailBottomBar(
    comic: Comic,
    readHistoryManager: ReadHistoryManager,
    readHistory: Map<Int, ComicReadHistory>,
    onLike: () -> Unit,
    onCollect: () -> Unit,
    onRelated: () -> Unit,
    onDownload: () -> Unit,
    onRead: (Int) -> Unit,
    onChapters: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .defaultMinSize(minHeight = 80.dp)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row {
                IconButton(onClick = onLike) {
                    if (comic.isLike) {
                        Icon(Icons.Default.Favorite, contentDescription = "\u5df2\u559c\u6b22", tint = MaterialTheme.colorScheme.error)
                    } else {
                        Icon(Icons.Default.FavoriteBorder, contentDescription = "\u559c\u6b22")
                    }
                }
                IconButton(onClick = onCollect) {
                    if (comic.isCollect) {
                        Icon(Icons.Filled.Bookmark, contentDescription = "\u5df2\u6536\u85cf", tint = MaterialTheme.colorScheme.tertiary)
                    } else {
                        Icon(Icons.Filled.BookmarkBorder, contentDescription = "\u6536\u85cf")
                    }
                }
                IconButton(onClick = onRelated) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "\u76f8\u5173")
                }
                IconButton(onClick = onDownload) {
                    Icon(Icons.Default.Download, contentDescription = "\u7f13\u5b58")
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            val lastReadChapterId = readHistoryManager.lastReadChapterId(comic, readHistory)
            if (comic.comicChapterList.isEmpty()) {
                Button(
                    onClick = { onRead(lastReadChapterId ?: comic.id) },
                    shape = CircleShape
                ) {
                    Text(if (lastReadChapterId != null) "\u7ee7\u7eed\u9605\u8bfb" else "\u5f00\u59cb\u9605\u8bfb")
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        onClick = onChapters,
                        shape = CircleShape
                    ) {
                        Text("\u7ae0\u8282")
                    }
                    Button(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        onClick = {
                            val targetChapterId = lastReadChapterId
                                ?: comic.comicChapterList.firstOrNull()?.id
                                ?: comic.id
                            onRead(targetChapterId)
                        },
                        shape = CircleShape
                    ) {
                        Text(if (lastReadChapterId != null) "\u7ee7\u7eed" else "\u9605\u8bfb")
                    }
                }
            }
        }
    }
}
