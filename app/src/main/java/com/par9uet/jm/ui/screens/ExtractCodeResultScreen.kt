package com.par9uet.jm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.ComicCoverImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin
import java.util.concurrent.ConcurrentHashMap

/** 会话级详情缓存：从详情页返回结果页时直接复用，避免重新加载 */
private val extractResultComicCache = ConcurrentHashMap<Int, Comic>()

private sealed interface ExtractResultItemState {
    data object Loading : ExtractResultItemState
    data class Success(val comic: Comic) : ExtractResultItemState
    data class Error(val message: String) : ExtractResultItemState
}

/**
 * 批量提取编码结果页（"找到漫画"列表）
 *
 * 展示每个编码对应的漫画，条目样式与原提取预览弹窗一致；点击进入详情页，
 * 从详情页返回后仍停留在本列表。
 */
@Composable
fun ExtractCodeResultScreen(
    codes: String,
    comicRepository: ComicRepository = getKoin().get(),
) {
    val mainNavController = LocalMainNavController.current
    val codeList = remember(codes) {
        codes.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }
    val itemStates by produceState(
        initialValue = codeList.associateWith { ExtractResultItemState.Loading as ExtractResultItemState },
        key1 = codeList
    ) {
        for (code in codeList) {
            val id = code.toIntOrNull()
            if (id == null) {
                value = value + (code to ExtractResultItemState.Error("无效编码：$code"))
                continue
            }
            val cached = extractResultComicCache[id]
            if (cached != null) {
                value = value + (code to ExtractResultItemState.Success(cached))
                continue
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { comicRepository.getComicDetail(id) }.getOrNull()
            }
            when (result) {
                is NetWorkResult.Success<*> -> {
                    val comic = (result.data as ComicDetailResponse).toComic()
                    extractResultComicCache[id] = comic
                    value = value + (code to ExtractResultItemState.Success(comic))
                }

                is NetWorkResult.Error -> {
                    value = value + (code to ExtractResultItemState.Error(result.message))
                }

                null -> {
                    value = value + (code to ExtractResultItemState.Error("获取漫画详情异常"))
                }
            }
        }
    }

    CommonScaffold(title = "找到漫画") {
        if (codeList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "未识别到编码",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "共识别 ${codeList.size} 个编码，点击漫画进入详情",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(codeList, key = { it }) { code ->
                    ExtractResultItem(
                        code = code,
                        state = itemStates[code] ?: ExtractResultItemState.Loading,
                        onClick = { comic ->
                            mainNavController.navigate("comicDetail/${comic.id}")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtractResultItem(
    code: String,
    state: ExtractResultItemState,
    onClick: (Comic) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        when (state) {
            is ExtractResultItemState.Success -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onClick(state.comic) }
                ) {
                    FoundComicContent(comic = state.comic)
                }
            }

            is ExtractResultItemState.Error -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(152.dp)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoverPlaceholderBox {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AssistChip(onClick = {}, label = { Text("JM$code") })
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            ExtractResultItemState.Loading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(152.dp)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoverPlaceholderBox {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AssistChip(onClick = {}, label = { Text("JM$code") })
                        Text(
                            text = "正在获取详情...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 与原"找到漫画"弹窗一致的条目内容：左侧封面 + 右侧编码/标题/作者/标签 */
@Composable
private fun FoundComicContent(comic: Comic) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ComicCoverImage(
            comic = comic,
            modifier = Modifier
                .width(96.dp)
                .height(128.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AssistChip(onClick = {}, label = { Text("JM${comic.id}") })
            Text(
                text = comic.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (comic.authorList.isNotEmpty()) {
                Text(
                    text = "作者：${comic.authorList.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (comic.tagList.isNotEmpty()) {
                Text(
                    text = "标签：${comic.tagList.take(10).joinToString("、")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CoverPlaceholderBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(128.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
