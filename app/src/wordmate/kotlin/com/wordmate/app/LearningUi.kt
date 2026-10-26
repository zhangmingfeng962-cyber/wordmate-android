package com.wordmate.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LearningCard(card: WordCard, vm: AppViewModel, settings: AppSettings,exercise:Boolean=false) {
    val highlighted by vm.repo.speech.letter.collectAsState()
    var recording by remember(card.id,settings.accent){mutableStateOf<Pronunciation?>(null)}
    var voiceError by remember(card.id){mutableStateOf("")}
    LaunchedEffect(card.id,settings.accent){recording=vm.repo.pronunciation.cached(card.spelling,settings.accent)}
    fun readWord(slow:Boolean=false,system:Boolean=false){vm.repo.speech.speakWord(card.spelling,settings.accent,slow,system){
        recording=it.getOrNull();voiceError=it.exceptionOrNull()?.message.orEmpty()
    }}
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (card.image.isNotBlank()) {
            AsyncImage(model = File(vm.getApplication<android.app.Application>().filesDir, card.image),
                contentDescription = "单词例句的场景配图", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(if(exercise)205.dp else 215.dp).clip(MaterialTheme.shapes.large))
        } else Note("还缺少配图，可联网查找、使用 AI 生成，或上传你自己的图片。")
        Text(card.spelling, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Teal)
        if (card.phonetic.isNotBlank()) Text(card.phonetic, color = InkMuted, style = MaterialTheme.typography.titleMedium)
        Text(card.meaning.ifBlank { "尚未补齐中文意思" }, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { readWord() }) {
                Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("读单词")
            }
            OutlinedButton(onClick = { readWord(true) }) { Text("慢速") }
            OutlinedButton(onClick = { vm.repo.speech.stop() }) { Text("停止") }
        }
        recording?.let{RecordingCredit(it)}
        if(voiceError.isNotBlank()){
            Note(voiceError)
            TextButton(onClick={readWord(system=true)}){Text("使用系统合成语音")}
        }
        Surface(color = PaleTeal, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("顺着拼写记", style = MaterialTheme.typography.labelLarge, color = Teal)
                Text(card.chunks().takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: card.spelling,
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                if (highlighted >= 0) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    card.spelling.forEachIndexed { index, letter ->
                        Text(letter.toString(), color = if (highlighted == index) Color.White else Teal,
                            modifier = Modifier.background(if (highlighted == index) Teal else Color.Transparent, MaterialTheme.shapes.small).padding(3.dp),
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
                TextButton(onClick = { vm.repo.speech.spell(card.spelling, settings.accent) { it.exceptionOrNull()?.let { e -> vm.notify(e.message.orEmpty()) } } },
                    contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Outlined.RecordVoiceOver, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("逐字母拼读 · 系统语音")
                }
                Text("分块帮助记忆拼写；可在编辑中调整。", style = MaterialTheme.typography.labelSmall, color = InkMuted)
            }
        }
        if (card.exampleEn.isNotBlank()) Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("把单词放进生活", style = MaterialTheme.typography.labelLarge, color = InkMuted)
                Text(card.exampleEn, style = MaterialTheme.typography.titleMedium)
                Text(card.exampleZh.ifBlank { "尚未补齐例句译文" }, color = InkMuted)
                TextButton(onClick = { vm.repo.speech.speak(card.exampleEn, settings.accent) { it.exceptionOrNull()?.let { e -> vm.notify(e.message.orEmpty()) } } }) { Text("听例句 · 系统语音") }
            }
        }
        if (card.error.isNotBlank()) Note(card.error)
    }
}

@Composable fun RecordingCredit(recording:Pronunciation){
    val uri=androidx.compose.ui.platform.LocalUriHandler.current
    var details by remember{mutableStateOf(false)}
    TextButton(onClick={details=true}){Text("真人录音 · "+recording.author+" · 来源与许可",style=MaterialTheme.typography.labelSmall)}
    if(details)AlertDialog(onDismissRequest={details=false},title={Text("录音来源")},text={Column{
        Text(recording.author+"\n"+recording.license+"\n"+recording.description)
        TextButton(onClick={uri.openUri(recording.sourceUrl)}){Text("查看原始录音")}
        TextButton(onClick={uri.openUri(recording.licenseUrl)}){Text("查看许可证")}
    }},confirmButton={TextButton(onClick={details=false}){Text("关闭")}})
}

@Composable
fun WordDetailScreen(vm: AppViewModel, card: WordCard, settings: AppSettings, pickImage: () -> Unit, onDeleted: () -> Unit) {
    var edit by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var imageSearch by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { vm.repo.speech.stop() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { edit = true }, label = { Text("编辑资料 / 选词义") }, leadingIcon = { Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp)) })
            AssistChip(onClick = { vm.enrich(card.id) }, label = { Text("自动补全") }, leadingIcon = { Icon(Icons.Outlined.AutoFixHigh, null, Modifier.size(16.dp)) })
        }
        LearningCard(card, vm, settings)
        SectionTitle("选择更适合你的画面")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { imageSearch = true }, modifier = Modifier.weight(1f)) { Text("联网找图") }
            OutlinedButton(onClick = pickImage, modifier = Modifier.weight(1f)) { Text("上传图片") }
        }
        FilledTonalButton(onClick = { vm.generateImage(card.id) }, modifier = Modifier.fillMaxWidth(),
            enabled = card.exampleEn.isNotBlank()) { Text("AI 生成场景图 / 重新生成") }
        Text("AI 图片生成可能产生接口费用。已有图片只会在你主动换图时替换。", style = MaterialTheme.typography.bodySmall, color = InkMuted)
        if (card.imageCredit.isNotBlank() || card.sources.isNotBlank()) {
            HorizontalDivider()
            Text("资料来源", style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(listOf(card.imageCredit, card.sources).filter(String::isNotBlank).joinToString("\n\n"),
                style = MaterialTheme.typography.bodySmall, color = InkMuted) }
        }
        TextButton(onClick = { remove = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除这个单词") }
        Spacer(Modifier.height(30.dp))
    }
    if (edit) EditWordDialog(card, onDismiss = { edit = false }, onSave = { vm.save(it) { edit = false } })
    if (imageSearch) ImageSearchDialog(vm, card, onDismiss = { imageSearch = false })
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("删除 " + card.spelling + "？") },
        text = { Text("这个词的学习进度和答题记录也会移除。") },
        confirmButton = { TextButton(onClick = { vm.delete(card.id) { remove = false; onDeleted() } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { remove = false }) { Text("保留") } })
}

@Composable
fun EditWordDialog(card: WordCard, onDismiss: () -> Unit, onSave: (WordCard) -> Unit) {
    var meaning by remember { mutableStateOf(card.meaning) }
    var phonetic by remember { mutableStateOf(card.phonetic) }
    var en by remember { mutableStateOf(card.exampleEn) }
    var zh by remember { mutableStateOf(card.exampleZh) }
    var chunks by remember { mutableStateOf(card.chunks().joinToString(" · ")) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑 " + card.spelling) }, text = {
        Column(Modifier.heightIn(max = dialogBodyHeight()).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("选择要记的词义，例句和配图也应表达这个意思。", style = MaterialTheme.typography.bodySmall, color = InkMuted)
            card.meaningList().distinct().forEach { value ->
                FilterChip(selected = meaning == value, onClick = { meaning = value }, label = { Text(value) })
            }
            OutlinedTextField(meaning, { meaning = it }, label = { Text("本次记忆的中文意思") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phonetic, { phonetic = it }, label = { Text("音标（可选）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(en, { en = it }, label = { Text("包含完整单词的英文例句") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(zh, { zh = it }, label = { Text("例句中文翻译") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(chunks, { chunks = it }, label = { Text("拼读分块，用 · 分隔") }, placeholder = { Text("beau · ti · ful") }, modifier = Modifier.fillMaxWidth())
            Text("分块连接后必须保持原拼写。不确定时可以留空，或把完整单词作为一块。", style = MaterialTheme.typography.bodySmall, color = InkMuted)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        val segments = chunks.split('·', '|', '/', ' ').map(String::trim).filter(String::isNotBlank)
        when {
            segments.isNotEmpty() && !LearningRules.validSegments(card.spelling, segments) -> error = "分块拼接后必须与 " + card.spelling + " 完全一致。"
            en.isNotBlank() && !ContentParsing.hasWord(en, card.spelling) -> error = "例句需要包含 " + card.spelling + " 的完整拼写。"
            else -> onSave(card.copy(meaning = meaning.trim(), meanings = codec.encodeToString((card.meaningList() + meaning.trim()).filter(String::isNotBlank).distinct()),
                phonetic = phonetic.trim(), exampleEn = en.trim(), exampleZh = zh.trim(), segments = codec.encodeToString(segments)))
        }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable fun ImageSearchDialog(vm: AppViewModel, card: WordCard, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf(card.imageQuery.ifBlank { card.spelling }) }
    var results by remember { mutableStateOf<List<ImageCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var offset by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun search(next: Boolean) {
        if (loading || query.isBlank()) return
        loading = true; error = ""; offset = if (next) offset + 8 else 0
        scope.launch {
            try { results = vm.repo.content.imageCandidates(query.trim(), offset); if (results.isEmpty()) error = "暂时没有合适图片，可以更换关键词、上传图片或使用 AI 生成。" }
            catch (e: Exception) { error = e.message.orEmpty() }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { search(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("找一个记得住的画面") }, text = {
        Column(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("英文场景关键词") }, singleLine = true)
            Row { TextButton(onClick = { search(false) }, enabled = !loading) { Text("搜索") }; TextButton(onClick = { search(true) }, enabled = !loading) { Text("换一批") } }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = InkMuted)
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                results.forEach { candidate ->
                    Card(onClick = { vm.selectImage(card.id, candidate, onDismiss) }) {
                        AsyncImage(model = candidate.url, contentDescription = candidate.title, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(140.dp))
                        Text(candidate.title, modifier = Modifier.padding(10.dp), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
fun QuizScreen(vm: AppViewModel, session: DailySession?, words: List<WordCard>, settings: AppSettings, busy: Boolean, onDone: () -> Unit) {
    if (session == null) { EmptyState("今天还没有学习任务", "返回首页，添加或完善单词后开始。", Icons.Outlined.AutoStories); TextButton(onClick = onDone) { Text("返回首页") }; return }
    if (session.phase == "done") {
        Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Spacer(Modifier.height(20.dp)); Icon(Icons.Outlined.TaskAlt, null, tint = Teal, modifier = Modifier.size(80.dp))
            Text("今天又记住了一点。", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("完成 " + session.completed + " 个词 · 首轮两项全对 " + session.correct + " 个", style = MaterialTheme.typography.titleMedium)
            Note("意思和拼写已经分别记录。薄弱项会更早回到你的复习计划里；错词重考不会抹掉首次错误。")
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("回到今日学习") }
        }
        return
    }
    val item = session.current()
    val card = words.firstOrNull { it.id == item?.wordId }
    if (card == null || !card.complete() || session.phase == "unavailable") {
        EmptyState("这个词需要先完善资料", "回词本补齐资料，或跳过这个词继续今日任务。", Icons.Outlined.EditNote)
        Button(onClick = vm::next) { Text("跳过并继续") }; TextButton(onClick = onDone) { Text("返回词本") }
        return
    }
    var audioReady by remember(session.day, session.index, session.phase) { mutableStateOf(false) }
    var playing by remember(session.day, session.index, session.phase) { mutableStateOf(false) }
    var audioError by remember(session.day, session.index, session.phase) { mutableStateOf("") }
    var typed by rememberSaveable(session.day, session.index) { mutableStateOf(session.typed) }
    val context = LocalContext.current
    fun pronounce() {
        playing = true; audioReady = false; audioError = ""
        vm.repo.speech.speak(card.spelling, settings.accent) { result ->
            playing = false; audioReady = result.isSuccess; audioError = result.exceptionOrNull()?.message.orEmpty()
        }
    }
    LaunchedEffect(session.day, session.index, session.phase) {
        if (session.phase in listOf("meaning", "spelling")) pronounce() else vm.repo.speech.stop()
    }
    DisposableEffect(Unit) { onDispose { vm.repo.speech.stop() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val total = session.queue().size
        LinearProgressIndicator(progress = { session.index.toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
        Text((session.index + 1).toString() + " / " + total + if (item?.retry == true) "    ·    错词再练一次" else if (item?.newWord == true) "    ·    未学词" else "    ·    到期复习",
            style = MaterialTheme.typography.labelLarge, color = InkMuted)
        when (session.phase) {
            "study" -> {
                SectionTitle("先认识这个单词", "看画面、读例句，再顺着拼写记一遍。")
                LearningCard(card, vm, settings)
                Button(onClick = vm::beginQuiz, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("准备好了，开始抽查") }
            }
            "meaning", "spelling" -> {
                val meaningPhase = session.phase == "meaning"
                SectionTitle(if (meaningPhase) "听一听，它是什么意思？" else "听一听，写下它的拼写",
                    if (meaningPhase) "第 1 步 · 中文意思" else "第 2 步 · 英文拼写")
                Surface(color = PaleTeal, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Headphones, null, modifier = Modifier.size(54.dp), tint = Teal)
                        Text(if (playing) "正在播放单词…" else if (audioReady) "已播放，现在试着回忆" else "先播放一遍完整单词", color = Teal)
                        OutlinedButton(onClick = { pronounce() }) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, null); Spacer(Modifier.width(6.dp)); Text("再听一遍") }
                    }
                }
                if (audioError.isNotBlank()) {
                    Note(audioError)
                    TextButton(onClick = {
                        try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                        catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    }) { Text("打开系统朗读设置") }
                }
                if (meaningPhase) {
                    session.choices().forEach { choice ->
                        OutlinedButton(onClick = { vm.choose(choice) }, enabled = audioReady && !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text(choice, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
                    }
                    TextButton(onClick = { vm.choose("__forgot__") }, enabled = audioReady && !busy) { Text("暂时想不起来") }
                } else {
                    OutlinedTextField(typed, { typed = it.take(100); vm.draft(typed) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        enabled = audioReady && !busy, label = { Text("输入英文拼写") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
                    if (session.assisted) Note("首字母是 " + card.spelling.first() + "，共 " + card.spelling.length + " 个字符。本次会记为需要加强。")
                    Button(onClick = { vm.submit(typed) }, enabled = audioReady && !busy && typed.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("提交，查看解析") }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = vm::hint, enabled = audioReady && !busy && !session.assisted) { Text("看拼写提示") }
                        TextButton(onClick = { vm.submit(typed, true) }, enabled = audioReady && !busy) { Text("暂时想不起来") }
                    }
                }
            }
            "reveal" -> {
                SectionTitle(if (session.meaningCorrect && session.spellingCorrect) "记住了，做得很好。" else "再看一遍，就更熟一点。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuggestionChip(onClick = {}, label = { Text("意思 · " + if (session.meaningCorrect) "正确" else "需加强") })
                    SuggestionChip(onClick = {}, label = { Text("拼写 · " + if (session.spellingCorrect) "正确" else "需加强") })
                }
                if (!session.spellingCorrect) Note("你的拼写：" + session.typed.ifBlank { "未填写" } + "\n" +
                    if (session.assisted) "使用了提示，本次安排加强复习。" else LearningRules.diff(card.spelling, session.typed))
                LearningCard(card, vm, settings)
                Button(onClick = vm::next, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("继续") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
