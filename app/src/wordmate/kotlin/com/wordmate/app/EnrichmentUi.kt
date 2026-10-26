package com.wordmate.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable fun EnrichmentPanel(jobs: List<EnrichmentJob>, paused: Boolean,
    onAll: () -> Unit, onRetry: () -> Unit, onPause: () -> Unit, onResume: () -> Unit,
    accountNotice: String = "", onFree: () -> Unit = {}) {
    var details by remember { mutableStateOf(false) }
    val done = jobs.count { it.state == "success" }
    val failed = jobs.count { it.state == "failed" }
    val partial = jobs.count { it.state == "partial" }
    val running = jobs.filter { it.state == "running" }
    val pending = jobs.count { it.state == "pending" }
    Surface(color = PaleTeal, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (jobs.isEmpty()) "免费优先 · 后台补全，不用等图片就能先练"
                else "总 " + jobs.size + " · 完成 " + done + " · 处理中 " + running.size + " · 等待 " + pending + " · 失败 " + failed +
                    if (partial > 0) " · 待补 " + partial else "",
                style = MaterialTheme.typography.labelSmall, color = Teal)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onAll, modifier = Modifier.weight(1.2f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("一键补全全部") }
                OutlinedButton(onClick = onRetry, enabled = failed + partial > 0, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)) { Text("重试失败项") }
                IconButton(onClick = if (paused) onResume else onPause, enabled = paused || pending + running.size > 0) {
                    Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, if (paused) "继续补全" else "暂停补全")
                }
            }
            if (accountNotice.isNotBlank()) {
                Text(accountNotice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("AI 任务已暂停，已保存资料可继续练习。处理服务商账户后再重试。", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = onResume) { Text("已处理账户，继续") }
                    TextButton(onClick = onFree) { Text("关闭 AI，先用免费资源") }
                }
            } else if (paused) Text("已暂停，阶段成果已保存；点击右侧播放按钮继续。", style = MaterialTheme.typography.labelSmall)
            else if (running.isNotEmpty()) Text(running.joinToString(" / ") { it.wordId + "：" + it.stage },
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (jobs.isNotEmpty()) TextButton(onClick = { details = true }) { Text("查看补全详情") }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("补全详情") }, text = {
        LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(jobs.sortedBy { if (it.state == "failed") 0 else 1 }, key = { it.wordId }) { job ->
                Column {
                    Text(job.wordId + " · " + job.stage, style = MaterialTheme.typography.titleSmall)
                    Text(job.error.ifBlank { if (job.state == "success") "资料已保存" else "已有成果会保留，可先练有释义的词" },
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { details = false }) { Text("关闭") } })
}
