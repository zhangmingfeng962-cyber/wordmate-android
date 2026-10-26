package com.wordmate.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.encodeToString
import java.time.LocalDate

@Composable
fun TodayScreen(words: List<WordCard>, reviews: List<ReviewState>, session: DailySession?, settings: AppSettings,
    onStart: () -> Unit, onAdd: () -> Unit, onPending: () -> Unit, enabled: Boolean) {
    val ready = words.count { it.complete() }
    val due = reviews.count { it.started && it.due() <= today() && it.lastDay != today() && words.any { w -> w.id == it.wordId && w.textReady() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Text(LocalDate.now().toString().replace("-", " / ") + "    ·    每天一点，慢慢记牢", style = MaterialTheme.typography.labelMedium, color = InkMuted)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Teal), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("YOUR WORDS\nYOUR PACE", color = Color(0xFFBBD9CA), letterSpacing = 2.sp, style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.Outlined.AutoStories, null, tint = Color(0xFFD5E7D8), modifier = Modifier.size(52.dp))
                    }
                    Text("今天，\n记住一点。", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(if (words.isEmpty()) "从你想记住的第一个单词开始。" else "先听一遍，再想一想。\n让声音、句子和画面一起留下来。", color = Color(0xFFD5E7D8), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = if (words.isEmpty()) onAdd else onStart, enabled = enabled,
                        colors = ButtonDefaults.buttonColors(containerColor = Apricot, contentColor = Ink), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(when { words.isEmpty() -> "添加我的第一个单词"; session?.phase == "done" -> "查看今日完成情况";
                            session != null -> "继续今日学习"; else -> "开始今日学习" }, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(words.size.toString(), "我的单词", Modifier.weight(1f))
                StatCard(due.toString(), "到期复习", Modifier.weight(1f))
                StatCard((session?.completed ?: 0).toString(), "今日已学", Modifier.weight(1f))
            }
        }
        item {
            SectionTitle("你的学习节奏", "只从自己的词本中安排，先复习，再学习未学词。")
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("每天最多 " + settings.newLimit + " 个未学词 · " + settings.reviewLimit + " 个复习词", fontWeight = FontWeight.Medium)
                    HorizontalDivider(color = Color(0xFFEBEEE7))
                    Text("每组最多 5 个词，图片选择、词义和听写穿插练。图片未齐时先练文字，薄弱项多练一次。", style = MaterialTheme.typography.bodyMedium, color = InkMuted)
                }
            }
        }
        if (words.size > ready) item {
            Card(onClick = onPending, colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0DE))) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.AutoFixHigh, null, tint = Color(0xFF95622D))
                    Column { Text((words.size - ready).toString() + " 个词还在补充句图", fontWeight = FontWeight.SemiBold)
                        Text("有释义的词已经可以先练，点此查看补全进度。", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item { Note("图片识别或输入拼写，都能添加单词。免费资源优先；需要时，可使用你配置的 AI 补齐资料。") }
    }
}

@Composable fun StatCard(value: String, label: String, modifier: Modifier) {
    Surface(modifier = modifier, color = Color.White, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Teal)
            Text(label, style = MaterialTheme.typography.labelMedium, color = InkMuted)
        }
    }
}

@Composable
fun WordbookScreen(words: List<WordCard>, reviews: List<ReviewState>, onWord: (String) -> Unit, onAdd: () -> Unit,
    queuePanel: @Composable () -> Unit = {}) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    val shown = words.filter { (query.isBlank() || it.spelling.contains(query, true) || it.meaning.contains(query)) &&
        when(filter) { 1 -> !it.complete() || it.chunks().isEmpty(); 2 -> it.textReady(); else -> true } }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        SectionTitle("我的词本", words.size.toString() + " 个由你选择的单词")
        Spacer(Modifier.height(10.dp))
        queuePanel()
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("搜索单词或中文意思") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "待完善", "可学习").forEachIndexed { index, title ->
                FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(title) })
            }
        }
        if (shown.isEmpty()) {
            EmptyState(if (words.isEmpty()) "这里还没有单词" else "没有找到对应的词", "拍一张单词表，或输入你想记住的拼写。", Icons.AutoMirrored.Outlined.MenuBook)
            if (words.isEmpty()) OutlinedButton(onClick = onAdd, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("添加单词") }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { word ->
                val review = reviews.firstOrNull { it.wordId == word.id }
                Card(onClick = { onWord(word.id) }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(word.spelling, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(word.meaning.ifBlank { "等你补齐中文意思" }, maxLines = 2, style = MaterialTheme.typography.bodyMedium, color = InkMuted)
                            Text(when { !word.textReady() -> "待补中文意思"
                                !word.complete() -> "可练词义 / 听写 · " + word.missing() + "待补"
                                review?.started != true -> "未学习"
                                review.due() <= today() -> "已到复习时间"
                                else -> "下次复习 " + LocalDate.ofEpochDay(review.due()) },
                                style = MaterialTheme.typography.labelSmall, color = if (word.complete()) Teal else Color(0xFF966635))
                        }
                        Icon(Icons.Outlined.ChevronRight, "查看 " + word.spelling, tint = InkMuted)
                    }
                }
            }
        }
    }
}

@Composable fun AddWordsDialog(onDismiss: () -> Unit, onAdd: (List<String>) -> Unit, busy: Boolean) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("输入想记的单词") }, text = {
        Column(Modifier.heightIn(max = dialogBodyHeight()).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("每行一个词，也可以一次粘贴多个单词。保存后自动寻找意思、例句和配图。", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(text, { text = it; error = "" }, minLines = 3, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("beautiful\nremember\ncuriosity") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = !busy && text.isNotBlank(), onClick = {
        val values = text.lines().map(String::trim).filter(String::isNotBlank)
        if (values.size > 300 || values.any { !validWord(it) }) error = "每行填写一个英文单词，每次最多 300 个。" else onAdd(values)
    }) { Text("添加并补全") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private data class OcrChoice(val value: String, val checked: Boolean = true)
@Composable fun OcrWordsDialog(initial: List<String>, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit, busy: Boolean) {
    val choices = remember(initial) { mutableStateListOf<OcrChoice>().apply { addAll(initial.map { OcrChoice(it) }) } }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("确认识别的单词") }, text = {
        Column(Modifier.heightIn(max = dialogBodyHeight())) {
            Text("勾选想记的词，并改正识别错误。标题、例句中的词可取消勾选。", style = MaterialTheme.typography.bodySmall, color = InkMuted)
            Row {
                TextButton(onClick = { choices.indices.forEach { choices[it] = choices[it].copy(checked = true) } }) { Text("全选") }
                TextButton(onClick = { choices.indices.forEach { choices[it] = choices[it].copy(checked = false) } }) { Text("全不选") }
            }
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 360.dp)) {
                items(choices.size) { index ->
                    val choice = choices[index]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(choice.checked, { choices[index] = choice.copy(checked = it) })
                        OutlinedTextField(choice.value, { choices[index] = choices[index].copy(value = it) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
                    }
                }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = !busy && choices.any { it.checked }, onClick = {
        val selected = choices.filter { it.checked }.map { it.value.trim() }
        if (selected.any { !validWord(it) }) error = "请修正无效的英文拼写后再添加。" else onAdd(selected)
    }) { Text("添加选中的词") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
