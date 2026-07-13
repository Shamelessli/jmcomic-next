package com.par9uet.jm.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Recommend
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PASSWORD
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.ui.viewModel.UserViewModel
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel

/**
 * 首次启动引导页
 *
 * 引导步骤：
 * 0. 欢迎介绍
 * 1. NSFW 内容警告
 * 2. 数据源说明
 * 3. 通知权限授予
 * 4. 应用锁设置（可跳过）
 * 5. AI 开关（声明 unlimitedai，无道德审查）
 * 6. 提取编码 + 剪切板自动检测
 * 7. 登录账号（可跳过）
 * 8. 若已登录：偏好推荐开关（声明请求网络 API，可能不稳定）
 *
 * 右上角随时可跳过整个引导。
 */
@Composable
fun WelcomeScreen(
    onComplete: () -> Unit,
    localSettingManager: LocalSettingManager = getKoin().get(),
    userManager: UserManager = getKoin().get(),
    userViewModel: UserViewModel = koinActivityViewModel(),
) {
    val localSetting by localSettingManager.localSettingState.collectAsState()
    val isLogin by userManager.isLoginState.collectAsState(false)
    val loginState by userViewModel.loginState.collectAsState()

    var step by remember { mutableStateOf(0) }
    var preferenceStepHandled by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            TextButton(
                onClick = {
                    localSettingManager.updateOnboardingCompleted(true)
                    onComplete()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Text("跳过", fontWeight = FontWeight.Medium)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(top = 80.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (step) {
                    0 -> WelcomeStep(onNext = { step = 1 })
                    1 -> NsfwWarningStep(onNext = { step = 2 })
                    2 -> DataSourceStep(onNext = { step = 3 })
                    3 -> PermissionStep(onNext = { step = 4 })
                    4 -> AppLockStep(
                        localSetting = localSetting,
                        onToggle = { enabled ->
                            localSettingManager.updateAppLockEnabled(enabled)
                            if (!enabled) {
                                localSettingManager.updateAppLockPassword("")
                                localSettingManager.updateAppLockPattern("")
                            }
                        },
                        onPasswordSet = { pwd ->
                            localSettingManager.updateAppLockPassword(pwd)
                            localSettingManager.updateAppLockUnlockMode(APP_LOCK_TYPE_PASSWORD)
                        },
                        onNext = { step = 5 }
                    )
                    5 -> AiStep(
                        enabled = localSetting.showAiEntry,
                        onToggle = { localSettingManager.updateShowAiEntry(it) },
                        onNext = { step = 6 }
                    )
                    6 -> ExtractCodeStep(
                        clipboardAutoDetectEnabled = localSetting.clipboardAutoDetectEnabled,
                        onToggleClipboard = { localSettingManager.updateClipboardAutoDetectEnabled(it) },
                        onNext = { step = 7 }
                    )
                    7 -> LoginStep(
                        isLogin = isLogin,
                        loginState = loginState,
                        onLogin = { u, p -> userViewModel.login(u, p) },
                        onNext = {
                            if (isLogin && !preferenceStepHandled) {
                                step = 8
                            } else {
                                localSettingManager.updateOnboardingCompleted(true)
                                onComplete()
                            }
                        }
                    )
                    8 -> PreferenceRecommendStep(
                        enabled = localSetting.preferenceRecommendEnabled,
                        onToggle = { localSettingManager.updatePreferenceRecommendEnabled(it) },
                        onComplete = {
                            preferenceStepHandled = true
                            localSettingManager.updateOnboardingCompleted(true)
                            onComplete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StepHeader(
    icon: ImageVector,
    title: String,
    description: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun StepButtonRow(
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    primaryEnabled: Boolean = true,
    primaryLoading: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onPrimary,
            enabled = primaryEnabled && !primaryLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (primaryLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(primaryText)
                }
            } else {
                Text(primaryText)
            }
        }
        if (secondaryText != null && onSecondary != null) {
            OutlinedButton(
                onClick = onSecondary,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(secondaryText)
            }
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    StepHeader(
        icon = Icons.Rounded.WavingHand,
        title = "欢迎使用 JM Mobile",
        description = "本应用提供漫画浏览、下载与阅读功能。接下来将引导你完成几项基础设置，整个过程约 1 分钟。你也可以随时点击右上角跳过。"
    )
    StepButtonRow(
        primaryText = "开始",
        onPrimary = onNext
    )
}

@Composable
private fun NsfwWarningStep(onNext: () -> Unit) {
    StepHeader(
        icon = Icons.Rounded.WarningAmber,
        title = "内容警告",
        description = "本应用包含 NSFW（成人）内容，仅适合 18 岁及以上用户使用。继续使用即表示你已确认自己已达到法定年龄，并自愿浏览相关内容。"
    )
    StepButtonRow(
        primaryText = "我已了解，继续",
        onPrimary = onNext
    )
}

@Composable
private fun DataSourceStep(onNext: () -> Unit) {
    StepHeader(
        icon = Icons.Rounded.Storage,
        title = "数据源说明",
        description = "本应用支持两种数据源：\n\n内置 API：稳定可靠，无需额外配置，但无个性化推荐。\n\n网络 API：可配置自定义域名，支持基于登录账号的个性化推荐，但需要手动配置且可能不稳定。\n\n默认使用内置 API，你稍后可在设置中切换。"
    )
    StepButtonRow(
        primaryText = "下一步",
        onPrimary = onNext
    )
}

@Composable
private fun PermissionStep(onNext: () -> Unit) {
    var granted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        granted = isGranted
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            granted = true
        }
    }

    StepHeader(
        icon = Icons.Rounded.Notifications,
        title = "通知权限",
        description = "用于下载进度通知。Android 13 及以上需要授权，低版本默认已授予。" +
                if (granted) "\n\n状态：已授予" else "\n\n状态：未授予（可稍后在系统设置中开启）"
    )
    StepButtonRow(
        primaryText = if (granted) "已授予，下一步" else "重新请求",
        onPrimary = {
            if (granted) {
                onNext()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onNext()
            }
        },
        secondaryText = "稍后再说",
        onSecondary = onNext
    )
}

@Composable
private fun AppLockStep(
    localSetting: com.par9uet.jm.data.models.LocalSetting,
    onToggle: (Boolean) -> Unit,
    onPasswordSet: (String) -> Unit,
    onNext: () -> Unit,
) {
    var enabled by remember { mutableStateOf(localSetting.appLockEnabled) }
    var password by remember { mutableStateOf("") }
    var passwordSet by remember { mutableStateOf(localSetting.appLockPassword.isNotEmpty()) }

    StepHeader(
        icon = Icons.Rounded.Lock,
        title = "应用锁（可选）",
        description = "为应用增加一层保护，从后台返回时需要解锁。可设置 4-8 位数字密码。"
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("启用应用锁", style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = enabled,
            onCheckedChange = {
                enabled = it
                onToggle(it)
                if (!it) {
                    passwordSet = false
                    password = ""
                }
            }
        )
    }
    AnimatedVisibility(visible = enabled && !passwordSet) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = password,
                onValueChange = { value ->
                    password = value.filter { it.code in 48..57 }.take(8)
                },
                label = { Text("设置 4-8 位数字密码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (password.length in 4..8) {
                        onPasswordSet(password)
                        passwordSet = true
                    }
                },
                enabled = password.length in 4..8,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存密码")
            }
        }
    }
    AnimatedVisibility(visible = enabled && passwordSet) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "密码已设置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
    StepButtonRow(
        primaryText = "下一步",
        onPrimary = onNext,
        secondaryText = "跳过",
        onSecondary = onNext
    )
}

@Composable
private fun AiStep(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onNext: () -> Unit,
) {
    StepHeader(
        icon = Icons.Rounded.AutoAwesome,
        title = "AI 助手（可选）",
        description = "启用后将在主界面显示 AI 入口。本应用使用的 AI 服务为 unlimitedai，无道德审查，可自由对话与联网搜索。请遵守当地法律法规，理性使用。"
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("启用 AI 入口", style = MaterialTheme.typography.bodyLarge)
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
    StepButtonRow(
        primaryText = "下一步",
        onPrimary = onNext,
        secondaryText = "跳过",
        onSecondary = onNext
    )
}

@Composable
private fun ExtractCodeStep(
    clipboardAutoDetectEnabled: Boolean,
    onToggleClipboard: (Boolean) -> Unit,
    onNext: () -> Unit,
) {
    StepHeader(
        icon = Icons.Rounded.ContentPaste,
        title = "提取编码功能",
        description = "本应用提供编码提取功能：用户分享的文字中往往夹杂数字（如\"加里奥在40岁的时候...获得了882万的悬赏金\"），把所有数字拼起来就是漫画编码。\n\n在首页点击\"提取\"按钮，粘贴文字即可自动提取编码并预览漫画详情。\n\n你还可以开启\"剪切板自动检测\"：应用回到前台时自动读取剪切板，检测到编码文字会弹出跳转提示。此功能默认关闭，可在设置中开启。"
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("剪切板自动检测", style = MaterialTheme.typography.bodyLarge)
        Switch(checked = clipboardAutoDetectEnabled, onCheckedChange = onToggleClipboard)
    }
    StepButtonRow(
        primaryText = "下一步",
        onPrimary = onNext,
        secondaryText = "跳过",
        onSecondary = onNext
    )
}

@Composable
private fun LoginStep(
    isLogin: Boolean,
    loginState: com.par9uet.jm.ui.models.CommonUIState<*>,
    onLogin: (String, String) -> Unit,
    onNext: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    StepHeader(
        icon = Icons.Rounded.Login,
        title = "登录账号（可选）",
        description = if (isLogin) {
            "已成功登录，可继续下一步。"
        } else {
            "登录后可同步收藏、阅读历史、签到等。也可稍后在应用内登录。"
        }
    )
    if (!isLogin) {
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { value ->
                username = value.filter { it.code in 0..127 }
            },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { value ->
                password = value.filter { it.code in 0..127 }
            },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        if (loginState.isError) {
            Text(
                text = loginState.errorMsg ?: "登录失败",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
        StepButtonRow(
            primaryText = "登录",
            onPrimary = {
                if (username.isNotBlank() && password.isNotBlank()) {
                    onLogin(username, password)
                }
            },
            primaryLoading = loginState.isLoading,
            secondaryText = "跳过",
            onSecondary = onNext
        )
    } else {
        StepButtonRow(
            primaryText = "下一步",
            onPrimary = onNext
        )
    }
}

@Composable
private fun PreferenceRecommendStep(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onComplete: () -> Unit,
) {
    StepHeader(
        icon = Icons.Rounded.Recommend,
        title = "偏好推荐（可选）",
        description = "已检测到登录。开启后将在首页显示基于你账号的个性化推荐分类。\n注意：此功能会请求网络 API，可能不稳定，且会暴露你的登录态给网络 API。"
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("启用偏好推荐", style = MaterialTheme.typography.bodyLarge)
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
    StepButtonRow(
        primaryText = "完成",
        onPrimary = onComplete
    )
}
