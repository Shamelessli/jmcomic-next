package com.par9uet.jm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.par9uet.jm.network.DOH_SERVER_CUSTOM
import com.par9uet.jm.network.DohLatencyResult
import com.par9uet.jm.network.DohManager
import com.par9uet.jm.network.DohServer
import com.par9uet.jm.network.builtinDohServers
import com.par9uet.jm.network.isValidDohUrl
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.ui.components.CommonScaffold
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

@Composable
fun DohSettingScreen(
    localSettingManager: LocalSettingManager = getKoin().get(),
    dohManager: DohManager = getKoin().get(),
) {
    val localSetting by localSettingManager.localSettingState.collectAsState()
    val status by dohManager.status.collectAsState()
    val latency by dohManager.latencyState.collectAsState()
    val scope = rememberCoroutineScope()
    var testingAll by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var customError by remember { mutableStateOf("") }

    val customServer = remember(localSetting.dohCustomServerName, localSetting.dohCustomServerUrl) {
        DohServer(
            id = DOH_SERVER_CUSTOM,
            name = localSetting.dohCustomServerName.ifBlank { "自定义 DoH" },
            displayUrl = localSetting.dohCustomServerUrl,
        )
    }
    val servers = remember(customServer) { builtinDohServers + customServer }

    CommonScaffold(title = "DoH") {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        DohSwitchRow(
                            title = "启用 DoH",
                            summary = if (status.active) "应用网络请求均使用 ${status.serverName} 解析" else "关闭时使用系统 DNS",
                            checked = localSetting.dohEnabled,
                            onCheckedChange = dohManager::setEnabled,
                        )
                        DohSwitchRow(
                            title = "启动时自动启用",
                            summary = "打开应用后自动恢复 DoH；关闭时可在本页手动开启",
                            checked = localSetting.dohAutoStart,
                            onCheckedChange = dohManager::setAutoStart,
                        )
                        DohSwitchRow(
                            title = "使用设备证书",
                            summary = "允许 Android 系统及用户安装的证书验证 DoH 连接",
                            checked = localSetting.dohUseDeviceCertificates,
                            onCheckedChange = dohManager::setUseDeviceCertificates,
                            icon = Icons.Rounded.Security,
                        )
                        DohSwitchRow(
                            title = "优先尝试 IPv6",
                            summary = "关闭时只使用 IPv4，适合没有 IPv6 路由的设备",
                            checked = localSetting.dohPreferIpv6,
                            onCheckedChange = dohManager::setPreferIpv6,
                        )
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = if (status.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                if (status.active) {
                                    "运行中 · 应用请求均通过 ${status.serverName} · 已缓存 ${status.cacheEntryCount} 个域名"
                                } else {
                                    "未运行 · 当前请求使用系统 DNS"
                                },
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (status.active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (status.lastError.isNotBlank()) {
                            Text(status.lastError, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("解析线路", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "选择线路后可测试延迟；自定义地址必须使用 HTTPS。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        enabled = !testingAll,
                        onClick = {
                            testingAll = true
                            scope.launch {
                                servers.forEach { dohManager.testServer(it) }
                                testingAll = false
                            }
                        },
                    ) {
                        if (testingAll) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("测试全部")
                        }
                    }
                }
            }
            items(servers, key = { it.id }) { server ->
                DohServerRow(
                    server = server,
                    selected = localSetting.dohServerId == server.id,
                    result = latency[server.id],
                    onSelect = {
                        if (server.id == DOH_SERVER_CUSTOM && !isValidDohUrl(localSetting.dohCustomServerUrl)) {
                            showCustomDialog = true
                        } else {
                            dohManager.selectServer(server.id)
                        }
                    },
                    onTest = { scope.launch { dohManager.testServer(server) } },
                    onEdit = if (server.id == DOH_SERVER_CUSTOM) {{ showCustomDialog = true }} else null,
                )
            }
            item {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { dohManager.clearCache() },
                    enabled = status.cacheEntryCount > 0,
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Text("清空 DoH 缓存", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    if (showCustomDialog) {
        var name by remember { mutableStateOf(localSetting.dohCustomServerName) }
        var url by remember { mutableStateOf(localSetting.dohCustomServerUrl) }
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("自定义 DoH") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("HTTPS 地址") },
                        singleLine = true,
                        supportingText = { if (customError.isNotBlank()) Text(customError, color = MaterialTheme.colorScheme.error) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!isValidDohUrl(url)) {
                        customError = "请输入有效的 HTTPS DoH 地址"
                    } else {
                        dohManager.saveCustomServer(name, url)
                        customError = ""
                        showCustomDialog = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showCustomDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun DohSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.Dns,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DohServerRow(
    server: DohServer,
    selected: Boolean,
    result: DohLatencyResult?,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = MaterialTheme.shapes.large,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Icon(
                    imageVector = if (selected) Icons.Rounded.Check else Icons.Rounded.Dns,
                    contentDescription = if (selected) "当前线路" else null,
                    modifier = Modifier.padding(10.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(server.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    server.displayUrl.ifBlank { "填写你的 HTTPS DoH 地址" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (result != null) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (result.elapsedMs != null) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            result.elapsedMs?.let { "$it ms" } ?: "测速失败",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = if (result.elapsedMs != null) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            IconButton(onClick = onTest) {
                Icon(Icons.Rounded.Refresh, contentDescription = "测试 ${server.name}")
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = "编辑自定义 DoH")
                }
            }
        }
    }
}
