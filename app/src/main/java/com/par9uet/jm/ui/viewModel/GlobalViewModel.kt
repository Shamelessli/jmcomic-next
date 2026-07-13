package com.par9uet.jm.ui.viewModel

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
                runCatching { task.init() }
                    .onFailure { e ->
                        log("初始化任务 ${task.getAppTaskInfo().taskName} 失败：${e.message}")
                    }
            }
            if (!initManager.deferred.isCompleted) {
                initManager.deferred.complete("")
            }
            log("完成全局初始化")
        }
    }
}
