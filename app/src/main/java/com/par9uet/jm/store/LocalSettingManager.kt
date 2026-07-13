package com.par9uet.jm.store

import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.storage.LocalSettingStorage
import com.par9uet.jm.task.AppInitTask
import com.par9uet.jm.task.AppTaskInfo
import com.par9uet.jm.utils.LauncherDisguiseApplier
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.normalizeBlockedTag
import com.par9uet.jm.utils.normalizeBlockedTagList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class LocalSettingManager(
    private val localSettingStorage: LocalSettingStorage,
    private val launcherDisguiseApplier: LauncherDisguiseApplier,
) : AppInitTask {
    private val _localSettingState = MutableStateFlow(LocalSetting())
    val localSettingState = _localSettingState.asStateFlow()

    fun updateComicApiSource(comicApiSource: String) =
        updateSetting { it.copy(comicApiSource = comicApiSource) }

    fun updatePreferenceRecommendEnabled(enabled: Boolean) =
        updateSetting { it.copy(preferenceRecommendEnabled = enabled) }

    fun updateOnboardingCompleted(completed: Boolean) =
        updateSetting { it.copy(onboardingCompleted = completed) }

    fun updateClipboardAutoDetectEnabled(enabled: Boolean) =
        updateSetting { it.copy(clipboardAutoDetectEnabled = enabled) }

    fun updateApi(api: String) = updateSetting { it.copy(api = api) }

    fun updateTheme(theme: String) = updateSetting { it.copy(theme = theme) }

    fun updateShunt(shunt: String) = updateSetting { it.copy(shunt = shunt) }

    fun updatePrefetchCount(prefetchCount: String) =
        updateSetting { it.copy(prefetchCount = prefetchCount.toInt()) }

    fun updateReadMode(readMode: String) = updateSetting { it.copy(readMode = readMode) }

    fun closeShowComicScrollReadTip() =
        updateSetting { it.copy(showComicScrollReadTip = false) }

    fun closeShowComicPageReadTip() =
        updateSetting { it.copy(showComicPageReadTip = false) }

    fun updateReadTapMode(readTapMode: String) =
        updateSetting { it.copy(readTapMode = readTapMode) }

    fun updateLauncherDisguise(launcherDisguise: String) {
        val disguise = LauncherDisguise.fromId(launcherDisguise)
        updateSetting { it.copy(launcherDisguise = disguise.id) }
        launcherDisguiseApplier.apply(disguise)
    }

    fun updateNotificationSettings(show: Boolean, showName: Boolean) =
        updateSetting {
            it.copy(
                showComicCacheNotification = show,
                showComicCacheNotificationName = show && showName
            )
        }

    fun updateShowComicCacheNotification(show: Boolean) =
        updateSetting { it.copy(showComicCacheNotification = show) }

    fun updateShowComicCacheNotificationName(show: Boolean) =
        updateSetting { it.copy(showComicCacheNotificationName = show) }

    fun updateShowAiEntry(show: Boolean) =
        updateSetting { it.copy(showAiEntry = show) }

    fun addBlockedTag(tag: String) {
        val normalizedTag = normalizeBlockedTag(tag)
        if (normalizedTag.isBlank()) return
        updateSetting {
            it.copy(
                blockedTagList = normalizeBlockedTagList(it.blockedTagList + normalizedTag)
            )
        }
    }

    fun replaceBlockedTags(tags: List<String>) =
        updateSetting { it.copy(blockedTagList = normalizeBlockedTagList(tags)) }

    fun removeBlockedTag(tag: String) {
        val normalizedTag = normalizeBlockedTag(tag)
        updateSetting {
            it.copy(
                blockedTagList = it.blockedTagList.filterNot { item ->
                    item.equals(normalizedTag, ignoreCase = true)
                }
            )
        }
    }

    fun updateAppLockEnabled(enabled: Boolean) =
        updateSetting { it.copy(appLockEnabled = enabled) }

    fun updateAppLockPassword(pwd: String) =
        updateSetting { it.copy(appLockPassword = pwd) }

    fun updateAppLockPasswordLength(len: Int) =
        updateSetting { it.copy(appLockPasswordLength = len.coerceIn(4, 8)) }

    fun updateAppLockPattern(pattern: String) =
        updateSetting { it.copy(appLockPattern = pattern) }

    fun updateAppLockUnlockMode(mode: String) =
        updateSetting { it.copy(appLockUnlockMode = mode) }

    fun dismissNsfwWarning() =
        updateSetting { it.copy(nsfwWarningDismissed = true) }

    private fun updateSetting(update: (LocalSetting) -> LocalSetting) {
        _localSettingState.update(update)
        localSettingStorage.set(_localSettingState.value)
    }

    private var appTaskInfo = AppTaskInfo(
        taskName = "load local app settings",
        sort = 3,
    )

    override suspend fun init() {
        log("local app settings init start")
        _localSettingState.update {
            localSettingStorage.get()
        }
        launcherDisguiseApplier.apply(LauncherDisguise.fromId(_localSettingState.value.launcherDisguise))
        log("local app settings init finished")
    }

    override fun getAppTaskInfo(): AppTaskInfo = appTaskInfo
}
