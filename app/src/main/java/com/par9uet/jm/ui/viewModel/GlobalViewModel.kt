package com.par9uet.jm.ui.viewModel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.store.InitManager
import com.par9uet.jm.task.AppInitTask
import com.par9uet.jm.utils.log
import kotlinx.coroutines.launch

class GlobalViewModel(
    private val appInitTaskList: List<AppInitTask>,
    private val initManager: InitManager
) : ViewModel() {

    fun init() {
        viewModelScope.launch {
            // 任一任务异常不能阻塞 deferred 完成，否则会导致永久黑屏
            appInitTaskList.sortedBy { it.getAppTaskInfo().sort }.forEach { task ->
                try {
                    task.init()
                } catch (e: Throwable) {
                    // 使用 try-catch 而非 runCatching + onFailure，避免 onFailure 回调再次抛出异常导致级联崩溃
                    try {
                        log("初始化任务", "${task.getAppTaskInfo().taskName} 失败：${e.message}")
                    } catch (_: Throwable) {
                        // log() 也失败时，仅输出到 logcat
                        try { Log.e("[JM-MOBILE]", "初始化任务 ${task.getAppTaskInfo().taskName} 失败：${e.message}") } catch (_: Throwable) {}
                    }
                }
            }
            if (!initManager.deferred.isCompleted) {
                initManager.deferred.complete("")
            }
            try {
                log("全局初始化", "完成全局初始化")
            } catch (_: Throwable) {
                try { Log.d("[JM-MOBILE]", "完成全局初始化") } catch (_: Throwable) {}
            }
        }
    }
}
