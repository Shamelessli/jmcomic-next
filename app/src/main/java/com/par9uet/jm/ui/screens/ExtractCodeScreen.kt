package com.par9uet.jm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.ui.components.CommonScaffold
import org.koin.compose.getKoin

/**
 * 提取编码页面
 *
 * 用户粘贴包含数字的文字（如分享文案），自动提取数字作为漫画编码。
 * 支持批量：以空格/换行分隔的多段文字分别提取，跳转到结果列表页展示。
 */
@Composable
fun ExtractCodeScreen(
    toastManager: ToastManager = getKoin().get(),
) {
    val mainNavController = LocalMainNavController.current
    val clipboardManager = LocalClipboardManager.current
    var inputText by rememberSaveable { mutableStateOf("") }

    fun extractCodes(text: String): List<String> {
        return text.split(Regex("\\s+"))
            .map { segment -> segment.filter { it.isDigit() } }
            .filter { it.isNotBlank() }
            .distinct()
    }

    val recognizedCodes = extractCodes(inputText)

    fun extractAndNavigate(text: String) {
        val codes = extractCodes(text)
        if (codes.isEmpty()) {
            toastManager.showAsync("未检测到数字，无法提取编码")
            return
        }
        mainNavController.navigate("extractCodeResult/${codes.joinToString(",")}")
    }

    CommonScaffold(title = "提取编码") {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "粘贴包含数字的文字，自动提取所有数字拼成漫画编码；多个编码可用空格或换行分隔批量提取",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                placeholder = { Text("例：JM123456 789012\n或一段包含数字的分享文案") },
                supportingText = {
                    Text("已识别编码：${if (recognizedCodes.isEmpty()) "—" else recognizedCodes.joinToString("、")}")
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val clipText = clipboardManager.getText()?.text ?: ""
                        if (clipText.isNotBlank()) {
                            inputText = clipText
                            extractAndNavigate(clipText)
                        } else {
                            toastManager.showAsync("剪切板为空")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null)
                    Text("粘贴", modifier = Modifier.padding(start = 4.dp))
                }
                Button(
                    onClick = { extractAndNavigate(inputText) },
                    enabled = inputText.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Text("提取", modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (recognizedCodes.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "共识别 ${recognizedCodes.size} 个编码，点击提取查看结果",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
