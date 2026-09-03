package com.par9uet.jm.store

import com.par9uet.jm.utils.log
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 进程级下载并发门控。
 *
 * WorkManager 默认每个进程最多并发 4 个 worker，批量下载时所有章节 worker 会
 * 同时跑满。这里在 worker 内再用一个跨 worker 共享的可变信号量把"真正并行下载
 * 的章节数"限制到用户设置值（1-4）。
 *
 * 许可数从 [LocalSettingManager] 读取，借鉴 ComicReadViewModel 的可变信号量写法：
 * 目标许可数变化时重建 Semaphore，使运行中修改设置无需重启即可生效（已占用许可的
 * worker 自然结束后新限制才完全生效）。
 */
class DownloadConcurrencyGate(
    private val localSettingManager: LocalSettingManager,
) {
    @Volatile
    private var permits: Int = -1
    @Volatile
    private var semaphore: Semaphore = Semaphore(DEFAULT_PERMITS)

    private fun currentPermits(): Int =
        localSettingManager.localSettingState.value.downloadConcurrency.coerceIn(1, MAX_PERMITS)

    private fun acquireSemaphore(): Semaphore {
        val target = currentPermits()
        if (permits != target) {
            synchronized(this) {
                if (permits != target) {
                    log("DownloadConcurrencyGate", "并发许可数调整为 $target")
                    semaphore = Semaphore(target)
                    permits = target
                }
            }
        }
        return semaphore
    }

    /** 获取一个下载许可后执行 [action]，正常/异常/取消都会释放许可。 */
    suspend fun <T> withPermit(action: suspend () -> T): T {
        return acquireSemaphore().withPermit { action() }
    }

    companion object {
        private const val DEFAULT_PERMITS = 2
        private const val MAX_PERMITS = 4
    }
}
