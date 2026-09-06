package com.par9uet.jm.store

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 进程级"单页解码落盘"门控：限制全 App 同时执行 重网络取图+全尺寸解码+编码落盘
 * 的页面数。章级并发由 [DownloadConcurrencyGate] 控制（可同时跑多个章节 worker），
 * 若不限制页级并发，章数 × 页数会让全尺寸位图瞬间把内存打满；这里用跨 worker
 * 共享的信号量把真正在解码落盘的页面总数压到常量上限。
 */
class DownloadPageGate {
    private val semaphore = Semaphore(PAGE_PERMITS)

    suspend fun <T> withPermit(action: suspend () -> T): T = semaphore.withPermit { action() }

    companion object {
        /** 全进程同时解码落盘的页数上限（跨所有章节 worker 共享）。 */
        private const val PAGE_PERMITS = 3
    }
}
