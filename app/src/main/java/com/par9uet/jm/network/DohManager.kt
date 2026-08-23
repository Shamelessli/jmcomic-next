package com.par9uet.jm.network

import android.util.Base64
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.task.AppInitTask
import com.par9uet.jm.task.AppTaskInfo
import com.par9uet.jm.utils.applyTlsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DohRuntimeStatus(
    val active: Boolean = false,
    val serverName: String = "",
    val serverUrl: String = "",
    val cacheEntryCount: Int = 0,
    val lastError: String = "",
)

data class DohLatencyResult(
    val elapsedMs: Long? = null,
    val error: String = "",
)

/**
 * A process-wide DNS implementation for all app-owned OkHttp clients.
 * The DoH bootstrap client deliberately uses system DNS (or a fixed bootstrap IP)
 * so resolving the DNS server never recurses into this resolver.
 */
class DohManager(
    private val localSettingManager: LocalSettingManager,
) : Dns, AppInitTask {
    private val random = SecureRandom()
    private val _status = MutableStateFlow(DohRuntimeStatus())
    val status = _status.asStateFlow()
    private val _latencyState = MutableStateFlow<Map<String, DohLatencyResult>>(emptyMap())
    val latencyState = _latencyState.asStateFlow()

    @Volatile
    private var sessionEnabled = false

    @Volatile
    private var resolverKey = ""

    @Volatile
    private var resolver: DohResolver? = null

    @Volatile
    private var resolverFailure: String = ""

    override fun lookup(hostname: String): List<InetAddress> {
        val setting = localSettingManager.localSettingState.value
        val selectedResolver = ensureResolver()
        if (selectedResolver != null) {
            return try {
                selectedResolver.lookup(hostname)
            } catch (error: Exception) {
                publishStatus(lastError = error.message ?: "DoH 解析失败")
                throw error
            }
        }
        // A user-enabled DoH connection must never silently fall back to a system
        // resolver. Failing the request makes the status visible and guarantees
        // that app-owned requests are either resolved through DoH or not sent.
        if (sessionEnabled && setting.dohEnabled) {
            throw UnknownHostException(resolverFailure.ifBlank { "DoH 解析器未就绪" })
        }
        return Dns.SYSTEM.lookup(hostname)
            .filter { address -> localSettingManager.localSettingState.value.dohPreferIpv6 || address is Inet4Address }
            .sortedWith(compareBy<InetAddress> { it is Inet6Address })
    }

    fun setEnabled(enabled: Boolean) {
        localSettingManager.updateDohEnabled(enabled)
        sessionEnabled = enabled
        rebuildResolver()
    }

    fun setAutoStart(enabled: Boolean) {
        localSettingManager.updateDohAutoStart(enabled)
    }

    fun selectServer(serverId: String) {
        localSettingManager.updateDohServer(serverId)
        rebuildResolver()
    }

    fun saveCustomServer(name: String, url: String) {
        require(isValidDohUrl(url)) { "请输入 HTTPS DoH 地址" }
        localSettingManager.updateDohCustomServer(name, url)
        rebuildResolver()
    }

    fun setUseDeviceCertificates(enabled: Boolean) {
        localSettingManager.updateDohUseDeviceCertificates(enabled)
        rebuildResolver()
    }

    fun setPreferIpv6(enabled: Boolean) {
        localSettingManager.updateDohPreferIpv6(enabled)
        rebuildResolver()
    }

    fun clearCache() {
        resolver?.clearCache()
        publishStatus(lastError = "")
    }

    suspend fun testServer(server: DohServer): DohLatencyResult = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        var testResolver: DohResolver? = null
        val result = runCatching {
            val setting = localSettingManager.localSettingState.value
            val resolver = DohResolver(
                server = server,
                preferIpv6 = setting.dohPreferIpv6,
                useDeviceCertificates = setting.dohUseDeviceCertificates,
                random = random,
            )
            testResolver = resolver
            resolver.lookup("www.qq.com")
            DohLatencyResult(elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
        }.getOrElse { DohLatencyResult(error = it.message ?: "测试失败") }.also {
            testResolver?.close()
        }
        _latencyState.value = _latencyState.value + (server.id to result)
        result
    }

    fun selectedServer(): DohServer = localSettingManager.localSettingState.value.toDohServer()

    override suspend fun init() {
        // DoH is opt-in on every fresh process. Auto-start restores the prior choice.
        sessionEnabled = localSettingManager.localSettingState.value.let {
            it.dohEnabled && it.dohAutoStart
        }
        rebuildResolver()
    }

    override fun getAppTaskInfo(): AppTaskInfo = AppTaskInfo(
        taskName = "DoH 网络配置",
        sort = 1,
    )

    private fun ensureResolver(): DohResolver? {
        val setting = localSettingManager.localSettingState.value
        val key = setting.dohResolverKey(sessionEnabled)
        if (key != resolverKey) rebuildResolver()
        return resolver
    }

    @Synchronized
    private fun rebuildResolver() {
        val setting = localSettingManager.localSettingState.value
        val key = setting.dohResolverKey(sessionEnabled)
        resolverKey = key
        resolverFailure = ""
        resolver = if (sessionEnabled && setting.dohEnabled) {
            runCatching {
                DohResolver(
                    server = setting.toDohServer(),
                    preferIpv6 = setting.dohPreferIpv6,
                    useDeviceCertificates = setting.dohUseDeviceCertificates,
                    random = random,
                )
            }.onFailure { error ->
                resolverFailure = error.message ?: "DoH 解析器初始化失败"
            }.getOrNull()
        } else {
            null
        }
        publishStatus(lastError = resolverFailure)
    }

    private fun publishStatus(lastError: String = _status.value.lastError) {
        val activeResolver = resolver
        _status.value = DohRuntimeStatus(
            active = activeResolver != null,
            serverName = activeResolver?.server?.name.orEmpty(),
            serverUrl = activeResolver?.server?.displayUrl.orEmpty(),
            cacheEntryCount = activeResolver?.cacheSize ?: 0,
            lastError = lastError,
        )
    }
}

private class DohResolver(
    val server: DohServer,
    private val preferIpv6: Boolean,
    useDeviceCertificates: Boolean,
    private val random: SecureRandom,
) : Dns {
    private data class CachedAddress(
        val addresses: List<InetAddress>,
        val expiresAt: Long,
    )

    private val cache = ConcurrentHashMap<String, CachedAddress>()
    val cacheSize: Int get() = cache.size

    private val bootstrapDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (server.bootstrapHost != null && hostname.equals(server.bootstrapHost, ignoreCase = true)) {
                server.bootstrapIps.map { InetAddress.getByName(it) }
            } else {
                Dns.SYSTEM.lookup(hostname).let { addresses ->
                    if (preferIpv6) addresses else addresses.filterIsInstance<Inet4Address>()
                }
            }
        }
    private val client = OkHttpClient.Builder()
        .dns(bootstrapDns)
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .applyTlsCompat(includeDeviceCertificates = useDeviceCertificates)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isIpLiteral()) {
            val address = InetAddress.getByName(hostname)
            if (!preferIpv6 && address !is Inet4Address) {
                throw UnknownHostException("IPv6 已关闭：$hostname")
            }
            return listOf(address)
        }
        val key = hostname.lowercase()
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAt > now }?.let { return it.addresses }

        // IPv6 is opt-in. When disabled, do not even query AAAA records so a device
        // without IPv6 routing cannot fail before trying the usable IPv4 address.
        val types = if (preferIpv6) listOf(TYPE_AAAA, TYPE_A) else listOf(TYPE_A)
        val records = types.flatMap { type -> resolve(hostname, type) }
            .distinctBy { it.address.hostAddress }
        if (records.isEmpty()) {
            throw UnknownHostException("DoH 未返回 $hostname 的地址")
        }
        val ttlSeconds = records.minOf { it.ttlSeconds }.coerceIn(20L, 24 * 60 * 60L)
        val addresses = records.map { it.address }
        cache[key] = CachedAddress(addresses, now + ttlSeconds * 1000L)
        return addresses
    }

    fun clearCache() = cache.clear()

    fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun resolve(hostname: String, type: Int): List<DnsRecord> {
        val query = createQuery(hostname, type)
        val encoded = Base64.encodeToString(query, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val endpoint = server.endpointUrl.toHttpUrlOrNull()
            ?: throw UnknownHostException("DoH 地址无效")
        val request = Request.Builder()
            .url(endpoint.newBuilder().addQueryParameter("dns", encoded).build())
            .header("Accept", "application/dns-message")
            .header("User-Agent", "JM-Mobile-DoH")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UnknownHostException("DoH 服务返回 HTTP ${response.code}")
            }
            val bytes = response.body?.bytes() ?: throw UnknownHostException("DoH 服务返回空数据")
            return parseResponse(bytes, type)
        }
    }

    private fun createQuery(hostname: String, type: Int): ByteArray {
        val labels = hostname.trimEnd('.').split('.')
        if (labels.isEmpty() || labels.any { it.isBlank() || it.length > 63 }) {
            throw UnknownHostException("域名格式无效：$hostname")
        }
        return ByteArrayOutputStream().apply {
            val id = random.nextInt(0x10000)
            writeU16(id)
            writeU16(0x0100) // Recursion desired.
            writeU16(1)
            writeU16(0)
            writeU16(0)
            writeU16(0)
            labels.forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                write(bytes.size)
                write(bytes)
            }
            write(0)
            writeU16(type)
            writeU16(CLASS_IN)
        }.toByteArray()
    }

    private fun parseResponse(bytes: ByteArray, requestedType: Int): List<DnsRecord> {
        if (bytes.size < DNS_HEADER_SIZE) throw UnknownHostException("DoH 响应不完整")
        val flags = bytes.u16(2)
        if ((flags and 0x000f) != 0) throw UnknownHostException("DoH 解析失败，rcode=${flags and 0x000f}")
        val questionCount = bytes.u16(4)
        val answerCount = bytes.u16(6)
        var offset = DNS_HEADER_SIZE
        repeat(questionCount) {
            offset = bytes.skipName(offset)
            offset += 4
            if (offset > bytes.size) throw UnknownHostException("DoH 问题段无效")
        }
        val records = mutableListOf<DnsRecord>()
        repeat(answerCount) {
            offset = bytes.skipName(offset)
            if (offset + 10 > bytes.size) throw UnknownHostException("DoH 回答段无效")
            val type = bytes.u16(offset)
            val recordClass = bytes.u16(offset + 2)
            val ttl = bytes.u32(offset + 4)
            val length = bytes.u16(offset + 8)
            offset += 10
            if (offset + length > bytes.size) throw UnknownHostException("DoH 地址数据无效")
            if (recordClass == CLASS_IN && type == requestedType &&
                ((type == TYPE_A && length == 4) || (type == TYPE_AAAA && length == 16))
            ) {
                records += DnsRecord(InetAddress.getByAddress(bytes.copyOfRange(offset, offset + length)), ttl)
            }
            offset += length
        }
        return records
    }

    private data class DnsRecord(val address: InetAddress, val ttlSeconds: Long)

    private fun ByteArray.u16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private fun ByteArray.u32(offset: Int): Long =
        ((this[offset].toLong() and 0xff) shl 24) or
            ((this[offset + 1].toLong() and 0xff) shl 16) or
            ((this[offset + 2].toLong() and 0xff) shl 8) or
            (this[offset + 3].toLong() and 0xff)

    private fun ByteArray.skipName(start: Int): Int {
        var offset = start
        while (offset < size) {
            val sizeByte = this[offset].toInt() and 0xff
            when {
                sizeByte == 0 -> return offset + 1
                sizeByte and 0xc0 == 0xc0 -> return offset + 2
                sizeByte and 0xc0 != 0 || offset + sizeByte >= size -> throw UnknownHostException("DoH 域名压缩格式无效")
                else -> offset += sizeByte + 1
            }
        }
        throw UnknownHostException("DoH 域名超出响应范围")
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write((value shr 8) and 0xff)
        write(value and 0xff)
    }

    companion object {
        private const val DNS_HEADER_SIZE = 12
        private const val CLASS_IN = 1
        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28
    }
}

private fun LocalSetting.toDohServer(): DohServer = resolveDohServer(
    selectedId = dohServerId,
    customName = dohCustomServerName,
    customUrl = dohCustomServerUrl,
)

private fun LocalSetting.dohResolverKey(sessionEnabled: Boolean): String = listOf(
    dohEnabled,
    sessionEnabled,
    dohServerId,
    dohCustomServerName,
    dohCustomServerUrl,
    dohUseDeviceCertificates,
    dohPreferIpv6,
).joinToString("|")

private fun String.isIpLiteral(): Boolean =
    matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")) || contains(':')
