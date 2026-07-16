package com.par9uet.jm

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import com.par9uet.jm.store.InitManager
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.ui.screens.AppLockScreen
import com.par9uet.jm.ui.screens.AppScreen
import com.par9uet.jm.ui.screens.LoadingScreen
import com.par9uet.jm.ui.screens.NsfwWarningDialog
import com.par9uet.jm.ui.screens.WelcomeScreen
import com.par9uet.jm.ui.viewModel.GlobalViewModel
import com.par9uet.jm.ui.viewModel.UserViewModel
import kotlinx.coroutines.flow.first
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
fun App(
    globalViewModel: GlobalViewModel = koinActivityViewModel(),
    userViewModel: UserViewModel = koinActivityViewModel(),
    toastManager: ToastManager = getKoin().get(),
    localSettingManager: LocalSettingManager = getKoin().get(),
    initManager: InitManager = getKoin().get(),
    userManager: UserManager = getKoin().get(),
    remoteSettingManager: com.par9uet.jm.store.RemoteSettingManager = getKoin().get(),
    imageLoader: coil.ImageLoader = getKoin().get()
) {
    LaunchedEffect(Unit) {
        globalViewModel.init()
    }
    val localSetting by localSettingManager.localSettingState.collectAsState()
    val remoteSetting by remoteSettingManager.remoteSettingState.collectAsState()

    // 锁定状态：初始为 true（启动即锁定），等待本地设置加载完成后根据 appLockEnabled 决定
    // 这样可以避免启动时主界面内容闪现后再显示锁屏
    var isLocked by remember { mutableStateOf(true) }
    var settingsLoaded by remember { mutableStateOf(false) }
    // NSFW 警告本次会话是否已处理
    var sessionNsfwDismissed by remember { mutableStateOf(false) }
    // 首次启动引导
    var showOnboarding by remember { mutableStateOf(false) }
    // 启动加载动画（初始化期间及引导完成后显示）
    var showLoadingScreen by remember { mutableStateOf(true) }

    // 本地设置初始化加载完成后再决定启动时是否锁定
    // 增加超时保护：最多等待 8 秒，避免网络初始化卡死导致永久黑屏
    LaunchedEffect(Unit) {
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(8000L) {
                initManager.deferred.await()
            }
        }
        settingsLoaded = true
        // 首次启动且未完成引导时显示欢迎页
        if (!localSettingManager.localSettingState.value.onboardingCompleted) {
            showOnboarding = true
        }
        // 仅当应用锁未开启时才解锁；若已开启，isLocked 保持 true，立即显示锁屏
        if (!localSettingManager.localSettingState.value.appLockEnabled) {
            isLocked = false
        }
        if (localSettingManager.localSettingState.value.nsfwWarningDismissed) {
            sessionNsfwDismissed = true
        }
    }
    // 启动加载动画：设置加载完成且无需引导时，显示 2.5 秒后自动消失
    LaunchedEffect(settingsLoaded, showOnboarding) {
        if (settingsLoaded && !showOnboarding) {
            kotlinx.coroutines.delay(2500L)
            showLoadingScreen = false
        }
    }
    // 应用锁被关闭时解除锁定
    LaunchedEffect(localSetting.appLockEnabled) {
        if (settingsLoaded && !localSetting.appLockEnabled) isLocked = false
    }

    // 自动签到：设置加载完成且自动签到开关开启时执行
    LaunchedEffect(settingsLoaded) {
        if (!settingsLoaded) return@LaunchedEffect
        val ls = localSettingManager.localSettingState.value
        if (!ls.autoSignInEnabled) return@LaunchedEffect
        if (!userManager.isLoginState.first()) return@LaunchedEffect
        kotlinx.coroutines.delay(2000L)
        userViewModel.getSignInData()
        val signData = kotlinx.coroutines.withTimeoutOrNull(10000L) {
            userViewModel.signDataState.first { state -> !state.isLoading }
        } ?: return@LaunchedEffect
        val todayDayOfMonth = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
        val isSigned = signData.data?.dateMap?.get(todayDayOfMonth)?.isSign == true
        if (isSigned) return@LaunchedEffect
        userViewModel.signIn()
    }

    // 从后台返回时重新锁定
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, localSetting.appLockEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && localSetting.appLockEnabled) {
                isLocked = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 剪切板自动检测漫画编码（设置开关开启时）
    var clipboardDetectedComicId by remember { mutableStateOf<Int?>(null) }
    var clipboardDetectedComic by remember { mutableStateOf<com.par9uet.jm.data.models.Comic?>(null) }
    var clipboardDetectLoading by remember { mutableStateOf(false) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var lastClipboardText by remember { mutableStateOf("") }
    var pendingNavComicId by remember { mutableStateOf(-1) }
    val mainNavController = rememberNavController()

    DisposableEffect(lifecycleOwner, localSetting.clipboardAutoDetectEnabled, settingsLoaded) {
        if (!localSetting.clipboardAutoDetectEnabled) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val clipText = clipboardManager.getText()?.text ?: ""
                    if (clipText.isNotBlank() && clipText != lastClipboardText) {
                        lastClipboardText = clipText
                        val digits = clipText.filter { it.isDigit() }
                        if (digits.length in 3..12) {
                            clipboardDetectLoading = true
                            clipboardDetectedComicId = digits.toIntOrNull()
                        }
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    // 剪切板检测后获取详情
    val comicRepository = remember { org.koin.core.context.GlobalContext.get().get<com.par9uet.jm.repository.ComicRepository>() }
    LaunchedEffect(clipboardDetectedComicId) {
        val id = clipboardDetectedComicId ?: return@LaunchedEffect
        clipboardDetectLoading = true
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { comicRepository.getComicDetail(id) }.getOrNull()
        }
        when (result) {
            is com.par9uet.jm.retrofit.model.NetWorkResult.Success<*> -> {
                @Suppress("UNCHECKED_CAST")
                clipboardDetectedComic = (result.data as com.par9uet.jm.retrofit.model.ComicDetailResponse).toComic()
            }
            else -> {
                toastManager.showAsync("剪切板检测：漫画编码 ${id} 无效")
                clipboardDetectedComicId = null
            }
        }
        clipboardDetectLoading = false
    }

    // 剪切板检测确认跳转
    LaunchedEffect(pendingNavComicId) {
        if (pendingNavComicId > 0) {
            mainNavController.navigate("comicDetail/$pendingNavComicId")
            pendingNavComicId = -1
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        toastManager.message.collect { text ->
            snackbarHostState.showSnackbar(
                message = text,
                actionLabel = null,
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
        }
    }

    // 启动加载动画：设置加载前或引导完成后的最短显示时间内显示加载页
    if (!settingsLoaded || (showLoadingScreen && !showOnboarding)) {
        LoadingScreen()
        return
    }

    // 优先级：欢迎引导 > 应用锁 > NSFW 警告 > 主应用
    val showAppLock = localSetting.appLockEnabled && isLocked && !showOnboarding
    val showNsfwDialog = !showAppLock && !showOnboarding &&
            !sessionNsfwDismissed &&
            !localSetting.nsfwWarningDismissed
    // NSFW 弹窗显示时模糊背景（API 31+ 支持，低版本仅显示半透明遮罩）
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    if (showOnboarding) {
        WelcomeScreen(
            onComplete = {
                showOnboarding = false
                // 引导完成后若应用锁已启用且仍处于锁定状态，保持锁定
                // 否则解锁进入主应用
                if (!localSetting.appLockEnabled) {
                    isLocked = false
                }
            }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 主应用内容 + Snackbar，当 NSFW 弹窗显示时模糊
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (showNsfwDialog && canBlur) Modifier.blur(32.dp) else Modifier
                )
        ) {
            AppScreen(externalNavController = mainNavController)
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 80.dp)
                    .imePadding()
            )
        }
        if (showAppLock) {
            AppLockScreen(
                unlockMode = localSetting.appLockUnlockMode,
                correctPassword = localSetting.appLockPassword,
                correctPattern = localSetting.appLockPattern,
                passwordLength = localSetting.appLockPasswordLength,
                onUnlock = { isLocked = false }
            )
        } else if (showNsfwDialog) {
            NsfwWarningDialog(
                onAccept = { dontShowAgain ->
                    if (dontShowAgain) localSettingManager.dismissNsfwWarning()
                    sessionNsfwDismissed = true
                },
                onDismiss = {
                    // 本次会话关闭，下次启动再次提示
                    sessionNsfwDismissed = true
                }
            )
        }

        // 剪切板自动检测漫画编码弹窗（左侧封面小窗口 + 右侧信息）
        val detectedComic = clipboardDetectedComic
        if (detectedComic != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    clipboardDetectedComic = null
                    clipboardDetectedComicId = null
                },
                title = { androidx.compose.material3.Text("检测到漫画编码", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                    ) {
                        // 左侧封面小窗口
                        coil.compose.AsyncImage(
                            model = "${remoteSetting.imgHost}/media/albums/${detectedComic.id}_3x4.jpg",
                            imageLoader = imageLoader,
                            contentDescription = "${detectedComic.name}的封面",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .width(96.dp)
                                .height(128.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        )
                        // 右侧信息
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
                        ) {
                            androidx.compose.material3.Text(
                                text = "JM${detectedComic.id}",
                                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            androidx.compose.material3.Text(
                                text = detectedComic.name,
                                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            if (detectedComic.authorList.isNotEmpty()) {
                                androidx.compose.material3.Text(
                                    text = "作者：${detectedComic.authorList.joinToString("、")}",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            if (detectedComic.tagList.isNotEmpty()) {
                                androidx.compose.material3.Text(
                                    text = "标签：${detectedComic.tagList.take(8).joinToString("、")}",
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val navId = detectedComic.id
                        clipboardDetectedComic = null
                        clipboardDetectedComicId = null
                        pendingNavComicId = navId
                    }) { androidx.compose.material3.Text("跳转详情") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        clipboardDetectedComic = null
                        clipboardDetectedComicId = null
                    }) { androidx.compose.material3.Text("取消") }
                }
            )
        }
    }
}
