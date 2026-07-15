package com.par9uet.jm.utils

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 为 OkHttpClient.Builder 添加 TLS 兼容配置：
 * 1. 启用 TLS 1.2（Android 6 默认不启用）
 * 2. 信任系统证书（配合 network_security_config.xml 信任用户证书）
 */
fun OkHttpClient.Builder.applyTlsCompat(): OkHttpClient.Builder {
    try {
        // 配置兼容的 TLS 版本（Android 6 需要 TLS 1.2）
        val compatSpec = ConnectionSpec.Builder(ConnectionSpec.COMPATIBLE_TLS)
            .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
            .build()
        connectionSpecs(listOf(compatSpec, ConnectionSpec.CLEARTEXT))

        // 使用系统默认的 TrustManager，确保兼容 Android 6 的信任库
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as java.security.KeyStore?)
        val trustManagers = tmf.trustManagers
        val x509TrustManager = trustManagers.first { it is X509TrustManager } as X509TrustManager

        // 配置 SSLContext 使用 TLS 1.2
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(null, trustManagers, java.security.SecureRandom())

        sslSocketFactory(sslContext.socketFactory, x509TrustManager)
    } catch (e: Exception) {
        // 如果自定义 SSL 配置失败，回退到默认配置
        logError("TlsCompat", "TLS 兼容配置失败，使用默认配置: ${e.message}")
    }
    return this
}
