package com.par9uet.jm.data.models

const val COMIC_API_SOURCE_BUILTIN = "builtin"
const val COMIC_API_SOURCE_NETWORK = "network"
const val APP_LOCK_TYPE_PASSWORD = "password"
const val APP_LOCK_TYPE_PATTERN = "pattern"

data class LocalSetting(
    val comicApiSourceList: List<String> = listOf(
        COMIC_API_SOURCE_BUILTIN,
        COMIC_API_SOURCE_NETWORK,
    ),
    val comicApiSource: String = COMIC_API_SOURCE_BUILTIN,
    // 偏好推荐开关：开启后将请求网络 API 获取基于登录账号的个性化推荐，可能不稳定
    val preferenceRecommendEnabled: Boolean = false,
    val apiList: List<String> = listOf(
        "https://www.cdnhth.club",
        "https://www.cdnmhwscc.vip",
        "https://www.jmapiproxyxxx.vip",
        "https://www.cdnxxx-proxy.xyz",
        "https://www.jmeadpoolcdn.life"
    ),
    val api: String = apiList[0],
    val themeList: List<String> = listOf(
        "auto",
        "light",
        "dark",
    ),
    val theme: String = "auto",
    val shunt: String = "1",
    val shuntList: List<String> = listOf(
        "1",
        "2",
        "3",
        "4",
    ),
    // 阅读页预先加载的图片张数
    val prefetchCount: Int = 3,
    // scroll || page || tap
    val readMode: String = "scroll",
    // default || side
    val readTapMode: String = "default",
    val launcherDisguise: String = "default",
    val showComicScrollReadTip: Boolean = true,
    val showComicPageReadTip: Boolean = true,
    val showComicCacheNotification: Boolean = true,
    val showComicCacheNotificationName: Boolean = true,
    val showAiEntry: Boolean = false,
    val blockedTagList: List<String> = listOf(),
    val appLockEnabled: Boolean = false,
    // 密码锁：空字符串表示未设置
    val appLockPassword: String = "",
    // 密码长度 4-8 位
    val appLockPasswordLength: Int = 4,
    // 图案锁：空字符串表示未设置（点序号拼接，例如 "01246"）
    val appLockPattern: String = "",
    // 解锁模式："password" | "pattern" | "both"
    val appLockUnlockMode: String = APP_LOCK_TYPE_PASSWORD,
    val nsfwWarningDismissed: Boolean = false,
    // 是否已完成首次启动引导
    val onboardingCompleted: Boolean = false,
    // 剪切板自动检测漫画编码：检测到包含数字的文字自动弹出跳转提示，默认关闭
    val clipboardAutoDetectEnabled: Boolean = false,
)
