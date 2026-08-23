package com.par9uet.jm.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Recommend
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PASSWORD
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PATTERN
import com.par9uet.jm.data.models.APP_LOCK_METHOD_BIOMETRIC
import com.par9uet.jm.data.models.APP_LOCK_RULE_ANY
import com.par9uet.jm.data.models.APP_LOCK_RULE_REQUIRED
import com.par9uet.jm.data.models.CACHE_INTEGRITY_CHECK_FULL
import com.par9uet.jm.data.models.CACHE_INTEGRITY_CHECK_OFF
import com.par9uet.jm.data.models.CACHE_INTEGRITY_CHECK_PARTIAL
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.network.DohManager
import com.par9uet.jm.network.DohServer
import com.par9uet.jm.network.builtinDohServers
import com.par9uet.jm.ui.viewModel.UserViewModel
import com.par9uet.jm.utils.biometricCapabilities
import kotlinx.coroutines.launch
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
 * 7. 缓存完整性检查
 * 8. DoH 网络解析（可选）
 * 9. 登录账号（可跳过）
 * 10. 自动签到（可选）
 * 11. 若已登录：偏好推荐开关（声明请求网络 API，可能不稳定）
 *
 * 右上角随时可跳过整个引导。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeScreen(
    onComplete: () -> Unit,
    localSettingManager: LocalSettingManager = getKoin().get(),
    userManager: UserManager = getKoin().get(),
    dohManager: DohManager = getKoin().get(),
    userViewModel: UserViewModel = koinActivityViewModel(),
) {
    val localSetting by localSettingManager.localSettingState.collectAsState()
    val isLogin by userManager.isLoginState.collectAsState(false)
    val loginState by userViewModel.loginState.collectAsState()
    val context = LocalContext.current
    val biometricCaps = remember(context) { biometricCapabilities(context) }
    val totalSteps = 12
    val dohLatency by dohManager.latencyState.collectAsState()
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(0) }
    var preferenceStepHandled by remember { mutableStateOf(false) }

    // 提升到顶层的状态，供内容区和按钮区共享
    var appLockEnabled by remember { mutableStateOf(localSetting.appLockEnabled) }
    var appLockPasswordSet by remember { mutableStateOf(localSetting.appLockPassword.isNotEmpty()) }
    var appLockPatternSet by remember { mutableStateOf(localSetting.appLockPattern.isNotEmpty()) }
    var appLockUnlockMode by remember { mutableStateOf(localSetting.appLockUnlockMode) }
    var appLockFingerprintEnabled by remember { mutableStateOf(localSetting.appLockFingerprintEnabled) }
    var appLockFaceEnabled by remember { mutableStateOf(localSetting.appLockFaceEnabled) }
    var appLockUnlockRule by remember { mutableStateOf(localSetting.appLockUnlockRule) }
    var appLockRequiredMethods by remember { mutableStateOf(localSetting.appLockRequiredMethods) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showPatternDialog by remember { mutableStateOf(false) }
    var loginUsername by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var permissionGranted by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> permissionGranted = isGranted }

    fun skipOnboarding() {
        if (appLockEnabled && !appLockPasswordSet && !appLockPatternSet &&
            !appLockFingerprintEnabled && !appLockFaceEnabled
        ) {
            appLockEnabled = false
            localSettingManager.updateAppLockEnabled(false)
        }
        localSettingManager.updateOnboardingCompleted(true)
        onComplete()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = "快速设置",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        LinearProgressIndicator(
                            progress = { ((step + 1).toFloat() / totalSteps).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { skipOnboarding() },
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text("跳过", fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        },
        bottomBar = {
            // 当前步骤的按钮，固定在底部
            Surface(
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp)
                        .padding(top = 14.dp, bottom = 16.dp),
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
                            primaryEnabled = !appLockEnabled || appLockPasswordSet || appLockPatternSet ||
                                appLockFingerprintEnabled || appLockFaceEnabled,
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
                        7 -> StepButtons(primaryText = "下一步", onPrimary = { step = 8 })
                        8 -> StepButtons(primaryText = "下一步", onPrimary = { step = 9 })
                        9 -> {
                            if (isLogin) {
                                StepButtons(
                                    primaryText = "下一步",
                                    onPrimary = {
                                        if (!preferenceStepHandled) {
                                            step = 10
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
                        10 -> StepButtons(
                            primaryText = "下一步",
                            onPrimary = { step = 11 }
                        )
                        11 -> StepButtons(
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
                    .padding(horizontal = 20.dp)
                    .padding(top = 24.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedContent(targetState = step, label = "welcomeStep") { currentStep ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when (currentStep) {
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
                                fingerprintEnabled = appLockFingerprintEnabled,
                                faceEnabled = appLockFaceEnabled,
                                fingerprintAvailable = biometricCaps.canAuthenticate && biometricCaps.hasFingerprintHardware,
                                faceAvailable = biometricCaps.canAuthenticate && biometricCaps.hasFaceHardware,
                                unlockRule = appLockUnlockRule,
                                requiredMethods = appLockRequiredMethods,
                                onToggle = { enabled ->
                                    appLockEnabled = enabled
                                    localSettingManager.updateAppLockEnabled(enabled)
                                },
                                onFingerprintToggle = { enabled ->
                                    appLockFingerprintEnabled = enabled
                                    localSettingManager.updateAppLockFingerprintEnabled(enabled)
                                },
                                onFaceToggle = { enabled ->
                                    appLockFaceEnabled = enabled
                                    localSettingManager.updateAppLockFaceEnabled(enabled)
                                },
                                onUnlockRuleSet = { rule ->
                                    appLockUnlockRule = rule
                                    localSettingManager.updateAppLockUnlockRule(rule)
                                },
                                onRequiredMethodsSet = { methods ->
                                    appLockRequiredMethods = methods
                                    localSettingManager.updateAppLockRequiredMethods(methods)
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
                            7 -> CacheIntegrityCheckStepContent(
                                mode = localSetting.cacheIntegrityCheckMode,
                                onModeChange = localSettingManager::updateCacheIntegrityCheckMode,
                            )
                            8 -> DohGuideStepContent(
                                enabled = localSetting.dohEnabled,
                                autoStart = localSetting.dohAutoStart,
                                selectedServerId = localSetting.dohServerId,
                                latency = dohLatency,
                                servers = builtinDohServers,
                                onToggle = dohManager::setEnabled,
                                onAutoStartToggle = dohManager::setAutoStart,
                                onServerSelect = dohManager::selectServer,
                                onTest = { server -> scope.launch { dohManager.testServer(server) } },
                            )
                            9 -> LoginStepContent(
                                isLogin = isLogin,
                                loginState = loginState,
                                username = loginUsername,
                                password = loginPassword,
                                onUsernameChange = { loginUsername = it.filter { ch -> ch.code in 0..127 } },
                                onPasswordChange = { loginPassword = it.filter { ch -> ch.code in 0..127 } }
                            )
                            10 -> AutoSignInStepContent(
                                enabled = localSetting.autoSignInEnabled,
                                onToggle = { localSettingManager.updateAutoSignInEnabled(it) }
                            )
                            11 -> PreferenceRecommendStepContent(
                                enabled = localSetting.preferenceRecommendEnabled,
                                recommendSource = localSetting.recommendSource,
                                onToggle = { localSettingManager.updatePreferenceRecommendEnabled(it) },
                                onRecommendSourceChange = { localSettingManager.updateRecommendSource(it) }
                            )
                        }
                    }
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            modifier = Modifier.size(88.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
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
    Spacer(modifier = Modifier.height(22.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            controls()
        }
    }
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
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.extraLarge,
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
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.extraLarge,
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
    fingerprintEnabled: Boolean,
    faceEnabled: Boolean,
    fingerprintAvailable: Boolean,
    faceAvailable: Boolean,
    unlockRule: String,
    requiredMethods: List<String>,
    onToggle: (Boolean) -> Unit,
    onFingerprintToggle: (Boolean) -> Unit,
    onFaceToggle: (Boolean) -> Unit,
    onUnlockRuleSet: (String) -> Unit,
    onRequiredMethodsSet: (List<String>) -> Unit,
    onShowPasswordDialog: () -> Unit,
    onShowPatternDialog: () -> Unit,
) {
    StepWithControlLayout(
        icon = Icons.Rounded.Lock,
        title = "应用锁（可选）",
        description = "密码、图形、指纹和面容均可独立启用。可选择任一方式通过，或要求指定方式全部通过。系统生物识别面板最终使用哪种模态由 Android 决定。"
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
                BiometricGuideRow(
                    title = "指纹",
                    icon = Icons.Rounded.Fingerprint,
                    checked = fingerprintEnabled,
                    available = fingerprintAvailable,
                    onCheckedChange = onFingerprintToggle,
                )
                BiometricGuideRow(
                    title = "面容",
                    icon = Icons.Rounded.Face,
                    checked = faceEnabled,
                    available = faceAvailable,
                    onCheckedChange = onFaceToggle,
                )
                if (passwordSet || patternSet || fingerprintEnabled || faceEnabled) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "解锁方式",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = unlockRule == APP_LOCK_RULE_ANY, onClick = { onUnlockRuleSet(APP_LOCK_RULE_ANY) }, label = { Text("任一通过") })
                        FilterChip(selected = unlockRule == APP_LOCK_RULE_REQUIRED, onClick = {
                            onUnlockRuleSet(APP_LOCK_RULE_REQUIRED)
                            if (requiredMethods.isEmpty()) {
                                onRequiredMethodsSet(buildList {
                                    if (passwordSet) add(APP_LOCK_TYPE_PASSWORD)
                                    if (patternSet) add(APP_LOCK_TYPE_PATTERN)
                                    if (fingerprintEnabled || faceEnabled) add(APP_LOCK_METHOD_BIOMETRIC)
                                })
                            }
                        }, label = { Text("指定项全部通过") })
                    }
                    if (unlockRule == APP_LOCK_RULE_REQUIRED) {
                        val choices = buildList {
                            if (passwordSet) add(APP_LOCK_TYPE_PASSWORD to "密码")
                            if (patternSet) add(APP_LOCK_TYPE_PATTERN to "图形")
                            if (fingerprintEnabled || faceEnabled) add(APP_LOCK_METHOD_BIOMETRIC to "系统生物识别")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            choices.forEach { (method, label) ->
                                FilterChip(
                                    selected = method in requiredMethods,
                                    onClick = {
                                        val next = if (method in requiredMethods) requiredMethods - method else requiredMethods + method
                                        if (next.isNotEmpty()) onRequiredMethodsSet(next)
                                    },
                                    label = { Text("必须：$label") },
                                )
                            }
                        }
                    }
                    Text(
                        text = if (unlockRule == APP_LOCK_RULE_ANY) "任一已启用方式验证成功即可进入" else "必须完成上方所有选中的必需验证",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BiometricGuideRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    available: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(if (available) title else "$title（不可用或未录入）")
        }
        Switch(checked = checked, enabled = available, onCheckedChange = onCheckedChange)
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
private fun CacheIntegrityCheckStepContent(
    mode: String,
    onModeChange: (String) -> Unit,
) {
    StepWithControlLayout(
        icon = Icons.Rounded.Storage,
        title = "缓存完整性检查",
        description = "打开已缓存漫画时可检查文件是否被删除或损坏。完全检查会逐章核对图片页数，缓存较多时会花费更多时间；发现问题后可直接重新下载。默认关闭。",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "关闭（默认）" to CACHE_INTEGRITY_CHECK_OFF,
                "部分检查：配置、封面" to CACHE_INTEGRITY_CHECK_PARTIAL,
                "完全检查：配置、封面、全部图片" to CACHE_INTEGRITY_CHECK_FULL,
            ).forEach { (label, value) ->
                FilterChip(
                    selected = mode == value,
                    onClick = { onModeChange(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun DohGuideStepContent(
    enabled: Boolean,
    autoStart: Boolean,
    selectedServerId: String,
    latency: Map<String, com.par9uet.jm.network.DohLatencyResult>,
    servers: List<DohServer>,
    onToggle: (Boolean) -> Unit,
    onAutoStartToggle: (Boolean) -> Unit,
    onServerSelect: (String) -> Unit,
    onTest: (DohServer) -> Unit,
) {
    StepWithControlLayout(
        icon = Icons.Rounded.Dns,
        title = "DoH 网络解析（可选）",
        description = "DoH 可减少运营商 DNS 污染，改善首页、搜索和封面请求。默认关闭；你可以先测速并选择线路，也可以稍后在设置 → 连接 → DoH 中配置自定义地址。"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("启用 DoH", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("启动时自动启用", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "打开应用后自动恢复 DoH",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoStart, onCheckedChange = onAutoStartToggle)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("选择线路并测速", style = MaterialTheme.typography.titleSmall)
            servers.forEach { server ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedServerId == server.id,
                        onClick = { onServerSelect(server.id) },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(server.name)
                        Text(
                            server.displayUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    latency[server.id]?.let { result ->
                        Text(
                            result.elapsedMs?.let { "$it ms" } ?: "失败",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (result.elapsedMs != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                    androidx.compose.material3.IconButton(onClick = { onTest(server) }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "测试线路")
                    }
                }
            }
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
        Spacer(modifier = Modifier.height(22.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                )
                if (loginState.isError) {
                    Text(
                        text = loginState.errorMsg ?: "登录失败",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
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
