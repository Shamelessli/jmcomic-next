package com.par9uet.jm.retrofit

import com.par9uet.jm.BuildConfig
import com.par9uet.jm.utils.md5

// Secrets are injected at build time; no service credential is stored in source.
val BUILTIN_TOKEN_SECRET: String
    get() = BuildConfig.JM_BUILTIN_TOKEN_SECRET
const val BUILTIN_APP_VERSION = "2.0.20"
val APP_DATA_SECRET: String
    get() = BuildConfig.JM_APP_DATA_SECRET

/**
 * 管理 API 请求的上下文信息。
 *
 * 官方 API 模式使用固定时间戳（API_TS），内置 API 模式使用每请求时间戳。
 * 由于 OkHttp 拦截器链和 Retrofit 转换器在同一线程上同步执行，
 * ThreadLocal 可以安全地在拦截器和转换器之间传递每请求时间戳。
 */
object ApiContext {
    private val perRequestTimestamp = ThreadLocal<Long>()

    fun setTimestamp(ts: Long) {
        perRequestTimestamp.set(ts)
    }

    fun getTimestamp(): Long {
        return perRequestTimestamp.get() ?: API_TS
    }

    /**
     * 获取数据解密密钥。
     * 两种模式的数据解密密钥均为 md5(timestamp + APP_DATA_SECRET)。
     */
    fun getDataDecryptKey(): String {
        return md5("${getTimestamp()}$APP_DATA_SECRET")
    }
}
