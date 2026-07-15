package com.par9uet.jm.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PATTERN
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
@OptIn(ExperimentalMaterial3Api::class)
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

    // 提升到顶层的状态，供内容区和按钮区共享
    var appLockEnabled by remember { mutableStateOf(localSetting.appLockEnabled) }
    var appLockPasswordSet by remember { mutableStateOf(localSetting.appLockPassword.isNotEmpty()) }
    var appLockPatternSet by remember { mutableStateOf(localSetting.appLockPattern.isNotEmpty()) }
    var appLockUnlockMode by remember { mutableStateOf(localSetting.appLockUnlockMode) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showPatternDialog by remember { mutableStateOf(false) }
    var loginUsername by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var permissionGranted by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> permissionGranted = isGranted }

    fun skipOnboarding() {
        localSettingManager.updateOnboardingCompleted(true)
        onComplete()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                title = {},
                actions = {
                    TextButton(onClick = { skipOnboarding() }) {
                        Text("跳过", fontWeight = FontWeight.Medium)
                    }
                }
            )
        },
        bottomBar = {
            // 当前步骤的按钮，固定在底部
            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp)
                        .padding(top = 12.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (step) {
                        0 -> StepButtons(primaryText = "开始", onPrimary = { step = 1 })
                        1 -> StepButtons(primaryText = "我已了解，继续", onPrimary = { step = 2 })
                        2 -> StepButtons(primaryText = "下一步", onPrimary = { step = 3 })
                        3 -> StepButtons(
                            primaryText = if (permissionGranted) "已授予，下一步" else "重新请求",
                            onPrimary = {
                                if (permissionGranted) {
                                    step = 4
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    step = 4
                                }
                            },
                            secondaryText = "稍后再说",
                            onSecondary = { step = 4 }
                        )
                        4 -> StepButtons(
                            primaryText = "下一步",
                            onPrimary = { step = 5 },
                            secondaryText = "跳过",
                            onSecondary = { step = 5 }
                        )
                        5 -> StepButtons(
                            primaryText = "下一步",
                            onPrimary = { step = 6 },
                            secondaryText = "跳过",
                            onSecondary = { step = 6 }
                        )
                        6 -> StepButtons(
                            primaryText = "下一步",
                            onPrimary = { step = 7 },
                            secondaryText = "跳过",
                            onSecondary = { step = 7 }
                        )
                        7 -> {
                            if (isLogin) {
                                StepButtons(
                                    primaryText = "下一步",
                                    onPrimary = {
                                        if (!preferenceStepHandled) {
                                            step = 8
                                        } else {
                                            skipOnboarding()
                                        }
                                    }
                                )
                            } else {
                                StepButtons(
                                    primaryText = "登录",
                                    onPrimary = {
                                        if (loginUsername.isNotBlank() && loginPassword.isNotBlank()) {
                                            userViewModel.login(loginUsername, loginPassword)
                                        }
                                    },
                                    primaryEnabled = loginUsername.isNotBlank() && loginPassword.isNotBlank(),
                                    primaryLoading = loginState.isLoading,
                                    secondaryText = "跳过",
                                    onSecondary = { skipOnboarding() }
                                )
                            }
                        }
                        8 -> StepButtons(
                            primaryText = "下一步",
                            onPrimary = { step = 9 }
                        )
                        9 -> StepButtons(
                            primaryText = "完成",
                            onPrimary = {
                                preferenceStepHandled = true
                                skipOnboarding()
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        // 内容区：居中显示，平板模式限制宽度
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val maxContentWidth = if (maxWidth >= 600.dp) 420.dp else maxWidth
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = maxContentWidth)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (step) {
                    0 -> WelcomeStepContent()
                    1 -> NsfwWarningStepContent()
                    2 -> DataSourceStepContent()
                    3 -> PermissionStepContent(
                        granted = permissionGranted,
                        onRequestPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                permissionGranted = true
                            }
                        }
                    )
                    4 -> AppLockStepContent(
                        enabled = appLockEnabled,
                        passwordSet = appLockPasswordSet,
                        patternSet = appLockPatternSet,
                        unlockMode = appLockUnlockMode,
                        onToggle = { enabled ->
                            appLockEnabled = enabled
                            localSettingManager.updateAppLockEnabled(enabled)
                            if (!enabled) {
                                localSettingManager.updateAppLockPassword("")
                                localSettingManager.updateAppLockPattern("")
                                appLockPasswordSet = false
                                appLockPatternSet = false
                            }
                        },
                        onPasswordSet = { pwd ->
                            localSettingManager.updateAppLockPassword(pwd)
                            appLockPasswordSet = true
                            if (!appLockPatternSet && appLockUnlockMode != APP_LOCK_TYPE_PASSWORD) {
                                appLockUnlockMode = APP_LOCK_TYPE_PASSWORD
                                localSettingManager.updateAppLockUnlockMode(APP_LOCK_TYPE_PASSWORD)
                            }
                        },
                        onPatternSet = { pattern ->
                            localSettingManager.updateAppLockPattern(pattern)
                            appLockPatternSet = true
                            if (!appLockPasswordSet && appLockUnlockMode != APP_LOCK_TYPE_PATTERN) {
                                appLockUnlockMode = APP_LOCK_TYPE_PATTERN
                                localSettingManager.updateAppLockUnlockMode(APP_LOCK_TYPE_PATTERN)
                            }
                        },
                        onUnlockModeSet = { mode ->
                            appLockUnlockMode = mode
                            localSettingManager.updateAppLockUnlockMode(mode)
                        },
                        onShowPasswordDialog = { showPasswordDialog = true },
                        onShowPatternDialog = { showPatternDialog = true }
                    )
                    5 -> AiStepContent(
                        enabled = localSetting.showAiEntry,
                        onToggle = { localSettingManager.updateShowAiEntry(it) }
                    )
                    6 -> ExtractCodeStepContent(
                        clipboardAutoDetectEnabled = localSetting.clipboardAutoDetectEnabled,
                        onToggleClipboard = { localSettingManager.updateClipboardAutoDetectEnabled(it) }
                    )
                    7 -> LoginStepContent(
                        isLogin = isLogin,
                        loginState = loginState,
                        username = loginUsername,
                        password = loginPassword,
                        onUsernameChange = { loginUsername = it.filter { ch -> ch.code in 0..127 } },
                        onPasswordChange = { loginPassword = it.filter { ch -> ch.code in 0..127 } }
                    )
                    8 -> AutoSignInStepContent(
                        enabled = localSetting.autoSignInEnabled,
                        onToggle = { localSettingManager.updateAutoSignInEnabled(it) }
                    )
                    9 -> PreferenceRecommendStepContent(
                        enabled = localSetting.preferenceRecommendEnabled,
                        recommendSource = localSetting.recommendSource,
                        onToggle = { localSettingManager.updatePreferenceRecommendEnabled(it) },
                        onRecommendSourceChange = { localSettingManager.updateRecommendSource(it) }
                    )
                }
            }
        }
    }

    // 密码/图案设置对话框
    if (showPasswordDialog) {
        SetAppLockPasswordDialog(
            lockType = APP_LOCK_TYPE_PASSWORD,
            onConfirm = { pwd ->
                localSettingManager.updateAppLockPassword(pwd)
                appLockPasswordSet = true
                showPasswordDialog = false
                if (!appLockPatternSet && appLockUnlockMode != APP_LOCK_TYPE_PASSWORD) {
                    appLockUnlockMode = APP_LOCK_TYPE_PASSWORD
                    localSettingManager.updateAppLockUnlockMode(APP_LOCK_TYPE_PASSWORD)
                }
            },
            onDismiss = { showPasswordDialog = false }
        )
    }
    if (showPatternDialog) {
        SetAppLockPasswordDialog(
            lockType = APP_LOCK_TYPE_PATTERN,
            onConfirm = { pattern ->
                localSettingManager.updateAppLockPattern(pattern)
                appLockPatternSet = true
                showPatternDialog = false
                if (!appLockPasswordSet && appLockUnlockMode != APP_LOCK_TYPE_PATTERN) {
                    appLockUnlockMode = APP_LOCK_TYPE_PATTERN
                    localSettingManager.updateAppLockUnlockMode(APP_LOCK_TYPE_PATTERN)
                }
            },
            onDismiss = { showPatternDialog = false }
        )
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

/**
 * 含有开关等控制组件的步骤布局。
 * 统一居中展示：上方 StepHeader（图标+标题+说明），下方控制组件。
 * 若内容较长开关被滚动隐藏，用户仍可通过右上角"跳过"按钮跳过。
 */
@Composable
private fun StepWithControlLayout(
    icon: ImageVector,
    title: String,
    description: String,
    controls: @Composable () -> Unit,
) {
    StepHeader(icon = icon, title = title, description = description)
    Spacer(modifier = Modifier.height(16.dp))
    controls()
}

@Composable
private fun StepButtons(
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    primaryEnabled: Boolean = true,
    primaryLoading: Boolean = false,
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

@Composable
private fun WelcomeStepContent() {
    StepHeader(
        icon = Icons.Rounded.WavingHand,
        title = "欢迎使用 JM Mobile",
        description = "本应用提供漫画浏览、下载与阅读功能。接下来将引导你完成几项基础设置，整个过程约 1 分钟。你也可以随时点击右上角跳过。"
    )
}

@Composable
private fun NsfwWarningStepContent() {
    StepHeader(
        icon = Icons.Rounded.WarningAmber,
        title = "内容警告",
        description = "本应用包含 NSFW（成人）内容，仅适合 18 岁及以上用户使用。继续使用即表示你已确认自己已达到法定年龄，并自愿浏览相关内容。"
    )
}

@Composable
private fun DataSourceStepContent() {
    StepHeader(
        icon = Icons.Rounded.Storage,
        title = "数据源说明",
        description = "本应用支持两种数据源：\n\n内置 API：稳定可靠，无需额外配置，但无个性化推荐。\n\n网络 API：可配置自定义域名，支持基于登录账号的个性化推荐，但需要手动配置且可能不稳定。\n\n默认使用内置 API，你稍后可在设置中切换。"
    )
}

@Composable
private fun PermissionStepContent(
    granted: Boolean,
    onRequestPermission: () -> Unit
) {
    StepHeader(
        icon = Icons.Rounded.Notifications,
        title = "通知权限",
        description = "用于下载进度通知。Android 13 及以上需要授权，低版本默认已授予。" +
                if (granted) "\n\n状态：已授予" else "\n\n状态：未授予（可稍后在系统设置中开启）"
    )
    LaunchedEffect(Unit) {
        if (!granted) onRequestPermission()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppLockStepContent(
    enabled: Boolean,
    passwordSet: Boolean,
    patternSet: Boolean,
    unlockMode: String,
    onToggle: (Boolean) -> Unit,
    onPasswordSet: (String) -> Unit,
    onPatternSet: (String) -> Unit,
    onUnlockModeSet: (String) -> Unit,
    onShowPasswordDialog: () -> Unit,
    onShowPatternDialog: () -> Unit,
) {
    StepWithControlLayout(
        icon = Icons.Rounded.Lock,
        title = "应用锁（可选）",
        description = "为应用增加一层保护，从后台返回时需要解锁。可设置数字密码和/或图案锁，并选择解锁方式。"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("启用应用锁", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle(it) }
            )
        }
        AnimatedVisibility(visible = enabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("数字密码", style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (passwordSet) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        TextButton(onClick = onShowPasswordDialog) {
                            Text(if (passwordSet) "重设" else "设置")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("图案锁", style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (patternSet) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        TextButton(onClick = onShowPatternDialog) {
                            Text(if (patternSet) "重设" else "设置")
                        }
                    }
                }
                if (passwordSet || patternSet) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "解锁方式",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = unlockMode == APP_LOCK_TYPE_PASSWORD,
                            onClick = { onUnlockModeSet(APP_LOCK_TYPE_PASSWORD) },
                            enabled = passwordSet,
                            label = { Text("仅密码") }
                        )
                        FilterChip(
                            selected = unlockMode == APP_LOCK_TYPE_PATTERN,
                            onClick = { onUnlockModeSet(APP_LOCK_TYPE_PATTERN) },
                            enabled = patternSet,
                            label = { Text("仅图案") }
                        )
                        FilterChip(
                            selected = unlockMode == APP_LOCK_UNLOCK_MODE_BOTH,
                            onClick = { onUnlockModeSet(APP_LOCK_UNLOCK_MODE_BOTH) },
                            enabled = passwordSet && patternSet,
                            label = { Text("图案+密码") }
                        )
                    }
                    Text(
                        text = when (unlockMode) {
                            APP_LOCK_TYPE_PASSWORD -> "从后台返回时需输入数字密码解锁"
                            APP_LOCK_TYPE_PATTERN -> "从后台返回时需绘制图案解锁"
                            APP_LOCK_UNLOCK_MODE_BOTH -> "从后台返回时需先输入密码再绘制图案解锁"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AiStepContent(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    StepWithControlLayout(
        icon = Icons.Rounded.AutoAwesome,
        title = "AI 助手（可选）",
        description = "启用后将在主界面显示 AI 入口。本应用使用的 AI 服务为 unlimitedai，无道德审查，可自由对话与联网搜索。请遵守当地法律法规，理性使用。"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("启用 AI 入口", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ExtractCodeStepContent(
    clipboardAutoDetectEnabled: Boolean,
    onToggleClipboard: (Boolean) -> Unit,
) {
    StepWithControlLayout(
        icon = Icons.Rounded.ContentPaste,
        title = "提取编码功能",
        description = "本应用提供编码提取功能：用户分享的文字中往往夹杂数字（如\"加里奥在40岁的时候...获得了882万的悬赏金\"），把所有数字拼起来就是漫画编码。\n\n在首页点击\"提取\"按钮，粘贴文字即可自动提取编码并预览漫画详情。\n\n你还可以开启\"剪切板自动检测\"：应用回到前台时自动读取剪切板，检测到编码文字会弹出跳转提示。此功能默认关闭，可在设置中开启。"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("剪切板自动检测", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = clipboardAutoDetectEnabled, onCheckedChange = onToggleClipboard)
        }
    }
}

@Composable
private fun LoginStepContent(
    isLogin: Boolean,
    loginState: com.par9uet.jm.ui.models.CommonUIState<*>,
    username: String,
    password: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
) {
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
            onValueChange = onUsernameChange,
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
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
    }
}

@Composable
private fun AutoSignInStepContent(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    StepWithControlLayout(
        icon = Icons.Rounded.AutoAwesome,
        title = "自动签到（可选）",
        description = "已检测到登录。开启后将在每次启动应用时自动为你完成签到，省去手动操作。"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("启用自动签到", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferenceRecommendStepContent(
    enabled: Boolean,
    recommendSource: String,
    onToggle: (Boolean) -> Unit,
    onRecommendSourceChange: (String) -> Unit,
) {
    StepWithControlLayout(
        icon = Icons.Rounded.Recommend,
        title = "偏好推荐（可选）",
        description = "已检测到登录。开启后将在首页显示基于你账号的个性化推荐分类。可在内置 API 推荐（基于收藏标签的客户端推荐）与网络 API 推荐之间切换。"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("启用偏好推荐", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        if (enabled) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "推荐源",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = recommendSource == "builtin",
                    onClick = { onRecommendSourceChange("builtin") },
                    label = { Text("内置 API 推荐") }
                )
                FilterChip(
                    selected = recommendSource == "network",
                    onClick = { onRecommendSourceChange("network") },
                    label = { Text("网络 API 推荐") }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (recommendSource == "builtin")
                    "内置 API 推荐：基于你的收藏标签偏好在客户端计算推荐，不依赖网络 API。"
                else
                    "网络 API 推荐：请求网络 API 获取基于登录账号的个性化推荐，可能不稳定。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
