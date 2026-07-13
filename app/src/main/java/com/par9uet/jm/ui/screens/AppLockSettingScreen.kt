package com.par9uet.jm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PASSWORD
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PATTERN
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.ui.components.CommonScaffold
import com.par9uet.jm.ui.components.SelectDialog
import com.par9uet.jm.ui.components.SelectOption
import org.koin.compose.getKoin

// 解锁模式展示文本
private val unlockModeTextMap = mapOf(
    APP_LOCK_UNLOCK_MODE_PASSWORD to "仅密码",
    APP_LOCK_UNLOCK_MODE_PATTERN to "仅图案",
    APP_LOCK_UNLOCK_MODE_BOTH to "两种都要",
)

@Composable
fun AppLockSettingScreen(
    localSettingManager: LocalSettingManager = getKoin().get()
) {
    val localSetting by localSettingManager.localSettingState.collectAsState()

    // 密码/图案是否已设置
    val hasPassword by remember(localSetting) {
        derivedStateOf { localSetting.appLockPassword.isNotEmpty() }
    }
    val hasPattern by remember(localSetting) {
        derivedStateOf { localSetting.appLockPattern.isNotEmpty() }
    }
    val hasAnyMethod by remember(hasPassword, hasPattern) {
        derivedStateOf { hasPassword || hasPattern }
    }

    // 弹窗状态
    var showPasswordLengthDialog by remember { mutableStateOf(false) }
    var showSetPasswordDialog by remember { mutableStateOf(false) }
    var showSetPatternDialog by remember { mutableStateOf(false) }
    // 设置密码时的临时长度（仅在选择完长度后弹出输入框时使用）
    var pendingPasswordLength by remember { mutableStateOf(localSetting.appLockPasswordLength) }

    // 关闭密码时需要清理相关状态
    fun disablePassword() {
        localSettingManager.updateAppLockPassword("")
        // 调整解锁模式
        val newMode = when {
            hasPattern -> APP_LOCK_UNLOCK_MODE_PATTERN
            else -> APP_LOCK_UNLOCK_MODE_PASSWORD
        }
        localSettingManager.updateAppLockUnlockMode(newMode)
        // 若应用锁仍开启且无任何解锁方式，则关闭应用锁
        if (!hasPattern && localSetting.appLockEnabled) {
            localSettingManager.updateAppLockEnabled(false)
        }
    }

    // 关闭图案时需要清理相关状态
    fun disablePattern() {
        localSettingManager.updateAppLockPattern("")
        val newMode = when {
            hasPassword -> APP_LOCK_UNLOCK_MODE_PASSWORD
            else -> APP_LOCK_UNLOCK_MODE_PATTERN
        }
        localSettingManager.updateAppLockUnlockMode(newMode)
        if (!hasPassword && localSetting.appLockEnabled) {
            localSettingManager.updateAppLockEnabled(false)
        }
    }

    CommonScaffold(title = "应用锁") {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: 设置解锁方式
            item {
                SettingsSection(title = "设置解锁方式") {
                    // 密码开关
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Key,
                        title = "密码",
                        value = hasPassword,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                // 先选择密码长度，再弹出输入框
                                pendingPasswordLength = localSetting.appLockPasswordLength
                                showPasswordLengthDialog = true
                            } else {
                                disablePassword()
                            }
                        }
                    )
                    // 密码已设置时显示长度修改入口
                    if (hasPassword) {
                        SettingsRow(
                            icon = Icons.Rounded.Key,
                            title = "密码长度",
                            value = "${localSetting.appLockPasswordLength} 位"
                        ) {
                            pendingPasswordLength = localSetting.appLockPasswordLength
                            showPasswordLengthDialog = true
                        }
                    }
                    // 图案锁开关
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Gesture,
                        title = "图案锁",
                        value = hasPattern,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                showSetPatternDialog = true
                            } else {
                                disablePattern()
                            }
                        }
                    )
                }
            }

            // Section 2: 解锁模式（仅当两种方式都已设置时显示）
            if (hasPassword && hasPattern) {
                item {
                    SettingsSection(title = "解锁模式") {
                        unlockModeTextMap.forEach { (mode, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = localSetting.appLockUnlockMode == mode,
                                        onClick = { localSettingManager.updateAppLockUnlockMode(mode) }
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = localSetting.appLockUnlockMode == mode,
                                    onClick = { localSettingManager.updateAppLockUnlockMode(mode) }
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Section 3: 启用应用锁
            item {
                SettingsSection(title = "启用") {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Lock,
                        title = "启用应用锁",
                        value = localSetting.appLockEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                if (hasAnyMethod) {
                                    localSettingManager.updateAppLockEnabled(true)
                                }
                                // 没有任何解锁方式时不允许开启（弹窗提示由下方 hint 处理）
                            } else {
                                localSettingManager.updateAppLockEnabled(false)
                            }
                        }
                    )
                    if (!hasAnyMethod) {
                        Text(
                            text = "请先设置至少一种解锁方式",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // 密码长度选择对话框
        if (showPasswordLengthDialog) {
            val lengthOptions = remember {
                (4..8).map { SelectOption("$it 位", it.toString()) }
            }
            SelectDialog(
                title = "密码长度",
                value = pendingPasswordLength.toString(),
                selectOptionList = lengthOptions,
                onSelect = { value ->
                    pendingPasswordLength = value.toIntOrNull()?.toInt() ?: 4
                    showPasswordLengthDialog = false
                    showSetPasswordDialog = true
                },
                onDismissRequest = { showPasswordLengthDialog = false }
            )
        }

        // 设置密码弹窗
        if (showSetPasswordDialog) {
            SetAppLockPasswordDialog(
                lockType = APP_LOCK_TYPE_PASSWORD,
                passwordLength = pendingPasswordLength,
                onConfirm = { pwd ->
                    localSettingManager.updateAppLockPasswordLength(pendingPasswordLength)
                    localSettingManager.updateAppLockPassword(pwd)
                    // 若两种方式都已设置，默认解锁模式为 both；否则为 password
                    val newMode = if (hasPattern) {
                        APP_LOCK_UNLOCK_MODE_BOTH
                    } else {
                        APP_LOCK_UNLOCK_MODE_PASSWORD
                    }
                    localSettingManager.updateAppLockUnlockMode(newMode)
                    showSetPasswordDialog = false
                },
                onDismiss = {
                    showSetPasswordDialog = false
                }
            )
        }

        // 设置图案弹窗
        if (showSetPatternDialog) {
            SetAppLockPasswordDialog(
                lockType = APP_LOCK_TYPE_PATTERN,
                onConfirm = { pattern ->
                    localSettingManager.updateAppLockPattern(pattern)
                    val newMode = if (hasPassword) {
                        APP_LOCK_UNLOCK_MODE_BOTH
                    } else {
                        APP_LOCK_UNLOCK_MODE_PATTERN
                    }
                    localSettingManager.updateAppLockUnlockMode(newMode)
                    showSetPatternDialog = false
                },
                onDismiss = {
                    showSetPatternDialog = false
                }
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            modifier = Modifier.padding(horizontal = 4.dp),
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    SettingsBaseRow(
        icon = icon,
        title = title,
        value = value,
        onClick = onClick,
        trailingContent = {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    value: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsBaseRow(
        icon = icon,
        title = title,
        value = if (value) "已设置" else "未设置",
        onClick = { onCheckedChange(!value) },
        trailingContent = {
            Switch(
                checked = value,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

@Composable
private fun SettingsBaseRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
    trailingContent: @Composable () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        modifier = Modifier.size(22.dp),
                        imageVector = icon,
                        contentDescription = null
                    )
                }
            }
        },
        headlineContent = { Text(text = title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(
                text = value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    )
}
