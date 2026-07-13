package com.par9uet.jm.storage

import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PASSWORD
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PATTERN
import com.par9uet.jm.data.models.COMIC_API_SOURCE_BUILTIN
import com.par9uet.jm.data.models.COMIC_API_SOURCE_NETWORK
import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.utils.normalizeBlockedTagList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class LocalSettingStorage(
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val STORAGE_KEY = "localSetting"
    }

    private var _state = MutableStateFlow<LocalSetting?>(null)
    val state = _state.asStateFlow()

    fun set(localSetting: LocalSetting) {
        _state.update {
            localSetting
        }
        secureStorage.set(STORAGE_KEY, this.state.value)
    }

    fun get(): LocalSetting {
        if (_state.value == null) {
            _state.update {
                val savedJson = secureStorage.getString(STORAGE_KEY)
                val saved = secureStorage.get<LocalSetting>(
                    STORAGE_KEY,
                    object : TypeToken<LocalSetting>() {}.type
                ) ?: LocalSetting()
                // 旧版本字段 appLockType 迁移到 appLockUnlockMode
                val legacyAppLockType = parseLegacyAppLockType(savedJson)
                val migratedUnlockMode = if (savedJson.hasField("appLockUnlockMode")) {
                    saved.appLockUnlockMode
                } else if (legacyAppLockType != null) {
                    legacyAppLockType
                } else {
                    APP_LOCK_TYPE_PASSWORD
                }
                saved.copy(
                    comicApiSource = if (savedJson.hasField("comicApiSource")) {
                        listOf(COMIC_API_SOURCE_BUILTIN, COMIC_API_SOURCE_NETWORK)
                            .firstOrNull { it == saved.comicApiSource }
                            ?: COMIC_API_SOURCE_BUILTIN
                    } else {
                        COMIC_API_SOURCE_BUILTIN
                    },
                    showComicCacheNotification = if (savedJson.hasField("showComicCacheNotification")) {
                        saved.showComicCacheNotification
                    } else {
                        true
                    },
                    showComicCacheNotificationName = if (savedJson.hasField("showComicCacheNotificationName")) {
                        saved.showComicCacheNotificationName
                    } else {
                        true
                    },
                    launcherDisguise = if (savedJson.hasField("launcherDisguise")) {
                        LauncherDisguise.fromId(saved.launcherDisguise).id
                    } else {
                        LauncherDisguise.Default.id
                    },
                    blockedTagList = normalizeBlockedTagList(
                        runCatching { saved.blockedTagList }.getOrNull() ?: listOf()
                    ),
                    appLockPassword = if (savedJson.hasField("appLockPassword")) {
                        saved.appLockPassword ?: ""
                    } else {
                        ""
                    },
                    appLockPasswordLength = if (savedJson.hasField("appLockPasswordLength")) {
                        saved.appLockPasswordLength.coerceIn(4, 8)
                    } else {
                        4
                    },
                    appLockPattern = if (savedJson.hasField("appLockPattern")) {
                        saved.appLockPattern ?: ""
                    } else {
                        ""
                    },
                    appLockUnlockMode = migratedUnlockMode
                )
            }
        }
        return _state.value ?: LocalSetting()
    }

    fun remove() {
        _state.update {
            LocalSetting()
        }
        secureStorage.remove(STORAGE_KEY)
    }
}

private fun String?.hasField(name: String): Boolean {
    return this?.contains("\"$name\"") == true
}

/**
 * 解析旧版本存储中的 appLockType 字段（已废弃，迁移到 appLockUnlockMode）
 */
private fun parseLegacyAppLockType(json: String?): String? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val obj = JsonParser.parseString(json).asJsonObject
        if (!obj.has("appLockType")) return null
        val value = obj.get("appLockType").asString
        when (value) {
            APP_LOCK_TYPE_PATTERN -> APP_LOCK_TYPE_PATTERN
            else -> APP_LOCK_TYPE_PASSWORD
        }
    }.getOrNull()
}
