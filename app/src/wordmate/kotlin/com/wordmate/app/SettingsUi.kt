package com.wordmate.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(vm: AppViewModel, settings: AppSettings, requestNotification: () -> Unit, onExport: () -> Unit, onRestore: () -> Unit) {
    var newCount by remember(settings.newLimit) { mutableStateOf(settings.newLimit.toString()) }
    var reviewCount by remember(settings.reviewLimit) { mutableStateOf(settings.reviewLimit.toString()) }
    var accent by remember(settings.accent) { mutableStateOf(settings.accent) }
    var reminder by remember(settings.reminder) { mutableStateOf(settings.reminder) }
    var hour by remember(settings.reminderHour) { mutableStateOf(settings.reminderHour.toString()) }
    var minute by remember(settings.reminderMinute) { mutableStateOf(settings.reminderMinute.toString().padStart(2, '0')) }
    var ai by remember(settings.aiEnabled) { mutableStateOf(settings.aiEnabled) }
    var textBase by remember(settings.textBase) { mutableStateOf(settings.textBase) }
    var textModel by remember(settings.textModel) { mutableStateOf(settings.textModel) }
    var imageBase by remember(settings.imageBase) { mutableStateOf(settings.imageBase) }
    var imageModel by remember(settings.imageModel) { mutableStateOf(settings.imageModel) }
    var textKey by remember { mutableStateOf("") }
    var imageKey by remember { mutableStateOf("") }
    var licenses by remember { mutableStateOf(false) }
    val secretVersion by vm.secretVersion.collectAsState()
    val savedTextKey = remember(secretVersion) { vm.repo.secrets.has("text") }
    val savedImageKey = remember(secretVersion) { vm.repo.secrets.has("image") }
    val busy by vm.busy.collectAsState()
    val enabled = busy.isBlank()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionTitle("适合你的节奏", "词本和学习进度只保存在这台手机。")
        SettingsCard {
            Text("每日学习量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(newCount, { newCount = it.filter(Char::isDigit).take(3) }, label = { Text("未学词 0–100") },
                    singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(reviewCount, { reviewCount = it.filter(Char::isDigit).take(3) }, label = { Text("复习词 0–200") },
                    singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Text("仅从你添加的词中安排。数量修改对下一次新建的每日计划生效。", style = MaterialTheme.typography.bodySmall, color = InkMuted)
            Text("英文发音", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = accent == "en-US", onClick = { accent = "en-US" }, label = { Text("美式英语") })
                FilterChip(selected = accent == "en-GB", onClick = { accent = "en-GB" }, label = { Text("英式英语") })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("每日学习提醒")
                Switch(reminder, { reminder = it; if (it) requestNotification() })
            }
            if (reminder) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(hour, { hour = it.filter(Char::isDigit).take(2) }, label = { Text("时 0–23") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    OutlinedTextField(minute, { minute = it.filter(Char::isDigit).take(2) }, label = { Text("分 0–59") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                }
                Text("使用手机本地时间，可能受系统省电影响延迟；打开 App 后仍会正常生成每日学习任务。", style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
        }
        SectionTitle("AI 补全", "免费词典、例句与图库优先，AI 补齐缺失资料。")
        SettingsCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("允许使用 AI 补全", style = MaterialTheme.typography.titleSmall)
                Switch(ai, { ai = it })
            }
            Text("开启并保存后，添加或补全单词时可自动调用服务。文字和图片服务可能收费，图片生成通常需要单独支持。", style = MaterialTheme.typography.bodySmall, color = InkMuted)
            Text("文字服务", fontWeight = FontWeight.Bold)
            OutlinedTextField(textBase, { textBase = it }, label = { Text("文字 API 地址") }, placeholder = { Text("https://服务地址/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(textModel, { textModel = it }, label = { Text("文字模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(textKey, { textKey = it }, label = { Text(if (savedTextKey) "文字密钥 · 已加密保存" else "文字 API Key") },
                placeholder = { Text(if (savedTextKey) "留空保留原密钥" else "粘贴你的密钥") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { vm.test(false) }, enabled = enabled) { Text("测试已保存的文字服务") }
                if (savedTextKey) TextButton(onClick = { vm.clearKey(false) }, enabled = enabled) { Text("清除密钥") }
            }
            HorizontalDivider()
            Text("图片服务", fontWeight = FontWeight.Bold)
            OutlinedTextField(imageBase, { imageBase = it }, label = { Text("图片 API 地址") }, placeholder = { Text("https://服务地址/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(imageModel, { imageModel = it }, label = { Text("图片模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(imageKey, { imageKey = it }, label = { Text(if (savedImageKey) "图片密钥 · 已加密保存" else "图片 API Key") },
                placeholder = { Text(if (savedImageKey) "留空保留原密钥" else "可与文字服务使用不同密钥") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { vm.test(true) }, enabled = enabled) { Text("检查已保存的图片服务") }
                if (savedImageKey) TextButton(onClick = { vm.clearKey(true) }, enabled = enabled) { Text("清除密钥") }
            }
            Text("请先保存再测试。图片服务检查仅访问模型列表，不生成图片、不验证实际生成权限。", style = MaterialTheme.typography.bodySmall, color = InkMuted)
        }
        Button(onClick = {
            vm.saveSettings(settings.copy(newLimit = newCount.toIntOrNull() ?: -1, reviewLimit = reviewCount.toIntOrNull() ?: -1,
                accent = accent, reminder = reminder, reminderHour = hour.toIntOrNull() ?: -1, reminderMinute = minute.toIntOrNull() ?: -1,
                aiEnabled = ai, textBase = textBase.trim(), textModel = textModel.trim(), imageBase = imageBase.trim(), imageModel = imageModel.trim()), textKey, imageKey)
            textKey = ""; imageKey = ""
        }, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("保存全部设置") }
        SectionTitle("备份与恢复")
        SettingsCard {
            Text("备份包括词本、图片、缓存读音和学习进度，不包括 API 密钥。", style = MaterialTheme.typography.bodyMedium, color = InkMuted)
            OutlinedButton(onClick = onExport, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(8.dp)); Text("导出完整备份")
            }
            OutlinedButton(onClick = onRestore, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("从备份恢复")
            }
        }
        Note("图片文字在手机本地识别。联网补全会把单词和必要的例句发给所选资料服务或 AI；原始识别照片不会由词伴上传。")
        TextButton(onClick = { licenses = true }) { Text("资料来源与开源说明") }
        Text("词伴 1.1.0  ·  只学你选择的词", style = MaterialTheme.typography.labelMedium, color = InkMuted, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(24.dp))
    }
    if (licenses) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val dictionaryLicense = remember { runCatching { context.assets.open("ECDICT-LICENSE.txt").bufferedReader().use { it.readText() } }.getOrDefault("") }
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text("资料来源与说明") }, text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("英汉查询词库：ECDICT\nhttps://github.com/skywind3000/ECDICT\n内置 35,000 条常用查询词，只用于补充资料。", style = MaterialTheme.typography.bodySmall)
                Text("例句：Tatoeba，保留作者及来源链接。图片：Wikimedia Commons，保留作者、许可及来源。每张词卡可查看来源。AI 内容会标注为生成内容，支持编辑校对。", style = MaterialTheme.typography.bodySmall)
                Text("通用干扰图：Twemoji graphics by Twitter and other contributors，CC BY 4.0。https://github.com/twitter/twemoji · https://creativecommons.org/licenses/by/4.0/ 。24 张矢量图片仅用于练习选项，不加入学习词本。", style = MaterialTheme.typography.bodySmall)
                Text("OCR：Google ML Kit 随包中文及拉丁文字模型。模型在本机识别；SDK 可能收集运行指标，具体见 Google 隐私政策。", style = MaterialTheme.typography.bodySmall)
                Text("朗读：Android 系统文字转语音。离线能力取决于已安装的英文语音包，成功生成的完整读音会缓存在本机。", style = MaterialTheme.typography.bodySmall)
                Text(dictionaryLicense, style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("关闭") } })
    }
}

@Composable private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
