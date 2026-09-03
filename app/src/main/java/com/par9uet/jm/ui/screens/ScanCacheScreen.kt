package com.par9uet.jm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.par9uet.jm.store.CacheScanManager
import com.par9uet.jm.store.CacheScanState
import com.par9uet.jm.ui.components.CommonScaffold
import org.koin.compose.getKoin

@Composable
fun ScanCacheScreen(
    cacheScanManager: CacheScanManager = getKoin().get(),
) {
    val scanState by cacheScanManager.scanState.collectAsState()

    LaunchedEffect(Unit) {
        cacheScanManager.startScan()
    }

    CommonScaffold(title = "扫描缓存文件夹") {
        when (val state = scanState) {
            is CacheScanState.Idle, is CacheScanState.Scanning -> {
                val stage = (state as? CacheScanState.Scanning)?.stage ?: "准备扫描"
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = stage,
                            modifier = Modifier.padding(top = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            is CacheScanState.Failed -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "扫描失败",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = state.reason,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        modifier = Modifier.padding(top = 20.dp),
                        onClick = { cacheScanManager.startScan() }
                    ) {
                        Text(text = "重新扫描")
                    }
                }
            }

            is CacheScanState.Done -> {
                ScanResultContent(
                    report = state.report,
                    onRescan = { cacheScanManager.startScan() }
                )
            }
        }
    }
}

@Composable
private fun ScanResultContent(
    report: com.par9uet.jm.store.CacheScanReport,
    onRescan: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "扫描完成",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (report.importedComics == 0 && report.importedChapters == 0) {
                    Text(
                        text = "没有发现可导入的缓存目录，也没有需要清理的内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ScanStatLine("导入漫画", "${report.importedComics} 部")
                    ScanStatLine("已完整章节", "${report.importedChapters} 章")
                    ScanStatLine("加入补页队列", "${report.repairChapters} 章")
                    ScanStatLine("跳过已在列表", "${report.skippedExistingChapters} 章")
                    ScanStatLine("目录缺失章节", "${report.skippedMissingChapters} 章")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "完整性清理",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                ScanStatLine("删除重复目录", "${report.deletedDuplicateDirs} 个")
                ScanStatLine("删除空/残留目录", "${report.deletedEmptyDirs} 个")
                ScanStatLine("移除失效记录", "${report.removedDeadRecords} 条")
            }
        }

        if (report.keptDuplicateDirs.isNotEmpty()) {
            ScanDirListCard(
                title = "保留的重复目录（含数据，未覆盖不删）",
                names = report.keptDuplicateDirs
            )
        }
        if (report.unrecognizedDirs.isNotEmpty()) {
            ScanDirListCard(
                title = "无法识别的目录（有图片但缺少合法 JSON）",
                names = report.unrecognizedDirs
            )
        }

        TextButton(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            onClick = onRescan
        ) {
            Text(text = "重新扫描")
        }
    }
}

@Composable
private fun ScanStatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ScanDirListCard(title: String, names: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            names.take(20).forEach { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (names.size > 20) {
                Text(
                    text = "…等共 ${names.size} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
