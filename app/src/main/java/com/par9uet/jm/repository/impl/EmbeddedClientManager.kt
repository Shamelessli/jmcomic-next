package com.par9uet.jm.repository.impl

import com.par9uet.jm.storage.CookieStorage
import com.par9uet.jm.utils.log
import io.github.jukomu.jmcomic.api.enums.ClientType
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import io.github.jukomu.jmcomic.core.config.JmConfiguration
import io.github.jukomu.jmcomic.core.net.OkHttpBuilder
import okhttp3.Cookie
import java.time.Duration

/**
 * 共享内置 API 客户端管理器。
 * 确保 UserRepositoryImpl 和 ComicRepositoryImpl 使用同一个 JmApiClient 实例，
 * 这样 login() 设置的 loggedInUserName 和内部 Cookie 状态可以被所有 Repository 共享。
 * 解决内置 API 模式下 POST 请求（如创建收藏夹）返回 401 "請先登入會員" 的问题。
 *
 * Android 6 兼容：JmDomainManager 的域名探活使用 CompletableFuture.runAsync（ForkJoinPool），
 * 在 Android 6 上可能初始化失败导致 blockUntilInitialized 永久阻塞。
 * 此处在创建客户端后启动守护线程，超时后强制解除阻塞。
 */
class EmbeddedClientManager(
    private val cookieStorage: CookieStorage,
) {
    @Volatile
    private var client: JmApiClient? = null

    fun getClient(): JmApiClient {
        return client ?: synchronized(this) {
            client ?: createClient().also { client = it }
        }
    }

    private fun createClient(): JmApiClient {
        val config = JmConfiguration.Builder()
            .clientType(ClientType.API)
            .timeout(Duration.ofSeconds(20))
            .imageTimeout(Duration.ofSeconds(60))
            .downloadThreadPoolSize(2)
            .domainProbeTimeoutMs(3000)
            .build()
        val context = OkHttpBuilder.build(config)
        val domainManager = context.domainManager
        val clientWithCookieInjection = context.client.newBuilder()
            .addInterceptor { chain ->
                val cookies = cookieStorage.get()
                val request = if (cookies.isNotEmpty()) {
                    val cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }
                    chain.request().newBuilder()
                        .header("Cookie", cookieHeader)
                        .build()
                } else {
                    chain.request()
                }
                val response = chain.proceed(request)
                // 从响应头提取 Set-Cookie，同步到 cookieStorage，保证登录态持久化
                val setCookieHeaders = response.headers("Set-Cookie")
                if (setCookieHeaders.isNotEmpty()) {
                    val newCookies = setCookieHeaders.mapNotNull { Cookie.parse(request.url, it) }
                    if (newCookies.isNotEmpty()) {
                        val existing = cookieStorage.get().toMutableList()
                        val newKeys = newCookies.map { "${it.domain}:${it.path}:${it.name}" }.toSet()
                        existing.removeAll { "${it.domain}:${it.path}:${it.name}" in newKeys }
                        existing.addAll(newCookies)
                        cookieStorage.set(existing)
                    }
                }
                response
            }
            .build()
        val jmClient = JmApiClient(config, clientWithCookieInjection, context.cookieManager, domainManager)

        // 守护线程：域名探活初始化超时后强制解除阻塞，避免 Android 6 上永久卡死
        Thread({
            try {
                // 等待 8 秒让域名探活完成
                Thread.sleep(8000)
                if (!domainManager.isInitialized) {
                    log("EmbeddedClientManager: 域名探活初始化超时，强制解除阻塞")
                    domainManager.setInitialized(true)
                }
            } catch (e: InterruptedException) {
                // 忽略
            }
        }, "embedded-domain-init-guard").apply {
            isDaemon = true
            start()
        }

        return jmClient
    }
}
