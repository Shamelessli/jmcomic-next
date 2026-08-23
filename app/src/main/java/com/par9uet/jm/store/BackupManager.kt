package com.par9uet.jm.store

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.par9uet.jm.data.models.AiChatConversation
import com.par9uet.jm.data.models.AiPersona
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.utils.logError
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

const val BACKUP_PROTECTION_NONE = "none"
const val BACKUP_PROTECTION_PASSWORD = "password"
const val BACKUP_PROTECTION_PATTERN = "pattern"
const val BACKUP_PROTECTION_BOTH = "both"
const val BACKUP_FORMAT_VERSION = 5

private const val KDF_ITERATIONS = 210_000
private const val KEY_BITS = 256

data class BackupContentOptions(
    val includeLocalSetting: Boolean = true,
    val includeDownloadPath: Boolean = false,
    val includeAiChats: Boolean = false,
    val includePersonas: Boolean = false,
    val includeComicCache: Boolean = false,
) {
    val isEmpty: Boolean get() = !includeLocalSetting && !includeDownloadPath &&
        !includeAiChats && !includePersonas && !includeComicCache
}

data class BackupMeta(
    val version: Int = BACKUP_FORMAT_VERSION,
    val timestamp: Long = System.currentTimeMillis(),
    val protectionType: String = BACKUP_PROTECTION_NONE,
    val passwordHash: String? = null,
    val patternHash: String? = null,
    val biometricBinding: String? = null,
    val encryptionSalt: String? = null,
    val encryptionIv: String? = null,
    val kdfIterations: Int = KDF_ITERATIONS,
    val includeLocalSetting: Boolean = true,
    val includeDownloadPath: Boolean = false,
    val includeAiChats: Boolean = false,
    val includePersonas: Boolean = false,
    val includeComicCache: Boolean = false,
    val comicCacheCount: Int = 0,
)

data class ChapterBackup(val id: Int, val name: String, val sortOrder: Long)

data class ComicGroupBackup(
    val id: Int,
    val name: String,
    val authors: List<String>,
    val tags: List<String>,
    val chapters: List<ChapterBackup>,
) {
    val chapterCount: Int get() = chapters.size
}

data class ComicCacheBackup(val groups: List<ComicGroupBackup> = emptyList())

data class BackupFile(
    val meta: BackupMeta,
    var data: JsonObject = JsonObject(),
    val encryptedData: String? = null,
)

class BackupManager {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    private var pendingPassword: String? = null

    fun createBackup(
        localSetting: LocalSetting?,
        aiChats: List<AiChatConversation>?,
        personas: List<AiPersona>?,
        comicCache: ComicCacheBackup? = null,
        options: BackupContentOptions,
        protectionType: String = BACKUP_PROTECTION_PASSWORD,
        password: String? = null,
        pattern: String? = null,
        biometricBinding: String? = null,
    ): String {
        require(!options.isEmpty) { "至少需要选择一项备份内容" }
        require(protectionType != BACKUP_PROTECTION_NONE) { "备份必须设置密码或图形保护" }
        val credential = credential(protectionType, password, pattern)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val data = buildData(localSetting, aiChats, personas, comicCache, options)
        val encrypted = encrypt(gson.toJson(data), credential, salt, iv, KDF_ITERATIONS)
        val meta = BackupMeta(
            protectionType = protectionType,
            passwordHash = password?.takeIf { needsPassword(protectionType) }
                ?.let { verifier("password:$it", salt, KDF_ITERATIONS) },
            patternHash = pattern?.takeIf { needsPattern(protectionType) }
                ?.let { verifier("pattern:$it", salt, KDF_ITERATIONS) },
            biometricBinding = biometricBinding,
            encryptionSalt = encode(salt),
            encryptionIv = encode(iv),
            includeLocalSetting = options.includeLocalSetting,
            includeDownloadPath = options.includeDownloadPath,
            includeAiChats = options.includeAiChats,
            includePersonas = options.includePersonas,
            includeComicCache = options.includeComicCache && comicCache != null,
            comicCacheCount = comicCache?.groups?.size ?: 0,
        )
        return gson.toJson(BackupFile(meta = meta, encryptedData = encode(encrypted)))
    }

    fun parseBackup(json: String): Result<BackupFile> = runCatching {
        val obj = JsonParser.parseString(json).asJsonObject
        val meta = gson.fromJson(obj.getAsJsonObject("meta"), BackupMeta::class.java)
            ?: error("备份文件缺少 meta 字段")
        val data = obj.getAsJsonObject("data") ?: JsonObject()
        val encryptedData = obj.get("encryptedData")?.takeUnless { it.isJsonNull }?.asString
        if (meta.version >= BACKUP_FORMAT_VERSION && encryptedData.isNullOrBlank()) {
            error("加密备份缺少 encryptedData")
        }
        BackupFile(meta, data, encryptedData)
    }

    fun extractLocalSetting(backup: BackupFile): LocalSetting? {
        backup.data.getAsJsonObject("localSetting")?.let { return gson.fromJson(it, LocalSetting::class.java) }
        return if (backup.meta.version <= 1) {
            runCatching { gson.fromJson(backup.data, LocalSetting::class.java) }.getOrNull()
        } else null
    }

    fun extractAiChats(backup: BackupFile): List<AiChatConversation> {
        val array = backup.data.getAsJsonArray("aiChats") ?: return emptyList()
        return runCatching { gson.fromJson(array, Array<AiChatConversation>::class.java).toList() }
            .getOrDefault(emptyList())
    }

    fun extractPersonas(backup: BackupFile): List<AiPersona> {
        val array = backup.data.getAsJsonArray("aiPersonas") ?: return emptyList()
        return runCatching { gson.fromJson(array, Array<AiPersona>::class.java).toList() }
            .getOrDefault(emptyList())
    }

    fun extractComicCache(backup: BackupFile): ComicCacheBackup {
        val obj = backup.data.getAsJsonObject("comicCache") ?: return ComicCacheBackup()
        return runCatching { gson.fromJson(obj, ComicCacheBackup::class.java) }.getOrDefault(ComicCacheBackup())
    }

    fun needsPassword(backup: BackupFile): Boolean = needsPassword(backup.meta.protectionType)
    fun needsPattern(backup: BackupFile): Boolean = needsPattern(backup.meta.protectionType)

    fun verifyPassword(backup: BackupFile, password: String): Boolean {
        val valid = verifyFactor(backup, "password:$password", backup.meta.passwordHash)
        if (!valid) return false
        if (backup.meta.version < BACKUP_FORMAT_VERSION) return true
        return if (backup.meta.protectionType == BACKUP_PROTECTION_PASSWORD) {
            unlock(backup, password, null)
        } else {
            pendingPassword = password
            true
        }
    }

    fun verifyPattern(backup: BackupFile, pattern: String): Boolean {
        val valid = verifyFactor(backup, "pattern:$pattern", backup.meta.patternHash)
        if (!valid) return false
        if (backup.meta.version < BACKUP_FORMAT_VERSION) return true
        val password = pendingPassword
        pendingPassword = null
        return unlock(backup, password, pattern)
    }

    fun readFromUri(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrElse {
        logError("BackupManager", "读取备份文件失败: ${it.message}")
        null
    }

    suspend fun buildComicCacheBackup(
        allDownloads: List<com.par9uet.jm.database.model.DownloadComic>
    ): ComicCacheBackup {
        val groups = allDownloads.groupBy { it.groupId.takeIf { id -> id != 0 } ?: it.id }
            .map { (groupId, items) ->
                val first = items.first()
                ComicGroupBackup(
                    id = groupId,
                    name = first.groupName.ifBlank { first.name },
                    authors = first.authorList,
                    tags = first.tagList,
                    chapters = items.sortedBy { it.createTime }.map {
                        ChapterBackup(it.id, it.chapterName, it.createTime)
                    },
                )
            }.sortedBy { it.id }
        return ComicCacheBackup(groups)
    }

    fun writeToUri(context: Context, uri: Uri, content: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri)?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
            true
        } ?: false
    }.getOrElse {
        logError("BackupManager", "写入备份文件失败: ${it.message}")
        false
    }

    private fun buildData(
        localSetting: LocalSetting?,
        aiChats: List<AiChatConversation>?,
        personas: List<AiPersona>?,
        comicCache: ComicCacheBackup?,
        options: BackupContentOptions,
    ) = JsonObject().apply {
        if ((options.includeLocalSetting || options.includeDownloadPath) && localSetting != null) {
            add("localSetting", gson.toJsonTree(localSetting.copy(
                appLockPassword = "",
                appLockPattern = "",
                downloadTreeUri = if (options.includeDownloadPath) localSetting.downloadTreeUri else "",
            )))
        }
        if (options.includeAiChats && aiChats != null) add("aiChats", gson.toJsonTree(aiChats))
        if (options.includePersonas && personas != null) add("aiPersonas", gson.toJsonTree(personas))
        if (options.includeComicCache && comicCache != null) add("comicCache", gson.toJsonTree(comicCache))
    }

    private fun unlock(backup: BackupFile, password: String?, pattern: String?): Boolean = runCatching {
        val salt = decode(requireNotNull(backup.meta.encryptionSalt))
        val iv = decode(requireNotNull(backup.meta.encryptionIv))
        val encrypted = decode(requireNotNull(backup.encryptedData))
        val clear = decrypt(
            encrypted,
            credential(backup.meta.protectionType, password, pattern),
            salt,
            iv,
            backup.meta.kdfIterations,
        )
        backup.data = JsonParser.parseString(clear).asJsonObject
        true
    }.getOrDefault(false)

    private fun verifyFactor(backup: BackupFile, input: String, expected: String?): Boolean {
        expected ?: return false
        if (backup.meta.version < BACKUP_FORMAT_VERSION) {
            return MessageDigest.isEqual(expected.toByteArray(), legacySha256(input.substringAfter(':')).toByteArray())
        }
        val salt = backup.meta.encryptionSalt?.let(::decode) ?: return false
        return MessageDigest.isEqual(
            decode(expected),
            derive(input, salt, backup.meta.kdfIterations, 128),
        )
    }

    private fun credential(type: String, password: String?, pattern: String?): String = when (type) {
        BACKUP_PROTECTION_PASSWORD -> "password:${requireNotNull(password)}"
        BACKUP_PROTECTION_PATTERN -> "pattern:${requireNotNull(pattern)}"
        BACKUP_PROTECTION_BOTH -> "password:${requireNotNull(password)}|pattern:${requireNotNull(pattern)}"
        else -> error("不支持无保护备份")
    }

    private fun verifier(input: String, salt: ByteArray, iterations: Int) =
        encode(derive(input, salt, iterations, 128))

    private fun encrypt(clear: String, credential: String, salt: ByteArray, iv: ByteArray, iterations: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive(credential, salt, iterations, KEY_BITS), "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(clear.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(encrypted: ByteArray, credential: String, salt: ByteArray, iv: ByteArray, iterations: Int): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive(credential, salt, iterations, KEY_BITS), "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun derive(input: String, salt: ByteArray, iterations: Int, bits: Int): ByteArray {
        val spec = PBEKeySpec(input.toCharArray(), salt, iterations, bits)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun needsPassword(type: String) = type == BACKUP_PROTECTION_PASSWORD || type == BACKUP_PROTECTION_BOTH
    private fun needsPattern(type: String) = type == BACKUP_PROTECTION_PATTERN || type == BACKUP_PROTECTION_BOTH
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
    private fun legacySha256(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
